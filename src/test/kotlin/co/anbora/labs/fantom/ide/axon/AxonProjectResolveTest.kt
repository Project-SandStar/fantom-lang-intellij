package co.anbora.labs.fantom.ide.axon

import co.anbora.labs.fantom.lang.axon.copy.AxonCopyException
import co.anbora.labs.fantom.lang.axon.copy.AxonCopyLimits
import co.anbora.labs.fantom.lang.axon.copy.AxonFunction
import co.anbora.labs.fantom.lang.axon.copy.AxonFunctionList
import co.anbora.labs.fantom.lang.axon.copy.AxonFunctionSource
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectCopy
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectInfo
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectKey
import co.anbora.labs.fantom.lang.axon.psi.AxonDefExpr
import co.anbora.labs.fantom.lang.axon.psi.AxonNameUse
import co.anbora.labs.fantom.lang.axon.psi.AxonParam
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Task 4.30: names that are not in the file resolve, complete, show docs and are found as
 * usages through the functions of the active Axon project copy. Every name here is made up.
 */
class AxonProjectResolveTest : BasePlatformTestCase() {

    private lateinit var cache: File
    private val data = HashMap<AxonProjectKey, List<AxonFunction>>()
    private val site = AxonProjectKey("demo", "site")
    private val plant = AxonProjectKey("demo", "plant")
    private val copy get() = AxonProjectCopy.getInstance(project)

    /** An in-memory source: no network. */
    private class MemSource(val data: Map<AxonProjectKey, List<AxonFunction>>) : AxonFunctionSource {
        override val id = "server"
        override fun listProjects(check: () -> Unit) = data.map { AxonProjectInfo(it.key, it.value.size, null) }
        override fun fetch(key: AxonProjectKey, sync: Boolean, check: () -> Unit): AxonFunctionList =
            AxonFunctionList(key, null, data[key] ?: throw AxonCopyException(AxonCopyException.Kind.UNKNOWN_PROJECT, "unknown"))
    }

    override fun setUp() {
        super.setUp()
        cache = FileUtil.createTempDirectory("axon-resolve-cache", null)
        copy.testCacheRoot = cache
        copy.testSource = MemSource(data)
    }

    override fun tearDown() {
        try {
            copy.dropNow()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            copy.testSource = null
            copy.testCacheRoot = null
            cache.walkTopDown().forEach { it.setWritable(true) }
            FileUtil.delete(cache)
        } finally {
            super.tearDown()
        }
    }

    private fun fn(name: String, src: String, doc: String? = null) = AxonFunction(name, src, doc, null, AxonCopyLimits.sha256(src))

    /** Makes [key] the active project copy with [functions]. Runs inline: the source is in memory. */
    private fun pick(key: AxonProjectKey, vararg functions: AxonFunction) {
        data[key] = functions.toList()
        copy.selectNow(key)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    private fun siteProject() = pick(
        site,
        fn("siteArea", "(site) => site->area", "Area of a site.\nIn square metres."),
        fn("siteReport", "() => readAll(site)"),
    )

    private fun useAtCaret(text: String): AxonNameUse {
        val file = myFixture.configureByText("a.axon", text)
        assertEmpty("parse errors", PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java))
        val leaf = file.findElementAt(myFixture.caretOffset)!!
        val use = PsiTreeUtil.getParentOfType(leaf, AxonNameUse::class.java, false)!!
        assertEquals("the caret is not on the name", leaf, use.referenceNameElement)
        return use
    }

    private fun resolveAt(text: String): PsiElement? = useAtCaret(text).reference!!.resolve()

    private fun assertIsFunctionFile(name: String, found: PsiElement?) {
        assertTrue("$name must resolve to a file, got $found", found is PsiFile)
        val vf = (found as PsiFile).virtualFile
        assertEquals(copy.findFunctionFile(name), vf)
        assertTrue(copy.isInCopy(vf))
    }

    // ---- 1. resolve and Ctrl+click -------------------------------------------------------------

    fun testCallResolvesToTheFileOfTheProjectFunction() {
        siteProject()
        assertIsFunctionFile("siteArea", resolveAt("total: (s) => <caret>siteArea(s) * 2"))
    }

    fun testBareReferenceResolvesToo() {
        siteProject()
        assertIsFunctionFile("siteArea", resolveAt("(list) => list.map(<caret>siteArea)"))
    }

    fun testDotCallResolvesToo() {
        siteProject()
        assertIsFunctionFile("siteArea", resolveAt("(s) => s.<caret>siteArea()"))
    }

    fun testLocalAndParameterShadowTheProjectFunction() {
        siteProject()
        assertTrue(resolveAt("(s) => do\n  siteArea: 1\n  <caret>siteArea\nend") is AxonDefExpr)
        assertTrue(resolveAt("(siteArea) => <caret>siteArea") is AxonParam)
    }

    fun testFunctionOfTheSameFileBeatsTheProjectFunction() {
        siteProject()
        val found = resolveAt("siteArea: (x) => x\nuse: () => <caret>siteArea(1)")
        assertTrue(found is AxonDefExpr)
        assertEquals("a.axon", found!!.containingFile.name)
    }

    fun testTwoProjectsNeverMix() {
        siteProject()
        assertIsFunctionFile("siteArea", resolveAt("() => <caret>siteArea(1)"))
        assertNull(resolveAt("() => <caret>plantLoad()"))

        pick(plant, fn("plantLoad", "() => 42"))
        assertNull("a function of the old project", resolveAt("() => <caret>siteArea(1)"))
        val load = resolveAt("() => <caret>plantLoad()")
        assertIsFunctionFile("plantLoad", load)
        assertTrue((load as PsiFile).virtualFile.path.contains("demo/plant"))
    }

