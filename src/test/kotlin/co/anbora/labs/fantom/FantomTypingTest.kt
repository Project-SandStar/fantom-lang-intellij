package co.anbora.labs.fantom

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FantomTypingTest : BasePlatformTestCase() {

    private fun doType(before: String, typed: String, after: String) {
        myFixture.configureByText("A.fan", before)
        myFixture.type(typed)
        myFixture.checkResult(after)
    }

    private fun doEnter(before: String, after: String) {
        myFixture.configureByText("A.fan", before)
        myFixture.type('\n')
        myFixture.checkResult(after)
    }

    private fun doBackspace(before: String, after: String) {
        myFixture.configureByText("A.fan", before)
        myFixture.type('\b')
        myFixture.checkResult(after)
    }

    fun testDoubleQuotePairs() = doType(
        "class C { Void f() { x := <caret> } }", "\"",
        "class C { Void f() { x := \"<caret>\" } }")

    fun testClosingDoubleQuoteSkips() = doType(
        "class C { Void f() { x := \"a<caret>\" } }", "\"",
        "class C { Void f() { x := \"a\"<caret> } }")

    fun testBacktickPairs() = doType(
        "class C { Void f() { x := <caret> } }", "`",
        "class C { Void f() { x := `<caret>` } }")

    fun testPipeAfterParenPairs() = doType(
        "class C { Void f() { list.each(<caret>) } }", "|",
        "class C { Void f() { list.each(|<caret>|) } }")

    fun testPipeBeforePipeSkips() = doType(
        "class C { Void f() { list.each(|Int x<caret>|) } }", "|",
        "class C { Void f() { list.each(|Int x|<caret>) } }")

    fun testPipeInsideStringStaysSingle() = doType(
        "class C { Void f() { x := \"a <caret> b\" } }", "|",
        "class C { Void f() { x := \"a |<caret> b\" } }")

    fun testPipeAfterPipeStaysSingle() = doType(
        "class C { Void f() { x := a |<caret> b } }", "|",
        "class C { Void f() { x := a ||<caret> b } }")

    fun testEnterBetweenBracesIndents() = doEnter(
        "class C {\n  Void f() {<caret>}\n}",
        "class C {\n  Void f() {\n    <caret>\n  }\n}")

    fun testEnterAfterOpenBraceIndents() = doEnter(
        "class C {\n  Void f() {<caret>\n  }\n}",
        "class C {\n  Void f() {\n    <caret>\n  }\n}")

    fun testEnterInFandocContinues() = doEnter(
        "class C {\n  ** doc<caret>\n  Void f() {}\n}",
        "class C {\n  ** doc\n  ** <caret>\n  Void f() {}\n}")

    fun testEnterAfterCloseBraceKeepsIndent() = doEnter(
        "class C {\n  Void f() {\n  }<caret>\n}",
        "class C {\n  Void f() {\n  }\n  <caret>\n}")

    fun testBackspaceRemovesEmptyPipePair() = doBackspace(
        "class C { Void f() { list.each(|<caret>|) } }",
        "class C { Void f() { list.each(<caret>) } }")

    fun testBackspaceRemovesEmptyQuotePair() = doBackspace(
        "class C { Void f() { x := \"<caret>\" } }",
        "class C { Void f() { x := <caret> } }")

    fun testBackspaceRemovesEmptyBacktickPair() = doBackspace(
        "class C { Void f() { x := `<caret>` } }",
        "class C { Void f() { x := <caret> } }")

    fun testCloseBraceDedents() = doType(
        "class C {\n  Void f() {\n    <caret>\n}",
        "}",
        "class C {\n  Void f() {\n  }<caret>\n}")
}
