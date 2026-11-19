package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.axon.AxonSyncAction
import co.anbora.labs.fantom.lang.axon.copy.AxonCopyException
import co.anbora.labs.fantom.lang.axon.copy.AxonCopyLimits
import co.anbora.labs.fantom.lang.axon.copy.AxonFallbackFolder
import co.anbora.labs.fantom.lang.axon.copy.AxonFunction
import co.anbora.labs.fantom.lang.axon.copy.AxonFunctionList
import co.anbora.labs.fantom.lang.axon.copy.AxonJson
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectCopy
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectKey
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectSettings
import co.anbora.labs.fantom.lang.axon.copy.AxonServerSettings
import co.anbora.labs.fantom.lang.axon.copy.AxonServerToken
import co.anbora.labs.fantom.lang.axon.copy.AxonSyncResult
import co.anbora.labs.fantom.lang.axon.copy.AxonSyncTarget
import co.anbora.labs.fantom.lang.axon.proj.AxonProjFolderSync
import co.anbora.labs.fantom.lang.axon.proj.AxonProjFolders
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.util.concurrent.Callable

/**
 * "Sync Axon Functions" into a project folder of the global proj folder (proj-folder
 * mode), against the fake Axon server: a new project, an update, a local edit, a removed
 * function, the metadata, and no write outside the project folder. Every name is made up.
 */
class AxonProjFolderSyncTest : BasePlatformTestCase() {
    private lateinit var server: FakeAxonServer
    private lateinit var global: File
    private val temps = ArrayList<File>()
    private val key = AxonProjectKey("demo", "site")
    private val token = "tok-51d0-made-up"

    private val copy get() = AxonProjectCopy.getInstance(project)
    private val site get() = File(global, "demo/site")
    private val func get() = File(site, "func")

    override fun setUp() {
        super.setUp()
        copy.testCacheRoot = temp("axon-cache")
        global = temp("proj")
        server = FakeAxonServer(token)
        server.put("demo/site", "calcDailyAvg", "(points) => points.hisRead(yesterday)", "Daily average")
        server.put("demo/site", "siteReport", "() => readAll(site).map(calcDailyAvg)")
        server.put("plant-a/bldg2", "plantLoad", "() => 42")
        AxonServerSettings.getInstance().url = server.url
        AxonServerToken.set(token)
        AxonServerSettings.getInstance().projFolder = global.path
        AxonProjFolders.clearForTest()
    }

    override fun tearDown() {
        try {
            AxonServerSettings.getInstance().url = ""
            AxonServerToken.set(null)
            AxonProjectSettings.getInstance(project).activeKey = null
            copy.dropNow()
            AxonServerSettings.getInstance().projFolder = ""
            AxonProjFolders.clearForTest()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            copy.testCacheRoot = null
            server.close()
            temps.forEach { FileUtil.delete(it) }
        } finally {
            super.tearDown()
        }
    }

    private fun temp(name: String): File = FileUtil.createTempDirectory(name, null).also { temps += it }

