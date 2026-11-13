package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.debugger.FantomDebugRunner
import co.anbora.labs.fantom.ide.debugger.FantomPositionManager
import co.anbora.labs.fantom.ide.run.FantomCommandLine
import co.anbora.labs.fantom.ide.run.FantomRunConfiguration
import co.anbora.labs.fantom.ide.run.FantomRunConfigurationType
import co.anbora.labs.fantom.ide.run.FantomTestRunConfiguration
import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.debugger.engine.JavaDebugProcess
import com.intellij.debugger.engine.JavaStackFrame
import com.intellij.debugger.engine.events.DebuggerCommandImpl
import com.intellij.debugger.ui.breakpoints.JavaLineBreakpointType
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.intellij.xdebugger.XDebuggerUtil
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Debug of the Fantom Test configuration (`fant` under JDWP, results still in the test tree) and
 * of a standalone script (`fan Foo.fan`, compiled into a pod with a generated name).
 * Skips when `~/fantom/fantom-1.0.84` is absent.
 */
class FantomDebuggerGapsTest : HeavyPlatformTestCase() {
    private val home = File(System.getProperty("user.home"), "fantom/fantom-1.0.84")
    private var session: XDebugSession? = null

    private val testSource = """
        class FooTest : Test
        {
          Void testA()
          {
            a := 1
            verifyEq(a, 1)
          }

          Void testB()
          {
            b := 2
            verifyEq(b, 3)
          }
        }
    """.trimIndent() + "\n"

    private val buildSource = """
        using build
        class Build : build::BuildPod
        {
          new make()
          {
            podName = "dbgtestpod"
            summary = "debug test pod"
            depends = ["sys 1.0"]
            srcDirs = [`fan/`]
          }
        }
    """.trimIndent() + "\n"

    private val scriptSource = """
        class Hello
        {
          static Void main()
          {
            x := 40
            y := x + 2
            echo("y=${'$'}y")
          }
        }
    """.trimIndent() + "\n"

