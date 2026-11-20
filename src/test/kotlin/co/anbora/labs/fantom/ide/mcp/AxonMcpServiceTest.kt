package co.anbora.labs.fantom.ide.mcp

import co.anbora.labs.fantom.ide.axon.AxonDocumentationProvider
import co.anbora.labs.fantom.lang.axon.copy.AxonCopyException
import co.anbora.labs.fantom.lang.axon.copy.AxonCopyLimits
import co.anbora.labs.fantom.lang.axon.copy.AxonFunction
import co.anbora.labs.fantom.lang.axon.copy.AxonFunctionList
import co.anbora.labs.fantom.lang.axon.copy.AxonFunctionSource
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectCopy
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectInfo
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectKey
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectSettings
import co.anbora.labs.fantom.lang.axon.copy.AxonServerSettings
import co.anbora.labs.fantom.lang.axon.proj.AxonProjFolders
import co.anbora.labs.fantom.lang.axon.project.AxonProjectFunctions
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.Callable

/**
 * Task 4.31: the Axon agent tools give the answers the editor gives, for a made-up proj folder
 * and a made-up project copy; they refuse files outside the IDE project and the copy, answer
 * "indexing, retry" in dumb mode, and cap their output. Every name here is made up.
 */
class AxonMcpServiceTest : BasePlatformTestCase() {
    private lateinit var cache: File
    private val copy get() = AxonProjectCopy.getInstance(project)
    private val server get() = AxonServerSettings.getInstance()

    override fun setUp() {
        super.setUp()
        server.projFolder = ""
        AxonProjFolders.clearForTest()
        cache = FileUtil.createTempDirectory("axon-agent-cache", null)
        copy.testCacheRoot = cache
    }

    override fun tearDown() {
        try {
            copy.dropNow()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            AxonProjectSettings.getInstance(project).activeKey = null
            copy.testSource = null
            copy.testCacheRoot = null
            cache.walkTopDown().forEach { it.setWritable(true) }
            FileUtil.delete(cache)
            server.projFolder = ""
            AxonProjFolders.clearForTest()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        } finally {
            super.tearDown()
        }
    }

    // ---- calling the tools as the MCP server does: off the UI thread ----------------------------

