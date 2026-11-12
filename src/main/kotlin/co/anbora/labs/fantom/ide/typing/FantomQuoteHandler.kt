package co.anbora.labs.fantom.ide.typing

import co.anbora.labs.fantom.lang.core.psi.FantomTypes.CHAR_LITERAL
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.STRING_LITERAL
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.URI_LITERAL
import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.psi.TokenType
import com.intellij.psi.tree.TokenSet

private val LITERALS = TokenSet.create(STRING_LITERAL, CHAR_LITERAL, URI_LITERAL)

/**
 * Pairs `"`, `'` and `` ` ``.
 *
 * The Fantom lexer only produces a literal token when the closing quote exists. A lone quote
 * is a [TokenType.BAD_CHARACTER], so this handler treats a bad character that is a quote as an
 * open literal.
 */
class FantomQuoteHandler : SimpleTokenSetQuoteHandler(LITERALS) {

    override fun isOpeningQuote(iterator: HighlighterIterator, offset: Int): Boolean {
        if (iterator.tokenType == TokenType.BAD_CHARACTER) {
            return iterator.start == offset && isQuote(iterator.document.charsSequence[offset])
        }
        return super.isOpeningQuote(iterator, offset)
    }

    override fun isClosingQuote(iterator: HighlighterIterator, offset: Int): Boolean =
        LITERALS.contains(iterator.tokenType) && super.isClosingQuote(iterator, offset)

    override fun hasNonClosedLiteral(editor: Editor, iterator: HighlighterIterator, offset: Int): Boolean {
        val start = iterator.start
        try {
            val document = editor.document
            val chars = document.charsSequence
            val lineEnd = document.getLineEndOffset(document.getLineNumber(offset))
            while (!iterator.atEnd() && iterator.start < lineEnd) {
                val type = iterator.tokenType
                if (type == TokenType.BAD_CHARACTER && isQuote(chars[iterator.start])) return true
                if (LITERALS.contains(type) && isNonClosedLiteral(iterator, chars)) return true
                iterator.advance()
            }
        } finally {
            while (iterator.atEnd() || iterator.start != start) iterator.retreat()
        }
        return false
    }

    override fun isNonClosedLiteral(iterator: HighlighterIterator, chars: CharSequence): Boolean {
        val start = iterator.start
        val end = iterator.end
        if (end - start < 2) return true
        if (chars[start] != chars[end - 1]) return true
        // A literal that runs over a line end is an open quote that swallowed a later literal.
        for (i in start until end) if (chars[i] == '\n') return true
        return false
    }

    private fun isQuote(c: Char): Boolean = c == '"' || c == '\'' || c == '`'
}
