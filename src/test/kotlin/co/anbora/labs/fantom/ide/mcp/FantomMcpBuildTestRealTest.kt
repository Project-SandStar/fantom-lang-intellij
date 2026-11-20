package co.anbora.labs.fantom.ide.mcp

import co.anbora.labs.fantom.TestInstalls
import co.anbora.labs.fantom.ide.settings.FantomAgentSettings
import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.Callable

/**
 * `fantom_build` and `fantom_run_test` against a real pod on disk, with the Fantom install on
 * this machine. Skips when there is none (or fails when FANTOM_REQUIRE_INSTALLS=true).
 */
class FantomMcpBuildTestRealTest : BasePlatformTestCase() {
    private var home: File? = null
    private var podDir: File? = null
    private var podRoot: com.intellij.openapi.vfs.VirtualFile? = null

    override fun setUp() {
        super.setUp()
        home = FantomHome.detect()
        FantomSettings.getInstance(project).homePath = home?.path
        FantomAgentSettings.getInstance(project).allowAgentRun = true
        FantomSettings.getInstance(project).compileOnSave = compileOnSave
    }

    /** Off in one run of each test below, as the Build Pod tests do; on in [testBrokenFileWithCompileOnSave]. */
    private var compileOnSave = false

    override fun tearDown() {
        try {
            FantomAgentSettings.getInstance(project).allowAgentRun = false
            FantomSettings.getInstance(project).homePath = null
            FantomSettings.getInstance(project).compileOnSave = true
            // The light project outlives the test: drop the root, or the next test finds this pod.
            podRoot?.let { PsiTestUtil.removeContentEntry(module, it) }
            podDir?.let { FileUtil.delete(it); LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it) }
        } finally {
            super.tearDown()
        }
    }

    private fun skip(): Boolean =
        SystemInfo.isWindows || !TestInstalls.requireInstall(home?.let { File(it, "bin/fant") }, "a Fantom home with bin/fant")

    private fun pod(test: String, extra: String = "") {
        val dir = FileUtil.createTempDirectory("mcp-pod", null, true)
        File(dir, "build.fan").writeText(
            "using build\nclass Build : BuildPod\n{\n  new make()\n  {\n    podName = \"ijmcppod\"\n" +
                "    summary = \"test\"\n    version = Version(\"0.1\")\n    depends = [\"sys 1.0\"]\n    srcDirs = [`fan/`]\n  }\n}\n"
        )
        File(dir, "fan").mkdirs()
        File(dir, "fan/FooTest.fan").writeText(test)
        if (extra.isNotEmpty()) File(dir, "fan/Extra.fan").writeText(extra)
        podDir = dir
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, vf)
        PsiTestUtil.addContentRoot(module, vf)
        podRoot = vf
    }

    private fun call(work: suspend () -> FantomMcpService.Answer): FantomMcpService.Answer {
        val f = ApplicationManager.getApplication().executeOnPooledThread(Callable { runCatching { runBlocking { work() } } })
        val end = System.currentTimeMillis() + 240_000
        while (!f.isDone) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
            if (System.currentTimeMillis() > end) fail("the tool did not end")
        }
        return f.get().getOrThrow()
    }

    fun testBrokenFileGivesItsErrorWithFileAndLine() {
        if (skip()) return
        pod("class FooTest : Test { Void testA() { verify(true) } }\n", "class Extra\n{\n  Str name() { return nme }\n}\n")
        val text = call { FantomMcpService.build(project, "ijmcppod", "", 0) }.text
        assertTrue(text, text.startsWith("pod ijmcppod: FAILED, 1 error"))
        assertTrue(text, text.lines().any { it.startsWith("ERROR ") && it.contains("Extra.fan:3:") && it.endsWith("Unknown variable 'nme'") })
        assertFalse(File(home, "lib/fan/ijmcppod.pod").exists())
    }

    /** Compile on save is on by default; its compile must not hide the agent's result. */
    fun testBrokenFileWithCompileOnSave() {
        compileOnSave = true
        FantomSettings.getInstance(project).compileOnSave = true
        testBrokenFileGivesItsErrorWithFileAndLine()
    }

    fun testFailingTestGivesItsMessage() {
        if (skip()) return
        pod("class FooTest : Test\n{\n  Void testA() { verify(true) }\n  Void testB()\n  {\n    verifyEq(1, 2)\n  }\n}\n")
        val text = call { FantomMcpService.runTest(project, "ijmcppod", "FooTest", "", 0) }.text
        assertTrue(text, text.startsWith("fant ijmcppod::FooTest: FAILED, 1 passed, 1 failed"))
        assertTrue(text, text.contains("PASS ijmcppod::FooTest.testA"))
        val fail = text.lines().single { it.startsWith("FAIL ijmcppod::FooTest.testB") }
        assertTrue(fail, fail.contains("FooTest.fan:6"))
        assertTrue(fail, fail.contains("Test failed"))
        assertFalse(File(home, "lib/fan/ijmcppod.pod").exists())
    }
}
