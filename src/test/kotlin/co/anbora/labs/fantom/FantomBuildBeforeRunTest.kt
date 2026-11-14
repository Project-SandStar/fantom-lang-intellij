package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.debugger.FantomDebugRunner
import co.anbora.labs.fantom.ide.run.FantomBuildBeforeRunTask
import co.anbora.labs.fantom.ide.run.FantomBuildBeforeRunTaskProvider
import co.anbora.labs.fantom.ide.run.FantomPodLaunch
import co.anbora.labs.fantom.ide.run.FantomRunConfiguration
import co.anbora.labs.fantom.ide.run.FantomRunConfigurationType
import co.anbora.labs.fantom.ide.run.FantomTestRunConfiguration
import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.debugger.engine.JavaDebugProcess
import com.intellij.debugger.engine.JavaStackFrame
import com.intellij.debugger.engine.events.DebuggerCommandImpl
import com.intellij.debugger.ui.breakpoints.JavaLineBreakpointType
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.RunManager
import com.intellij.execution.RunManagerEx
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.ExecutionManagerImpl
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.util.Key
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
 * The "Build Fantom pod" before-launch step: Run and Debug of a pod whose installed copy is
 * stale use the pod built from the current sources. The "install" is a folder with a
 * `fan.props` holding an older build of the pod; the configuration's working directory points
 * at it, so without the step `fant` runs against that old pod. Launches go through
 * [ExecutionManager.restartRunProfile], so the before-launch steps really run.
 * Skips when `~/fantom/fantom-1.0.84` is absent.
 */
class FantomBuildBeforeRunTest : HeavyPlatformTestCase() {
    private val home = File(System.getProperty("user.home"), "fantom/fantom-1.0.84")
    private var session: XDebugSession? = null
    private val handlers = mutableListOf<ProcessHandler>()

    private val buildSource = """
        using build
        class Build : build::BuildPod
        {
          new make()
          {
            podName = "brpod"
            summary = "before-run test pod"
            depends = ["sys 1.0"]
            srcDirs = [`fan/`]
          }
        }
    """.trimIndent() + "\n"

    private val oldTest = """
        class FooTest : Test
        {
          Void testOld()
          {
            verifyEq(1, 1)
          }
        }
    """.trimIndent() + "\n"

    /** `testNew` exists only in the sources, not in the installed pod. Line 9 (0-based) is `n := 41`. */
    private val newTest = """
        class FooTest : Test
        {
          Void testOld()
          {
            verifyEq(1, 1)
          }

          Void testNew()
          {
            n := 41
            verifyEq(n + 1, 42)
          }
        }
    """.trimIndent() + "\n"

    private val brokenTest = newTest.replace("verifyEq(n + 1, 42)", "verifyEq(n + 1, noSuchName)")

    override fun setUp() {
        super.setUp()
        (ExecutionManager.getInstance(project) as ExecutionManagerImpl).forceCompilationInTests = true
    }

