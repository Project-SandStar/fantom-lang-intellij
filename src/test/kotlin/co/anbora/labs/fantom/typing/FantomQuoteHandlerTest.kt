package co.anbora.labs.fantom.typing

import co.anbora.labs.fantom.ide.typing.FantomQuoteHandler
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** [FantomQuoteHandler]: pairs and skips `"`, `'` and `` ` ``. Cases not in the root FantomTypingTest. */
class FantomQuoteHandlerTest : BasePlatformTestCase() {

    private fun doType(before: String, typed: String, after: String) {
        myFixture.configureByText("A.fan", before)
        myFixture.type(typed)
        myFixture.checkResult(after)
    }

    fun testSingleQuotePairs() = doType(
        "class C { Void f() { x := <caret> } }", "'",
        "class C { Void f() { x := '<caret>' } }")

    fun testClosingSingleQuoteSkips() = doType(
        "class C { Void f() { x := 'a<caret>' } }", "'",
        "class C { Void f() { x := 'a'<caret> } }")

    fun testClosingBacktickSkips() = doType(
        "class C { Void f() { x := `a<caret>` } }", "`",
        "class C { Void f() { x := `a`<caret> } }")

    fun testTypedStringRoundTrip() = doType(
        "class C { Void f() { x := <caret> } }", "\"ab\"",
        "class C { Void f() { x := \"ab\"<caret> } }")

    fun testQuoteOnLineWithOpenLiteralStaysSingle() = doType(
        "class C { Void f() { x := <caret> + \"a } }", "\"",
        "class C { Void f() { x := \"<caret> + \"a } }")

    fun testQuoteBeforeClosedStringPairs() = doType(
        "class C { Void f() { x := <caret> + \"a\" } }", "\"",
        "class C { Void f() { x := \"<caret>\" + \"a\" } }")
}
