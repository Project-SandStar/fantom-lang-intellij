package co.anbora.labs.fantom.ide.compiler

import co.anbora.labs.fantom.lang.library.FantomBuildFile
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * The "Build pod" gutter icon of a `build.fan`. `podRootOf` wants a local file,
 * so the pod is written to a real temp folder.
 */
class FantomBuildPodLineMarkerTest : BasePlatformTestCase() {
    private var podDir: File? = null

    private val build = "using build\nclass Build : BuildPod\n{\n  new make()\n  {\n    podName = \"ijmarker\"\n    summary = \"s\"\n  }\n}\n"

    override fun tearDown() {
        try {
            podDir?.let { FileUtil.delete(it) }
        } finally {
            super.tearDown()
        }
    }

    private fun localFile(name: String, text: String): PsiFile {
        val dir = podDir ?: FileUtil.createTempDirectory("fantom-marker", null, true).also { podDir = it }
        val io = File(dir, name).apply { parentFile.mkdirs(); writeText(text) }
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(io)!!
        return psiManager.findFile(vf)!!
    }

    private fun markedLeaves(file: PsiFile) =
        PsiTreeUtil.collectElements(file) { it.firstChild == null }
            .mapNotNull { leaf -> FantomBuildPodLineMarkerProvider().getLineMarkerInfo(leaf)?.let { leaf to it } }

    fun testMarkerOnPodNameAssignment() {
        val file = localFile("build.fan", build)
        val offset = FantomBuildFile.of(file).podNameOffset
        assertTrue(offset > 0)
        val marked = markedLeaves(file)
        assertEquals(marked.map { it.first.text }.toString(), 1, marked.size)
        val (leaf, info) = marked.single()
        assertEquals(offset, leaf.textRange.startOffset)
        // The icon sits on the `podName` name token of the assignment.
        assertEquals("podName", leaf.text)
        assertEquals(AllIcons.Actions.Compile, info.icon)
        assertEquals("Build pod", info.lineMarkerTooltip)
    }

    fun testNoMarkerOutsideBuildFan() {
        assertEmpty(markedLeaves(localFile("fan/Foo.fan", "class Foo { Void make() { podName = \"x\" } }")))
    }

    fun testNoMarkerWithoutPodName() {
        assertEmpty(markedLeaves(localFile("build.fan", "using build\nclass Build : BuildGroup { new make() {} }\n")))
    }
}