    fun testEmptyProjectAndNoProject() {
        assertNull("no copy", resolveAt("() => <caret>siteArea(1)"))
        pick(site)
        assertNull("empty project", resolveAt("() => <caret>siteArea(1)"))
        myFixture.configureByText("b.axon", "(sitePoint) => site<caret>")
        // the parameter is the only item, so completion inserts it at once
        val items = myFixture.completeBasic()
        assertTrue(items == null || items.map { it.lookupString }.filter { it.startsWith("site") } == listOf("sitePoint"))
        if (items == null) myFixture.checkResult("(sitePoint) => sitePoint<caret>")
    }

    // ---- 5. an unresolved name is no error ------------------------------------------------------

    fun testUnknownNamesAreNoErrorWithAProject() {
        siteProject()
        myFixture.configureByText("a.axon", "(x) => readAll(site).hisRead(yesterday()).map(r => siteArea(r) + noSuchFunc(r))")
        assertEmpty(myFixture.doHighlighting(HighlightSeverity.WARNING))
    }

    // ---- 2. completion ----------------------------------------------------------------------------

    fun testCompletionOffersProjectFunctionsWithTheFirstDocLine() {
        siteProject()
        myFixture.configureByText("a.axon", "(sitePoint) => site<caret>")
        myFixture.completeBasic()
        val items = myFixture.lookupElements!!.associateBy { it.lookupString }
        assertTrue(items.keys.containsAll(listOf("sitePoint", "siteArea", "siteReport")))
        val area = LookupElementPresentation.renderElement(items.getValue("siteArea"))
        assertEquals("  Area of a site.", area.tailText)
        assertEquals("demo/site", area.typeText)
        assertNull(LookupElementPresentation.renderElement(items.getValue("siteReport")).tailText)
    }

    fun testCompletionKeepsTheLocalWhenItHasTheNameOfAProjectFunction() {
        siteProject()
        myFixture.configureByText("a.axon", "(siteArea, sitePoint) => site<caret>")
        myFixture.completeBasic()
        val area = myFixture.lookupElements!!.filter { it.lookupString == "siteArea" }
        assertEquals(1, area.size)
        assertTrue(area.single().psiElement is AxonParam)
    }

    // ---- 3. quick documentation ------------------------------------------------------------------

    fun testQuickDocOfAProjectFunction() {
        siteProject()
        val target = resolveAt("() => <caret>siteArea(1)")
        val doc = AxonDocumentationProvider().generateDoc(target, null)!!
        assertTrue(doc, doc.contains("siteArea(site)"))
        assertTrue(doc, doc.contains("demo/site"))
        assertTrue(doc, doc.contains("Area of a site."))
        assertEquals("siteArea(site)  [demo/site]", AxonDocumentationProvider().getQuickNavigateInfo(target, null))

        val report = AxonDocumentationProvider().generateDoc(resolveAt("() => <caret>siteReport()"), null)!!
        assertTrue(report, report.contains("siteReport()"))
    }

    fun testQuickDocOfAFunctionOfTheFile() {
        val target = resolveAt("calc: (a, b: 2) => a + b\nuse: () => <caret>calc(1)")
        val doc = AxonDocumentationProvider().generateDoc(target, null)!!
        assertTrue(doc, doc.contains("calc(a, b: 2)"))
        assertNull("a local is no function", AxonDocumentationProvider().generateDoc(resolveAt("(x) => do\n  v: 1\n  <caret>v\nend"), null))
    }

    // ---- 4. find usages ------------------------------------------------------------------------

    fun testFindUsagesOfAProjectFunction() {
        siteProject()
        myFixture.addFileToProject("one.axon", "() => siteArea(1)")
        myFixture.addFileToProject("two.axon", "(s) => s.siteArea()")
        myFixture.addFileToProject("shadow.axon", "(siteArea) => siteArea")
        myFixture.addFileToProject("other.axon", "() => siteReport()")
        val target = PsiManager.getInstance(project).findFile(copy.findFunctionFile("siteArea")!!)!!
        assertTrue(AxonFindUsagesProvider().canFindUsagesFor(target))
        val files = ReferencesSearch.search(target, GlobalSearchScope.projectScope(project)).findAll()
            .map { it.element.containingFile.name }.sorted()
        assertEquals(listOf("one.axon", "two.axon"), files)
    }

    // ---- 6. speed ------------------------------------------------------------------------------

    fun testAProjectOf20000FunctionsResolvesFast() {
        val n = 20_000
        pick(site, *Array(n) { fn("fn$it", "() => $it") })
        assertEquals(n, copy.functions().size)

        val text = StringBuilder("(x) => do\n")
        val names = (0 until n step 100).map { "fn$it" }
        for (name in names) text.append("  $name(x)\n")
        text.append("end\n")
        val file = myFixture.configureByText("big.axon", text.toString())
        val uses = PsiTreeUtil.findChildrenOfType(file, AxonNameUse::class.java).filter { it.referenceName!!.startsWith("fn") }
        assertEquals(names.size, uses.size)

        uses.first().reference!!.resolve() // load the folder once
        val start = System.nanoTime()
        for (use in uses) assertNotNull(use.referenceName, use.reference!!.resolve())
        val avgMs = (System.nanoTime() - start) / 1_000_000.0 / uses.size
        assertTrue("one name took $avgMs ms on average", avgMs < 10.0)
    }
}
