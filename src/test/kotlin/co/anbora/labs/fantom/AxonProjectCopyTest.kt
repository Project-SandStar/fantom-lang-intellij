package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.axon.AxonConfigurable
import co.anbora.labs.fantom.ide.axon.AxonProjectWidget
import co.anbora.labs.fantom.ide.axon.AxonProjectWidgetFactory
import co.anbora.labs.fantom.ide.axon.AxonProjectWidgets
import co.anbora.labs.fantom.lang.axon.AxonFileType
import co.anbora.labs.fantom.lang.axon.copy.AxonCopyException
import co.anbora.labs.fantom.lang.axon.copy.AxonCopyLimits
import co.anbora.labs.fantom.lang.axon.copy.AxonFunction
import co.anbora.labs.fantom.lang.axon.copy.AxonFunctionList
import co.anbora.labs.fantom.lang.axon.copy.AxonFunctionSource
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectCache
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectCopy
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectCopyWritingAccessProvider
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectInfo
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectKey
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectLibraryRootsProvider
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectSettings
import co.anbora.labs.fantom.lang.axon.copy.AxonServerClient
import co.anbora.labs.fantom.lang.axon.copy.AxonServerSettings
import co.anbora.labs.fantom.lang.axon.copy.AxonServerToken
import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.State
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.WritingAccessProvider
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * The Axon project copy against a fake server on the loopback address and against a
 * made-up fallback folder: fetch, errors, the library root, the status bar text, the
 * settings and the push client. Every name here is made up.
 */
class AxonProjectCopyTest : BasePlatformTestCase() {
    private lateinit var server: FakeAxonServer
    private lateinit var cache: File
    private val temps = ArrayList<File>()
    private val key = AxonProjectKey("demo", "site")
    private val token = "tok-3f9a-not-in-xml"

    private val copy get() = AxonProjectCopy.getInstance(project)
    private val settings get() = AxonProjectSettings.getInstance(project)

    override fun setUp() {
        super.setUp()
        cache = temp("axon-cache")
        copy.testCacheRoot = cache
        server = FakeAxonServer(token)
        server.put("demo/site", "calcDailyAvg", "(points) => points.hisRead(yesterday)", "Daily average")
        server.put("demo/site", "siteReport", "() => readAll(site)")
        server.put("demo/plant", "plantLoad", "() => 42")
        AxonServerSettings.getInstance().url = server.url
        AxonServerToken.set(token)
    }

    override fun tearDown() {
        try {
            copy.testSource = null
            AxonServerSettings.getInstance().url = ""
            AxonServerToken.set(null)
            copy.dropNow()
            settings.fallbackPath = ""
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            copy.testCacheRoot = null
            server.close()
            temps.forEach { it.walkTopDown().forEach { f -> f.setWritable(true) }; FileUtil.delete(it) }
        } finally {
            super.tearDown()
        }
    }

    private fun temp(name: String): File = FileUtil.createTempDirectory(name, null).also { temps += it }

    /** Runs [work] off the UI thread, as the plugin does, while this thread serves UI events. */
    private fun <T> bg(work: () -> T): T = await(start(work))

    /** Starts [work] off the UI thread and does not wait for it. */
    private fun <T> start(work: () -> T): Future<Result<T>> =
        // the result is carried out as a value: the pool logs an exception that leaves a task as an error
        ApplicationManager.getApplication().executeOnPooledThread(Callable { runCatching(work) })

