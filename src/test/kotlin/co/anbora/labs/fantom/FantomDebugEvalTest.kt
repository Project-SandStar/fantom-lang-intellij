package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.debugger.FantomDebugRunner
import co.anbora.labs.fantom.ide.run.FantomRunConfiguration
import co.anbora.labs.fantom.ide.run.FantomRunConfigurationType
import co.anbora.labs.fantom.lang.FantomLanguage
import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.debugger.ui.breakpoints.JavaLineBreakpointType
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.swing.Icon

/**
 * Fantom expression evaluation in a live debug session: compile a temp pod with the local Fantom
 * install, stop in `main` after all locals are set, evaluate Fantom expressions there, then check
 * that a breakpoint with the Fantom condition `i == 3` stops only when it is true.
 * Skips when `~/fantom/fantom-1.0.84` is absent.
 */
class FantomDebugEvalTest : HeavyPlatformTestCase() {
    private val home = File(System.getProperty("user.home"), "fantom/fantom-1.0.84")
    private var session: XDebugSession? = null

    private val fooSource = """
        class Foo
        {
          Str name := "fooName"

          static Void main(Str[] args)
          {
            x := 3
            y := x * 2
            s := "abc"
            list := [1, 2, 3]
            map := ["a": 1]
            f := Foo()
            Foo? g := null
            echo("${'$'}x ${'$'}y ${'$'}s ${'$'}list ${'$'}map ${'$'}f.name ${'$'}g")
            for (i := 0; i < 5; ++i)
            {
              echo(i)
            }
          }
        }
    """.trimIndent() + "\n"

    private val evalLine = 13 // 0-based: the first `echo`
    private val loopLine = 16 // 0-based: `echo(i)`

    private val buildSource = """
        using build
        class Build : build::BuildPod
        {
          new make()
          {
            podName = "evalpod"
            summary = "debug eval"
            depends = ["sys 1.0"]
            srcDirs = [`fan/`]
          }
        }
    """.trimIndent() + "\n"

    override fun tearDown() {
        try {
            session?.let { s ->
                s.stop()
                PlatformTestUtil.waitWithEventsDispatching("debug session did not stop", { s.isStopped }, 30)
                val handler = s.debugProcess.processHandler
                handler.destroyProcess()
                handler.waitFor(10_000)
            }
            FantomSettings.getInstance(project).homePath = null
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun compilePod(root: File): File {
        val pod = File(root, "evalpod").apply { File(this, "fan").mkdirs() }
        File(pod, "build.fan").writeText(buildSource)
        File(pod, "fan/Foo.fan").writeText(fooSource)
        val work = File(root, "work").apply { mkdirs() }
        File(work, "fan.props").writeText("// debugger eval test work dir\n")
        val proc = ProcessBuilder(File(home, "bin/fan").path, File(pod, "build.fan").path, "compile")
            .directory(work).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        assertTrue("fan build timed out", proc.waitFor(120, TimeUnit.SECONDS))
        assertTrue("fan build failed:\n$out", File(work, "lib/fan/evalpod.pod").isFile)
        return work
    }

    fun testEvaluateFantomExpressionsAndCondition() {
        if (!File(home, "bin/fan").isFile) return
        val root = Files.createTempDirectory("fantom-debug-eval").toFile()
        val work = compilePod(root)
        FantomSettings.getInstance(project).homePath = home.path

        val podDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(root, "evalpod"))!!
        VfsUtil.markDirtyAndRefresh(false, true, true, podDir)
        PsiTestUtil.addContentRoot(module, podDir)
        // The module has no Java SDK, as in a plain Fantom project: FantomJdkRootsProvider supplies java.base.
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val foo = podDir.findFileByRelativePath("fan/Foo.fan")!!

        val type = XDebuggerUtil.getInstance().findBreakpointType(JavaLineBreakpointType::class.java)
        WriteAction.runAndWait<Throwable> {
            val manager = XDebuggerManager.getInstance(project).breakpointManager
            manager.addLineBreakpoint(type, foo.url, evalLine, type.createBreakpointProperties(foo, evalLine))
            val conditional = manager.addLineBreakpoint(type, foo.url, loopLine, type.createBreakpointProperties(foo, loopLine))
            conditional.conditionExpression =
                XDebuggerUtil.getInstance().createExpression("i == 3", FantomLanguage, null, EvaluationMode.EXPRESSION)
        }

        val settings = RunManager.getInstance(project)
            .createConfiguration("Foo", FantomRunConfigurationType.instance.mainFactory)
        val config = settings.configuration as FantomRunConfiguration
        config.target = "evalpod::Foo"
        config.workingDir = work.path

        project.messageBus.connect(testRootDisposable).subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
            override fun processStarted(debugProcess: XDebugProcess) {
                session = debugProcess.session
            }
        })
        var pauses = 0
        val env = ExecutionEnvironmentBuilder.create(DefaultDebugExecutor.getDebugExecutorInstance(), settings)
            .runner(FantomDebugRunner()).build()
        env.runner.execute(env)

