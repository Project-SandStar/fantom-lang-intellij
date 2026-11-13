package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.compiler.FantomCompilerDiagnostics
import co.anbora.labs.fantom.lang.library.FantomHome
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Compiles a two-test pod with the real `fan` in a private work folder, runs the real `fant`
 * on it, and feeds the output into the test tree. Skips without a Fantom home.
 */
class FantomTestRunnerIntegrationTest : BasePlatformTestCase() {

    fun testRealFant() {
        val home = FantomHome.detect()
        if (home == null || SystemInfo.isWindows) {
            println("FantomTestRunnerIntegrationTest skipped: no Fantom home found")
            return
        }
        val root = FileUtil.createTempDirectory("fant-run", null, true)
        val pod = File(root, "ijfanttest").apply { mkdirs() }
        File(pod, "build.fan").writeText(
            """
            using build
            class Build : build::BuildPod
            {
              new make()
              {
                podName = "ijfanttest"
                summary = "test pod"
                version = Version("0.1")
                depends = ["sys 1.0"]
                srcDirs = [`fan/`]
              }
            }
            """.trimIndent() + "\n"
        )
        File(pod, "fan").mkdirs()
        File(pod, "fan/FooTest.fan").writeText(
            "class FooTest : Test { Void testA() { verify(true) } Void testB() { verifyEq(1, 2) } }\n"
        )
        val work = FantomCompilerDiagnostics.prepareWorkDir(File(root, "work"), pod)!!
        val compile = CapturingProcessHandler(FantomCompilerDiagnostics.commandLine(home, File(pod, "build.fan"), work))
            .runProcess(120_000)
        assertEquals(compile.stdout + compile.stderr, 0, compile.exitCode)

        val fant = GeneralCommandLine(File(home, "bin/fant").path, "ijfanttest")
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withWorkDirectory(work)
            .withRedirectErrorStream(true)
        fant.environment.remove("FAN_ENV")
        fant.environment.remove("FAN_ENV_PATH")
        val output = CapturingProcessHandler(fant).runProcess(120_000)
        assertFalse(output.stdout, output.isTimeout)

        val tree = FantomTestEventsConverterTest.run(project, testRootDisposable, output.stdout)
        val tests = tree.allTests.filter { it.isLeaf }
        assertEquals(output.stdout, 1, tests.count { it.isDefect })
        assertEquals(output.stdout, 1, tests.count { it.isPassed })
        assertEquals(listOf("FooTest"), tree.children.map { it.name })
        assertEquals("fantom://ijfanttest::FooTest.testB", tests.single { it.isDefect }.locationUrl)
        assertFalse(File(home, "lib/fan/ijfanttest.pod").exists())
    }
}
