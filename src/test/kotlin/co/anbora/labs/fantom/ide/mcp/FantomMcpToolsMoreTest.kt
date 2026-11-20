package co.anbora.labs.fantom.ide.mcp

import co.anbora.labs.fantom.ide.compiler.FantomCompileResult
import co.anbora.labs.fantom.ide.compiler.FantomCompilerService
import co.anbora.labs.fantom.ide.compiler.FantomDiagnostic
import co.anbora.labs.fantom.ide.hints.FantomTypeHintsProvider
import co.anbora.labs.fantom.ide.settings.FantomAgentSettings
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.library.FantomBuildFile
import co.anbora.labs.fantom.lang.library.FantomSettings
import co.anbora.labs.fantom.lang.library.deps.FantomDependencyResolver
import co.anbora.labs.fantom.lang.types.FantomTypeAt
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.Callable

/**
 * The 4.21 tools on a made-up pod `demo`: the agent switch, `fantom_type_of` against the
 * IDE's own lookup, `fantom_pod_deps`, and the build and test answers from canned output.
 */
class FantomMcpToolsMoreTest : BasePlatformTestCase() {

    private val build = """
        class Build : build::BuildPod {
          new make() { podName = "demo"; depends = ["sys 1.0", "concurrent 1.0+"] }
        }
    """.trimIndent()

    private val foo = """
        class Foo {
          Str[] names := Str[,]
          Int count() { return 3 }
          Void m() {
            x := count()
            ys := names.map |n| { n.size }
            echo(x)
          }
        }
    """.trimIndent()

    override fun tearDown() {
        try {
            FantomAgentSettings.getInstance(project).allowAgentRun = false
            FantomSettings.getInstance(project).homePath = null
        } finally {
            super.tearDown()
        }
    }

