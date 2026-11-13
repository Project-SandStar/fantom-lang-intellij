package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.debugger.FantomDebugRunner
import co.anbora.labs.fantom.ide.run.FantomRunConfiguration
import co.anbora.labs.fantom.ide.run.FantomRunConfigurationType
import co.anbora.labs.fantom.lang.library.FantomSettings
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
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.frame.XStackFrame
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The Debug spike: compile a temp pod with the local Fantom install, set a Java line breakpoint in
 * its `.fan` file, start the Fantom run configuration with the Debug executor, and check that the
 * VM suspends there and the top frame is `Foo.fan:N`. Skips when `~/fantom/fantom-1.0.84` is absent.
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
        var paused: XStackFrame? = null
        val env = ExecutionEnvironmentBuilder.create(DefaultDebugExecutor.getDebugExecutorInstance(), settings)
            .runner(FantomDebugRunner()).build()
        env.runner.execute(env)

        PlatformTestUtil.waitWithEventsDispatching("debug session did not start", { session != null }, 60)
        val s = session!!
        s.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() {
                paused = s.currentStackFrame
            }
        })
        PlatformTestUtil.waitWithEventsDispatching("VM did not suspend at the breakpoint", { s.isSuspended }, 60)
        val position = s.currentPosition!!
        assertEquals("Foo.fan", position.file.name)
        assertEquals(line, position.line)
        val frame = paused ?: s.currentStackFrame!!
        assertEquals("Foo.fan", frame.sourcePosition!!.file.name)
        assertEquals(line, frame.sourcePosition!!.line)
        println("FANTOM-DEBUG suspended at ${position.file.name}:${position.line + 1}, frame=$frame")
    }
}
