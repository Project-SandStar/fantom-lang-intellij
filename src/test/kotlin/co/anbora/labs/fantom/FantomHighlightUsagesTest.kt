package co.anbora.labs.fantom

import com.intellij.codeInsight.highlighting.HighlightUsagesHandler
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Highlight usages in file (Cmd+Shift+F7). The platform handler finds the target
 * through the Fantom references, so locals, params, fields, methods and types
 * work without a Fantom-specific handler factory.
 */
class FantomHighlightUsagesTest : BasePlatformTestCase() {

    private val code = """
        class Foo
        {
          Int count := 0
          Void inc(Int step)
          {
            total := step + count
            total = total + 1
            count = total
            inc(step)
          }
          Foo self() { Foo f := this; return f }
        }
    """.trimIndent()

    /** Puts the caret on occurrence [nth] (0-based) of [word], highlights, returns the highlighted offsets. */
    private fun highlight(word: String, nth: Int = 0): List<Int> {
        var at = -1
        repeat(nth + 1) { at = code.indexOf(word, at + 1) }
        assertTrue("no occurrence $nth of $word", at >= 0)
        myFixture.configureByText("A.fan", code)
        myFixture.editor.caretModel.moveToOffset(at)
        HighlightUsagesHandler.invoke(project, myFixture.editor, myFixture.file)
        val doc = myFixture.editor.document
        val ranges = myFixture.editor.markupModel.allHighlighters
            .map { TextRange(it.startOffset, it.endOffset) }
            .distinct()
        ranges.forEach { assertEquals(word, doc.getText(it)) }
        return ranges.map { it.startOffset }.sorted()
    }

    private fun occurrences(word: String): List<Int> =
        Regex("\\b$word\\b").findAll(code).map { it.range.first }.toList()

    fun testLocal() {
        assertEquals(occurrences("total"), highlight("total", 2))
    }

    fun testParam() {
        assertEquals(occurrences("step"), highlight("step"))
    }

    fun testField() {
        assertEquals(occurrences("count"), highlight("count", 1))
    }

    fun testMethod() {
        assertEquals(occurrences("inc"), highlight("inc", 1))
    }

    fun testType() {
        assertEquals(occurrences("Foo"), highlight("Foo", 2))
    }
}