    private fun call(work: suspend () -> FantomMcpService.Answer): FantomMcpService.Answer {
        val f = ApplicationManager.getApplication().executeOnPooledThread(Callable { runCatching { runBlocking { work() } } })
        val end = System.currentTimeMillis() + 60_000
        while (!f.isDone) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(5)
            if (System.currentTimeMillis() > end) fail("the tool did not end")
        }
        return f.get().getOrThrow()
    }

    private fun projectOf(path: String) = call { AxonMcpService.projectOf(project, path) }
    private fun functions(path: String = "", key: String = "", filter: String = "", limit: Int = 0) =
        call { AxonMcpService.functions(project, path, key, filter, limit) }
    private fun function(path: String = "", key: String = "", name: String = "") =
        call { AxonMcpService.function(project, path, key, name) }

    private fun ok(answer: FantomMcpService.Answer): String {
        assertTrue("expected Ok, got: ${answer.text}", answer is FantomMcpService.Answer.Ok)
        return answer.text
    }

    private fun failText(answer: FantomMcpService.Answer): String {
        assertTrue("expected Fail, got: ${answer.text}", answer is FantomMcpService.Answer.Fail)
        return answer.text
    }

    // ---- made-up data -------------------------------------------------------------------------

    private fun meta(instance: String, project: String) = """{"instance":"$instance","project":"$project","functionCount":1}"""

    /** proj/demo/site and proj/demo/other, each with a `siteArea`, and a backup copy. */
    private fun layout() {
        myFixture.addFileToProject("proj/demo/site/.sync-metadata.json", meta("demo", "site"))
        myFixture.addFileToProject("proj/demo/site/func/siteArea.axon", "(site) => site->area")
        myFixture.addFileToProject(
            "proj/demo/site/func/siteArea.trio",
            "name: siteArea\nfunc\ndoc:\n  Area of a site.\n  In square metres.\nmod: 2024-03-01T10:00:00Z\nsrc: (site) => site->area\n",
        )
        myFixture.addFileToProject("proj/demo/site/func/siteReport.axon", "() => readAll(site).map(siteArea)")
        myFixture.addFileToProject("proj/demo/other/.sync-metadata.json", meta("demo", "other"))
        myFixture.addFileToProject("proj/demo/other/func/siteArea.axon", "(site) => 0")
        myFixture.addFileToProject("proj/demo/other/func/otherOnly.axon", "() => siteArea(1)")
        myFixture.addFileToProject("proj/demo/backup/site/.sync-metadata.json", meta("demo", "site"))
        myFixture.addFileToProject("proj/demo/backup/site/func/old.axon", "() => siteArea(1)")
    }

    private fun psi(path: String): PsiFile =
        PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path) ?: error("no $path"))!!

    private fun selectCopy(): AxonProjectKey {
        val key = AxonProjectKey("demo", "site")
        copy.testSource = object : AxonFunctionSource {
            override val id = "server"
            override fun listProjects(check: () -> Unit) = listOf(AxonProjectInfo(key, 2, null))
            override fun fetch(key: AxonProjectKey, sync: Boolean, check: () -> Unit): AxonFunctionList {
                if (key.project != "site") throw AxonCopyException(AxonCopyException.Kind.UNKNOWN_PROJECT, "unknown")
                return AxonFunctionList(
                    key, null,
                    listOf(
                        AxonFunction("siteArea", "(site) => site->area", "Area of a site.\nIn square metres.", "2024-01-02T03:04:05Z", AxonCopyLimits.sha256("(site) => site->area")),
                        AxonFunction("siteReport", "() => 1", null, null, AxonCopyLimits.sha256("() => 1")),
                    ),
                )
            }
        }
        copy.selectNow(key)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        return key
    }

    // ---- axon_project_of ----------------------------------------------------------------------

    fun testProjectOfAProjFolderFileIsTheEditorsProject() {
        layout()
        val text = ok(projectOf("proj/demo/site/func/siteReport.axon"))
        val ide = AxonProjectFunctions.of(psi("proj/demo/site/func/siteReport.axon"))!!.title
        assertEquals("demo/site", ide)
        assertEquals("$ide (proj folder)\nfolder: proj/demo/site\nfunctions: 2", text)
        assertTrue(ok(projectOf("proj/demo/other/func/otherOnly.axon")).startsWith("demo/other (proj folder)"))
        // a folder works too
        assertTrue(ok(projectOf("proj/demo/site/func")).startsWith("demo/site (proj folder)"))
    }

    fun testProjectOfTakesTheKeyFromTheMetadata() {
        myFixture.addFileToProject("proj/folder-a/folder-b/.sync-metadata.json", meta("plant-a", "bldg2"))
        myFixture.addFileToProject("proj/folder-a/folder-b/func/load.axon", "() => 42")
        assertTrue(ok(projectOf("proj/folder-a/folder-b/func/load.axon")).startsWith("plant-a/bldg2 (proj folder)"))
    }

    fun testABackupFolderHasNoProject() {
        layout()
        assertTrue(ok(projectOf("proj/demo/backup/site/func/old.axon")).startsWith("none: a backup"))
        assertTrue(failText(functions("proj/demo/backup/site/func/old.axon")).startsWith("no Axon project"))
    }

    fun testAFileOutsideAnyProject() {
        myFixture.addFileToProject("x.axon", "() => 1")
        assertEquals("none: not in a proj folder, and no Axon project is active", ok(projectOf("x.axon")))
        assertTrue(failText(functions()).startsWith("no Axon project"))
    }

    fun testAnActiveProjectWithNoCopyYet() {
        myFixture.addFileToProject("x.axon", "() => 1")
        AxonProjectSettings.getInstance(project).activeKey = AxonProjectKey("demo", "site")
        val text = ok(projectOf("x.axon"))
        assertTrue(text, text.startsWith("none: the active Axon project demo/site has no copy yet"))
    }

    // ---- axon_functions -----------------------------------------------------------------------

    fun testFunctionsOfAProjFolderAreTheEditorsFunctions() {
        layout()
        val ide = AxonProjectFunctions.of(psi("proj/demo/site/func/siteReport.axon"))!!.functions().map { it.name }.sorted()
        assertEquals(listOf("siteArea", "siteReport"), ide)
        assertEquals(
            "demo/site (proj folder): 2 functions\n" +
                "siteArea  proj/demo/site/func/siteArea.axon  Area of a site.\n" +
                "siteReport  proj/demo/site/func/siteReport.axon",
            ok(functions("proj/demo/site/func/siteReport.axon")),
        )
        // by key, the same answer
        assertEquals(ok(functions("proj/demo/site/func/siteReport.axon")), ok(functions(key = "demo/site")))
        assertEquals(
            "demo/other (proj folder): 1 function matching \"site\"\nsiteArea  proj/demo/other/func/siteArea.axon",
            ok(functions(key = "demo/other", filter = "site")),
        )
    }

    fun testFunctionsAreCapped() {
        myFixture.addFileToProject("proj/demo/big/.sync-metadata.json", meta("demo", "big"))
        for (i in 1..7) myFixture.addFileToProject("proj/demo/big/func/f$i.axon", "() => $i")
        val text = ok(functions("proj/demo/big/func/f1.axon", limit = 3))
        val lines = text.lines()
        assertEquals("demo/big (proj folder): 7 functions", lines[0])
        assertEquals(listOf("f1", "f2", "f3"), lines.subList(1, 4).map { it.substringBefore(' ') })
        assertTrue(text, lines[4].startsWith("... 4 more"))
        assertEquals(5, lines.size)
        // limit 0 is the default; a huge limit is clamped
        assertEquals(8, ok(functions("proj/demo/big/func/f1.axon", limit = 100_000)).lines().size)
    }

    fun testTwoFoldersWithOneKeyAreNeverMixed() {
        layout()
        myFixture.addFileToProject("proj2/demo/site/.sync-metadata.json", meta("demo", "site"))
        myFixture.addFileToProject("proj2/demo/site/func/siteArea.axon", "(site) => 2")
        val text = failText(functions(key = "demo/site"))
        assertTrue(text, text.startsWith("2 project folders are demo/site"))
        assertTrue(text, text.contains("proj/demo/site") && text.contains("proj2/demo/site"))
        // a path picks one
        assertTrue(ok(function("proj2/demo/site/func/siteArea.axon")).endsWith("src:\n(site) => 2"))
    }

    fun testAnUnknownKey() {
        layout()
        assertTrue(failText(functions(key = "demo/nowhere")).startsWith("no project demo/nowhere in the IDE"))
        assertTrue(failText(functions(key = "nokey")).startsWith("not a project key"))
    }

    // ---- axon_function ------------------------------------------------------------------------

    fun testFunctionIsWhatQuickDocShows() {
        layout()
        val info = AxonDocumentationProvider.info(psi("proj/demo/site/func/siteArea.axon"))!!
        assertEquals("siteArea(site)", info.signature)
        val expected = "siteArea(site)\n" +
            "project: demo/site (proj folder)\n" +
            "file: proj/demo/site/func/siteArea.axon\n" +
            "mod: 2024-03-01T10:00:00Z\n" +
            "doc:\n${info.doc}\n" +
            "src:\n(site) => site->area"
        assertEquals("Area of a site.\nIn square metres.", info.doc)
        assertEquals(expected, ok(function("proj/demo/site/func/siteArea.axon")))
        // by name from another file of the project, and by key
        assertEquals(expected, ok(function("proj/demo/site/func/siteReport.axon", name = "siteArea")))
        assertEquals(expected, ok(function(key = "demo/site", name = "siteArea")))
    }

    fun testFunctionOfTheSameNameInAnotherProjectIsNeverReturned() {
        layout()
        assertTrue(ok(function("proj/demo/site/func/siteReport.axon", name = "siteArea")).endsWith("src:\n(site) => site->area"))
        assertTrue(ok(function("proj/demo/other/func/otherOnly.axon", name = "siteArea")).endsWith("src:\n(site) => 0"))
        assertTrue(failText(function("proj/demo/other/func/otherOnly.axon", name = "siteReport")).startsWith("no function siteReport in demo/other"))
    }

    fun testFunctionWithoutTrioHasNoDocAndAnUnknownMod() {
        layout()
        val text = ok(function("proj/demo/site/func/siteReport.axon"))
        assertTrue(text, text.contains("\nmod: unknown\ndoc: none\nsrc:\n() => readAll(site).map(siteArea)"))
    }

    fun testFunctionShowsUnsavedEdits() {
        layout()
        val vf = myFixture.findFileInTempDir("proj/demo/site/func/siteArea.axon")!!
        WriteCommandAction.runWriteCommandAction(project) {
            FileDocumentManager.getInstance().getDocument(vf)!!.setText("(site, unit) => site->area")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val text = ok(function("proj/demo/site/func/siteArea.axon"))
        assertTrue(text, text.startsWith("siteArea(site, unit)\n"))
        assertTrue(text, text.endsWith("src:\n(site, unit) => site->area"))
    }

    fun testSourceIsCapped() {
        myFixture.addFileToProject("proj/demo/site/.sync-metadata.json", meta("demo", "site"))
        myFixture.addFileToProject("proj/demo/site/func/long.axon", "// " + "x".repeat(AxonMcpService.MAX_SOURCE + 100) + "\n() => 1")
        val text = ok(function("proj/demo/site/func/long.axon"))
        assertTrue(text.contains("... cut at ${AxonMcpService.MAX_SOURCE} of "))
        assertTrue(text.length < AxonMcpService.MAX_SOURCE + 1000)
    }

    // ---- the active copy ----------------------------------------------------------------------

    fun testTheActiveCopy() {
        val key = selectCopy()
        myFixture.addFileToProject("one.axon", "() => siteArea(1)")
        val head = "$key (active copy)"
        assertTrue(ok(projectOf("one.axon")).startsWith(head))

        val areaFile = copy.findFunctionFile("siteArea")!!
        val reportFile = copy.findFunctionFile("siteReport")!!
        val list = ok(functions())
        assertEquals("$head: 2 functions\nsiteArea  ${areaFile.path}  Area of a site.\nsiteReport  ${reportFile.path}", list)
        assertEquals(list, ok(functions("one.axon")))
        assertEquals(list, ok(functions(key = "demo/site")))

        // the file the list gives is a path the other tools take
        assertTrue(ok(projectOf(areaFile.path)).startsWith(head))
        val info = AxonDocumentationProvider.info(PsiManager.getInstance(project).findFile(areaFile))!!
        val text = ok(function(areaFile.path))
        assertEquals(
            "${info.signature}\nproject: $head\nfile: ${areaFile.path}\nmod: 2024-01-02T03:04:05Z\ndoc:\n${info.doc}\nsrc:\n(site) => site->area",
            text,
        )
        assertEquals(text, ok(function(name = "siteArea")))
    }

    // ---- guards -------------------------------------------------------------------------------

    fun testAGlobalProjFolderThatIsNotOpenIsRefused() {
        val root = FileUtil.createTempDirectory("axon-global-proj", null)
        try {
            File(root, "demo/hidden/func").mkdirs()
            File(root, "demo/hidden/.sync-metadata.json").writeText(meta("demo", "hidden"))
            File(root, "demo/hidden/func/secretFn.axon").writeText("() => \"secret source\"")
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(root)!!.refresh(false, true)
            server.projFolder = root.path
            AxonProjFolders.clearForTest()
            val file = File(root, "demo/hidden/func/secretFn.axon").path
            for (a in listOf(projectOf(file), functions(file), function(file), function(file, name = "secretFn"))) {
                val t = failText(a)
                assertTrue(t, t.startsWith("not a file in the project or the Axon copy"))
                assertFalse(t, t.contains("secret source"))
            }
            assertTrue(failText(functions(key = "demo/hidden")).startsWith("no project demo/hidden in the IDE"))
            assertTrue(failText(projectOf("../outside.axon")).startsWith("file not found"))
        } finally {
            FileUtil.delete(root)
        }
    }

    fun testDumbModeAnswersIndexing() {
        layout()
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertEquals(FantomMcpQueries.INDEXING, failText(projectOf("proj/demo/site/func/siteArea.axon")))
            assertEquals(FantomMcpQueries.INDEXING, failText(functions("proj/demo/site/func/siteArea.axon")))
            assertEquals(FantomMcpQueries.INDEXING, failText(function("proj/demo/site/func/siteArea.axon")))
        }
    }

    // ---- the toolset --------------------------------------------------------------------------

    fun testToolNamesAndRoutingText() {
        val tools = AxonMcpToolset::class.java.declaredMethods.filter { it.isAnnotationPresent(McpTool::class.java) }
        assertEquals(listOf("axon_function", "axon_functions", "axon_project_of"), tools.map { it.name }.sorted())
        for (m in tools) {
            val d = m.getAnnotation(McpDescription::class.java)!!.description
            assertTrue("${m.name} must route to the Axon MCP server", d.contains("Axon MCP server"))
            assertTrue("${m.name} must say what it answers from", d.contains("IDE"))
            assertTrue("${m.name} must name the dumb mode answer", d.contains(FantomMcpQueries.INDEXING))
        }
    }

    fun testToolsetIsRegistered() {
        val ours = com.intellij.mcpserver.McpToolset.EP.extensionList.filterIsInstance<AxonMcpToolset>()
        assertEquals(1, ours.size)
    }
}