    /** Waits for the work of [start], while this thread serves UI events. */
    private fun <T> await(f: Future<Result<T>>): T {
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

    private fun cacheFiles(): List<String> =
        cache.walkTopDown().filter { it.isFile }.map { it.relativeTo(cache).path }.sorted().toList()

    // ---- server: list and fetch ----------------------------------------------------------------

    fun testListProjects() {
        val list = bg { copy.listProjectsNow() }
        assertEquals(listOf("demo/plant", "demo/site"), list.map { it.key.toString() })
        assertEquals(listOf(1, 2), list.map { it.functionCount })
        assertEquals(listOf("GET /api/ide/projects"), server.requests.toList())
        assertEquals(listOf("Bearer $token"), server.authHeaders.toList())
        assertTrue(cacheFiles().isEmpty())
    }

    fun testSelectFetchesAndWritesTheCopy() {
        val written = bg { copy.selectNow(key) }
        assertEquals(listOf("GET /api/ide/projects/demo/site/functions?sync=false"), server.requests.toList())
        assertEquals(listOf(".axon-copy.json", "func/calcDailyAvg.axon", "func/siteReport.axon", "index.json").map { "demo/site/$it" }, cacheFiles())
        assertEquals("server", written.source)

        assertEquals(key, copy.activeKey)
        assertEquals(key, settings.activeKey)
        assertEquals(File(cache, "demo/site"), copy.cacheRoot)
        assertEquals(listOf("calcDailyAvg", "siteReport"), copy.functions().map { it.name })
        assertEquals("Daily average", copy.function("calcDailyAvg")!!.doc)
        assertNull(copy.function("siteReport")!!.doc)
        assertEquals("2026-01-02T03:04:05Z", copy.function("calcDailyAvg")!!.mod)
        assertEquals(AxonCopyLimits.sha256("() => readAll(site)"), copy.function("siteReport")!!.hash)

        val vf = copy.findFunctionFile("calcDailyAvg")!!
        assertEquals("(points) => points.hisRead(yesterday)", String(vf.contentsToByteArray()))
        assertEquals(AxonFileType, vf.fileType)
        assertNull(copy.findFunctionFile("noSuchFunc"))
    }

    fun testTheCopyIsALibraryRootOutsideTheProjectContent() {
        bg { copy.selectNow(key) }
        val libs = AxonProjectLibraryRootsProvider().getAdditionalProjectLibraries(project)
        assertEquals(1, libs.size)
        val lib = libs.single() as AxonProjectLibraryRootsProvider.AxonLibrary
        assertEquals("Axon: demo/site", lib.presentableText)
        assertEquals(listOf(copy.libraryRoot), lib.sourceRoots.toList())
        assertEquals(File(cache, "demo/site/func").path, FileUtil.toSystemDependentName(copy.libraryRoot!!.path))
        assertEquals(listOf(copy.libraryRoot), AxonProjectLibraryRootsProvider().getRootsToWatch(project).toList())

        val vf = copy.findFunctionFile("siteReport")!!
        val index = ProjectFileIndex.getInstance(project)
        assertTrue("the file is not in a library", index.isInLibrary(vf))
        assertFalse("the file is in the project content", index.isInContent(vf))
        assertFalse(vf.path.startsWith(project.basePath!!))
    }

    fun testTheFilesAreReadOnly() {
        bg { copy.selectNow(key) }
        val vf = copy.findFunctionFile("calcDailyAvg")!!
        assertFalse(vf.isWritable)
        assertTrue(copy.isInCopy(vf))
        assertEquals(listOf(vf), AxonProjectCopyWritingAccessProvider(project).requestWriting(listOf(vf)).toList())
        // through the extension point: the provider is registered
        assertFalse(WritingAccessProvider.isPotentiallyWritable(vf, project))
        val other = myFixture.addFileToProject("mine.axon", "() => 1").virtualFile
        assertFalse(copy.isInCopy(other))
        assertTrue(WritingAccessProvider.isPotentiallyWritable(other, project))
    }

    fun testRefreshAsksTheServerToSync() {
        bg { copy.selectNow(key) }
        assertNull(copy.copy!!.syncedAt)
        server.put("demo/site", "newFunc", "() => 7")
        server.projects["demo/site"]!!.remove("siteReport")
        server.requests.clear()

        bg { copy.refreshNow() }
        assertEquals(listOf("GET /api/ide/projects/demo/site/functions?sync=true"), server.requests.toList())
        assertEquals("2026-05-06T07:08:09Z", copy.copy!!.syncedAt)
        assertEquals(listOf("calcDailyAvg", "newFunc"), copy.functions().map { it.name })
        assertNull(copy.findFunctionFile("siteReport"))
        assertEquals("() => 7", String(copy.findFunctionFile("newFunc")!!.contentsToByteArray()))
        assertFalse(File(cache, "demo/site/func/siteReport.axon").exists())
    }

    fun testARefreshSeesNewSourceOfTheSameFunction() {
        bg { copy.selectNow(key) }
        assertEquals("() => readAll(site)", String(copy.findFunctionFile("siteReport")!!.contentsToByteArray()))
        server.put("demo/site", "siteReport", "() => readAll(site and geoCity)")
        bg { copy.refreshNow() }
        val vf = copy.findFunctionFile("siteReport")!!
        assertTrue(vf.isValid)
        assertEquals("() => readAll(site and geoCity)", String(vf.contentsToByteArray()))
    }

    fun testNamesWithSpacesAndDotsAreSentAsEncodedPathSegments() {
        val wild = AxonProjectKey("demo site", "site.v2")
        server.put("demo site/site.v2", "calc daily avg", "() => 1")
        server.put("demo site/site.v2", "..", "() => 2")
        server.put("demo site/site.v2", "../../evil", "() => 3")
        bg { copy.selectNow(wild) }
        assertEquals("GET /api/ide/projects/demo%20site/site.v2/functions?sync=false", server.requests.single())
        assertEquals(
            listOf(".axon-copy.json", "func/%2E%2E%2F%2E%2E%2Fevil.axon", "func/%2E%2E.axon", "func/calc%20daily%20avg.axon", "index.json")
                .map { "demo%20site/site%2Ev2/$it" },
            cacheFiles())
        // nothing was written outside the cache folder
        assertEquals(listOf("demo%20site"), cache.list()!!.toList())
        assertFalse(File(cache.parentFile, "evil.axon").exists())
        assertFalse(File(cache.parentFile.parentFile, "evil.axon").exists())
        assertEquals("() => 3", String(copy.findFunctionFile("../../evil")!!.contentsToByteArray()))
        assertEquals("() => 2", String(copy.findFunctionFile("..")!!.contentsToByteArray()))
        assertEquals("Axon: demo site/site.v2", AxonProjectWidgets.text(project))
    }

    fun testAProjectNamedDotDotIsOnePathSegment() {
        server.put("demo/..", "calcDailyAvg", "() => 1")
        bg { copy.selectNow(AxonProjectKey("demo", "..")) }
        assertEquals("GET /api/ide/projects/demo/%2E%2E/functions?sync=false", server.requests.single())
        assertEquals(File(cache, "demo/%2E%2E"), copy.cacheRoot)
    }

    // ---- server: errors ------------------------------------------------------------------------

    fun testAWrongTokenIs401AndWritesNothing() {
        AxonServerToken.set("wrong")
        failsWith(AxonCopyException.Kind.UNAUTHORIZED) { copy.selectNow(key) }
        failsWith(AxonCopyException.Kind.UNAUTHORIZED) { copy.listProjectsNow() }
        AxonServerToken.set(null)
        val e = failsWith(AxonCopyException.Kind.UNAUTHORIZED) { copy.selectNow(key) }
        assertEquals(listOf("Bearer wrong", "Bearer wrong", ""), server.authHeaders.toList())
        assertFalse(e.message!!.contains("wrong"))
        assertTrue(cacheFiles().isEmpty())
        assertNull(copy.activeKey)
        assertNull(copy.copy)
    }

    fun testAnUnknownProjectIs404AndWritesNothing() {
        failsWith(AxonCopyException.Kind.UNKNOWN_PROJECT) { copy.selectNow(AxonProjectKey("demo", "nope")) }
        assertEquals(listOf("GET /api/ide/projects/demo/nope/functions?sync=false"), server.requests.toList())
        assertTrue(cacheFiles().isEmpty())
        assertNull(copy.activeKey)
        assertEquals("Axon: none", AxonProjectWidgets.text(project))
    }

    fun testAReplyForAnotherProjectWritesNothing() {
        server.mode = "wrongKey"
        failsWith(AxonCopyException.Kind.BAD_REPLY) { copy.selectNow(key) }
        assertTrue(cacheFiles().isEmpty())
        assertNull(copy.activeKey)
        assertTrue(AxonProjectLibraryRootsProvider().getAdditionalProjectLibraries(project).isEmpty())
    }

    fun testATimeoutWritesNothing() {
        server.mode = "slow"
        copy.testSource = AxonServerClient(server.url, token, connectTimeoutMs = 2_000, readTimeoutMs = 200)
        val start = System.currentTimeMillis()
        failsWith(AxonCopyException.Kind.TIMEOUT) { copy.selectNow(key) }
        assertTrue(System.currentTimeMillis() - start < 1_400)
        assertTrue(cacheFiles().isEmpty())
        assertNull(copy.activeKey)
    }

    fun testAServerThatIsNotThere() {
        server.close()
        failsWith(AxonCopyException.Kind.IO) { copy.selectNow(key) }
        assertTrue(cacheFiles().isEmpty())
    }

    fun testBadRepliesKeepTheOldCopy() {
        bg { copy.selectNow(key) }
        val before = cacheFiles().associateWith { File(cache, it).readText() }
        val vf = copy.findFunctionFile("calcDailyAvg")!!
        server.put("demo/site", "calcDailyAvg", "() => \"changed on the server\"")

        for ((mode, kind) in listOf(
            "cut" to AxonCopyException.Kind.BAD_REPLY,
            "garbage" to AxonCopyException.Kind.BAD_REPLY,
            "error500" to AxonCopyException.Kind.IO,
            "wrongKey" to AxonCopyException.Kind.BAD_REPLY,
        )) {
            server.mode = mode
            failsWith(kind) { copy.refreshNow() }
            assertEquals(mode, before, cacheFiles().associateWith { File(cache, it).readText() })
            assertEquals(mode, key, copy.activeKey)
            assertTrue(mode, vf.isValid)
            assertEquals(mode, "(points) => points.hisRead(yesterday)", String(vf.contentsToByteArray()))
            assertEquals(mode, 1, AxonProjectLibraryRootsProvider().getAdditionalProjectLibraries(project).size)
        }
        // picking another project that fails keeps the old key and the old copy too
        server.mode = "ok"
        failsWith(AxonCopyException.Kind.UNKNOWN_PROJECT) { copy.selectNow(AxonProjectKey("demo", "nope")) }
        assertEquals(key, copy.activeKey)
        assertEquals(before.keys.toList(), cacheFiles().filter { !it.contains(".old-") })
    }

    fun testTheErrorOfTheServerIsShownButCutShort() {
        server.mode = "error500"
        val e = failsWith(AxonCopyException.Kind.IO) { copy.listProjectsNow() }
        assertTrue(e.message, e.message!!.contains("HTTP 500"))
        assertTrue(e.message, e.message!!.contains("the database is down"))
    }

    fun testARedirectIsNotFollowedSoTheTokenStaysWithTheConfiguredHost() {
        FakeAxonServer(token).use { elsewhere ->
            elsewhere.put("demo/site", "stolen", "() => 0")
            server.mode = "redirect"
            server.redirectTo = elsewhere.url
            failsWith(AxonCopyException.Kind.IO) { copy.selectNow(key) }
            assertTrue(elsewhere.requests.isEmpty())
            assertTrue(cacheFiles().isEmpty())
        }
    }

    fun testAReplyOverTheSizeCapIsRefused() {
        server.mode = "huge"
        copy.testSource = AxonServerClient(server.url, token, maxBytes = 100_000)
        failsWith(AxonCopyException.Kind.TOO_LARGE) { copy.selectNow(key) }
        assertTrue(cacheFiles().isEmpty())
    }

    fun testNoNetworkOnTheUiThread() {
        try {
            copy.selectNow(key)
            fail("a fetch on the UI thread must be refused")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("UI thread"))
        }
        assertTrue(server.requests.isEmpty())
    }

