package co.anbora.labs.fantom.typing

import co.anbora.labs.fantom.ide.typing.FantomBackspaceHandler
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** [FantomBackspaceHandler]: backspace in an empty pair removes both chars. Cases not in FantomTypingTest. */
class FantomBackspaceHandlerTest : BasePlatformTestCase() {

    private fun doBackspace(before: String, after: String) {
        myFixture.configureByText("A.fan", before)
        myFixture.type('\b')
        myFixture.checkResult(after)
    }

    fun testRemovesEmptySingleQuotePair() = doBackspace(
        "class C { Void f() { x := '<caret>' } }",
        "class C { Void f() { x := <caret> } }")

    fun testNonEmptyQuoteIsLeftAlone() = doBackspace(
        "class C { Void f() { x := \"a<caret>\" } }",
        "class C { Void f() { x := \"<caret>\" } }")

    fun testOtherCharIsLeftAlone() = doBackspace(
        "class C { Void f() { x := ab<caret>b } }",
        "class C { Void f() { x := a<caret>b } }")

    fun testParenPairUsesPlatform() = doBackspace(
        "class C { Void f() { g(<caret>) } }",
        "class C { Void f() { g<caret> } }")

    // Current behaviour: the handler only checks that the next char is the same quote, so
    // backspace on the closing quote of "a" also eats the opening quote of "b".
    fun testClosingQuoteBeforeNextStringEatsBoth() = doBackspace(
        "class C { Void f() { x := \"a\"<caret>\"b\" } }",
        "class C { Void f() { x := \"a<caret>b\" } }")

    // Current behaviour: same rule for `||`, the logical or operator.
    fun testOrOperatorIsRemovedWhole() = doBackspace(
        "class C { Void f() { x := a |<caret>| b } }",
        "class C { Void f() { x := a <caret> b } }")
}