    private fun call(work: suspend () -> FantomMcpService.Answer): FantomMcpService.Answer {
        val f = ApplicationManager.getApplication().executeOnPooledThread(Callable { runCatching { runBlocking { work() } } })
        val end = System.currentTimeMillis() + 120_000
        while (!f.isDone) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(5)
            if (System.currentTimeMillis() > end) fail("the tool did not end")
        }
        return f.get().getOrThrow()
    }

    private fun ok(a: FantomMcpService.Answer): String { assertTrue("expected Ok: ${a.text}", a is FantomMcpService.Answer.Ok); return a.text }
    private fun failText(a: FantomMcpService.Answer): String { assertTrue("expected Fail: ${a.text}", a is FantomMcpService.Answer.Fail); return a.text }

    // ---- the switch --------------------------------------------------------------------

    fun testSwitchIsOffByDefault() {
        assertFalse(FantomAgentSettings().allowAgentRun)
        assertFalse(FantomAgentSettings.getInstance(project).allowAgentRun)
    }

    fun testBuildAndTestAreRefusedWhenOff() {
        myFixture.addFileToProject("build.fan", build)
        myFixture.addFileToProject("Foo.fan", foo)
        assertEquals(FantomMcpRun.OFF, failText(call { FantomMcpService.build(project, "demo", "", 0) }))
        assertEquals(FantomMcpRun.OFF, failText(call { FantomMcpService.runTest(project, "demo", "FooTest", "", 0) }))
        // nothing compiled
        val root = File(project.basePath!!)
        assertNull(FantomCompilerService.getInstance(project).result(root))
    }

    fun testWhenOnTheToolsRunAndCheckTheirInput() {
        FantomAgentSettings.getInstance(project).allowAgentRun = true
        assertFalse(failText(call { FantomMcpService.runTest(project, "demo", "Foo; rm", "", 0) }) == FantomMcpRun.OFF)
        assertTrue(failText(call { FantomMcpService.runTest(project, "demo", "Foo; rm", "", 0) }).startsWith("type must be a plain name"))
        assertTrue(failText(call { FantomMcpService.runTest(project, "demo", "", "testA", 0) }).startsWith("method needs a type"))
        assertTrue(failText(call { FantomMcpService.build(project, "nopod", "", 0) }).startsWith("no pod nopod"))
    }

    // ---- type_of -----------------------------------------------------------------------

    /** The tool's first line ("" when it answers Fail) and the IDE's own type (null when it has none). */
    private fun typeAt(file: com.intellij.psi.PsiFile, text: String, delta: Int = 0): Pair<String, String?> {
        val offset = file.text.indexOf(text) + delta
        val ide = ReadAction.compute<String?, RuntimeException> {
            (FantomTypeAt.expressionAt(file, offset)
                ?: FantomMcpQueries.usageTargetAt(file, offset)?.let { FantomTypeAt.declarationType(it) })?.presentableText
        }
        val answer = call { FantomMcpService.typeOf(project, "Foo.fan", offset, 0, 0, "", 0) }
        val tool = if (answer is FantomMcpService.Answer.Ok) answer.text.lines().first() else null
        return (tool ?: "") to ide
    }

    fun testTypeOfMatchesTheIde() {
        myFixture.addFileToProject("build.fan", build)
        val file = myFixture.addFileToProject("Foo.fan", foo)
        for ((text, delta) in listOf("count()" to 0, "names.map" to 0, "names.map" to 7, "n.size" to 2, "ys" to 0, "x)" to 0)) {
            val (tool, ide) = typeAt(file, text, delta)
            assertEquals("at '$text'+$delta", ide ?: "", tool)
        }
        assertEquals("Int", typeAt(file, "count()").first)
        assertEquals("Str[]", typeAt(file, "names.map").first)
    }

    fun testTypeOfALocalMatchesItsInlayHint() {
        myFixture.addFileToProject("build.fan", build)
        val file = myFixture.addFileToProject("Foo.fan", foo)
        val decl = ReadAction.compute<FantomNamedElement, RuntimeException> {
            PsiTreeUtil.findChildrenOfType(file, FantomNamedElement::class.java).first { it.name == "x" }
        }
        val hint = ReadAction.compute<String?, RuntimeException> { FantomTypeHintsProvider.hintType(decl)?.presentableText }
        assertNotNull(hint)
        assertEquals(hint, typeAt(file, "x :=").first)
    }

    fun testTypeOfBySlotName() {
        myFixture.addFileToProject("build.fan", build)
        myFixture.addFileToProject("Foo.fan", foo)
        val text = ok(call { FantomMcpService.typeOf(project, "", -1, 0, 0, "demo::Foo.names", 0) })
        assertEquals("1 slot\nfield demo::Foo.names: Str[]", text)
        assertTrue(failText(call { FantomMcpService.typeOf(project, "", -1, 0, 0, "Foo", 0) }).startsWith("give a slot"))
    }

    // ---- pod_deps ----------------------------------------------------------------------

    fun testPodDepsListsBuildFanDepends() {
        myFixture.addFileToProject("build.fan", build)
        myFixture.addFileToProject("Foo.fan", foo)
        val text = ok(call { FantomMcpService.podDeps(project, "", "Foo.fan") })
        val lines = text.lines()
        assertTrue(text, lines[0].startsWith("pod demo depends, build target"))
        assertTrue(text, lines[0].endsWith(": 2 pods"))
        assertTrue(text, lines[1].startsWith("sys 1.0 -> "))
        assertTrue(text, lines[2].startsWith("concurrent 1.0+ -> "))
    }

    fun testDepsReportShowsSourcesAndTarget() {
        val rows = listOf(
            FantomDependencyResolver.Row("concurrent", "1.0+", "Fantom 1.0.84 source", FantomDependencyResolver.How.AUTO),
            FantomDependencyResolver.Row("gone", "1.0", "", FantomDependencyResolver.How.NOT_FOUND),
        )
        val deps = listOf(
            FantomBuildFile.Dependency("concurrent", "1.0+"), FantomBuildFile.Dependency("gone", "1.0"),
            FantomBuildFile.Dependency("bldg2", "1.0"),
        )
        val text = FantomMcpRun.depsReport("site", deps, false, rows, setOf("bldg2"), "Fantom 1.0.84", false) { "pods/bldg2" }
        assertEquals(
            """
            pod site depends, build target Fantom 1.0.84: 3 pods
            concurrent 1.0+ -> Fantom 1.0.84 source, auto
            gone 1.0 -> not found, not found
            bldg2 1.0 -> this project (pods/bldg2)
            """.trimIndent(),
            text,
        )
        assertTrue(FantomMcpRun.depsReport("site", null, false, rows, emptySet(), null, false) { null }.contains("not a literal"))
    }

    // ---- build and test answers ----------------------------------------------------------

    fun testBuildReportGivesFileAndLine() {
        val diag = FantomDiagnostic("/p/site/fan/Foo.fan", 3, 23, HighlightSeverity.ERROR, "Unknown variable 'nme'")
        val warn = FantomDiagnostic("/p/site/fan/Bar.fan", 5, 1, HighlightSeverity.WARNING, "Deprecated slot")
        val failed = FantomCompileResult(File("/p/site"), 0, mapOf(diag.path to listOf(diag), warn.path to listOf(warn)), 255, true)
        assertEquals(
            "pod site: FAILED, 1 error, 1 warning\nWARNING fan/Bar.fan:5:1 Deprecated slot\nERROR fan/Foo.fan:3:23 Unknown variable 'nme'",
            FantomMcpRun.buildReport("site", failed, { it.removePrefix("/p/site/") }, 50),
        )
        val clean = FantomCompileResult(File("/p/site"), 0, emptyMap(), 0, true)
        assertEquals("pod site: built, 0 errors, 0 warnings", FantomMcpRun.buildReport("site", clean, { it }, 50))
        val odd = FantomCompileResult(File("/p/site"), 0, emptyMap(), 255, true)
        assertTrue(FantomMcpRun.buildReport("site", odd, { it }, 50).contains("exit 255, no message with a place"))
        val timeout = FantomCompileResult(File("/p/site"), 0, emptyMap(), -1, false)
        assertTrue(FantomMcpRun.buildReport("site", timeout, { it }, 50).contains("did not finish"))
    }

    private val newPrinter = """
        -- Run: demo::FooTest.testA
           Pass: demo::FooTest.testA [1]
        -- Run: demo::FooTest.testB

        TEST FAILED
        sys::TestErr: Test failed: 1 [sys::Int] != 2 [sys::Int]
          fan.sys.Test.err (Test.java:239)
          demo::FooTest.testB (FooTest.fan:7)

        Time: 20ms

        Failed:
          demo::FooTest.testB

        ***
        *** 1 FAILURES [1 types, 2 methods, 1 verifies]
        ***
    """.trimIndent()

    private val oldPrinter = """
        -- Run:  demo::FooTest.testA...
           Pass: demo::FooTest.testA [1]
        -- Run:  demo::FooTest.testB...
        TEST FAILED
        sys::TestErr: Test failed: 1 [sys::Int] != 2 [sys::Int]
          fan.sys.Test.err (Test.java:239)
          demo::FooTest.testB (FooTest.fan:7)
        Time: 20ms
    """.trimIndent()

    fun testTestReportGivesMessageAndPlaceForBothPrinters() {
        for (printed in listOf(newPrinter, oldPrinter)) {
            val out = FantomMcpRun.Output(printed, 255, false, false, false)
            val text = FantomMcpRun.testReport("demo::FooTest", FantomMcpRun.parseFant(printed), out, { "fan/$it" }, 50)
            assertEquals(
                "fant demo::FooTest: FAILED, 1 passed, 1 failed\nPASS demo::FooTest.testA\n" +
                    "FAIL demo::FooTest.testB fan/FooTest.fan:7 sys::TestErr: Test failed: 1 [sys::Int] != 2 [sys::Int]",
                text,
            )
        }
    }

    fun testTestReportWhenNothingRan() {
        val out = FantomMcpRun.Output("ERROR: cannot load pod demo\n", 1, false, false, false)
        val text = FantomMcpRun.testReport("demo", emptyList(), out, { it }, 50)
        assertEquals("fant demo: FAILED, 0 passed, 0 failed, no test ran (exit 1)\nERROR: cannot load pod demo", text)
        val stopped = FantomMcpRun.Output("", -1, true, false, true)
        assertTrue(FantomMcpRun.testReport("demo", emptyList(), stopped, { it }, 50).contains("stopped after 300 s"))
        assertTrue(FantomMcpRun.testReport("demo", emptyList(), stopped, { it }, 50).contains("output cut at"))
    }

    fun testRunCapsOutputAndStopsOnTimeout() {
        val cmd = com.intellij.execution.configurations.GeneralCommandLine("sh", "-c", "yes 0123456789")
        val out = FantomMcpRun.run(cmd, 1_000, 10_000) { false }
        assertTrue(out.timedOut)
        assertTrue(out.truncated)
        assertEquals(10_000, out.text.length)
        val quick = FantomMcpRun.run(com.intellij.execution.configurations.GeneralCommandLine("sh", "-c", "echo hi"), 10_000, 100) { false }
        assertFalse(quick.timedOut)
        assertEquals(0, quick.exitCode)
        assertEquals("hi", quick.text.trim())
    }
}