    /** Runs [work] off the UI thread, as the plugin does, while this thread serves UI events. */
    private fun <T> bg(work: () -> T): T {
        val f = ApplicationManager.getApplication().executeOnPooledThread(Callable { runCatching(work) })
        val end = System.currentTimeMillis() + 60_000
        while (!f.isDone) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(5)
            if (System.currentTimeMillis() > end) fail("the background work did not end")
        }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        return f.get().getOrThrow()
    }

    private fun vf(file: File): VirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: error("no $file")

    /** Syncs the project that [file] belongs to, as the action does. */
    private fun syncAt(file: File): AxonSyncResult {
        val match = AxonSyncTarget.find(project, vf(file)) ?: error("no sync target for $file")
        assertTrue(match.hook is AxonProjFolderSync)
        return bg { copy.syncNow(match) }
    }

    /** The first sync: a new project folder under `<global>/demo/site`. */
    private fun firstSync(): AxonSyncResult {
        AxonProjectSettings.getInstance(project).activeKey = key
        return syncAt(global)
    }

    private fun meta(): Map<String, Any?> = AxonJson.parseObject(File(site, ".sync-metadata.json").readText())

    private fun metaFn(name: String): Map<String, Any?>? = AxonJson.obj(AxonJson.obj(meta()["functions"])?.get(name))

    private fun versions(): List<String> = File(site, ".versions").list()?.sorted().orEmpty()

    private fun snapshot(root: File): Map<String, String> =
        root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readText() }

    // ---- the sync -------------------------------------------------------------------------

    fun testANewProjectIsCreatedInTheServerLayout() {
        server.requests.clear()
        val r = firstSync()
        assertEquals(listOf("GET /api/ide/projects/demo/site/functions?sync=true"), server.requests.toList())
        assertEquals(listOf("calcDailyAvg", "siteReport"), r.added)
        assertEquals(emptyList<String>(), r.changed + r.removed + r.conflicts)
        assertEquals(listOf("calcDailyAvg.axon", "calcDailyAvg.trio", "siteReport.axon", "siteReport.trio"), func.list()!!.sorted())
        assertEquals("(points) => points.hisRead(yesterday)", File(func, "calcDailyAvg.axon").readText())
        assertEquals(
            "name:\"calcDailyAvg\"\ndoc:\"Daily average\"\nmod:\"1/2/2026, 3:04:05 AM UTC\"\nsrc:\n  (points) => points.hisRead(yesterday)",
            File(func, "calcDailyAvg.trio").readText(),
        )
        // the folder reads back the same: the fallback reader understands what was written
        val back = AxonFallbackFolder(global).fetch(key, false) {}
        assertEquals(listOf("calcDailyAvg", "siteReport"), back.functions.map { it.name })
        assertEquals("Daily average", back.functions[0].doc)
        assertEquals("() => readAll(site).map(calcDailyAvg)", back.functions[1].src)
        assertEquals("2026-01-02T03:04:05Z", back.functions[0].mod)
        // the read-only copy is not touched
        assertNull(copy.copy)
    }

    fun testTheMetadataIsWritten() {
        firstSync()
        val m = meta()
        assertEquals("demo", m["instance"])
        assertEquals("site", m["project"])
        assertEquals(2, (m["functionCount"] as Number).toInt())
        assertNotNull(AxonFallbackFolder.isoOf(m["lastSync"] as String))
        val fn = metaFn("siteReport")!!
        assertEquals("siteReport", fn["name"])
        assertEquals(AxonCopyLimits.sha256("() => readAll(site).map(calcDailyAvg)").substring(0, 16), fn["hash"])
        assertEquals("1/2/2026, 3:04:05 AM UTC", fn["lastModified"])
        assertNotNull(AxonFallbackFolder.isoOf(fn["synced"] as String))
        // the folder is a project now, with the key of the metadata
        assertEquals(key, AxonProjFolders.getInstance().projectOfNow(vf(File(func, "siteReport.axon")))?.key)
    }

    fun testAnUpdateKeepsTheOldFileInVersions() {
        firstSync()
        server.put("demo/site", "siteReport", "() => readAll(site)")
        server.put("demo/site", "newFunc", "() => 7")
        // from a file in the project folder this time
        val r = syncAt(File(func, "calcDailyAvg.axon"))
        assertEquals(listOf("newFunc"), r.added)
        assertEquals(listOf("siteReport"), r.changed)
        assertEquals(listOf("calcDailyAvg"), r.unchanged)
        assertEquals(emptyList<String>(), r.conflicts + r.removed)
        assertEquals("() => readAll(site)", File(func, "siteReport.axon").readText())
        val old = versions().filter { it.startsWith("siteReport_") && it.endsWith(".axon") }
        assertEquals(versions().toString(), 1, old.size)
        assertEquals("() => readAll(site).map(calcDailyAvg)", File(site, ".versions/${old.single()}").readText())
        assertTrue(r.message(), r.message().startsWith("Axon: demo/site synced into its folder, 1 added, 1 changed, 1 unchanged, 0 moved to .versions, 0 conflicts (3 functions)."))
        assertEquals(3, (meta()["functionCount"] as Number).toInt())
    }

    fun testALocalEditIsKeptAndTheSkySparkVersionGoesBesideIt() {
        firstSync()
        val hashBefore = metaFn("calcDailyAvg")!!["hash"]
        File(func, "calcDailyAvg.axon").writeText("(points) => points.hisRead(today) // local")
        server.put("demo/site", "calcDailyAvg", "(points) => points.hisRead(lastWeek)", "Daily average")
        val r = syncAt(site)
        assertEquals(listOf("calcDailyAvg"), r.conflicts)
        assertEquals(emptyList<String>(), r.changed)
        assertEquals("(points) => points.hisRead(today) // local", File(func, "calcDailyAvg.axon").readText())
        assertEquals("(points) => points.hisRead(lastWeek)", File(func, "calcDailyAvg.axon.skyspark").readText())
        // the folder did not take the SkySpark version: its hash stays, so the next sync sees the edit again
        assertEquals(hashBefore, metaFn("calcDailyAvg")!!["hash"])
        assertTrue(r.message(), r.message().contains("1 conflict ("))
        assertTrue(r.message(), r.message().contains("calcDailyAvg"))
    }

    fun testAnUnsavedEditorChangeIsALocalEdit() {
        firstSync()
        val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf(File(func, "siteReport.axon")))!!
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { doc.setText("() => 1 // typed") }
        server.put("demo/site", "siteReport", "() => 2")
        val r = syncAt(site)
        assertEquals(listOf("siteReport"), r.conflicts)
        assertEquals("() => 1 // typed", doc.text)
        assertEquals("() => 2", File(func, "siteReport.axon.skyspark").readText())
    }

    fun testAFunctionGoneInSkySparkMovesToVersions() {
        firstSync()
        server.projects["demo/site"]!!.remove("siteReport")
        val r = syncAt(site)
        assertEquals(listOf("siteReport"), r.removed)
        assertFalse(File(func, "siteReport.axon").exists())
        assertFalse(File(func, "siteReport.trio").exists())
        val moved = versions().filter { it.startsWith("siteReport_") }
        assertEquals(versions().toString(), 2, moved.size)
        assertEquals("() => readAll(site).map(calcDailyAvg)", File(site, ".versions/" + moved.single { it.endsWith(".axon") }).readText())
        assertNull(metaFn("siteReport"))
        assertEquals(1, (meta()["functionCount"] as Number).toInt())
        assertTrue(r.message(), r.message().contains("1 moved to .versions"))
    }

    fun testNothingOutsideTheProjectFolderIsTouched() {
        File(global, "demo/other/func").mkdirs()
        File(global, "demo/other/.sync-metadata.json").writeText("""{"instance":"demo","project":"other"}""")
        File(global, "demo/other/func/siteReport.axon").writeText("() => 0")
        File(global, "notes.txt").writeText("keep")
        File(global, "demo/backup/site/func").mkdirs()
        File(global, "demo/backup/site/func/siteReport.axon").writeText("() => -1")
        val before = snapshot(global)
        firstSync()
        server.projects["demo/site"]!!.remove("siteReport")
        syncAt(site)
        val after = snapshot(global)
        for ((path, text) in before) assertEquals(path, text, after[path])
        val added = after.keys - before.keys
        assertTrue(added.toString(), added.all { it.startsWith("demo${File.separator}site${File.separator}") })
    }

    fun testABadFunctionNameWritesNothing() {
        AxonProjectSettings.getInstance(project).activeKey = key
        val target = AxonSyncTarget.Target(key, site)
        val list = AxonFunctionList(key, null, listOf(AxonFunction("../evil", "() => 1", null, null, AxonCopyLimits.sha256("x"))))
        try {
            bg { AxonProjFolderSync().write(project, target, list) {} }
            fail("expected BAD_REPLY")
        } catch (e: AxonCopyException) {
            assertEquals(AxonCopyException.Kind.BAD_REPLY, e.kind)
        }
        assertEquals(emptyList<String>(), global.list()!!.toList())
    }

    fun testTheTrioKeepsOtherTags() {
        val f = AxonFunction("siteArea", "(site) =>\n  site->area\n", "Area.\nIn m2.", "2026-03-04T05:06:07Z", "h")
        val old = "name:\"siteArea\"\ndis:\"Site area\"\nfunc\nsrc:\n  (site) => 0\n"
        assertEquals(
            "name:\"siteArea\"\ndoc:\n  Area.\n  In m2.\ndis:\"Site area\"\nfunc\nmod:\"3/4/2026, 5:06:07 AM UTC\"\nsrc:\n  (site) =>\n    site->area\n  ",
            AxonProjFolderSync.trioText(old, f),
        )
    }

    // ---- the action -----------------------------------------------------------------------

    private fun update(file: VirtualFile): Presentation {
        val action = ActionManager.getInstance().getAction(AxonSyncAction.ID) as AxonSyncAction
        val ctx = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.VIRTUAL_FILE, file).build()
        val e = AnActionEvent.createEvent(action, ctx, Presentation(), ActionPlaces.PROJECT_VIEW_POPUP, ActionUiKind.POPUP, null)
        action.update(e)
        return e.presentation
    }

    fun testTheActionShowsOnFilesAndFoldersOfAProject() {
        File(global, "plant-a/bldg2/func").mkdirs()
        File(global, "plant-a/bldg2/func/plantLoad.axon").writeText("() => 1")
        for (f in listOf(File(global, "plant-a/bldg2"), File(global, "plant-a/bldg2/func"), File(global, "plant-a/bldg2/func/plantLoad.axon"))) {
            val p = update(vf(f))
            assertTrue(f.path, p.isVisible && p.isEnabled)
            assertTrue(p.description, p.description.contains("plant-a/bldg2"))
        }
        // the global folder itself: only with an active project, which it then creates
        assertFalse(update(vf(global)).isVisible)
        AxonProjectSettings.getInstance(project).activeKey = key
        assertTrue(update(vf(global)).description.contains("demo/site"))
        // a folder outside the layout does not show it
        assertFalse(update(vf(temp("elsewhere"))).isVisible)
    }

    fun testWithoutTheGlobalFolderAMetadataFolderIsAProject() {
        AxonServerSettings.getInstance().projFolder = ""
        AxonProjFolders.clearForTest()
        val folder = File(temp("loose"), "conn/bldg2")
        File(folder, "func").mkdirs()
        File(folder, ".sync-metadata.json").writeText("""{"instance":"plant-a","project":"bldg2","functions":{}}""")
        server.requests.clear()
        val r = syncAt(File(folder, "func"))
        assertEquals(listOf("GET /api/ide/projects/plant-a/bldg2/functions?sync=true"), server.requests.toList())
        assertEquals(listOf("plantLoad"), r.added)
        assertEquals("() => 42", File(folder, "func/plantLoad.axon").readText())
        assertEquals("plant-a", AxonJson.parseObject(File(folder, ".sync-metadata.json").readText())["instance"])
    }
}
