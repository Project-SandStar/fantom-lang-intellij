package co.anbora.labs.fantom.typing

import co.anbora.labs.fantom.ide.typing.FantomEnterHandler
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** [FantomEnterHandler]: the new line copies the indent, adds two spaces after `{`. Cases not in FantomTypingTest. */
class FantomEnterHandlerTest : BasePlatformTestCase() {

    private fun doEnter(before: String, after: String) {
        myFixture.configureByText("A.fan", before)
        myFixture.type('\n')
        myFixture.checkResult(after)
    }

    fun testPlainLineCopiesIndent() = doEnter(
        "class C {\n  Void f() {\n    x := 1<caret>\n  }\n}",
        "class C {\n  Void f() {\n    x := 1\n    <caret>\n  }\n}")

    fun testSplitLineKeepsRestOnNewLine() = doEnter(
        "class C {\n  Void f() {\n    a(); <caret>b()\n  }\n}",
        "class C {\n  Void f() {\n    a(); \n    <caret>b()\n  }\n}")

    fun testBracesWithSpacesBetween() = doEnter(
        "class C {\n  Void f() {<caret>  }\n}",
        "class C {\n  Void f() {\n    <caret>\n  }\n}")

    fun testIndentedFandocContinues() = doEnter(
        "class C {\n    ** first line<caret>\n    Void f() {}\n}",
        "class C {\n    ** first line\n    ** <caret>\n    Void f() {}\n}")

    fun testTopLevelFandocContinues() = doEnter(
        "** doc<caret>\nclass C {}",
        "** doc\n** <caret>\nclass C {}")

    fun testLineCommentDoesNotContinue() = doEnter(
        "class C {\n  // note<caret>\n}",
        "class C {\n  // note\n  <caret>\n}")

    // Current behaviour: the `{` check does not look at comments, so a `{` at the end of a
    // line comment still adds one indent step.
    fun testBraceAtEndOfLineCommentIndents() = doEnter(
        "class C {\n  // see {<caret>\n}",
        "class C {\n  // see {\n    <caret>\n}")
}
