package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.documentation.FantomDocumentationProvider
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.library.FantomLibraryRootsProvider
import co.anbora.labs.fantom.lang.library.FantomSettings
import co.anbora.labs.fantom.lang.pod.FantomPodStubActivity
import co.anbora.labs.fantom.lang.pod.FantomPodStubs
import co.anbora.labs.fantom.lang.resolve.FantomReference
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex
import java.io.File

/**
 * Home = skyspark-3.0.29 (compiled pods only), Fantom sources = fantom-1.0.84. The
 * SkySpark pods are read through `.fan` stubs made from their apidoc: `haystack::Etc`
 * resolves to a stub, completes and shows its fandoc. Skipped when an install is missing.
 */
class FantomSkySparkLibraryTest : BasePlatformTestCase() {

    private val skyspark = File(System.getProperty("user.home"), "skyspark/skyspark-3.0.29")
    private val fantom = File(System.getProperty("user.home"), "fantom/fantom-1.0.84")
    private val ready get() = skyspark.isDirectory && fantom.isDirectory

    override fun setUp() {
        super.setUp()
        if (!ready) return
        val settings = FantomSettings.getInstance(project)
        settings.homePath = skyspark.path
        settings.sourcesPath = fantom.path
        FantomSettings.clearPodCache()
        val t0 = System.nanoTime()
        val missing = settings.podsNeedingStubs().filter { FantomPodStubs.needsGeneration(it) }
        FantomPodStubActivity.generate(project, missing, null) // refreshes and fires a roots change
        FileBasedIndex.getInstance().ensureUpToDate(FantomDeclarationIndex.NAME, project, null)
        println("FantomSkySparkLibraryTest: ${missing.size} pods generated, roots + index ${(System.nanoTime() - t0) / 1_000_000}ms")
    }

    override fun tearDown() {
        try {
            if (ready) {
                FantomSettings.getInstance(project).homePath = null
                FantomSettings.getInstance(project).sourcesPath = null
                FantomPodStubActivity.refresh(project)
            }
        } finally {
            super.tearDown()
        }
    }

    private fun skip(): Boolean {
        if (!ready) println("FantomSkySparkLibraryTest skipped: ${skyspark.path} or ${fantom.path} missing")
        return !ready
    }

    private fun resolveAt(text: String): PsiElement {
        myFixture.configureByText("A.fan", text)
        val ref = myFixture.file.findReferenceAt(myFixture.caretOffset) as FantomReference
        return ref.resolveAll().single()
    }

    fun testStubLibraries() {
        if (skip()) return
        val pods = FantomSettings.getInstance(project).podSources()
        val stubs = pods.filter { FantomPodStubs.isStub(it) }
        println("FantomSkySparkLibraryTest: ${pods.size} pods, ${stubs.size} from stubs")
        assertTrue(stubs.any { it.pod == "haystack" })
        // sys comes from the Fantom sources, not from SkySpark's sys.pod.
        assertEquals(File(fantom, "src/sys"), pods.single { it.pod == "sys" }.podDir)
        assertEquals(pods.size, pods.map { it.pod }.toSet().size)
        val libs = FantomLibraryRootsProvider().getAdditionalProjectLibraries(project)
            .map { it as FantomLibraryRootsProvider.FantomLibrary }
        assertTrue(libs.any { it.presentableText == "haystack" })
        assertEquals(pods.size, libs.size)
    }

    fun testEtcResolvesToStub() {
        if (skip()) return
        val etc = resolveAt("using haystack\nclass A { Void f() { x := Et<caret>c.emptyDict } }")
        val file = etc.containingFile.virtualFile
        assertEquals("Etc.fan", file.name)
        assertTrue(file.path, file.path.contains("fantom-pod-stubs/haystack-3.0.29-"))
        assertTrue(ProjectFileIndex.getInstance(project).isInLibrarySource(file))
    }

    fun testEtcMembersComplete() {
        if (skip()) return
        myFixture.configureByText("A.fan", "using haystack\nclass A { Void f() { Etc.<caret> } }")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings ?: emptyList()
        assertTrue(items.take(30).toString(), "emptyDict" in items)
    }

    fun testQuickDocShowsApiDoc() {
        if (skip()) return
        val target = resolveAt("using haystack\nclass A { Void f() { x := Etc.empty<caret>Dict } }")
        val doc = FantomDocumentationProvider().generateDoc(target, null)
        assertNotNull(doc)
        assertTrue(doc!!, doc.contains("Get the emtpy Dict instance"))
    }
}