    fun testAUrlWithoutHttpIsRefused() {
        AxonServerSettings.getInstance().url = "ftp://127.0.0.1/x"
        failsWith(AxonCopyException.Kind.NOT_CONFIGURED) { copy.listProjectsNow() }
        AxonServerSettings.getInstance().url = ""
        failsWith(AxonCopyException.Kind.NOT_CONFIGURED) { copy.listProjectsNow() }
        assertTrue(server.requests.isEmpty())
    }

    // ---- one copy per IDE project, drop, restart -----------------------------------------------

    fun testPickingAnotherProjectDeletesTheOldCopy() {
        bg { copy.selectNow(key) }
        val old = copy.findFunctionFile("calcDailyAvg")!!
        bg { copy.selectNow(AxonProjectKey("demo", "plant")) }
        assertEquals(listOf(".axon-copy.json", "func/plantLoad.axon", "index.json").map { "demo/plant/$it" }, cacheFiles())
        assertEquals(AxonProjectKey("demo", "plant"), copy.activeKey)
        assertEquals(listOf("plantLoad"), copy.functions().map { it.name })
        assertNull(copy.findFunctionFile("calcDailyAvg"))
        assertFalse(old.isValid)
        val libs = AxonProjectLibraryRootsProvider().getAdditionalProjectLibraries(project)
        assertEquals(listOf("Axon: demo/plant"), libs.map { (it as AxonProjectLibraryRootsProvider.AxonLibrary).presentableText })
    }

