package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.axon.AxonPushAction
import co.anbora.labs.fantom.lang.axon.copy.AxonCopyException
import co.anbora.labs.fantom.lang.axon.copy.AxonFunction
import co.anbora.labs.fantom.lang.axon.copy.AxonJson
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectCopy
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectKey
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectSettings
import co.anbora.labs.fantom.lang.axon.copy.AxonServerSettings
import co.anbora.labs.fantom.lang.axon.copy.AxonServerToken
import co.anbora.labs.fantom.lang.axon.copy.AxonSyncResult
import co.anbora.labs.fantom.lang.axon.copy.AxonSyncTarget
import co.anbora.labs.fantom.lang.axon.proj.AxonFunctionPush
import co.anbora.labs.fantom.lang.axon.proj.AxonFunctionPush.Choice
import co.anbora.labs.fantom.lang.axon.proj.AxonFunctionPush.Outcome
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
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.util.concurrent.Callable

/**
 * "Push to SkySpark" (task 4.34) against the fake Axon server: a good push and the folder's
 * metadata, a conflict then overwrite or take the SkySpark version, a new function, a
 * read-only key (403), a refused key (401), no key, files that cannot be pushed, and no
 * network on the UI thread. Every name here is made up.
 */
class AxonFunctionPushTest : BasePlatformTestCase() {
    private lateinit var server: FakeAxonServer
    private lateinit var global: File
    private val temps = ArrayList<File>()
    private val key = AxonProjectKey("demo", "site")
    private val token = "tok-9e2b-made-up"

    private val copy get() = AxonProjectCopy.getInstance(project)
    private val site get() = File(global, "demo/site")
    private val func get() = File(site, "func")

