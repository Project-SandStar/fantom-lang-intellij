package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.inspections.FantomUnresolvedReferenceInspection
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.openapi.util.text.StringUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex
import java.io.File

/**
 * Runs [FantomUnresolvedReferenceInspection] over the `util` pod (as project files)
 * with the whole install as a library, and prints the warning count and the top names.
 * The count must stay small: tens, not hundreds. Skips when no Fantom home is found.
 * `-Dfantom.pod.dir=<dir>` picks another pod folder than `util/fan`.
 */
class FantomUnresolvedInspectionCorpusTest : BasePlatformTestCase() {

    private val tag = "FantomUnresolvedInspectionCorpusTest:"
    private var home: File? = null

    override fun setUp() {
        super.setUp()
        home = FantomHome.detect()
        val h = home ?: return
        FantomSettings.getInstance(project).homePath = h.path
        WriteAction.runAndWait<RuntimeException> {
            ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(EmptyRunnable.getInstance(), false, true)
        }
        FileBasedIndex.getInstance().ensureUpToDate(FantomDeclarationIndex.NAME, project, null)
    }

    override fun tearDown() {
        try {
            FantomSettings.getInstance(project).homePath = null
        } finally {
            super.tearDown()
        }
    }

    fun testUtilPodWarningCount() {
        if (home == null) {
            println("$tag skipped: no Fantom home found")
            return
        }
        val podDir = System.getProperty("fantom.pod.dir")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(home, "src/util/fan")
        assertTrue("$podDir is not a folder", podDir.isDirectory)
        val sources = podDir.listFiles { f -> f.isFile && f.extension == "fan" }?.sortedBy { it.name }.orEmpty()
        assertFalse("no .fan files in $podDir", sources.isEmpty())
        val podName = podDir.parentFile.name
        val files = sources.map { f ->
            myFixture.addFileToProject("$podName/fan/${f.name}", StringUtil.convertLineSeparators(f.readText()))
        }

        val inspection = FantomUnresolvedReferenceInspection()
        val manager = InspectionManager.getInstance(project)
        val names = HashMap<String, Int>()
        val examples = ArrayList<String>()
        var total = 0
        val start = System.nanoTime()
        for (psi in files) {
            val problems = ProgressManager.getInstance().runProcess<List<ProblemDescriptor>>(
                { inspection.processFile(psi, manager) }, EmptyProgressIndicator())
            for (p in problems) {
                total++
                val name = p.psiElement?.text ?: "?"
                names.merge(name, 1, Int::plus)
                if (examples.size < 10) {
                    val doc = psi.viewProvider.document
                    val line = p.psiElement?.let { doc?.getLineNumber(it.textOffset)?.plus(1) } ?: -1
                    examples += "${psi.name}:$line '$name'"
                }
            }
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        val top = names.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(10).map { "${it.key}=${it.value}" }
        println("$tag pod=$podName files=${files.size} warnings=$total distinctNames=${names.size} ms=$ms")
        println("$tag top names: $top")
        println("$tag examples: $examples")
        assertTrue("$total unresolved warnings on $podName, limit 100; top: $top", total < 100)
        // The default `util` pod is clean since it-block field assignment resolves (r23).
        if (System.getProperty("fantom.pod.dir").isNullOrBlank()) {
            assertEquals("unresolved warnings on util; top: $top", 0, total)
        }
    }
}
