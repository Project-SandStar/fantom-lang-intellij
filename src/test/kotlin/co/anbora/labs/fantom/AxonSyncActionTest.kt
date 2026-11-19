package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.axon.AxonSyncAction
import co.anbora.labs.fantom.lang.axon.copy.AxonCopyException
import co.anbora.labs.fantom.lang.axon.copy.AxonCopyLimits
import co.anbora.labs.fantom.lang.axon.copy.AxonFunction
import co.anbora.labs.fantom.lang.axon.copy.AxonFunctionList
import co.anbora.labs.fantom.lang.axon.copy.AxonFunctionSource
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectInfo
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectCopy
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectKey
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectLibraryRootsProvider
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectSettings
import co.anbora.labs.fantom.lang.axon.copy.AxonServerSettings
import co.anbora.labs.fantom.lang.axon.copy.AxonServerToken
import co.anbora.labs.fantom.lang.axon.copy.AxonSyncResult
import co.anbora.labs.fantom.lang.axon.copy.AxonSyncTarget
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.util.concurrent.Callable

/**
 * "Sync Axon Functions" (task 4.39) against the fake Axon server: the `sync=true` fetch,
 * the added, changed and removed functions, the menus, the reasons it is disabled, and
 * the proj-folder hook. Every name here is made up.
 */
class AxonSyncActionTest : BasePlatformTestCase() {
    private lateinit var server: FakeAxonServer
    private val temps = ArrayList<File>()
    private val key = AxonProjectKey("demo", "site")
    private val token = "tok-77c1-made-up"

    private val copy get() = AxonProjectCopy.getInstance(project)
    private val action get() = ActionManager.getInstance().getAction(AxonSyncAction.ID) as AxonSyncAction

    override fun setUp() {
        super.setUp()
        copy.testCacheRoot = temp("axon-cache")
        server = FakeAxonServer(token)
        server.put("demo/site", "calcDailyAvg", "(points) => points.hisRead(yesterday)")
        server.put("demo/site", "siteReport", "() => readAll(site)")
        server.put("demo/bldg2", "plantLoad", "() => 42")
        AxonServerSettings.getInstance().url = server.url
        AxonServerToken.set(token)
    }

