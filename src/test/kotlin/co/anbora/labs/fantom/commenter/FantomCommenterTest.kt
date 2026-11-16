package co.anbora.labs.fantom.commenter

import co.anbora.labs.fantom.ide.commenter.FantomCommenter
import co.anbora.labs.fantom.lang.FantomLanguage
import co.anbora.labs.fantom.lang.FantomParserDefinition
import co.anbora.labs.fantom.lang.core.psi.FantomTypes
import com.intellij.lang.LanguageCommenters
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** [FantomCommenter]: line comments with `//`, block comments with `/* */`. */
class FantomCommenterTest : BasePlatformTestCase() {

    private fun doAction(action: String, before: String, after: String) {
        myFixture.configureByText("A.fan", before)
        myFixture.performEditorAction(action)
        myFixture.checkResult(after)
    }

    fun testIsRegisteredForFantom() {
        assertTrue(LanguageCommenters.INSTANCE.forLanguage(FantomLanguage) is FantomCommenter)
    }

    fun testPrefixesAndTokens() {
        val c = FantomCommenter()
        assertEquals("//", c.lineCommentPrefix)
        assertEquals("/*", c.blockCommentPrefix)
        assertEquals("*/", c.blockCommentSuffix)
        assertNull(c.commentedBlockCommentPrefix)
        assertNull(c.commentedBlockCommentSuffix)
        assertEquals(FantomParserDefinition.END_OF_LINE_COMMENT, c.lineCommentTokenType)
        assertEquals(FantomParserDefinition.C_STYLE_COMMENT, c.blockCommentTokenType)
        assertEquals(FantomTypes.FANDOC_LINE_COMMENT, c.documentationCommentTokenType)
        assertEquals("**", c.documentationCommentLinePrefix)
        assertNull(c.documentationCommentPrefix)
        assertNull(c.documentationCommentSuffix)
        assertFalse(c.isDocumentationComment(null))
    }

    fun testCommentLine() = doAction(
        IdeActions.ACTION_COMMENT_LINE,
        "class C {\n  Void f() {\n    x<caret> := 1\n  }\n}",
        "class C {\n  Void f() {\n//    x := 1\n  }<caret>\n}")

    fun testUncommentLine() = doAction(
        IdeActions.ACTION_COMMENT_LINE,
        "class C {\n  Void f() {\n//    x<caret> := 1\n  }\n}",
        "class C {\n  Void f() {\n    x := 1\n  }<caret>\n}")

    fun testCommentSelectedLines() = doAction(
        IdeActions.ACTION_COMMENT_LINE,
        "class C {\n<selection>  Int a := 1\n  Int b := 2\n</selection>}",
        "class C {\n<selection>//  Int a := 1\n//  Int b := 2\n</selection>}")

    fun testBlockCommentSelection() = doAction(
        IdeActions.ACTION_COMMENT_BLOCK,
        "class C { Void f() { x := <selection>1 + 2</selection> } }",
        "class C { Void f() { x := <selection>/*1 + 2*/</selection> } }")

    fun testBlockUncommentSelection() = doAction(
        IdeActions.ACTION_COMMENT_BLOCK,
        "class C { Void f() { x := /*1<caret> + 2*/ } }",
        "class C { Void f() { x := 1<caret> + 2 } }")
}