    override fun setUp() {
        super.setUp()
        copy.testCacheRoot = temp("axon-cache")
        global = temp("proj")
        server = FakeAxonServer(token)
        server.put("demo/site", "calcDailyAvg", "(points) => points.hisRead(yesterday)", "Daily average")
        server.put("demo/site", "siteReport", "() => readAll(site)")
        AxonServerSettings.getInstance().url = server.url
        AxonServerToken.set(token)
        AxonServerSettings.getInstance().projFolder = global.path
        AxonProjFolders.clearForTest()
        // the folder as the first "Sync Axon Functions" writes it
        AxonProjectSettings.getInstance(project).activeKey = key
        syncAt(global)
        server.requests.clear()
        server.bodies.clear()
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

    private fun failsWith(kind: AxonCopyException.Kind, work: () -> Unit): AxonCopyException {
        try {
            bg(work)
        } catch (e: AxonCopyException) {
            assertEquals(e.message, kind, e.kind)
            return e
        }
        throw AssertionError("expected $kind")
    }

    private fun vf(file: File): VirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: error("no $file")

    private fun syncAt(file: File): AxonSyncResult {
        val match = AxonSyncTarget.find(project, vf(file)) ?: error("no sync target for $file")
        return bg { copy.syncNow(match) }
    }

    /** Saves [text] into [file] through the VFS, as the editor does. */
    private fun edit(file: File, text: String) {
        val v = vf(file)
        WriteCommandAction.runWriteCommandAction(project) { v.setBinaryContent(text.toByteArray(Charsets.UTF_8)) }
    }

    private fun target(name: String) = AxonFunctionPush.targetOf(vf(File(func, "$name.axon"))) ?: error("no push target for $name")

    private fun meta(): Map<String, Any?> = AxonJson.parseObject(File(site, ".sync-metadata.json").readText())

    private fun metaFn(name: String): Map<String, Any?>? = AxonJson.obj(AxonJson.obj(meta()["functions"])?.get(name))

    /** A [AxonFunctionPush.Ui] that answers as told and remembers what it was asked. */
    private class FakeUi(val create: Boolean = true, val choice: Choice = Choice.CANCEL) : AxonFunctionPush.Ui {
        val asked = ArrayList<String>()
        var serverSeen: AxonFunction? = null
        override fun confirmNew(plan: AxonFunctionPush.Plan): Boolean { asked += "new ${plan.name}"; return create }
        override fun conflict(plan: AxonFunctionPush.Plan, server: AxonFunction?): Choice {
            asked += "conflict ${plan.name}"; serverSeen = server; return choice
        }
    }

    private fun run(name: String, ui: FakeUi = FakeUi()): Outcome = bg { AxonFunctionPush.run(project, target(name), ui) }

    // ---- push ----------------------------------------------------------------------------------

    fun testAGoodPushUpdatesTheFolderSoTheNextSyncSeesNoLocalEdit() {
        edit(File(func, "calcDailyAvg.axon"), "(points) => points.hisRead(today)")
        val ui = FakeUi()
        val out = run("calcDailyAvg", ui)
        assertTrue(out.toString(), out is Outcome.Pushed)
        assertEquals(emptyList<String>(), ui.asked)
        assertEquals(listOf("POST /api/ide/projects/demo/site/functions/calcDailyAvg"), server.requests.toList())
        // the mod the folder got from SkySpark is sent back; no doc: the stored doc is kept
        assertEquals("""{"src":"(points) => points.hisRead(today)","expectedMod":"2026-01-02T03:04:05Z"}""", server.bodies.single())
        assertEquals("(points) => points.hisRead(today)", server.projects["demo/site"]!!["calcDailyAvg"]!!.src)

        val e = metaFn("calcDailyAvg")!!
        assertEquals(AxonProjFolderSync.shortHash("(points) => points.hisRead(today)"), e["hash"])
        assertEquals("2026-06-01T00:00:00Z", e["mod"])
        assertEquals("2026-06-01T00:00:00.000Z", e["modIso"])
        assertEquals("Daily average", e["doc"])
        val trio = File(func, "calcDailyAvg.trio").readText()
        assertTrue(trio, trio.contains("mod:\"6/1/2026, 12:00:00 AM UTC\""))
        assertTrue(trio, trio.contains("  (points) => points.hisRead(today)"))
        assertEquals(AxonProjFolderSync.shortHash(trio), e[AxonProjFolderSync.TRIO_HASH])

        val r = syncAt(site)
        assertEquals(emptyList<String>(), r.conflicts)
        assertTrue(r.unchanged.toString(), "calcDailyAvg" in r.unchanged!!)
        assertFalse(File(func, "calcDailyAvg.axon.skyspark").exists())
    }

    fun testAChangedTrioDocGoesAlong() {
        val trio = File(func, "calcDailyAvg.trio")
        edit(trio, trio.readText().replace("doc:\"Daily average\"", "doc:\"Average of one day\""))
        run("calcDailyAvg")
        assertTrue(server.bodies.single(), server.bodies.single().contains("\"doc\":\"Average of one day\""))
        assertEquals("Average of one day", server.projects["demo/site"]!!["calcDailyAvg"]!!.doc)
        assertEquals("Average of one day", metaFn("calcDailyAvg")!!["doc"])
    }

    fun testRemovedDuplicatesAreInTheMessage() {
        server.duplicates = listOf(mapOf("id" to "r:1", "mod" to "2025-01-01T00:00:00Z", "backup" to "removed-1.json"))
        val out = run("siteReport") as Outcome.Pushed
        assertEquals(1, out.result.removedDuplicates.size)
        val text = AxonFunctionPush.message(out.plan, out.result)
        assertTrue(text, text.contains("removed 1 older record") && text.contains("removed-1.json"))
        server.duplicates = emptyList()
        val plain = run("siteReport") as Outcome.Pushed
        assertEquals("Pushed siteReport to demo/site in SkySpark.", AxonFunctionPush.message(plain.plan, plain.result))
    }

    // ---- conflict ------------------------------------------------------------------------------

    private fun changeOnServer() =
        server.put("demo/site", "calcDailyAvg", "(points) => points.hisRead(lastWeek)", "Daily average", "2026-03-04T05:06:07Z")

    fun testAConflictThenOverwrite() {
        changeOnServer()
        edit(File(func, "calcDailyAvg.axon"), "(points) => 1")
        val ui = FakeUi(choice = Choice.OVERWRITE)
        val out = run("calcDailyAvg", ui)
        assertTrue(out.toString(), out is Outcome.Pushed)
        assertEquals(listOf("conflict calcDailyAvg"), ui.asked)
        // the dialog showed the SkySpark version, read through the IDE API
        assertEquals("(points) => points.hisRead(lastWeek)", ui.serverSeen!!.src)
        assertTrue(server.requests.toString(), "GET /api/ide/projects/demo/site/functions?sync=true" in server.requests)
        // the second push sends the server's mod
        assertTrue(server.bodies.last(), server.bodies.last().contains("\"expectedMod\":\"2026-03-04T05:06:07Z\""))
        assertEquals("(points) => 1", server.projects["demo/site"]!!["calcDailyAvg"]!!.src)
        assertEquals(AxonProjFolderSync.shortHash("(points) => 1"), metaFn("calcDailyAvg")!!["hash"])
    }

    fun testAConflictThenTakeTheSkySparkVersion() {
        changeOnServer()
        edit(File(func, "calcDailyAvg.axon"), "(points) => 2")
        val out = run("calcDailyAvg", FakeUi(choice = Choice.TAKE_SKYSPARK))
        assertTrue(out.toString(), out is Outcome.TookSkySpark)
        assertEquals("(points) => points.hisRead(lastWeek)", File(func, "calcDailyAvg.axon").readText())
        // SkySpark is not changed, and the local text is kept in .versions
        assertEquals("(points) => points.hisRead(lastWeek)", server.projects["demo/site"]!!["calcDailyAvg"]!!.src)
        val kept = File(site, ".versions").listFiles().orEmpty().filter { it.name.startsWith("calcDailyAvg_") && it.name.endsWith(".axon") }
        assertEquals("(points) => 2", kept.single().readText())
        assertEquals("2026-03-04T05:06:07Z", metaFn("calcDailyAvg")!!["mod"])
        assertEquals(emptyList<String>(), syncAt(site).conflicts)
    }

    fun testAConflictThenCancelChangesNothing() {
        changeOnServer()
        edit(File(func, "calcDailyAvg.axon"), "(points) => 3")
        val before = File(site, ".sync-metadata.json").readText()
        assertEquals(Outcome.Cancelled, run("calcDailyAvg", FakeUi(choice = Choice.CANCEL)))
        assertEquals("(points) => 3", File(func, "calcDailyAvg.axon").readText())
        assertEquals(before, File(site, ".sync-metadata.json").readText())
        assertEquals("(points) => points.hisRead(lastWeek)", server.projects["demo/site"]!!["calcDailyAvg"]!!.src)
    }

    // ---- a new function ------------------------------------------------------------------------

    fun testANewFunctionIsCreatedAfterAConfirm() {
        File(func, "newFunc.axon").writeText("() => 9")
        val ui = FakeUi(create = true)
        val out = run("newFunc", ui)
        assertTrue(out.toString(), out is Outcome.Pushed)
        assertEquals(listOf("new newFunc"), ui.asked)
        assertEquals("""{"src":"() => 9","expectedMod":null}""", server.bodies.single())
        assertEquals("() => 9", server.projects["demo/site"]!!["newFunc"]!!.src)
        assertEquals(AxonProjFolderSync.shortHash("() => 9"), metaFn("newFunc")!!["hash"])
        assertEquals(3, (meta()["functionCount"] as Number).toInt())
        assertTrue(File(func, "newFunc.trio").isFile)
        assertTrue(AxonFunctionPush.message((out as Outcome.Pushed).plan, out.result).contains("new function"))
    }

    fun testANewFunctionThatIsNotConfirmedSendsNothing() {
        File(func, "newFunc.axon").writeText("() => 9")
        assertEquals(Outcome.Cancelled, run("newFunc", FakeUi(create = false)))
        assertEquals(emptyList<String>(), server.requests.toList())
        assertNull(server.projects["demo/site"]!!["newFunc"])
    }

    // ---- errors --------------------------------------------------------------------------------

    fun testAReadOnlyKeyIs403WithAClearMessage() {
        server.mode = "readonly"
        val e = failsWith(AxonCopyException.Kind.READ_ONLY) { AxonFunctionPush.run(project, target("calcDailyAvg"), FakeUi()) }
        assertTrue(AxonPushAction.errorText(e), AxonPushAction.errorText(e).contains("read-only"))
        assertEquals("(points) => points.hisRead(yesterday)", server.projects["demo/site"]!!["calcDailyAvg"]!!.src)
    }

    fun testARefusedKeyIs401() {
        AxonServerToken.set("tok-wrong")
        val e = failsWith(AxonCopyException.Kind.UNAUTHORIZED) { AxonFunctionPush.run(project, target("calcDailyAvg"), FakeUi()) }
        assertTrue(AxonPushAction.errorText(e).contains("refused your access key"))
    }

    fun testNoKeyIsRefusedBeforeAnyNetwork() {
        AxonServerToken.set(null)
        assertEquals(AxonFunctionPush.NO_KEY, AxonFunctionPush.whyNot(project, vf(File(func, "calcDailyAvg.axon"))))
        failsWith(AxonCopyException.Kind.NOT_CONFIGURED) { AxonFunctionPush.run(project, target("calcDailyAvg"), FakeUi()) }
        assertEquals(emptyList<String>(), server.requests.toList())
    }

    fun testNoServerUrl() {
        AxonServerSettings.getInstance().url = ""
        assertEquals(AxonFunctionPush.NO_SERVER, AxonFunctionPush.whyNot(project, vf(File(func, "calcDailyAvg.axon"))))
    }

    fun testAFileOutsideAProjectFolderIsRefused() {
        val loose = File(temp("loose"), "other.axon").also { it.writeText("() => 1") }
        assertNull(AxonFunctionPush.targetOf(vf(loose)))
        assertEquals(AxonFunctionPush.OUTSIDE, AxonFunctionPush.whyNot(project, vf(loose)))
        // a .trio is not a function file
        assertEquals(AxonFunctionPush.NOT_AXON, AxonFunctionPush.whyNot(project, vf(File(func, "calcDailyAvg.trio"))))
    }

    fun testACopyFileIsRefused() {
        val versions = File(site, ".versions").also { it.mkdirs() }
        val old = File(versions, "calcDailyAvg_2026-01-01T00-00-00-000Z.axon").also { it.writeText("() => 0") }
        assertNull(AxonFunctionPush.targetOf(vf(old)))
        assertEquals(AxonFunctionPush.COPY, AxonFunctionPush.whyNot(project, vf(old)))
    }

    fun testNoNetworkOrDiskOnTheUiThread() {
        val t = target("calcDailyAvg")
        assertTrue(ApplicationManager.getApplication().isDispatchThread)
        try {
            AxonFunctionPush.plan(t)
            fail("plan ran on the UI thread")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("UI thread"))
        }
        val plan = bg { AxonFunctionPush.plan(t) }
        try {
            AxonFunctionPush.push(project, plan)
            fail("push ran on the UI thread")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("UI thread"))
        }
        assertEquals(emptyList<String>(), server.requests.toList())
    }

    // ---- the action ----------------------------------------------------------------------------

    private fun update(file: VirtualFile, place: String = ActionPlaces.EDITOR_POPUP): Presentation {
        val action = ActionManager.getInstance().getAction(AxonPushAction.ID) as AxonPushAction
        val ctx = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.VIRTUAL_FILE, file).build()
        val kind = if (place == ActionPlaces.EDITOR_POPUP || place == ActionPlaces.PROJECT_VIEW_POPUP) ActionUiKind.POPUP else ActionUiKind.NONE
        val e = AnActionEvent.createEvent(action, ctx, Presentation(), place, kind, null)
        action.update(e)
        return e.presentation
    }

    fun testTheActionIsEnabledOnAFunctionFileAndSaysWhyNotElsewhere() {
        val p = update(vf(File(func, "calcDailyAvg.axon")))
        assertTrue(p.isVisible && p.isEnabled)
        assertTrue(p.description, p.description.contains("demo/site"))
        val loose = File(temp("loose"), "other.axon").also { it.writeText("() => 1") }
        val q = update(vf(loose), ActionPlaces.PROJECT_VIEW_POPUP)
        assertTrue(q.isVisible)
        assertFalse(q.isEnabled)
        assertEquals(AxonFunctionPush.OUTSIDE, q.description)
        // not on a folder in a popup menu
        assertFalse(update(vf(site), ActionPlaces.PROJECT_VIEW_POPUP).isVisible)
        AxonServerToken.set(null)
        assertEquals(AxonFunctionPush.NO_KEY, update(vf(File(func, "calcDailyAvg.axon"))).description)
    }
}