    override fun tearDown() {
        try {
            session?.let { s ->
                if (!s.isStopped) {
                    s.stop()
                    PlatformTestUtil.waitWithEventsDispatching("debug session did not stop", { s.isStopped }, 30)
                }
            }
            for (h in handlers) {
                h.destroyProcess()
                h.waitFor(10_000)
            }
            FantomSettings.getInstance(project).homePath = null
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private class Fixture(val root: File, val pod: File, val install: File, val installedPod: File, val podDir: VirtualFile)

    /** Builds `brpod` with only `testOld` into the "install", then writes [source] as the current sources. */
    private fun staleInstall(source: String = newTest): Fixture {
        val root = Files.createTempDirectory("fantom-before-run").toFile()
        val pod = File(root, "brpod").apply { File(this, "fan").mkdirs() }
        File(pod, "build.fan").writeText(buildSource)
        File(pod, "fan/FooTest.fan").writeText(oldTest)
        val install = File(root, "install").apply { mkdirs() }
        File(install, "fan.props").writeText("// stale install\n")
        compile(File(pod, "build.fan"), install)
        val installedPod = File(install, "lib/fan/brpod.pod")
        assertTrue(installedPod.isFile)
        File(pod, "fan/FooTest.fan").writeText(source)
        FantomSettings.getInstance(project).homePath = home.path
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(pod)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, vf)
        PsiTestUtil.addContentRoot(module, vf)
        return Fixture(root, pod, install, installedPod, vf)
    }

    private fun testConfig(fx: Fixture, method: String = "testNew"): RunnerAndConfigurationSettings {
        val settings = RunManager.getInstance(project).createConfiguration("FooTest", FantomRunConfigurationType.instance.testFactory)
        val config = settings.configuration as FantomTestRunConfiguration
        config.pod = "brpod"
        config.testClass = "FooTest"
        config.testMethod = method
        config.workingDir = fx.install.path
        return settings
    }

    /** What one launch did. */
    private class Launch {
        @Volatile var started: ProcessHandler? = null
        @Volatile var notStarted = false
        @Volatile var descriptor: RunContentDescriptor? = null
        @Volatile var workDir: File? = null
        val output = StringBuffer()
        val done get() = notStarted || started?.isProcessTerminated == true
    }

    private fun launch(settings: RunnerAndConfigurationSettings, debug: Boolean): Launch {
        val l = Launch()
        val executor = if (debug) DefaultDebugExecutor.getDebugExecutorInstance() else DefaultRunExecutor.getRunExecutorInstance()
        val builder = ExecutionEnvironmentBuilder.create(executor, settings)
        if (debug) builder.runner(FantomDebugRunner())
        val env = builder.build { l.descriptor = it }
        project.messageBus.connect(testRootDisposable).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStarting(executorId: String, e: ExecutionEnvironment, handler: ProcessHandler) {
                if (e !== env) return
                l.workDir = FantomPodLaunch.builtWorkDir(e)
                handler.addProcessListener(object : ProcessListener {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        l.output.append(event.text)
                    }
                })
            }

            override fun processStarted(executorId: String, e: ExecutionEnvironment, handler: ProcessHandler) {
                if (e !== env) return
                handlers += handler
                l.started = handler
            }

            override fun processNotStarted(executorId: String, e: ExecutionEnvironment) {
                if (e === env) l.notStarted = true
            }
        })
        if (debug) {
            project.messageBus.connect(testRootDisposable).subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
                override fun processStarted(debugProcess: XDebugProcess) {
                    session = debugProcess.session
                }
            })
        }
        ExecutionManager.getInstance(project).restartRunProfile(env)
        return l
    }

    fun testNewConfigurationHasTheStepOn() {
        val settings = RunManager.getInstance(project).createConfiguration("Foo", FantomRunConfigurationType.instance.testFactory)
        val tasks = RunManagerEx.getInstanceEx(project).getBeforeRunTasks(settings.configuration)
        assertTrue("Build Fantom pod expected among $tasks", tasks.any { it is FantomBuildBeforeRunTask && it.isEnabled })
        val run = RunManager.getInstance(project).createConfiguration("Main", FantomRunConfigurationType.instance.mainFactory)
        assertTrue(RunManagerEx.getInstanceEx(project).getBeforeRunTasks(run.configuration).any { it is FantomBuildBeforeRunTask })
    }

    /** A configuration saved before the step existed gets it once; removed again, it stays removed. */
    fun testOldConfigurationGetsTheStepOnce() {
        val manager = RunManagerEx.getInstanceEx(project)
        val settings = RunManager.getInstance(project).createConfiguration("Old", FantomRunConfigurationType.instance.testFactory)
        manager.addConfiguration(settings)
        val cfg = settings.configuration as FantomTestRunConfiguration
        assertTrue("a new configuration is marked", cfg.buildStepAdded)
        // as read from an old workspace.xml: no step, no mark
        cfg.buildStepAdded = false
        manager.setBeforeRunTasks(cfg, emptyList())
        val element = org.jdom.Element("configuration")
        cfg.writeExternal(element)
        assertFalse(element.children.any { it.getAttributeValue("name") == "buildStepAdded" })

        assertTrue(co.anbora.labs.fantom.ide.run.FantomBuildStepMigration.migrate(project) >= 1)
        assertTrue(manager.getBeforeRunTasks(cfg).any { it is FantomBuildBeforeRunTask && it.isEnabled })
        assertTrue(cfg.buildStepAdded)

        manager.setBeforeRunTasks(cfg, emptyList())
        co.anbora.labs.fantom.ide.run.FantomBuildStepMigration.migrate(project)
        assertTrue("removed by the user, stays removed", manager.getBeforeRunTasks(cfg).none { it is FantomBuildBeforeRunTask })
    }

    fun testScriptTargetsAreNotBuilt() {
        assertNull(FantomPodLaunch.podOfTarget("/tmp/Hello.fan"))
        assertNull(FantomPodLaunch.podOfTarget("scripts/Hello.fan"))
        assertNull(FantomPodLaunch.podOfTarget(""))
        assertEquals("acme", FantomPodLaunch.podOfTarget("acme::Main.main"))
        assertEquals("acme", FantomPodLaunch.podOfTarget("acme"))
        val run = RunManager.getInstance(project).createConfiguration("Hello", FantomRunConfigurationType.instance.mainFactory)
        (run.configuration as FantomRunConfiguration).target = "/tmp/Hello.fan"
        assertEquals(FantomPodLaunch.Outcome.NotApplicable, FantomPodLaunch.build(project, run.configuration, null))
    }

    fun testWorkDirReplacesEnvironmentOverrides() {
        val cmd = GeneralCommandLine("fan").withEnvironment(mapOf("FAN_ENV" to "util::PathEnv", "FAN_ENV_PATH" to "/x", "KEEP" to "1"))
            .withWorkDirectory("/somewhere")
        val dir = Files.createTempDirectory("fantom-wd").toFile()
        FantomPodLaunch.applyWorkDir(cmd, dir)
        assertEquals(dir, cmd.workDirectory)
        assertFalse(cmd.effectiveEnvironment.containsKey("FAN_ENV"))
        assertFalse(cmd.effectiveEnvironment.containsKey("FAN_ENV_PATH"))
        assertEquals("1", cmd.effectiveEnvironment["KEEP"])
    }

    fun testRunUsesThePodBuiltFromTheSources() {
        if (!File(home, "bin/fant").isFile) return
        val fx = staleInstall()
        val before = fx.installedPod.lastModified() to fx.installedPod.length()
        val installFiles = fx.install.walkTopDown().map { it.path }.toSet()

        val l = launch(testConfig(fx), debug = false)
        PlatformTestUtil.waitWithEventsDispatching("fant did not finish", { l.done }, 180)
        println("FANTOM-BEFORE-RUN run output:\n${l.output}")
        assertFalse("the launch was stopped", l.notStarted)
        assertNotNull("the step should have built the pod", l.workDir)
        assertTrue("fresh pod expected in ${l.workDir}", File(l.workDir, "lib/fan/brpod.pod").isFile)
        assertTrue("testNew should run and pass:\n${l.output}", l.output.contains("brpod::FooTest.testNew"))
        assertTrue("All tests passed expected:\n${l.output}", l.output.contains("All tests passed") || l.output.contains("[1 tests"))
        assertFalse(l.output.contains("UnknownSlotErr") || l.output.contains("UnknownTypeErr"))

        // nothing was written into the install, nor into the real Fantom home
        assertFalse(File(home, "lib/fan/brpod.pod").exists())
        assertEquals(before, fx.installedPod.lastModified() to fx.installedPod.length())
        assertEquals(installFiles, fx.install.walkTopDown().map { it.path }.toSet())
    }

    fun testCompileErrorStopsTheLaunch() {
        if (!File(home, "bin/fant").isFile) return
        val fx = staleInstall(brokenTest)
        val l = launch(testConfig(fx), debug = false)
        PlatformTestUtil.waitWithEventsDispatching("the launch did not end", { l.done }, 180)
        assertTrue("a failed build must stop the launch", l.notStarted)
        assertNull("nothing may run", l.started)
    }

    fun testStepTurnedOffRunsTheInstalledPod() = stepOff(remove = false)

    fun testStepRemovedRunsTheInstalledPod() = stepOff(remove = true)

    /** Run with the step, then remove it and run the same environment again: the old work dir is not reused. */
    fun testRerunAfterRemovingTheStepDoesNotReuseTheBuild() {
        if (!File(home, "bin/fant").isFile) return
        val fx = staleInstall()
        val settings = testConfig(fx)
        val first = launch(settings, debug = false)
        PlatformTestUtil.waitWithEventsDispatching("fant did not finish", { first.done }, 180)
        assertNotNull(first.workDir)
        settings.configuration.beforeRunTasks = emptyList()
        val second = launch(settings, debug = false)
        PlatformTestUtil.waitWithEventsDispatching("fant did not finish", { second.done }, 180)
        assertNull("no step, no work dir", second.workDir)
        assertFalse(second.output.contains("All tests passed"))
    }

    private fun stepOff(remove: Boolean) {
        if (!File(home, "bin/fant").isFile) return
        val fx = staleInstall()
        val settings = testConfig(fx)
        if (remove) settings.configuration.beforeRunTasks = emptyList()
        RunManagerEx.disableTasks(project, settings.configuration, FantomBuildBeforeRunTaskProvider.ID)
        assertTrue("step should be off",
            RunManagerEx.getInstanceEx(project).getBeforeRunTasks(settings.configuration).none { it is FantomBuildBeforeRunTask && it.isEnabled })

        val l = launch(settings, debug = false)
        PlatformTestUtil.waitWithEventsDispatching("fant did not finish", { l.done }, 180)
        println("FANTOM-BEFORE-RUN step off output:\n${l.output}")
        assertNull("no build ran", l.workDir)
        assertFalse("testNew is not in the installed pod", l.output.contains("All tests passed"))
        assertTrue("the old pod has no testNew:\n${l.output}",
            l.output.contains("testNew") && (l.output.contains("Unknown") || l.output.contains("Err")))
    }

    fun testDebugStopsInTheNewTestMethod() {
        if (!File(home, "bin/fant").isFile) return
        val fx = staleInstall()
        val file = fx.podDir.findFileByRelativePath("fan/FooTest.fan")!!
        val line = 9 // 0-based: `n := 41` in testNew
        val type = XDebuggerUtil.getInstance().findBreakpointType(JavaLineBreakpointType::class.java)
        assertTrue(type.canPutAt(file, line, project))
        WriteAction.runAndWait<Throwable> {
            XDebuggerManager.getInstance(project).breakpointManager
                .addLineBreakpoint(type, file.url, line, type.createBreakpointProperties(file, line))
        }

        val l = launch(testConfig(fx), debug = true)
        PlatformTestUtil.waitWithEventsDispatching("debug session did not start", { session != null || l.notStarted }, 180)
        assertFalse("the launch was stopped", l.notStarted)
        val s = session!!
        PlatformTestUtil.waitWithEventsDispatching("VM did not suspend at the breakpoint:\n${l.output}", { s.isSuspended }, 90)
        assertNotNull(l.workDir)
        assertEquals("FooTest.fan", s.currentPosition!!.file.name)
        assertEquals(line, s.currentPosition!!.line)
        assertEquals("fan.brpod.FooTest.testNew", location(s))
        s.resume()
    }

    private fun compile(build: File, work: File) {
        val proc = ProcessBuilder(File(home, "bin/fan").path, build.path, "compile")
            .directory(work).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        assertTrue("fan build timed out", proc.waitFor(120, TimeUnit.SECONDS))
        assertEquals("fan build failed:\n$out", 0, proc.exitValue())
    }

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