    fun testDropDeletesTheCopy() {
        bg { copy.selectNow(key) }
        val vf = copy.findFunctionFile("calcDailyAvg")!!
        copy.dropNow()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(cacheFiles().isEmpty())
        assertFalse(File(cache, "demo").exists())
        assertNull(copy.activeKey)
        assertNull(settings.activeKey)
        assertNull(copy.cacheRoot)
        assertNull(copy.libraryRoot)
        assertTrue(copy.functions().isEmpty())
        assertNull(copy.findFunctionFile("calcDailyAvg"))
        assertFalse(vf.isValid)
        assertTrue(AxonProjectLibraryRootsProvider().getAdditionalProjectLibraries(project).isEmpty())
        assertEquals("Axon: none", AxonProjectWidgets.text(project))
        // a second drop is fine
        copy.dropNow()
    }

    fun testTheCopyIsRegisteredAgainAfterARestartWithoutTheNetwork() {
        bg { copy.selectNow(key) }
        copy.forgetForTest()
        assertNull(copy.libraryRoot)
        assertTrue(copy.functions().isEmpty())
        assertEquals(key, copy.activeKey)
        server.requests.clear()
        server.close()

        copy.load()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(server.requests.isEmpty())
        assertEquals(listOf("calcDailyAvg", "siteReport"), copy.functions().map { it.name })
        assertEquals("Daily average", copy.function("calcDailyAvg")!!.doc)
        assertEquals(1, AxonProjectLibraryRootsProvider().getAdditionalProjectLibraries(project).size)
        assertTrue(ProjectFileIndex.getInstance(project).isInLibrary(copy.findFunctionFile("siteReport")!!))
    }

    fun testLoadWithACopyThatIsGoneRegistersNothing() {
        bg { copy.selectNow(key) }
        File(cache, "demo/site/.axon-copy.json").apply { setWritable(true); delete() }
        copy.load()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertNull(copy.copy)
        assertTrue(AxonProjectLibraryRootsProvider().getAdditionalProjectLibraries(project).isEmpty())
        // the key stays: Refresh brings the copy back
        assertEquals(key, copy.activeKey)
        bg { copy.refreshNow() }
        assertEquals(2, copy.functions().size)
    }

    // ---- fallback folder -----------------------------------------------------------------------

    private fun projFolder(): File {
        val proj = temp("proj")
        File(proj, "demo/site/func").mkdirs()
        File(proj, "demo/site/.sync-metadata.json").writeText("""{"lastSync":"2026-04-05T06:07:08.000Z","functionCount":2}""")
        File(proj, "demo/site/func/calcDailyAvg.axon").writeText("(points) => points.hisRead(yesterday)")
        File(proj, "demo/site/func/calcDailyAvg.trio").writeText("name:calcDailyAvg\nfunc\ndoc:Daily average\n")
        File(proj, "demo/site/func/calc daily avg.axon").writeText("() => 1")
        File(proj, "lab/plant/func").mkdirs()
        File(proj, "lab/plant/.sync-metadata.json").writeText("{}")
        File(proj, "lab/notAProject/func").mkdirs()
        return proj
    }