    override fun tearDown() {
        try {
            AxonServerSettings.getInstance().url = ""
            AxonServerToken.set(null)
            copy.dropNow()
            AxonProjectSettings.getInstance(project).fallbackPath = ""
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

    private fun event(place: String, file: VirtualFile? = null, items: Array<Any>? = null): AnActionEvent {
        val b = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
        if (file != null) b.add(CommonDataKeys.VIRTUAL_FILE, file)
        if (items != null) b.add(PlatformCoreDataKeys.SELECTED_ITEMS, items)
        val uiKind = if (place == ActionPlaces.PROJECT_VIEW_POPUP) ActionUiKind.POPUP else ActionUiKind.NONE
        return AnActionEvent.createEvent(action, b.build(), Presentation(), place, uiKind, null)
    }

    private fun update(e: AnActionEvent): Presentation {
        action.update(e)
        return e.presentation
    }

    // ---- the sync -------------------------------------------------------------------------

    fun testSyncAsksTheServerToSyncAndReportsAddedChangedAndRemoved() {
        bg { copy.selectNow(key) }
        server.put("demo/site", "newFunc", "() => 7")
        server.put("demo/site", "calcDailyAvg", "(points) => points.hisRead(today)")
        server.projects["demo/site"]!!.remove("siteReport")
        server.requests.clear()

        val r = bg { copy.syncNow() }
        assertEquals(listOf("GET /api/ide/projects/demo/site/functions?sync=true"), server.requests.toList())
        assertEquals(listOf("newFunc"), r.added)
        assertEquals(listOf("calcDailyAvg"), r.changed)
        assertEquals(listOf("siteReport"), r.removed)
        assertEquals(2, r.total)
        assertEquals(listOf("calcDailyAvg", "newFunc"), copy.functions().map { it.name })
        assertTrue(r.message(), r.message().startsWith("Axon: demo/site synced, 1 added, 1 changed, 1 removed (2 functions)."))
        assertTrue(r.message(), r.message().contains("Removed: siteReport."))
    }

    fun testASyncWithNoChangeSaysSo() {
        bg { copy.selectNow(key) }
        val r = bg { copy.syncNow() }
        assertEquals("Axon: demo/site synced, 0 added, 0 changed, 0 removed (2 functions).", r.message())
    }

    fun testALongListOfNamesIsCutShort() {
        val after = (1..8).associate { "f$it" to "h" }
        val r = AxonSyncResult.diff(key, emptyMap(), after)
        assertTrue(r.message(), r.message().endsWith("Added: f1, f2, f3, f4, f5, and 3 more."))
    }

    fun testASyncThatFailsKeepsTheCopy() {
        bg { copy.selectNow(key) }
        AxonServerToken.set("wrong")
        try {
            bg { copy.syncNow() }
            fail("expected a 401")
        } catch (e: AxonCopyException) {
            assertEquals(AxonCopyException.Kind.UNAUTHORIZED, e.kind)
        }
        assertEquals(listOf("calcDailyAvg", "siteReport"), copy.functions().map { it.name })
    }

    fun testNoSyncWithoutAServerOrAProject() {
        try {
            bg { copy.syncNow() }
            fail("expected no project")
        } catch (e: AxonCopyException) {
            assertEquals(AxonCopyException.Kind.NOT_CONFIGURED, e.kind)
        }
        AxonServerSettings.getInstance().url = ""
        AxonProjectSettings.getInstance(project).fallbackPath = temp("proj").path
        try {
            bg { copy.syncNow() }
            fail("expected no server")
        } catch (e: AxonCopyException) {
            assertEquals(AxonCopyException.Kind.NOT_CONFIGURED, e.kind)
        }
        assertTrue(server.requests.isEmpty())
    }

    fun testTheBackgroundSyncNotifiesTheResult() {
        bg { copy.selectNow(key) }
        server.put("demo/site", "newFunc", "() => 7")
        val seen = ArrayList<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) { seen += notification }
        })
        // in unit tests the task runs inline on the UI thread with a test source only, so no network here
        val names = listOf("calcDailyAvg", "newFunc", "siteReport")
        copy.testSource = object : AxonFunctionSource {
            override val id = "server"
            override fun listProjects(check: () -> Unit) = emptyList<AxonProjectInfo>()
            override fun fetch(key: AxonProjectKey, sync: Boolean, check: () -> Unit) =
                AxonFunctionList(key, null, names.map { n ->
                    val old = copy.function(n)
                    AxonFunction(n, "() => 7", null, null, old?.hash ?: AxonCopyLimits.sha256(n))
                })
        }
        try {
            copy.sync()
        } finally {
            copy.testSource = null
        }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(seen.joinToString { it.content }, seen.any { it.content.startsWith("Axon: demo/site synced, 1 added") })
    }

    // ---- the action -----------------------------------------------------------------------

    fun testTheActionIsInTheToolsMenuAndTheProjectViewMenu() {
        val am = ActionManager.getInstance()
        for (group in listOf("ToolsMenu", "ProjectViewPopupMenu")) {
            val ids = (am.getAction(group) as com.intellij.openapi.actionSystem.DefaultActionGroup)
                .getChildActionsOrStubs().mapNotNull { am.getId(it) }
            assertTrue("$group: $ids", AxonSyncAction.ID in ids)
        }
    }

    fun testTheActionIsDisabledWithAReason() {
        // no active project
        var p = update(event(ActionPlaces.MAIN_MENU))
        assertTrue(p.isVisible)
        assertFalse(p.isEnabled)
        assertEquals(AxonSyncAction.NO_PROJECT, p.description)

        bg { copy.selectNow(key) }
        p = update(event(ActionPlaces.MAIN_MENU))
        assertTrue(p.isEnabled)

        // no key: the hint is what the last read of the password store saw
        bg { AxonServerToken.set(null) }
        p = update(event(ActionPlaces.MAIN_MENU))
        assertFalse(p.isEnabled)
        assertEquals(AxonSyncAction.NO_KEY, p.description)

        // no server
        AxonServerSettings.getInstance().url = ""
        p = update(event(ActionPlaces.MAIN_MENU))
        assertFalse(p.isEnabled)
        assertEquals(AxonSyncAction.NO_SERVER, p.description)
    }

    fun testInTheProjectViewOnlyOnTheRootAndTheAxonNode() {
        bg { copy.selectNow(key) }
        val src = myFixture.addFileToProject("src/a.fan", "class A {}").virtualFile
        assertFalse(update(event(ActionPlaces.PROJECT_VIEW_POPUP, src)).isVisible)
        assertFalse(update(event(ActionPlaces.PROJECT_VIEW_POPUP)).isVisible)

        // the library node: the Project view gives the node, the library and its folder
        val lib = AxonProjectLibraryRootsProvider().getAdditionalProjectLibraries(project).single()
        var p = update(event(ActionPlaces.PROJECT_VIEW_POPUP, null, arrayOf(lib)))
        assertTrue(p.isVisible)
        assertTrue(p.isEnabled)
        assertTrue(update(event(ActionPlaces.PROJECT_VIEW_POPUP, copy.libraryRoot)).isVisible)

        // the project root shows it; a folder outside the project does not
        val root = com.intellij.openapi.roots.ProjectRootManager.getInstance(project).contentRoots.first()
        assertTrue(update(event(ActionPlaces.PROJECT_VIEW_POPUP, root)).isVisible)
        val other = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(temp("other"))!!
        assertFalse(update(event(ActionPlaces.PROJECT_VIEW_POPUP, other)).isVisible)
    }

    fun testTheProjFolderHookGetsTheFunctionsAndTheCopyIsLeftAlone() {
        bg { copy.selectNow(key) }
        val folder = temp("proj")
        val hook = FakeTarget(AxonProjectKey("demo", "bldg2"), folder)
        ExtensionTestUtil.maskExtensions(AxonSyncTarget.EP_NAME, listOf(hook), testRootDisposable)
        val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(folder)!!

        val p = update(event(ActionPlaces.PROJECT_VIEW_POPUP, file))
        assertTrue(p.isVisible)
        assertTrue(p.isEnabled)
        assertTrue(p.description, p.description.contains("demo/bldg2"))

        server.requests.clear()
        val match = AxonSyncTarget.find(project, file)!!
        val r = bg { copy.syncNow(match) }
        assertEquals(listOf("GET /api/ide/projects/demo/bldg2/functions?sync=true"), server.requests.toList())
        assertEquals(listOf("plantLoad"), hook.written)
        assertEquals(listOf("plantLoad"), r.added)
        // the read-only copy of the active project is not touched
        assertEquals(key, copy.activeKey)
        assertEquals(listOf("calcDailyAvg", "siteReport"), copy.functions().map { it.name })
    }

    private class FakeTarget(val key: AxonProjectKey, val folder: File) : AxonSyncTarget {
        val written = ArrayList<String>()
        override fun targetFor(project: Project, file: VirtualFile): AxonSyncTarget.Target? =
            if (File(file.path) == folder) AxonSyncTarget.Target(key, folder) else null

        override fun write(project: Project, target: AxonSyncTarget.Target, list: AxonFunctionList, check: () -> Unit): AxonSyncResult {
            written += list.functions.map { it.name }
            return AxonSyncResult.diff(target.key, emptyMap(), list.functions.associate { it.name to it.hash })
        }
    }
}
