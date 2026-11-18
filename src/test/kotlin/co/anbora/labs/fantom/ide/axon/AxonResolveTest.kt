package co.anbora.labs.fantom.ide.axon

import co.anbora.labs.fantom.lang.axon.psi.AxonCatchVar
import co.anbora.labs.fantom.lang.axon.psi.AxonCellDef
import co.anbora.labs.fantom.lang.axon.psi.AxonDefExpr
import co.anbora.labs.fantom.lang.axon.psi.AxonFunctionSource
import co.anbora.labs.fantom.lang.axon.psi.AxonNameUse
import co.anbora.labs.fantom.lang.axon.psi.AxonNamedElement
import co.anbora.labs.fantom.lang.axon.psi.AxonParam
import co.anbora.labs.fantom.lang.axon.psi.AxonResolver
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Name resolution in one Axon file: locals, parameters, `defcomp` cells, catch variables
 * and top-level functions, and the [AxonFunctionSource] extension point.
 */
class AxonResolveTest : BasePlatformTestCase() {

    /** Resolves the name use at `<caret>` in [text]. The text must parse with no error. */
    private fun resolveAtCaret(text: String): PsiElement? {
        val file = myFixture.configureByText("a.axon", text)
        assertEmpty("parse errors", PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java))
        val leaf = file.findElementAt(myFixture.caretOffset)!!
        val use = PsiTreeUtil.getParentOfType(leaf, AxonNameUse::class.java, false)
        assertNotNull("no name use at the caret: '${leaf.text}'", use)
        assertEquals("the caret is not on the name of the use", leaf, use!!.referenceNameElement)
        return use.reference!!.resolve()
    }

    /** The declaration found at the caret, with its line (from 1). */
    private fun target(text: String): Pair<AxonNamedElement, Int> {
        val found = resolveAtCaret(text)
        assertNotNull("the name must resolve", found)
        val named = found as AxonNamedElement
        val line = myFixture.editor.document.getLineNumber(named.textOffset) + 1
        return named to line
    }

    fun testLocal() {
        val (decl, line) = target(
            """
            (a) => do
              total: a + 1
              <caret>total * 2
            end
            """.trimIndent()
        )
        assertTrue(decl is AxonDefExpr)
        assertEquals("total", decl.name)
        assertEquals(2, line)
    }

    fun testParam() {
        val (decl, line) = target("(point, dates) => point.hisRead(<caret>dates)")
        assertTrue(decl is AxonParam)
        assertEquals("dates", decl.name)
        assertEquals(1, line)
    }

    fun testSingleParamLambda() {
        val (decl, _) = target("list.map(v => <caret>v * 2)")
        assertTrue(decl is AxonParam)
        assertEquals("v", decl.name)
    }

    /** The inner lambda sees the outer parameter; its own parameter of the same name wins. */
    fun testNestedLambdas() {
        val (outer, _) = target("(a, b) => list.map(v => v + <caret>a)")
        assertTrue(outer is AxonParam)
        assertEquals("a", outer.name)

        val text = "(v) => list.map(v => <caret>v)"
        val (inner, _) = target(text)
        assertEquals(text.indexOf("map(v") + 4, inner.textOffset)
    }

    /** A `do` block opens no scope: a local of an inner block is visible after it. */
    fun testDoBlockOpensNoScope() {
        val (decl, line) = target(
            """
            (a) => do
              if (a) do
                x: 1
              end
              <caret>x
            end
            """.trimIndent()
        )
        assertEquals("x", decl.name)
        assertEquals(3, line)
    }

    /** A use before the definition of a local does not resolve to it. */
    fun testLocalIsNotVisibleBeforeItsDefinition() {
        assertNull(
            resolveAtCaret(
                """
                () => do
                  echo(<caret>later)
                  later: 1
                end
                """.trimIndent()
            )
        )
    }

    /** `x: x + 1` reads the outer `x`, not itself. The last definition before the use wins. */
    fun testRedefinition() {
        val (first, firstLine) = target(
            """
            () => do
              x: 1
              x: <caret>x + 1
              x
            end
            """.trimIndent()
        )
        assertEquals(2, firstLine)
        assertEquals("x", first.name)

        val (_, lastLine) = target(
            """
            () => do
              x: 1
              x: x + 1
              <caret>x
            end
            """.trimIndent()
        )
        assertEquals(3, lastLine)
    }

    fun testSameFileFunction() {
        val (decl, line) = target(
            """
            calcDailyAvg: (point) => point.hisRead(today()).avg

            report: (points) => points.map(p => <caret>calcDailyAvg(p))
            """.trimIndent()
        )
        assertTrue(decl is AxonDefExpr)
        assertEquals("calcDailyAvg", decl.name)
        assertEquals(1, line)
    }

    /** Top-level functions of one file see each other, so a call can come before the definition. */
    fun testSameFileFunctionDefinedLater() {
        val (decl, line) = target(
            """
            report: (points) => points.map(p => <caret>toName(p))

            toName: rec => rec->dis
            """.trimIndent()
        )
        assertEquals("toName", decl.name)
        assertEquals(3, line)
    }

    /** A function can call itself. */
    fun testRecursion() {
        val (decl, line) = target("fact: (n) => if (n <= 1) 1 else n * <caret>fact(n - 1)")
        assertEquals("fact", decl.name)
        assertEquals(1, line)
    }

    /** `a.f(b)` calls the function `f`: the name after the dot resolves like a plain call. */
    fun testDotCallToSameFileFunction() {
        val (decl, line) = target(
            """
            toName: rec => rec->dis

            names: (recs) => recs.first.<caret>toName
            """.trimIndent()
        )
        assertEquals("toName", decl.name)
        assertEquals(1, line)
    }

    fun testDefcompCell() {
        val (decl, line) = target(
            """
            defcomp
              in: {is: ^number}
              out: {is: ^number, ro}
              do
                out = <caret>in * 2
              end
            end
            """.trimIndent()
        )
        assertTrue(decl is AxonCellDef)
        assertEquals("in", decl.name)
        assertEquals(2, line)
    }

    fun testCatchVariable() {
        val (decl, _) = target("try readAll(site) catch (err) <caret>err->dis")
        assertTrue(decl is AxonCatchVar)
        assertEquals("err", decl.name)
    }

    /** The catch variable is not visible in the `try` body. */
    fun testCatchVariableNotInTryBody() {
        assertNull(resolveAtCaret("try echo(<caret>err) catch (err) null"))
    }

    /** A dict key, a `->tag` name and a marker tag in a filter are not name uses of a local. */
    fun testTagNamesAreNotReferences() {
        val file = myFixture.configureByText("a.axon", "(dis) => do\n  {dis: 1}\n  rec->dis\nend")
        val uses = PsiTreeUtil.findChildrenOfType(file, AxonNameUse::class.java).map { it.text }
        assertEquals(listOf("rec"), uses)
    }

    /** A function that is not in the file does not resolve, and that is not an error: the reference is soft. */
    fun testUnknownFunctionIsSoft() {
        val file = myFixture.configureByText("a.axon", "readAll(site).hisRead(yesterday())")
        val uses = PsiTreeUtil.findChildrenOfType(file, AxonNameUse::class.java)
        assertTrue(uses.isNotEmpty())
        for (use in uses) {
            val ref = use.reference!!
            assertNull(use.text, ref.resolve())
            assertTrue(use.text, ref.isSoft)
        }
        assertEmpty(myFixture.doHighlighting(HighlightSeverity.WARNING))
    }

    /** Completion variants: the names visible at the use, nearest first. */
    fun testVisibleNames() {
        val file = myFixture.configureByText(
            "a.axon",
            """
            helper: (x) => x

            main: (a, b) => do
              c: 1
              <caret>c
              d: 2
            end
            """.trimIndent()
        )
        val leaf = file.findElementAt(myFixture.caretOffset)!!
        assertEquals(listOf("c", "b", "a", "main", "helper"), AxonResolver.visible(leaf.parent).map { it.name })
    }

    /** A registered [AxonFunctionSource] supplies a function the file does not have; the file still wins. */
    fun testFunctionSourceExtensionPoint() {
        val library = myFixture.addFileToProject("lib/funcs.axon", "siteArea: (site) => site->area\nlocal: () => 1")
        val source = object : AxonFunctionSource {
            override fun findFunctions(name: String, context: PsiElement): Collection<PsiElement> =
                PsiTreeUtil.getChildrenOfTypeAsList(library, AxonDefExpr::class.java).filter { it.name == name }
        }
        ExtensionTestUtil.maskExtensions(AxonFunctionSource.EP_NAME, listOf(source), testRootDisposable)

        val fromSource = resolveAtCaret("total: (s) => <caret>siteArea(s) * 2")
        assertEquals(library, fromSource!!.containingFile)
        assertEquals("siteArea", (fromSource as AxonDefExpr).name)

        val (own, _) = target("local: () => 2\nuse: () => <caret>local()")
        assertEquals("a.axon", own.containingFile.name)

        assertNull(resolveAtCaret("x: () => <caret>notAnywhere()"))
    }

    /** Rename goes through the reference and the declaration. */
    fun testRename() {
        myFixture.configureByText("a.axon", "calc: (val<caret>ue) => do\n  twice: value * 2\n  twice + value\nend")
        myFixture.renameElementAtCaret("input")
        myFixture.checkResult("calc: (input) => do\n  twice: input * 2\n  twice + input\nend")
    }
}
