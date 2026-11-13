package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.debugger.FantomDebugRunner
import co.anbora.labs.fantom.ide.run.FantomRunConfiguration
import co.anbora.labs.fantom.ide.run.FantomRunConfigurationType
import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.debugger.engine.JavaDebugProcess
import com.intellij.debugger.engine.JavaStackFrame
import com.intellij.debugger.engine.events.DebuggerCommandImpl
import com.intellij.debugger.ui.breakpoints.JavaLineBreakpointType
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.swing.Icon

/**
 * The Debug spike: compile a temp pod with the local Fantom install, set a Java line breakpoint in
 * its `.fan` file, start the Fantom run configuration with the Debug executor, and check that the
 * VM suspends there and the top frame is `Foo.fan:N`; then step over and into a closure.
 * Skips when `~/fantom/fantom-1.0.84` is absent.
 */
class FantomDebuggerTest : HeavyPlatformTestCase() {
    private val home = File(System.getProperty("user.home"), "fantom/fantom-1.0.84")
    private var session: XDebugSession? = null

    private val fooSource = """
        class Foo
        {
          static Void main(Str[] args)
          {
            x := 3
            y := x * 2
            list := [1, 2, 3]
            list.each |Int v| { echo("v=${'$'}v y=${'$'}y") }
            f := Foo()
            echo(f.greet("world"))
          }

          Str greet(Str who)
          {
            msg := "hello ${'$'}who"
            return msg
          }
        }
    """.trimIndent() + "\n"