        PlatformTestUtil.waitWithEventsDispatching("debug session did not start", { session != null }, 60)
        val s = session!!
        s.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() {
                pauses++
            }
        })
        PlatformTestUtil.waitWithEventsDispatching("VM did not suspend at the breakpoint", { s.isSuspended }, 60)
        assertEquals(evalLine, s.currentPosition!!.line)

        val expected = linkedMapOf(
            "x" to "3",
            "s" to "abc",
            "x + 1" to "4",
            "list.size" to "3",
            "list[0]" to "1",
            "s.upper" to "ABC",
            "x > 2 && y != null" to "true",
            "f?.name ?: \"none\"" to "fooName",
            "g?.name ?: \"none\"" to "none",
            "map[\"a\"]" to "1",
            "s.size + list.size * 2" to "9",
            "\"x=\" + x" to "x=3",
            "f is Foo" to "true",
            "Str.spaces(2).size" to "2",
            "[x, y].size" to "2",
            // The platform's collection and map renderers: element count, then the elements.
            "list" to "size = 3",
            "map" to "size = 1",
        )
        val results = expected.keys.associateWith { evaluate(s, it) }
        println("FANTOM-DEBUG-EVAL $results")
        for ((expr, value) in expected) {
            val got = results.getValue(expr)
            assertFalse("$expr -> $got", got.startsWith("error"))
            assertTrue("$expr: expected $value, got $got", got.endsWith(" $value") || got.endsWith(" \"$value\"") || got == value)
        }
        assertEquals(listOf("0", "1", "2"), children(s, "list"))
        assertEquals(listOf("0"), children(s, "map"))
        // A Fantom object shows its JVM class and toString(), not `pod::Type`.
        val f = evaluate(s, "f")
        assertTrue("f: got $f", f.contains("\"fan.evalpod.Foo@"))
        assertTrue(children(s, "f").contains("name"))
        assertEquals("error: Not supported in the Fantom evaluator: -> (dynamic call)", evaluate(s, "f->name"))

        // Resume: the loop breakpoint stops only when `i == 3`.
        val before = pauses
        s.resume()
        PlatformTestUtil.waitWithEventsDispatching("conditional breakpoint not hit", { pauses > before && s.isSuspended }, 60)
        assertEquals(loopLine, s.currentPosition!!.line)
        val i = evaluate(s, "i")
        println("FANTOM-DEBUG-EVAL conditional stop i=$i")
        assertTrue("expected i == 3, got $i", i.endsWith(" 3"))
        assertEquals(before + 1, pauses)
    }

    /** Names of the children of the value of [expression], as the Variables view lists them. */
    private fun children(s: XDebugSession, expression: String): List<String> {
        val names = mutableListOf<String>()
        var done = false
        s.currentStackFrame!!.evaluator!!.evaluate(expression, object : XDebuggerEvaluator.XEvaluationCallback {
            override fun evaluated(value: XValue) {
                value.computeChildren(object : XCompositeNode {
                    override fun addChildren(children: XValueChildrenList, last: Boolean) {
                        for (i in 0 until children.size()) names += children.getName(i)
                        if (last) done = true
                    }
                    override fun tooManyChildren(remaining: Int) { done = true }
                    override fun setAlreadySorted(alreadySorted: Boolean) {}
                    override fun setErrorMessage(errorMessage: String) { names += "error: $errorMessage"; done = true }
                    override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) { setErrorMessage(errorMessage) }
                    override fun setMessage(message: String, icon: Icon?, attributes: SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) {}
                })
            }

            override fun errorOccurred(errorMessage: String) {
                names += "error: $errorMessage"
                done = true
            }
        }, null)
        PlatformTestUtil.waitWithEventsDispatching("children of $expression not computed", { done }, 30)
        return names
    }

    /** The type and the rendered value text of [presentation]. */
    private fun render(presentation: XValuePresentation): String {
        val text = StringBuilder()
        presentation.renderValue(object : XValuePresentation.XValueTextRenderer {
            override fun renderValue(value: String) { text.append(value) }
            override fun renderStringValue(value: String) { text.append(value) }
            override fun renderNumericValue(value: String) { text.append(value) }
            override fun renderKeywordValue(value: String) { text.append(value) }
            override fun renderValue(value: String, key: TextAttributesKey) { text.append(value) }
            override fun renderStringValue(value: String, additionalSpecialCharsToHighlight: String?, maxLength: Int) { text.append(value) }
            override fun renderComment(comment: String) {}
            override fun renderSpecialSymbol(symbol: String) { text.append(symbol) }
            override fun renderError(error: String) { text.append("error: ").append(error) }
        })
        return "${presentation.type} $text"
    }

    /**
     * Evaluates [expression] in the current frame: `<type> <value text>`, or `error: ...`.
     * A renderer label is computed after the first presentation, so the presentation is rendered
     * again until its value text is set.
     */
    private fun evaluate(s: XDebugSession, expression: String): String {
        var presentation: XValuePresentation? = null
        var error: String? = null
        s.currentStackFrame!!.evaluator!!.evaluate(expression, object : XDebuggerEvaluator.XEvaluationCallback {
            override fun evaluated(value: XValue) {
                value.computePresentation(object : XValueNode {
                    override fun setPresentation(icon: Icon?, type: String?, value: String, hasChildren: Boolean) {
                        error = "unexpected plain presentation: $type $value"
                    }

                    override fun setPresentation(icon: Icon?, p: XValuePresentation, hasChildren: Boolean) {
                        presentation = p
                    }

                    override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {}
                }, XValuePlace.TREE)
            }

            override fun errorOccurred(errorMessage: String) {
                error = "error: $errorMessage"
            }
        }, null)
        PlatformTestUtil.waitWithEventsDispatching("evaluation of $expression did not finish", {
            error != null || presentation?.let { render(it) }.let { it != null && !it.endsWith(" ") && !it.contains("Collecting data") }
        }, 30)
        return error ?: render(presentation!!)
    }
}
