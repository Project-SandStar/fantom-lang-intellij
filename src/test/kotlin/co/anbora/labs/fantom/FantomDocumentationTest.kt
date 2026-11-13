package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.documentation.FantomDocumentationProvider
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.codeInsight.documentation.DocumentationManager
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex
import java.io.File

/** Quick Documentation for Fantom declarations: signature block plus fandoc as HTML. */
class FantomDocumentationTest : BasePlatformTestCase() {

    private var home: File? = null

    override fun tearDown() {
        try {
            if (home != null) {
                FantomSettings.getInstance(project).homePath = null
                WriteAction.runAndWait<RuntimeException> {
                    ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(EmptyRunnable.getInstance(), false, true)
                }
            }
        } finally {
            super.tearDown()
        }
    }

    /** Configures the Fantom install as a library. Returns false when no home is on this machine. */
    private fun configureHome(): Boolean {
        home = FantomHome.detect()
        val h = home ?: run { println("FantomDocumentationTest: no Fantom home found, test skipped"); return false }
        FantomSettings.getInstance(project).homePath = h.path
        WriteAction.runAndWait<RuntimeException> {
            ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(EmptyRunnable.getInstance(), false, true)
        }
        FileBasedIndex.getInstance().ensureUpToDate(FantomDeclarationIndex.NAME, project, null)
        return true
    }

    private fun targetAtCaret(): PsiElement {
        val ref = myFixture.file.findReferenceAt(myFixture.caretOffset)
        assertNotNull("no reference at caret", ref)
        val target = ref!!.resolve()
        assertNotNull("reference at caret does not resolve", target)
        return target!!
    }

    @Suppress("DEPRECATION")
    private fun docAtCaret(): String {
        val target = targetAtCaret()
        val original = myFixture.file.findElementAt(myFixture.caretOffset)
        val provider = DocumentationManager.getProviderFromElement(target, original)
        val doc = provider.generateDoc(target, original)
        assertNotNull("no doc generated", doc)
        return doc!!
    }

    fun testMethodDocHasSignatureAndFandoc() {
        myFixture.configureByText("A.fan", """
            class A {
              Int total := 0

              ** Doc line
              ** Uses `total` and 'x'.
              Void f(Int a, Str? b := "x") {}

              Void g() { <caret>f(1) }
            }
        """.trimIndent())
        val doc = docAtCaret()
        assertTrue(doc, doc.contains("f(Int, Str?): Void"))
        assertTrue(doc, doc.contains("Doc line"))
        assertTrue(doc, doc.contains("<code>total</code>"))
        assertTrue(doc, doc.contains("<code>x</code>"))
        assertTrue(doc, doc.contains(">A<"))
    }

    fun testFirstSlotOfTypeFindsDocBeforeSlotDefs() {
        myFixture.configureByText("A.fan", """
            ** Class doc
            class A {
              ** First slot doc
              static Void first(Int a) {}
              Void g() { fir<caret>st(1) }
            }
        """.trimIndent())
        val doc = docAtCaret()
        assertTrue(doc, doc.contains("static first(Int): Void"))
        assertTrue(doc, doc.contains("First slot doc"))
        assertFalse(doc, doc.contains("Class doc"))
    }

    fun testFieldDocAndQuickNavigate() {
        myFixture.configureByText("A.fan", """
            class A {
              Void g() {}
              ** The count.
              const Int count := 0
              Void h() { echo(co<caret>unt) }
            }
        """.trimIndent())
        val doc = docAtCaret()
        assertTrue(doc, doc.contains("const count: Int"))
        assertTrue(doc, doc.contains("The count."))
        val target = targetAtCaret() as FantomNamedElement
        assertEquals("const count: Int", FantomDocumentationProvider().getQuickNavigateInfo(target, null))
    }

    @Suppress("DEPRECATION")
    fun testLocalDocShowsInferredType() {
        myFixture.configureByText("A.fan", """
            class A {
              Int count := 0
              Void h() { y := count; echo(<caret>y) }
            }
        """.trimIndent())
        val doc = docAtCaret()
        assertTrue(doc, doc.contains("local y: Int"))
        val target = targetAtCaret() as FantomNamedElement
        assertEquals("local y: Int", FantomDocumentationProvider().getQuickNavigateInfo(target, null))
    }

    @Suppress("DEPRECATION")
    fun testTypedLocalDocKeepsWrittenType() {
        myFixture.configureByText("A.fan", "class A { Void h() { Str? s := null; echo(<caret>s) } }")
        val target = targetAtCaret() as FantomNamedElement
        assertEquals("Str? s", FantomDocumentationProvider().getQuickNavigateInfo(target, null))
    }

    fun testClassDocFromFileLevelComments() {
        myFixture.addFileToProject("B.fan", "** B does things.\n**\n** Second paragraph.\nabstract class B {}\n")
        myFixture.configureByText("A.fan", "class A { <caret>B b }")
        val doc = docAtCaret()
        assertTrue(doc, doc.contains("abstract class B"))
        assertTrue(doc, doc.contains("<p>B does things.</p><p>Second paragraph.</p>"))
    }

    fun testSysStrDocFromLibrary() {
        if (!configureHome()) return
        myFixture.configureByText("A.fan", "class A { St<caret>r name := \"x\" }")
        val doc = docAtCaret()
        assertTrue(doc, doc.contains("class Str"))
        assertTrue(doc, doc.contains("sequence of Unicode characters"))
    }

    fun testFandocRenderedAsHtml() {
        myFixture.configureByText("A.fan", """
            class A {
              ** Returns the sum.
              **
              ** - first item
              ** - second with 'code'
              **
              ** **Important** note, see `#g`.
              **
              **   a + b
              Int f(Int a) { a }

              Void g() { <caret>f(1) }
            }
        """.trimIndent())
        val doc = docAtCaret()
        assertTrue(doc, doc.contains("<ul><li>first item</li><li>second with <code>code</code></li></ul>"))
        assertTrue(doc, doc.contains("<strong>Important</strong>"))
        assertTrue(doc, doc.contains("<code>#g</code>"))
        assertTrue(doc, doc.contains("<pre>a + b</pre>"))
        assertTrue(doc, doc.contains(DocumentationMarkup.CONTENT_START))
        assertTrue(doc, doc.contains(DocumentationMarkup.SECTIONS_START))
        assertTrue(doc, doc.contains("A.fan"))
        val content = doc.substringAfter(DocumentationMarkup.CONTENT_START).substringBefore(DocumentationMarkup.CONTENT_END)
        assertTrue(content, FandocHtml.balanced(content))
    }
}