    override fun tearDown() {
        try {
            session?.let { s ->
                if (!s.isStopped) {
                    s.stop()
                    PlatformTestUtil.waitWithEventsDispatching("debug session did not stop", { s.isStopped }, 30)
                }
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

    fun testFantLauncherByVersion() {
        val fantoms = File(System.getProperty("user.home"), "fantom")
        File(fantoms, "fantom-1.0.78").takeIf { File(it, "bin/fant").isFile }?.let {
            assertEquals("fanx.tools.Fant" to emptyList<String>(), FantomCommandLine.fantLauncher(it))
        }
        File(fantoms, "fantom-1.0.84").takeIf { File(it, "bin/fant").isFile }?.let {
            assertEquals("fanx.tools.Fan" to listOf("util::TestRunner"), FantomCommandLine.fantLauncher(it))
        }
        assertEquals("fanx.tools.Fant" to emptyList<String>(), FantomCommandLine.fantLauncher(File("/no/such/home")))
    }

    fun testScriptPodNamePattern() {
        assertEquals("Hello", FantomPositionManager.scriptPodBase("Hello.fan"))
        assertEquals("myscript2", FantomPositionManager.scriptPodBase("my-script2.fan"))
        assertEquals("abc", FantomPositionManager.scriptPodBase("1abc.fan"))
        assertTrue(FantomPositionManager.isScriptPod("Hello_0", "Hello.fan"))
        assertTrue(FantomPositionManager.sourceMatches("/tmp/x/Hello.fan", "Hello.fan"))
        assertTrue(FantomPositionManager.sourceMatches("Hello.fan", "Hello.fan"))
        assertFalse(FantomPositionManager.sourceMatches("/tmp/x/MyHello.fan", "Hello.fan"))
        assertTrue(FantomPositionManager.isScriptPod("myscript2_12", "my-script2.fan"))
        assertFalse(FantomPositionManager.isScriptPod("Hello", "Hello.fan"))
        assertFalse(FantomPositionManager.isScriptPod("Other_0", "Hello.fan"))
    }

    /** The debug command line of a Test configuration: the `fant` main class, JDWP, FAN_DEBUG and the specs. */
    fun testTestDebugCommandLine() {
        if (!File(home, "bin/fant").isFile) return
        val settings = RunManager.getInstance(project)
            .createConfiguration("FooTest", FantomRunConfigurationType.instance.testFactory)
        val config = settings.configuration as FantomTestRunConfiguration
        config.pod = "dbgtestpod"
        assertTrue(FantomDebugRunner().canRun(DefaultDebugExecutor.EXECUTOR_ID, config))
        val cmd = config.buildDebugCommandLine(5005, home, listOf("dbgtestpod::FooTest.testB"))
        val params = cmd.parametersList.list
        assertTrue(params.toString(), FantomRunConfiguration.jdwpAgent(5005) in params)
        val main = params.indexOf("fanx.tools.Fan")
        assertTrue(params.toString(), main >= 0)
        assertEquals(listOf("util::TestRunner", "dbgtestpod::FooTest.testB"), params.drop(main + 1))
        assertEquals("true", cmd.environment[FantomRunConfiguration.FAN_DEBUG])
    }

    /** Debug the Test configuration: stop in `testB`, resume, and the tree shows one pass and one fail. */
    fun testDebugTestConfigurationStopsInTestMethod() {
        if (!File(home, "bin/fant").isFile) return
        val root = Files.createTempDirectory("fantom-debug-test").toFile()
        val pod = File(root, "dbgtestpod").apply { File(this, "fan").mkdirs() }
        File(pod, "build.fan").writeText(buildSource)
        File(pod, "fan/FooTest.fan").writeText(testSource)
        val work = File(root, "work").apply { mkdirs() }
        File(work, "fan.props").writeText("// debugger test work dir\n")
        compile(File(pod, "build.fan"), work)
        assertTrue(File(work, "lib/fan/dbgtestpod.pod").isFile)
        FantomSettings.getInstance(project).homePath = home.path

        val podDir = contentRoot(pod)
        val file = podDir.findFileByRelativePath("fan/FooTest.fan")!!
        val line = 10 // 0-based: `b := 2` in testB
        addBreakpoint(file, line)

        val settings = RunManager.getInstance(project)
            .createConfiguration("FooTest", FantomRunConfigurationType.instance.testFactory)
        val config = settings.configuration as FantomTestRunConfiguration
        config.pod = "dbgtestpod"
        config.testClass = "FooTest"
        config.workingDir = work.path
        val s = startDebug(settings)

        PlatformTestUtil.waitWithEventsDispatching("VM did not suspend at the breakpoint", { s.isSuspended }, 60)
        assertEquals("FooTest.fan", s.currentPosition!!.file.name)
        assertEquals(line, s.currentPosition!!.line)
        assertEquals("fan.dbgtestpod.FooTest.testB", location(s))

        val console = s.runContentDescriptor.executionConsole
        assertTrue("test tree console expected, got $console", console is SMTRunnerConsoleView)
        s.resume()
        val handler = s.debugProcess.processHandler
        PlatformTestUtil.waitWithEventsDispatching("fant did not finish", { handler.isProcessTerminated }, 60)
        val rootNode = (console as SMTRunnerConsoleView).resultsViewer.testsRootNode
        PlatformTestUtil.waitWithEventsDispatching("test tree not finished", { !rootNode.isInProgress }, 30)
        val tests = rootNode.allTests.filter { it.isLeaf }
        println("FANTOM-DEBUG test tree: ${tests.map { it.name + (if (it.isDefect) " FAIL" else " ok") }}")
        assertEquals(1, tests.count { it.isPassed })
        assertEquals(1, tests.count { it.isDefect })
        assertEquals("testB", tests.single { it.isDefect }.name)
    }

    /** Debug `fan Hello.fan`: the script pod is `Hello_0`, and the breakpoint in `main` hits. */
    fun testDebugScriptStopsInMain() {
        if (!File(home, "bin/fan").isFile) return
        val root = Files.createTempDirectory("fantom-debug-script").toFile()
        val scripts = File(root, "scripts").apply { mkdirs() }
        File(scripts, "Hello.fan").writeText(scriptSource)
        val work = File(root, "work").apply { mkdirs() }
        File(work, "fan.props").writeText("// debugger test work dir\n")
        FantomSettings.getInstance(project).homePath = home.path

        val dir = contentRoot(scripts)
        val file = dir.findChild("Hello.fan")!!
        val line = 5 // 0-based: `y := x + 2`
        addBreakpoint(file, line)

        val settings = RunManager.getInstance(project)
            .createConfiguration("Hello", FantomRunConfigurationType.instance.mainFactory)
        val config = settings.configuration as FantomRunConfiguration
        config.target = File(scripts, "Hello.fan").path
        config.workingDir = work.path
        val s = startDebug(settings)
        PlatformTestUtil.waitWithEventsDispatching("VM did not suspend at the breakpoint", { s.isSuspended }, 120)
        assertEquals(file, s.currentPosition!!.file)
        assertEquals(line, s.currentPosition!!.line)
        val at = location(s)
        println("FANTOM-DEBUG script stop: $at")
        assertTrue("script class expected, got $at", Regex("""fan\.Hello_\d+\.Hello\.main""").matches(at))
    }

    private fun compile(build: File, work: File) {
        val proc = ProcessBuilder(File(home, "bin/fan").path, build.path, "compile")
            .directory(work).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        assertTrue("fan build timed out", proc.waitFor(120, TimeUnit.SECONDS))
        assertEquals("fan build failed:\n$out", 0, proc.exitValue())
    }

    private fun contentRoot(dir: File): VirtualFile {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, vf)
        PsiTestUtil.addContentRoot(module, vf)
        return vf
    }

    private fun addBreakpoint(file: VirtualFile, line: Int) {
        val type = XDebuggerUtil.getInstance().findBreakpointType(JavaLineBreakpointType::class.java)
        assertTrue("Java line breakpoint not accepted in ${file.name}", type.canPutAt(file, line, project))
        WriteAction.runAndWait<Throwable> {
            XDebuggerManager.getInstance(project).breakpointManager
                .addLineBreakpoint(type, file.url, line, type.createBreakpointProperties(file, line))
        }
    }

    private fun startDebug(settings: com.intellij.execution.RunnerAndConfigurationSettings): XDebugSession {
        project.messageBus.connect(testRootDisposable).subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
            override fun processStarted(debugProcess: XDebugProcess) {
                session = debugProcess.session
            }
        })
        val runner = FantomDebugRunner()
        assertTrue(runner.canRun(DefaultDebugExecutor.EXECUTOR_ID, settings.configuration))
        val env = ExecutionEnvironmentBuilder.create(DefaultDebugExecutor.getDebugExecutorInstance(), settings)
            .runner(runner).build()
        env.runner.execute(env)
        PlatformTestUtil.waitWithEventsDispatching("debug session did not start", { session != null }, 60)
        return session!!
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
}
