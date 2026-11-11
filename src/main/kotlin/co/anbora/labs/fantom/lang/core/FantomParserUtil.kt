package co.anbora.labs.fantom.lang.core

import co.anbora.labs.fantom.lang.FantomParserDefinition.Companion.END_OF_LINE_COMMENT
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.*
import com.intellij.lang.PsiBuilder
import com.intellij.lang.parser.GeneratedParserUtilBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * External rules used from `Fantom.bnf` as `<<name>>`.
 *
 * The real compiler (`Parser.fan`) is newline sensitive: a statement ends at a
 * newline, `;`, `}` or EOF (`endOfStmt`), and `(`, `[`, `++`, `--`, `?` only
 * continue an expression when they sit on the same line (or with no space)
 * after the previous token. Grammar-Kit skips whitespace and comments, so the
 * parser cannot see them; these helpers look at the raw token stream instead.
 */
object FantomParserUtil: GeneratedParserUtilBase() {

    // ---- contextual keywords ------------------------------------------------

    @JvmStatic
    fun getKeyword(b: PsiBuilder, level: Int): Boolean =
        contextualKeyword(b, "get", GET)

    @JvmStatic
    fun setKeyword(b: PsiBuilder, level: Int): Boolean =
        contextualKeyword(b, "set", SET)

    /** `enum` is an IDENTIFIER in the lexer; remap it to ENUM when the grammar asks for it. */
    @JvmStatic
    fun enumKeyword(b: PsiBuilder, level: Int): Boolean =
        contextualKeyword(b, "enum", ENUM)

    /** `facet` is an IDENTIFIER in the lexer; remap it to FACET when the grammar asks for it. */
    @JvmStatic
    fun facetKeyword(b: PsiBuilder, level: Int): Boolean =
        contextualKeyword(b, "facet", FACET)

    /**
     * `Parser.tryItBlock`: a `{` that is followed by a protection keyword, `static`,
     * `readonly`, `get` or `set` is a field accessor block, not an it-block.
     */
    @JvmStatic
    fun notAccessorBlock(b: PsiBuilder, level: Int): Boolean {
        if (b.tokenType != LBRACE) return true
        val next = b.lookAhead(1)
        if (next == PUBLIC || next == PROTECTED || next == PRIVATE || next == INTERNAL ||
            next == STATIC || next == READONLY || next == GET || next == SET) return false
        if (next == IDENTIFIER) {
            val m = b.mark()
            b.advanceLexer()
            val text = b.tokenText
            m.rollbackTo()
            if (text == "get" || text == "set") return false
        }
        return true
    }

    // ---- newline and whitespace sensitivity ---------------------------------

    /**
     * End of statement, like `Parser.endOfStmt`: `;` (consumed), or `}` / EOF
     * (not consumed), or a newline between the previous token and the current
     * one (nothing consumed).
     */
    @JvmStatic
    fun eos(b: PsiBuilder, level: Int): Boolean {
        val t = b.tokenType
        if (t == SEMICOLON) {
            b.advanceLexer()
            return true
        }
        if (t == null || t == RBRACE) return true
        return newlineBefore(b)
    }

    /** True when there is no newline between the previous token and the current one. */
    @JvmStatic
    fun sameLine(b: PsiBuilder, level: Int): Boolean = !newlineBefore(b)

    /** True when there is no whitespace or comment at all between the previous token and the current one. */
    @JvmStatic
    fun noSpaceBefore(b: PsiBuilder, level: Int): Boolean {
        val prev = b.rawLookup(-1) ?: return true
        return !isSkipped(prev)
    }

    /**
     * Walks back over the whitespace and comment tokens that Grammar-Kit skipped
     * before the current token and reports whether any of them holds a `\n`.
     * A line comment always ends at a newline, so it counts as one too.
     */
    private fun newlineBefore(b: PsiBuilder): Boolean {
        var i = -1
        while (true) {
            // Past the start of the file: the first token of a file starts a new line.
            val t = b.rawLookup(i) ?: return true
            if (!isSkipped(t)) return false
            if (t == END_OF_LINE_COMMENT || t == FANDOC_LINE_COMMENT) return true
            val start = b.rawTokenTypeStart(i)
            val end = b.rawTokenTypeStart(i + 1)
            val text = b.originalText
            for (k in start until end) {
                if (text[k] == '\n' || text[k] == '\r') return true
            }
            i--
        }
    }

    private fun isSkipped(t: IElementType): Boolean =
        t == TokenType.WHITE_SPACE || t in FANTOM_COMMENTS

    private fun contextualKeyword(
        b: PsiBuilder,
        keyword: String,
        elementType: IElementType,
        nextElementPredicate: (IElementType?) -> Boolean = { it !in tokenSetOf() }
    ): Boolean {
        if (b.tokenType == elementType ||
            b.tokenType == IDENTIFIER && b.tokenText == keyword && nextElementPredicate(b.lookAhead(1))
        ) {
            b.remapCurrentToken(elementType)
            b.advanceLexer()
            return true
        }
        return false
    }
}