    fun testTheFallbackFolderIsReadWhenNoServerUrlIsSet() {
        AxonServerSettings.getInstance().url = ""
        settings.fallbackPath = projFolder().path
        assertEquals(listOf("demo/site", "lab/plant"), copy.listProjectsNow().map { it.key.toString() })
        val c = copy.selectNow(key)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals("folder", c.source)
        assertEquals("2026-04-05T06:07:08.000Z", c.syncedAt)
        assertEquals(listOf("calc daily avg", "calcDailyAvg"), copy.functions().map { it.name })
        assertEquals("Daily average", copy.function("calcDailyAvg")!!.doc)
        assertEquals("() => 1", String(copy.findFunctionFile("calc daily avg")!!.contentsToByteArray()))
        assertTrue(ProjectFileIndex.getInstance(project).isInLibrary(copy.findFunctionFile("calcDailyAvg")!!))
        assertTrue(server.requests.isEmpty())
        assertTrue(AxonProjectWidgets.tooltip(project).contains("the fallback folder"))
    }

    fun testTheServerWinsOverTheFallbackFolderAndAFailingServerIsNotReplacedByIt() {
        settings.fallbackPath = projFolder().path
        bg { copy.selectNow(key) }
        assertEquals("server", copy.copy!!.source)
        assertEquals(listOf("calcDailyAvg", "siteReport"), copy.functions().map { it.name })
        server.mode = "error500"
        failsWith(AxonCopyException.Kind.IO) { copy.refreshNow() }
        assertEquals("server", copy.copy!!.source)
    }

    fun testNoSourceAtAll() {
        AxonServerSettings.getInstance().url = ""
        assertFalse(copy.isConfigured())
        val e = failsWith(AxonCopyException.Kind.NOT_CONFIGURED) { copy.listProjectsNow() }
        assertTrue(e.message!!.contains("Settings"))
    }

    // ---- push ----------------------------------------------------------------------------------

    fun testPushOkAndConflict() {
        bg { copy.selectNow(key) }
        server.requests.clear()
        val ok = bg { copy.pushFunction("calcDailyAvg", "() => \"v2\"") }
        assertTrue(ok.ok)
        assertFalse(ok.conflict)
        assertEquals("2026-06-01T00:00:00Z", ok.mod)
        assertEquals(AxonCopyLimits.sha256("() => \"v2\""), ok.hash)
        assertEquals(listOf("POST /api/ide/projects/demo/site/functions/calcDailyAvg"), server.requests.toList())
        // no "doc" in the body: the stored doc is kept
        assertEquals("""{"src":"() => \"v2\"","expectedMod":"2026-01-02T03:04:05Z"}""", server.bodies.single())
        assertEquals("Daily average", server.projects["demo/site"]!!["calcDailyAvg"]!!.doc)
        // the local copy is not touched by a push
        assertEquals("(points) => points.hisRead(yesterday)", String(copy.findFunctionFile("calcDailyAvg")!!.contentsToByteArray()))

        // the copy still holds the old mod: the next push is a conflict, and nothing is stored
        val conflict = bg { copy.pushFunction("calcDailyAvg", "() => \"v3\"", doc = "New doc") }
        assertFalse(conflict.ok)
        assertTrue(conflict.conflict)
        assertEquals("2026-06-01T00:00:00Z", conflict.mod)
        assertEquals(AxonCopyLimits.sha256("() => \"v2\""), conflict.hash)
        assertEquals("() => \"v2\"", server.projects["demo/site"]!!["calcDailyAvg"]!!.src)
        assertEquals("""{"src":"() => \"v3\"","doc":"New doc","expectedMod":"2026-01-02T03:04:05Z"}""", server.bodies.last())
    }

    fun testPushOfANewFunctionSendsExpectedModNullAndEncodesTheName() {
        bg { copy.selectNow(key) }
        server.requests.clear()
        val r = bg { copy.pushFunction("new func/v2", "() => 1", doc = "A doc") }
        assertTrue(r.ok)
        assertEquals("POST /api/ide/projects/demo/site/functions/new%20func%2Fv2", server.requests.single())
        assertEquals("""{"src":"() => 1","doc":"A doc","expectedMod":null}""", server.bodies.single())
        assertEquals("A doc", server.projects["demo/site"]!!["new func/v2"]!!.doc)
    }

    fun testPushErrors() {
        failsWith(AxonCopyException.Kind.NOT_CONFIGURED) { copy.pushFunction("calcDailyAvg", "() => 1") }
        val client = AxonServerClient(server.url, "wrong")
        failsWith(AxonCopyException.Kind.UNAUTHORIZED) { client.pushFunction(key, "calcDailyAvg", "() => 1", null, null) }
        val good = AxonServerClient(server.url, token)
        failsWith(AxonCopyException.Kind.UNKNOWN_PROJECT) { good.pushFunction(AxonProjectKey("demo", "nope"), "calcDailyAvg", "() => 1", null, null) }
        assertEquals("(points) => points.hisRead(yesterday)", server.projects["demo/site"]!!["calcDailyAvg"]!!.src)
    }

