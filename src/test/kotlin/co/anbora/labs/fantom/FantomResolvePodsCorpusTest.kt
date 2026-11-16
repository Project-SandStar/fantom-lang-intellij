package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.inspections.FantomUnresolvedReferenceInspection
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex
import java.io.File

/**
 * Resolve corpus: real `.fan` files of a few pods of `~/fantom/fantom-1.0.84/src`,
 * loaded as project files, with the same install as the library. "Unresolved" means
 * a warning of [FantomUnresolvedReferenceInspection] (the inspection the user sees).
 * The test asserts zero of them, apart from [ALLOWED], and lists each one as
 * `pod/File.fan:line 'name'`.
 *
 * The install is pinned (not [co.anbora.labs.fantom.lang.library.FantomHome.detect])
 * so the allow-list stays stable. Skips without it; fails on CI (see [TestInstalls]).
 * `-Dfantom.resolve.pods=a,b` picks other pods.
 */
class FantomResolvePodsCorpusTest : BasePlatformTestCase() {

    private val tag = "FantomResolvePodsCorpusTest:"
    private val home = File(System.getProperty("user.home"), "fantom/fantom-1.0.84")
    private var attached = false

    override fun tearDown() {
        try {
            if (attached) FantomSettings.getInstance(project).homePath = null
        } finally {
            super.tearDown()
        }
    }

    fun testPodsHaveNoUnresolvedNames() {
        if (!TestInstalls.requireInstall(File(home, "src/sys/fan"), "Fantom 1.0.84 sources")) return
        FantomSettings.getInstance(project).homePath = home.path
        attached = true
        WriteAction.runAndWait<RuntimeException> {
            ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(EmptyRunnable.getInstance(), false, true)
        }
        FileBasedIndex.getInstance().ensureUpToDate(FantomDeclarationIndex.NAME, project, null)

        val pods = System.getProperty("fantom.resolve.pods")?.takeIf { it.isNotBlank() }?.split(',')?.map { it.trim() }
            ?: PODS
        val files = ArrayList<Pair<String, PsiFile>>()
        for (pod in pods) {
            val podDir = File(home, "src/$pod/fan")
            assertTrue("$podDir is not a folder", podDir.isDirectory)
            val sources = podDir.walkTopDown().filter { it.isFile && it.extension == "fan" }.sortedBy { it.path }.toList()
            assertFalse("no .fan files in $podDir", sources.isEmpty())
            for (f in sources) {
                val rel = f.relativeTo(podDir).path
                files += "$pod/$rel" to myFixture.addFileToProject("$pod/fan/$rel", StringUtil.convertLineSeparators(f.readText()))
            }
        }

        val inspection = FantomUnresolvedReferenceInspection()
        val manager = InspectionManager.getInstance(project)
        val found = ArrayList<String>()
        val allowedSeen = HashSet<String>()
        val perPod = sortedMapOf<String, Int>()
        for ((path, psi) in files) {
            val problems = ProgressManager.getInstance().runProcess<List<ProblemDescriptor>>(
                { inspection.processFile(psi, manager) }, EmptyProgressIndicator())
            for (p in problems) {
                val name = p.psiElement?.text ?: "?"
                val key = "$path:$name"
                perPod.merge(path.substringBefore('/'), 1, Int::plus)
                if (key in ALLOWED) { allowedSeen += key; continue }
                val line = p.psiElement?.let { psi.viewProvider.document?.getLineNumber(it.textOffset)?.plus(1) } ?: -1
                found += "$path:$line '$name'"
            }
        }
        println("$tag pods=$pods files=${files.size} warningsPerPod=$perPod allowed=${allowedSeen.size} new=${found.size}")
        found.forEach { println("$tag unresolved $it") }
        assertTrue("unresolved names (${found.size}):\n" + found.joinToString("\n"), found.isEmpty())
        if (pods == PODS) {
            val stale = ALLOWED - allowedSeen
            assertTrue("allow-list entries that now resolve, remove them: $stale", stale.isEmpty())
        }
    }

    private companion object {
        /** Pods whose dependencies are all in the install, so the library covers every name. */
        val PODS = listOf("sys", "concurrent", "util", "inet", "xml", "web", "crypto", "fandoc", "graphics", "dom")

        /**
         * Known unresolved names, as `pod/File.fan:name` (no line, so edits above do not break it).
         * TODO: fix resolve for these in main code, then remove them.
         */
        val ALLOWED = setOf(
            // Bare `vals` / `fromStr` inside an enum: the compiler-made enum slots are not declared anywhere.
            "crypto/San.fan:vals",
            "graphics/Font.fan:fromStr",
            // `new make(..) { _make(tagName, ns) }` calls `private native Void _make(..)` declared below it.
            "dom/Elem.fan:_make",
        )
    }
}