    private val buildSource = """
        using build
        class Build : build::BuildPod
        {
          new make()
          {
            podName = "dbgpod"
            summary = "debug spike"
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

    /** `<root>/dbgpod/{build.fan,fan/Foo.fan}` compiled into `<root>/work/lib/fan/dbgpod.pod`. */
    private fun compilePod(root: File): File {
        val pod = File(root, "dbgpod").apply { File(this, "fan").mkdirs() }
        File(pod, "build.fan").writeText(buildSource)
        File(pod, "fan/Foo.fan").writeText(fooSource)
        val work = File(root, "work").apply { mkdirs() }
        File(work, "fan.props").writeText("// debugger test work dir\n")
        val proc = ProcessBuilder(File(home, "bin/fan").path, File(pod, "build.fan").path, "compile")
            .directory(work).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        assertTrue("fan build timed out", proc.waitFor(120, TimeUnit.SECONDS))
        assertTrue("fan build failed:\n$out", File(work, "lib/fan/dbgpod.pod").isFile)
        return work
    }

    fun testBreakpointInMainSuspendsAtFanLine() {
        if (!File(home, "bin/fan").isFile) return
        val root = Files.createTempDirectory("fantom-debug").toFile()
        val work = compilePod(root)
        FantomSettings.getInstance(project).homePath = home.path

        val podDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(root, "dbgpod"))!!
        VfsUtil.markDirtyAndRefresh(false, true, true, podDir)
        PsiTestUtil.addContentRoot(module, podDir)
        val foo = podDir.findFileByRelativePath("fan/Foo.fan")!!

        val line = 5 // 0-based: `y := x * 2`
        val type = XDebuggerUtil.getInstance().findBreakpointType(JavaLineBreakpointType::class.java)
        assertTrue("Java line breakpoint not accepted in Foo.fan", type.canPutAt(foo, line, project))
        WriteAction.runAndWait<Throwable> {
            XDebuggerManager.getInstance(project).breakpointManager
                .addLineBreakpoint(type, foo.url, line, type.createBreakpointProperties(foo, line))
        }

        val settings = RunManager.getInstance(project)
            .createConfiguration("Foo", FantomRunConfigurationType.instance.mainFactory)
        val config = settings.configuration as FantomRunConfiguration
        config.target = "dbgpod::Foo"
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

        // 1. The breakpoint: Foo.fan line 6, JVM frame fan.dbgpod.Foo.main.
        assertAt(s, line, "fan.dbgpod.Foo.main")
        val mainVars = variables(s.currentStackFrame!!)
        println("FANTOM-DEBUG variables at Foo.fan:${line + 1}: $mainVars")
        assertTrue("Fantom names expected (FAN_DEBUG): $mainVars", mainVars.containsAll(listOf("args", "x")))

        for (expr in listOf("x", "x + 1", "args.size()", "args", "Long.valueOf(x) + 1")) {
            println("FANTOM-DEBUG evaluate $expr -> ${evaluate(s, expr)}")
        }

        // 2. Step Over stays in Foo.fan: `list := ...`, then `list.each`.
        step(s, { pauses }) { s.stepOver(false) }
        assertAt(s, line + 1, "fan.dbgpod.Foo.main")
        step(s, { pauses }) { s.stepOver(false) }
        assertAt(s, line + 2, "fan.dbgpod.Foo.main")

        // 3. Step Into `list.each |Int v| {...}` skips fan.sys.List and lands in the closure.
        step(s, { pauses }) { s.stepInto() }
        val closure = location(s)
        println("FANTOM-DEBUG step into list.each -> ${s.currentPosition!!.file.name}:${s.currentPosition!!.line + 1} $closure")
        assertEquals("Foo.fan", s.currentPosition!!.file.name)
        assertEquals(line + 2, s.currentPosition!!.line)
        assertTrue("expected the closure class, got $closure", closure.startsWith("fan.dbgpod.Foo\$main\$0."))
        assertFalse("stopped in closure construction: $closure", closure.endsWith(".make") || closure.endsWith(".<init>"))
        val closureVars = variables(s.currentStackFrame!!)
        println("FANTOM-DEBUG variables in closure: $closureVars")
        assertTrue("closure parameter v expected: $closureVars", "v" in closureVars)
    }

    private fun step(s: XDebugSession, pauses: () -> Int, action: () -> Unit) {
        val before = pauses()
        action()
        PlatformTestUtil.waitWithEventsDispatching("step did not finish", { pauses() > before && s.isSuspended }, 60)
    }

    /** `class.method` of the JVM location of the current frame. */
    private fun location(s: XDebugSession): String {
        val frame = s.currentStackFrame as JavaStackFrame
        var result: String? = null
        (s.debugProcess as JavaDebugProcess).debuggerSession.process.managerThread.schedule(object : DebuggerCommandImpl() {
            override fun action() {
                val loc = frame.descriptor.location!!
                result = loc.declaringType().name() + "." + loc.method().name()
            }
        })
        PlatformTestUtil.waitWithEventsDispatching("no frame location", { result != null }, 30)
        return result!!
    }

    private fun assertAt(s: XDebugSession, line: Int, classAndMethod: String) {
        val position = s.currentPosition!!
        assertEquals("Foo.fan", position.file.name)
        assertEquals(line, position.line)
        val frame = s.currentStackFrame!!
        assertEquals("Foo.fan", frame.sourcePosition!!.file.name)
        assertEquals(line, frame.sourcePosition!!.line)
        assertEquals(classAndMethod, location(s))
        println("FANTOM-DEBUG suspended at ${position.file.name}:${position.line + 1}, frame=$frame")
    }

    /** Evaluates [expression] in the current frame: the value text, or `error: ...`. */
    private fun evaluate(s: XDebugSession, expression: String): String {
        var result: String? = null
        s.currentStackFrame!!.evaluator!!.evaluate(expression, object : XDebuggerEvaluator.XEvaluationCallback {
            override fun evaluated(value: XValue) {
                value.computePresentation(object : XValueNode {
                    override fun setPresentation(icon: Icon?, type: String?, value: String, hasChildren: Boolean) {
                        result = "$type $value"
                    }

                    override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
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
                        result = "${presentation.type} $text"
                    }

                    override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {}
                }, XValuePlace.TREE)
            }

            override fun errorOccurred(errorMessage: String) {
                result = "error: $errorMessage"
            }
        }, null)
        PlatformTestUtil.waitWithEventsDispatching("evaluation did not finish", { result != null }, 30)
        return result!!
    }

    /** The names the Variables view shows for [frame], plus any message or error. */
    private fun variables(frame: XStackFrame): List<String> {
        val names = mutableListOf<String>()
        var done = false
        frame.computeChildren(object : XCompositeNode {
            override fun addChildren(children: XValueChildrenList, last: Boolean) {
                for (i in 0 until children.size()) names += children.getName(i)
                if (last) done = true
            }

            override fun tooManyChildren(remaining: Int) {
                done = true
            }

            override fun setAlreadySorted(alreadySorted: Boolean) {}

            override fun setErrorMessage(errorMessage: String) {
                names += "[error: $errorMessage]"
                done = true
            }

            override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {
                names += "[error: $errorMessage]"
                done = true
            }

            override fun setMessage(message: String, icon: Icon?, attributes: SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) {
                names += "[message: $message]"
            }
        })
        PlatformTestUtil.waitWithEventsDispatching("variables not computed", { done }, 30)
        return names
    }
}