    // ---- widget --------------------------------------------------------------------------------

    fun testWidgetTextAndItems() {
        assertEquals("Axon: none", AxonProjectWidgets.text(project))
        assertEquals(listOf(AxonProjectWidgets.Item.PICK, AxonProjectWidgets.Item.SETTINGS), AxonProjectWidgets.items(project))
        assertTrue(AxonProjectWidgetFactory().isAvailable(project))

        bg { copy.selectNow(key) }
        assertEquals("Axon: demo/site", AxonProjectWidgets.text(project))
        assertEquals(AxonProjectWidgets.Item.values().toList(), AxonProjectWidgets.items(project))
        assertTrue(AxonProjectWidgets.tooltip(project).contains("2 functions from the Axon server"))
        val widget = AxonProjectWidget(project)
        try {
            assertEquals("Axon: demo/site", widget.getSelectedValue())
            assertEquals(AxonProjectWidget.ID, widget.ID())
            assertEquals(AxonProjectWidget.ID, AxonProjectWidgetFactory().id)
        } finally {
            Disposer.dispose(widget)
        }

        AxonServerSettings.getInstance().url = ""
        assertEquals(listOf(AxonProjectWidgets.Item.DROP, AxonProjectWidgets.Item.SETTINGS), AxonProjectWidgets.items(project))
        assertTrue(AxonProjectWidgetFactory().isAvailable(project))
        copy.dropNow()
        assertFalse(AxonProjectWidgetFactory().isAvailable(project))
        assertEquals("demo/site  (1 function)", AxonProjectWidgets.label(AxonProjectInfo(key, 1, null)))
    }

    /** An in-memory source, so that the widget actions can run inline on the test's UI thread. */
    private class MemSource(val data: MutableMap<AxonProjectKey, List<AxonFunction>>) : AxonFunctionSource {
        val syncs = ArrayList<Boolean>()
        override val id = "server"
        override fun listProjects(check: () -> Unit) = data.map { AxonProjectInfo(it.key, it.value.size, null) }
        override fun fetch(key: AxonProjectKey, sync: Boolean, check: () -> Unit): AxonFunctionList {
            syncs += sync
            return AxonFunctionList(key, null, data[key] ?: throw AxonCopyException(AxonCopyException.Kind.UNKNOWN_PROJECT, "unknown"))
        }
    }

    private fun fn(name: String, src: String) = AxonFunction(name, src, null, null, AxonCopyLimits.sha256(src))

    fun testWidgetRefreshAndDrop() {
        val mem = MemSource(mutableMapOf(key to listOf(fn("calcDailyAvg", "() => 1"))))
        copy.testSource = mem
        var fired = 0
        project.messageBus.connect(testRootDisposable).subscribe(AxonProjectCopy.TOPIC, AxonProjectCopy.Listener { fired++ })
        copy.select(key)
        assertEquals("Axon: demo/site", AxonProjectWidgets.text(project))
        assertTrue(fired > 0)

        mem.data[key] = listOf(fn("calcDailyAvg", "() => 2"), fn("siteReport", "() => 3"))
        AxonProjectWidgets.run(project, AxonProjectWidgets.Item.REFRESH)
        assertEquals(listOf(false, true), mem.syncs)
        assertEquals(listOf("calcDailyAvg", "siteReport"), copy.functions().map { it.name })
        assertEquals("() => 2", String(copy.findFunctionFile("calcDailyAvg")!!.contentsToByteArray()))

        AxonProjectWidgets.run(project, AxonProjectWidgets.Item.DROP)
        assertEquals("Axon: none", AxonProjectWidgets.text(project))
        assertTrue(cacheFiles().isEmpty())
    }

    // ---- a fetch that ends after a newer choice ------------------------------------------------

    /** A source whose fetch of [slow] waits, once it is [armed], until [release] is counted down. */
    private class GateSource(val inner: MemSource, val slow: AxonProjectKey) : AxonFunctionSource {
        @Volatile var armed = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override val id = "server"
        override fun listProjects(check: () -> Unit) = inner.listProjects(check)
        override fun fetch(key: AxonProjectKey, sync: Boolean, check: () -> Unit): AxonFunctionList {
            // the reply is made before the wait: it is the old state of the server
            val list = inner.fetch(key, sync, check)
            if (armed && key == slow) {
                entered.countDown()
                if (!release.await(30, TimeUnit.SECONDS)) throw AssertionError("the gate was not opened")
            }
            return list
        }
    }

    private val plant = AxonProjectKey("demo", "plant")

    private fun gate(): GateSource {
        val mem = MemSource(mutableMapOf(key to listOf(fn("calcDailyAvg", "() => 1")), plant to listOf(fn("plantLoad", "() => 2"))))
        return GateSource(mem, key).also { copy.testSource = it }
    }

    /** Starts [work], which must stop in the gate, and returns when it is there. */
    private fun startLate(gate: GateSource, work: () -> AxonProjectCache.Copy): Future<Result<AxonProjectCache.Copy>> {
        gate.armed = true
        val f = start(work)
        assertTrue("the fetch did not start", gate.entered.await(30, TimeUnit.SECONDS))
        return f
    }

