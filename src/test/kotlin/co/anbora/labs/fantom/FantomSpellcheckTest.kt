package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.spellchecker.FantomSpellcheckingStrategy
import co.anbora.labs.fantom.ide.spellchecker.FantomStringTokenizer
import co.anbora.labs.fantom.lang.FantomLanguage
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.SyntaxTraverser
import com.intellij.spellchecker.inspections.SpellCheckingInspection
import com.intellij.spellchecker.inspections.Splitter
import com.intellij.spellchecker.tokenizer.LanguageSpellchecking
import com.intellij.spellchecker.tokenizer.TokenConsumer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.EnumSet

/**
 * Spell checking: comments, fandoc, string text and declaration names reach the
 * spellchecker; URIs and string interpolation do not. The inspection itself lives
 * in another plugin (Grazie), so the test collects the words the strategy hands over.
 */
class FantomSpellcheckTest : BasePlatformTestCase() {

    fun testStrategyRegistered() {
        assertInstanceOf(LanguageSpellchecking.INSTANCE.forLanguage(FantomLanguage), FantomSpellcheckingStrategy::class.java)
    }

    fun testCheckedAndSkippedWords() {
        val file = myFixture.configureByText(
            "A.fan",
            """
            // a wrongg comment
            ** fandoc mispeled
            class Foo
            {
              Str s := "hellloo ${'$'}naamez and ${'$'}{frobnicatez} ok"
              Uri u := `http://exampel.com/zzqxw`
              Void doQwertzz() {}
            }
            """.trimIndent()
        )
        val strategy = LanguageSpellchecking.INSTANCE.forLanguage(FantomLanguage)
        val words = HashSet<String>()
        val consumer = object : TokenConsumer() {
            override fun consumeToken(
                element: PsiElement, text: String, useRename: Boolean, offset: Int, rangeToCheck: TextRange, splitter: Splitter,
            ) {
                splitter.split(text, rangeToCheck) { r -> words += r.substring(text).lowercase() }
            }
        }
        val scopes = EnumSet.allOf(SpellCheckingInspection.SpellCheckingScope::class.java)
        for (e in SyntaxTraverser.psiTraverser(file)) {
            SpellCheckingInspection.tokenize(strategy, e, consumer, scopes)
        }
        for (w in listOf("wrongg", "mispeled", "hellloo", "qwertzz", "fandoc")) assertTrue("$w not checked: $words", w in words)
        for (w in listOf("naamez", "frobnicatez", "exampel", "zzqxw")) assertFalse("$w checked: $words", w in words)
    }

    fun testStringRunsSkipInterpolationAndEscapes() {
        val literal = "\"ab ${'$'}x.y cd ${'$'}{a{b}} ef\\n gh\""
        val runs = FantomStringTokenizer.textRuns(literal).map { it.substring(literal) }
        assertEquals(listOf("ab ", " cd ", " ef", " gh"), runs)
    }
}
