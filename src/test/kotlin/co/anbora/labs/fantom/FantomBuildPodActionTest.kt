package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.compiler.FantomBuildPodAction
import co.anbora.labs.fantom.ide.compiler.FantomCompilerService
import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.notification.NotificationType
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * [FantomBuildPodAction] builds a temp pod on disk with the Fantom install on this machine.
 * Skips when there is none.
 */
class FantomBuildPodActionTest : BasePlatformTestCase() {
    private var home: File? = null
    private var podDir: File? = null

    override fun setUp() {
        super.setUp()
        home = FantomHome.detect()
        FantomSettings.getInstance(project).homePath = home?.path
        FantomSettings.getInstance(project).compileOnSave = false
    }

    override fun tearDown() {
        try {
            FantomSettings.getInstance(project).homePath = null
            FantomSettings.getInstance(project).compileOnSave = true
            podDir?.let { FileUtil.delete(it) }
        } finally {
            super.tearDown()
        }
    }

    private fun effectiveHome(): File? {
        val h = home?.let { FantomSettings.getInstance(project).effectiveHome() }
        if (h == null) println("FantomBuildPodActionTest skipped: no Fantom home found")
        return h
    }

    private fun pod(fooText: String): File {
        val dir = FileUtil.createTempDirectory("fantom-buildpod", null, true)
        File(dir, "build.fan").writeText(
            "using build\nclass Build : BuildPod\n{\n  new make()\n  {\n    podName = \"ijbuildpod\"\n" +
                "    summary = \"test\"\n    version = Version(\"0.1\")\n    depends = [\"sys 1.0\"]\n    srcDirs = [`fan/`]\n  }\n}\n"
        )
        File(dir, "fan").mkdirs()
        File(dir, "fan/Foo.fan").writeText(fooText)
        podDir = dir
        return dir
    }

    fun testPodRootFromSourceAndBuildFile() {
        val dir = pod("class Foo {}\n")
        val foo = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(dir, "fan/Foo.fan"))!!
        val build = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(dir, "build.fan"))!!
        assertEquals(build.parent, FantomBuildPodAction.podRootOf(foo))
        assertEquals(build.parent, FantomBuildPodAction.podRootOf(build))
        assertEquals("ijbuildpod", FantomBuildPodAction.podNameOf(project, build.parent))
    }

    fun testCleanPodBuilds() {
        val h = effectiveHome() ?: return
        val dir = pod("class Foo\n{\n  Str name() { return \"n\" }\n}\n")
        val result = FantomBuildPodAction.build(project, dir, h, null)
        assertNotNull(result)
        assertTrue(result!!.completed)
        assertEquals(0, result.exitCode)
        val (text, type) = FantomBuildPodAction.summary("ijbuildpod", result)
        assertEquals("Pod 'ijbuildpod' built", text)
        assertEquals(NotificationType.INFORMATION, type)
    }

    fun testBrokenPodReportsErrorsInline() {
        val h = effectiveHome() ?: return
        val dir = pod("class Foo\n{\n  Str name() { return nme }\n}\n")
        // Open first: the fixture saves the file when it opens it, and a file newer than the build shows nothing.
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(dir, "fan/Foo.fan"))!!
        myFixture.configureFromExistingVirtualFile(vf)
        val result = FantomBuildPodAction.build(project, dir, h, null)!!
        assertTrue(result.completed)
        val (text, type) = FantomBuildPodAction.summary("ijbuildpod", result)
        assertEquals(NotificationType.ERROR, type)
        assertTrue(text, text.startsWith("Pod 'ijbuildpod' failed: 1 error"))
        assertTrue(text, text.contains("Foo.fan:3: Unknown variable 'nme'"))
        // With compile-on-save off, the built pod's open file still shows the compiler's error.
        assertNotNull(FantomCompilerService.getInstance(project).builtRootFor(vf))
        val errors = myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.severity == HighlightSeverity.ERROR }
        assertEquals(listOf("Unknown variable 'nme'"), errors.map { it.description })
    }
}
