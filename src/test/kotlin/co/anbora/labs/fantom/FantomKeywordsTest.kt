package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.completion.FantomKeywords
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** [FantomKeywords]: which keyword group completion offers at each position. No install needed. */
class FantomKeywordsTest : BasePlatformTestCase() {

    private fun complete(text: String): List<String> {
        myFixture.configureByText("A.fan", text)
        myFixture.complete(CompletionType.BASIC)
        return myFixture.lookupElementStrings ?: emptyList()
    }

    private val allKeywords = (FantomKeywords.FILE + FantomKeywords.TYPE_BODY + FantomKeywords.STATEMENT + FantomKeywords.VALUES).toSet()

    fun testStatementStartInMethodBodyOffersStatementAndValueKeywords() {
        val items = complete("class C { Void f() { x := 1\n<caret> } }")
        val missing = (FantomKeywords.STATEMENT + FantomKeywords.VALUES).filter { it !in items }
        assertTrue("missing keywords: $missing", missing.isEmpty())
        // Header keywords belong to other positions.
        assertFalse(items.toString(), "class" in items)
        assertFalse(items.toString(), "using" in items)
    }

    fun testExpressionPositionOffersValuesButNoStatements() {
        val items = complete("class C { Void f() { x := <caret> } }")
        assertTrue(items.toString(), items.containsAll(FantomKeywords.VALUES))
        assertFalse(items.toString(), "while" in items)
        assertFalse(items.toString(), "return" in items)
    }

    fun testNoKeywordsAfterDot() {
        val items = complete("class P { Int count := 0 }\nclass C { Void f() { p := P(); p.<caret> } }")
        assertTrue(items.toString(), "count" in items)
        assertTrue("keywords after `.`: ${items.filter { it in allKeywords }}", items.none { it in allKeywords })
    }

    fun testTypeBodyOffersSlotFlags() {
        val items = complete("class C {\n  <caret>\n}")
        val missing = FantomKeywords.TYPE_BODY.filter { it !in items }
        assertTrue("missing keywords: $missing", missing.isEmpty())
        assertFalse(items.toString(), "while" in items)
    }

    fun testFileTopOffersHeaderKeywords() {
        val items = complete("<caret>")
        val missing = FantomKeywords.FILE.filter { it !in items }
        assertTrue("missing keywords: $missing", missing.isEmpty())
        assertFalse(items.toString(), "return" in items)
    }
}