    private fun assertWroteNothing(gate: GateSource, late: Future<Result<AxonProjectCache.Copy>>) {
        gate.release.countDown()
        try {
            await(late)
            fail("a fetch that ends after a newer choice must write nothing")
        } catch (e: ProcessCanceledException) {
            // expected: the fetch is dropped without a message
        }
    }

    private val plantFiles = listOf(".axon-copy.json", "func/plantLoad.axon", "index.json").map { "demo/plant/$it" }

    fun testAPickThatEndsAfterANewerPickWritesNothing() {
        val gate = gate()
        val late = startLate(gate) { copy.selectNow(key) }
        bg { copy.selectNow(plant) }
        assertWroteNothing(gate, late)
        assertEquals(plant, copy.activeKey)
        assertEquals(plantFiles, cacheFiles())
        assertEquals(listOf("plantLoad"), copy.functions().map { it.name })
        assertEquals("Axon: demo/plant", AxonProjectWidgets.text(project))
    }

    fun testAPickThatEndsAfterADropWritesNothing() {
        val gate = gate()
        bg { copy.selectNow(plant) }
        val late = startLate(gate) { copy.selectNow(key) }
        copy.dropNow()
        assertWroteNothing(gate, late)
        assertNull(copy.activeKey)
        assertTrue(cacheFiles().toString(), cacheFiles().isEmpty())
        assertNull(copy.copy)
        assertEquals("Axon: none", AxonProjectWidgets.text(project))
    }

    fun testARefreshThatEndsAfterADropWritesNothing() {
        val gate = gate()
        bg { copy.selectNow(key) }
        val late = startLate(gate) { copy.refreshNow() }
        copy.dropNow()
        assertWroteNothing(gate, late)
        assertNull(copy.activeKey)
        assertTrue(cacheFiles().toString(), cacheFiles().isEmpty())
        assertNull(copy.copy)
    }

    fun testARefreshThatEndsAfterAPickOfAnotherProjectWritesNothing() {
        val gate = gate()
        bg { copy.selectNow(key) }
        val late = startLate(gate) { copy.refreshNow() }
        bg { copy.selectNow(plant) }
        assertWroteNothing(gate, late)
        assertEquals(plant, copy.activeKey)
        assertEquals(plantFiles, cacheFiles())
        assertEquals(listOf("plantLoad"), copy.functions().map { it.name })
    }

    fun testARefreshOfAKeyThatIsNoLongerActiveWritesNothing() {
        val gate = gate()
        bg { copy.selectNow(key) }
        gate.inner.data[key] = listOf(fn("calcDailyAvg", "() => 9"), fn("siteReport", "() => 3"))
        val late = startLate(gate) { copy.refreshNow() }
        // the key changes with no pick and no drop, as when the workspace file is loaded again
        settings.activeKey = plant
        assertWroteNothing(gate, late)
        assertEquals(plant, copy.activeKey)
        assertEquals("() => 1", File(cache, "demo/site/func/calcDailyAvg.axon").readText())
        assertFalse(File(cache, "demo/site/func/siteReport.axon").exists())
        assertFalse(File(cache, "demo/plant").exists())
        settings.activeKey = key
    }

    fun testARefreshThatEndsInTimeStillWrites() {
        val gate = gate()
        bg { copy.selectNow(key) }
        gate.inner.data[key] = listOf(fn("calcDailyAvg", "() => 9"))
        val late = startLate(gate) { copy.refreshNow() }
        gate.release.countDown()
        assertEquals(key, await(late).key)
        assertEquals("() => 9", String(copy.findFunctionFile("calcDailyAvg")!!.contentsToByteArray()))
    }

    // ---- settings ------------------------------------------------------------------------------

    private fun xml(state: Any): String = JDOMUtil.write(XmlSerializer.serialize(state))

    fun testTheKeyAndTheFolderAreInTheWorkspaceFileAndTheTokenInNoXml() {
        val storages = AxonProjectSettings::class.java.getAnnotation(State::class.java).storages
        assertEquals(listOf(StoragePathMacros.WORKSPACE_FILE), storages.map { it.value })
        assertEquals("\$WORKSPACE_FILE\$", storages.single().value)

        settings.activeKey = AxonProjectKey("demoinstance", "siteproject")
        settings.fallbackPath = "/made/up/proj"
        val workspace = xml(settings.state)
        assertTrue(workspace, workspace.contains("demoinstance") && workspace.contains("siteproject") && workspace.contains("/made/up/proj"))

        // the shared project settings and the application file know nothing of them
        val shared = xml(FantomSettings.getInstance(project).state)
        val app = xml(AxonServerSettings.getInstance().state)
        for (text in listOf(shared, app)) {
            assertFalse(text, text.contains("demoinstance") || text.contains("siteproject") || text.contains("/made/up/proj"))
        }
        assertTrue(app, app.contains(server.url))
        assertEquals(token, AxonServerToken.get())
        for (text in listOf(workspace, shared, app)) assertFalse(text, text.contains(token))
        // the application file is not part of settings sync
        val appStorage = AxonServerSettings::class.java.getAnnotation(State::class.java).storages.single()
        assertEquals("fantom-axon.xml", appStorage.value)
        assertEquals(com.intellij.openapi.components.RoamingType.DISABLED, appStorage.roamingType)
        settings.activeKey = null
    }

