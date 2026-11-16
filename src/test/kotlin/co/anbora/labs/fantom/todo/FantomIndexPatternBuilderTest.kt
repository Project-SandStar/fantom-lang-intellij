package co.anbora.labs.fantom.todo

import co.anbora.labs.fantom.ide.todo.FantomIndexPatternBuilder
import co.anbora.labs.fantom.lang.FantomParserDefinition
import co.anbora.labs.fantom.lang.core.psi.FantomTypes
import co.anbora.labs.fantom.lang.lexer.FantomLexer
import com.intellij.psi.impl.search.IndexPatternBuilder
import com.intellij.psi.search.PsiTodoSearchHelper
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** [FantomIndexPatternBuilder]: TODO items in Fantom comments. Cases not in the root FantomTodoTest. */
class FantomIndexPatternBuilderTest : BasePlatformTestCase() {

    private val builder = FantomIndexPatternBuilder()

    private fun todos(text: String): List<String> {
        val file = myFixture.configureByText("A.fan", text)
        val doc = myFixture.editor.document
        return PsiTodoSearchHelper.getInstance(project).findTodoItems(file).map { doc.getText(it.textRange) }.sorted()
    }

    fun testIsRegistered() {
        assertTrue(IndexPatternBuilder.EP_NAME.extensionList.any { it is FantomIndexPatternBuilder })
    }

    fun testLexerAndTokensOnlyForFantomFiles() {
        val fan = myFixture.configureByText("A.fan", "class A {}")
        assertTrue(builder.getIndexingLexer(fan) is FantomLexer)
        assertNotNull(builder.getCommentTokenSet(fan))
        val txt = myFixture.configureByText("a.txt", "TODO x")
        assertNull(builder.getIndexingLexer(txt))
        assertNull(builder.getCommentTokenSet(txt))
    }

    fun testCommentDeltas() {
        assertEquals(2, builder.getCommentStartDelta(FantomParserDefinition.END_OF_LINE_COMMENT))
        assertEquals(2, builder.getCommentStartDelta(FantomParserDefinition.C_STYLE_COMMENT))
        assertEquals(2, builder.getCommentStartDelta(FantomTypes.FANDOC_LINE_COMMENT))
        assertEquals(0, builder.getCommentStartDelta(FantomTypes.IDENTIFIER))
        assertEquals(2, builder.getCommentEndDelta(FantomParserDefinition.C_STYLE_COMMENT))
        assertEquals(0, builder.getCommentEndDelta(FantomParserDefinition.END_OF_LINE_COMMENT))
        assertEquals(0, builder.getCommentEndDelta(null))
    }

    fun testLineCommentTodo() {
        assertEquals(listOf("TODO x"), todos("// TODO x\nclass A {}"))
    }

    fun testLowerCaseAndTrailingComment() {
        assertEquals(
            listOf("FIXME later", "todo lower"),
            todos("class A {\n  Int x := 1 // FIXME later\n  // todo lower\n}"),
        )
    }

    fun testBlockCommentEndIsNotPartOfItem() {
        assertEquals(listOf("TODO end "), todos("/* TODO end */ class A {}"))
    }

    fun testNoTodoInIdentifiersOrUris() {
        assertEmpty(todos("class TODO { Uri u := `TODO` }"))
    }
}
