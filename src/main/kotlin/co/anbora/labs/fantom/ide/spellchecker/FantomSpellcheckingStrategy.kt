package co.anbora.labs.fantom.ide.spellchecker

import co.anbora.labs.fantom.lang.FantomParserDefinition.Companion.FAN_SYS_TYPE
import co.anbora.labs.fantom.lang.FantomParserDefinition.Companion.SHABENG
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.DSL_STRING
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.STRING_LITERAL
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.URI_LITERAL
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.spellchecker.inspections.PlainTextSplitter
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.spellchecker.tokenizer.TokenConsumer
import com.intellij.spellchecker.tokenizer.Tokenizer

/**
 * Spell checking for Fantom: comments and fandoc (`**`), string literals and
 * declaration names. URIs, DSL strings and the `#!` line are skipped; in strings,
 * `$x`, `$x.y`, `${...}` and escapes are skipped too.
 */
class FantomSpellcheckingStrategy : SpellcheckingStrategy() {

    override fun getTokenizer(element: PsiElement): Tokenizer<*> =
        when (element.node?.elementType) {
            STRING_LITERAL -> FantomStringTokenizer
            URI_LITERAL, DSL_STRING, SHABENG, FAN_SYS_TYPE -> EMPTY_TOKENIZER
            else -> super.getTokenizer(element)
        }
}

/** Checks the plain-text runs of a string literal, between interpolations and escapes. */
object FantomStringTokenizer : Tokenizer<PsiElement>() {

    override fun tokenize(element: PsiElement, consumer: TokenConsumer) {
        val text = element.text
        for (range in textRuns(text)) {
            consumer.consumeToken(element, text, false, 0, range, PlainTextSplitter.getInstance())
        }
    }

    /** Ranges of [literal] (quotes included) that hold plain words. */
    fun textRuns(literal: String): List<TextRange> {
        val quote = if (literal.startsWith("\"\"\"")) 3 else if (literal.startsWith("\"")) 1 else 0
        val end = if (quote > 0 && literal.length >= 2 * quote && literal.endsWith("\"")) literal.length - quote else literal.length
        val out = ArrayList<TextRange>()
        var start = quote
        var i = quote
        fun flush(at: Int) {
            if (at > start) out += TextRange(start, at)
        }
        while (i < end) {
            val c = literal[i]
            when {
                c == '\\' -> {
                    flush(i)
                    i += if (i + 1 < end && literal[i + 1] == 'u') 6 else 2
                    start = i
                }
                c == '$' && i + 1 < end && literal[i + 1] == '{' -> {
                    flush(i)
                    var depth = 0
                    while (i < end) {
                        if (literal[i] == '{') depth++
                        if (literal[i] == '}' && --depth == 0) { i++; break }
                        i++
                    }
                    start = i
                }
                c == '$' && i + 1 < end && (literal[i + 1].isLetter() || literal[i + 1] == '_') -> {
                    flush(i)
                    i++
                    while (i < end) {
                        val d = literal[i]
                        if (d.isLetterOrDigit() || d == '_') i++
                        else if (d == '.' && i + 1 < end && (literal[i + 1].isLetter() || literal[i + 1] == '_')) i++
                        else break
                    }
                    start = i
                }
                else -> i++
            }
        }
        i = minOf(i, end)
        flush(i)
        return out
    }
}