    fun testSettingsPageSaysWhetherAKeyIsStored() {
        val page = AxonConfigurable(project)
        try {
            page.createComponent()
            // setUp stored a key: the page says so, and the field stays empty
            assertEquals(true, page.tokenStored)
            assertTrue(page.tokenState.text, page.tokenState.text.contains("is stored"))
            assertEquals("", String(page.tokenField.password))

            // remove it: the label changes at once, before the store write
            page.removeTokenBox.isSelected = true
            page.apply()
            assertEquals(false, page.tokenStored)
            assertTrue(page.tokenState.text, page.tokenState.text.contains("No access key"))
            bg { Thread.sleep(50) }
            assertNull(AxonServerToken.get())

            // type a new one and save: the label says stored again
            page.tokenField.text = token
            page.apply()
            assertEquals(true, page.tokenStored)
            bg { Thread.sleep(50) }
            assertEquals(token, AxonServerToken.get())
            page.reset()
            assertEquals(true, page.tokenStored)
        } finally {
            page.disposeUIResources()
        }
    }

    fun testConnectionTestReportsTheResult() {
        // the good key: the server answers with its projects
        val ok = bg { AxonConfigurable.testConnection(server.url, token, typedKey = true) }
        assertTrue(ok.second, ok.first)
        assertTrue(ok.second, ok.second.contains("works") && ok.second.contains("2 projects"))

        // a wrong key: refused, and the text says which key
        val bad = bg { AxonConfigurable.testConnection(server.url, "wrong-key", typedKey = false) }
        assertFalse(bad.first)
        assertTrue(bad.second, bad.second.contains("refused the stored key"))

        // no key at all
        val none = bg { AxonConfigurable.testConnection(server.url, null, typedKey = false) }
        assertFalse(none.first)
        assertTrue(none.second, none.second.contains("No access key"))

        // nothing listens on the URL
        val down = bg { AxonConfigurable.testConnection("http://127.0.0.1:1", token, typedKey = true) }
        assertFalse(down.first)
        assertTrue(down.second, down.second.contains("Cannot reach"))

        // the key never shows in the text
        for (r in listOf(ok, bad, none, down)) assertFalse(r.second.contains(token))
    }

    fun testSettingsPageStoresUrlFolderAndKeyAndNeverShowsTheToken() {
        val mem = MemSource(mutableMapOf(key to listOf(fn("calcDailyAvg", "() => 1"))))
        copy.testSource = mem
        val page = AxonConfigurable(project)
        try {
            page.createComponent()
            assertEquals(server.url, page.urlField.text)
            assertEquals("", String(page.tokenField.password))
            assertEquals("", page.keyField.text)
            assertFalse(page.isModified)

            page.urlField.text = server.url + "/"
            assertFalse("a slash at the end is no change", page.isModified)
            page.keyField.text = "demo"
            assertTrue(page.isModified)
            try {
                page.apply()
                fail("a key without a project must be refused")
            } catch (e: ConfigurationException) {
                // expected
            }
            page.urlField.text = "127.0.0.1:8080"
            page.keyField.text = ""
            try {
                page.apply()
                fail("a URL without http must be refused")
            } catch (e: ConfigurationException) {
                // expected
            }

            val folder = projFolder()
            page.urlField.text = "http://127.0.0.1:1/"
            page.tokenField.text = "new-token-5d1c"
            page.fallbackField.text = folder.path
            page.keyField.text = " demo/site "
            page.apply()
            assertEquals("http://127.0.0.1:1", AxonServerSettings.getInstance().url)
            assertEquals(folder.path, settings.fallbackPath)
            assertEquals("", String(page.tokenField.password))
            val end = System.currentTimeMillis() + 10_000
            while (copy.copy?.key != key && System.currentTimeMillis() < end) {
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
                Thread.sleep(10)
            }
            assertEquals("new-token-5d1c", AxonServerToken.get())
            assertEquals(key, settings.activeKey)
            assertEquals(listOf("calcDailyAvg"), copy.functions().map { it.name })
            assertFalse(page.isModified)
            page.reset()
            assertEquals("demo/site", page.keyField.text)
            assertEquals("", String(page.tokenField.password))

            // an empty token field keeps the stored token; the box removes it
            page.apply()
            assertEquals("new-token-5d1c", AxonServerToken.get())
            page.removeTokenBox.isSelected = true
            assertTrue(page.isModified)
            page.keyField.text = ""
            page.apply()
            val end2 = System.currentTimeMillis() + 10_000
            while ((AxonServerToken.get() != null || settings.activeKey != null || cacheFiles().isNotEmpty()) && System.currentTimeMillis() < end2) {
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
                Thread.sleep(10)
            }
            assertNull(AxonServerToken.get())
            assertNull(settings.activeKey)
            assertTrue(cacheFiles().isEmpty())
        } finally {
            page.disposeUIResources()
        }
    }
}
