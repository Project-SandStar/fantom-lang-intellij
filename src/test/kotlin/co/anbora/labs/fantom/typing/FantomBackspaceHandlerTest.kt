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

    fun testClosingQuoteBeforeNextStringRemovesOnlyItself() = doBackspace(
        "class C { Void f() { x := \"a\"<caret>\"b\" } }",
        "class C { Void f() { x := \"a<caret>\"b\" } }")

    fun testOrOperatorLosesOnePipe() = doBackspace(
        "class C { Void f() { x := a |<caret>| b } }",
        "class C { Void f() { x := a <caret>| b } }")

    fun testEmptyClosureParamsAfterAssignRemovesBoth() = doBackspace(
        "class C { Void f() { x := |<caret>| } }",
        "class C { Void f() { x := <caret> } }")

    fun testClosingBacktickBeforeNextUriRemovesOnlyItself() = doBackspace(
        "class C { Void f() { x := `a`<caret>`b` } }",
        "class C { Void f() { x := `a<caret>`b` } }")
}
