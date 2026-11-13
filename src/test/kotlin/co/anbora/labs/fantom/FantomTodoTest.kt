package co.anbora.labs.fantom

import com.intellij.psi.search.PsiTodoSearchHelper
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** TODO and FIXME in `//`, `/* */` and `**` comments reach the TODO index; strings do not. */
class FantomTodoTest : BasePlatformTestCase() {

    fun testTodoItemsInAllCommentKinds() {
        val file = myFixture.configureByText(
            "A.fan",
            """
            // TODO line comment
            /* FIXME block comment */
            ** TODO fandoc comment
            class Foo
            {
              Str s := "TODO not a comment"
            }
            """.trimIndent()
        )
        val doc = myFixture.editor.document
        val items = PsiTodoSearchHelper.getInstance(project).findTodoItems(file)
            .map { doc.getText(it.textRange).trim() }
            .sorted()
        assertEquals(listOf("FIXME block comment", "TODO fandoc comment", "TODO line comment"), items)
        assertEquals(3, PsiTodoSearchHelper.getInstance(project).getTodoItemsCount(file))
    }
}
