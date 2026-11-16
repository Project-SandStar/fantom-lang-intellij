package co.anbora.labs.fantom.typing

import co.anbora.labs.fantom.ide.typing.FantomTypedHandler
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * [FantomTypedHandler]: `|` pairs where a closure can start, and `}` on a blank line moves
 * to the indent of its `{`. Cases not covered by the root FantomTypingTest.
 */
class FantomTypedHandlerTest : BasePlatformTestCase() {

    private fun doType(before: String, typed: String, after: String) {
        myFixture.configureByText("A.fan", before)
        myFixture.type(typed)
        myFixture.checkResult(after)
    }

    fun testPipeAfterCommaPairs() = doType(
        "class C { Void f() { g(1, <caret>) } }", "|",
        "class C { Void f() { g(1, |<caret>|) } }")

    fun testPipeAfterAssignPairs() = doType(
        "class C { Void f() { x := <caret> } }", "|",
        "class C { Void f() { x := |<caret>| } }")

    fun testPipeAfterOpenBracePairs() = doType(
        "class C { Void f() {<caret>} }", "|",
        "class C { Void f() {|<caret>|} }")

    fun testPipeAfterReturnPairs() = doType(
        "class C { Func f() { return <caret> } }", "|",
        "class C { Func f() { return |<caret>| } }")

    fun testPipeAtLineStartPairs() = doType(
        "class C {\n  Void f() {\n    <caret>\n  }\n}", "|",
        "class C {\n  Void f() {\n    |<caret>|\n  }\n}")

    fun testPipeAfterIdentifierStaysSingle() = doType(
        "class C { Void f() { x := a <caret> } }", "|",
        "class C { Void f() { x := a |<caret> } }")

    fun testPipeBeforeIdentifierStaysSingle() = doType(
        "class C { Void f() { g(<caret>x) } }", "|",
        "class C { Void f() { g(|<caret>x) } }")

    fun testPipeAfterReturnedValueStaysSingle() = doType(
        "class C { Bool f() { return a <caret> } }", "|",
        "class C { Bool f() { return a |<caret> } }")

    fun testPipeInLineCommentStaysSingle() = doType(
        "class C { Void f() {} } // g(<caret>", "|",
        "class C { Void f() {} } // g(|<caret>")

    // The handler inserts `||` only where a closure can start, so it steps over a `|` only
    // when an opening `|` at such a place comes before it. The `||` operator has no such
    // opener: typing `|` before it inserts one pipe.
    fun testPipeBeforeOrOperatorInsertsPipe() = doType(
        "class C { Void f() { x := a <caret>|| b } }", "|",
        "class C { Void f() { x := a |<caret>|| b } }")

    fun testPipeBeforeClosingParamPipeStepsOver() = doType(
        "class C { Void f() { list.each(|Int a, Str b<caret>|) } }", "|",
        "class C { Void f() { list.each(|Int a, Str b|<caret>) } }")

    fun testPipeTwiceMakesEmptyParamList() = doType(
        "class C { Void f() { list.each(<caret>) } }", "||",
        "class C { Void f() { list.each(||<caret>) } }")

    fun testCloseBraceOnCodeLineIsLeftAlone() = doType(
        "class C {\n  Void f() {\n    x := 1 <caret>\n}", "}",
        "class C {\n  Void f() {\n    x := 1 }<caret>\n}")

    fun testCloseBraceInNestedBlockDedentsToItsBrace() = doType(
        "class C {\n  Void f() {\n    if (a) {\n        <caret>\n  }\n}", "}",
        "class C {\n  Void f() {\n    if (a) {\n    }<caret>\n  }\n}")

    fun testCloseBraceInStringIsLeftAlone() = doType(
        "class C {\n  Void f() {\n    x := \"\n        <caret>\"\n  }\n}", "}",
        "class C {\n  Void f() {\n    x := \"\n        }<caret>\"\n  }\n}")

    fun testCloseBraceWithoutOpenBraceIsLeftAlone() = doType(
        "    <caret>", "}",
        "    }<caret>")
}
