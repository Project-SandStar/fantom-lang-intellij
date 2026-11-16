package co.anbora.labs.fantom.typing

import co.anbora.labs.fantom.ide.typing.FantomBraceMatcher
import co.anbora.labs.fantom.lang.core.psi.FantomTypes
import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** [FantomBraceMatcher]: the three brace pairs, matching in the editor and pair typing. */
class FantomBraceMatcherTest : BasePlatformTestCase() {

    private fun doType(before: String, typed: String, after: String) {
        myFixture.configureByText("A.fan", before)
        myFixture.type(typed)
        myFixture.checkResult(after)
    }

    /** Offset of the brace that matches the brace at the caret. */
    private fun matchOf(text: String, forward: Boolean): Int {
        myFixture.configureByText("A.fan", text)
        return BraceMatchingUtil.getMatchedBraceOffset(myFixture.editor, forward, myFixture.file)
    }

    fun testPairs() {
        val pairs = FantomBraceMatcher().pairs.map { Triple(it.leftBraceType, it.rightBraceType, it.isStructural) }
        assertEquals(
            listOf(
                Triple(FantomTypes.LPAR, FantomTypes.RPAR, true),
                Triple(FantomTypes.LBRACKET, FantomTypes.RBRACKET, false),
                Triple(FantomTypes.LBRACE, FantomTypes.RBRACE, true),
            ),
            pairs,
        )
    }

    fun testCodeConstructStartIsTheBrace() {
        assertEquals(7, FantomBraceMatcher().getCodeConstructStart(null, 7))
        assertTrue(FantomBraceMatcher().isPairedBracesAllowedBeforeType(FantomTypes.LPAR, FantomTypes.IDENTIFIER))
    }

    fun testParenMatchesForward() {
        val text = "class C { Void f() { g(1, 2) } }"
        assertEquals(text.indexOf(')', text.indexOf("g(")), matchOf(text.replace("g(", "g<caret>("), true))
    }

    fun testBracketMatchesForward() {
        val text = "class C { Void f() { x := [1, 2] } }"
        assertEquals(text.indexOf(']'), matchOf(text.replace("[", "<caret>["), true))
    }

    fun testBraceMatchesBackward() {
        val text = "class C { Void f() { x := 1 } }"
        // Caret after the last `}`: the match is the `{` that opens the class.
        assertEquals(text.indexOf('{'), matchOf("$text<caret>", false))
    }

    fun testNestedBraceMatchesForward() {
        val text = "class C { Void f() { if (a) { b() } } }"
        val open = text.indexOf("{ b")
        assertEquals(text.indexOf('}', open), matchOf(text.substring(0, open) + "<caret>" + text.substring(open), true))
    }

    fun testOpenParenPairs() = doType(
        "class C { Void f() { g<caret> } }", "(",
        "class C { Void f() { g(<caret>) } }")

    fun testOpenBracketPairs() = doType(
        "class C { Void f() { x := <caret> } }", "[",
        "class C { Void f() { x := [<caret>] } }")

    fun testCloseParenSkips() = doType(
        "class C { Void f() { g(<caret>) } }", ")",
        "class C { Void f() { g()<caret> } }")

    fun testCloseBracketSkips() = doType(
        "class C { Void f() { x := [<caret>] } }", "]",
        "class C { Void f() { x := []<caret> } }")

    fun testEnterAfterUnmatchedBraceAddsClose() {
        myFixture.configureByText("A.fan", "class C {\n  Void f() {<caret>\n}")
        myFixture.type('\n')
        assertEquals("class C {\n  Void f() {\n    \n  }\n}", myFixture.editor.document.text)
        assertEquals("class C {\n  Void f() {\n    ".length, myFixture.caretOffset)
    }
}
