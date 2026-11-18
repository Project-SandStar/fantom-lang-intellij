package co.anbora.labs.fantom.lang.axon.parser

import co.anbora.labs.fantom.lang.axon.AxonTokenTypes
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes.CATCH
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes.DEFCOMP
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes.DO
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes.ELSE
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes.END
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes.FALSE
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes.IDENTIFIER
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes.IF
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes.LBRACE
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes.LBRACKET
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes.LINE_COMMENT
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes.LPAREN
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes.MINUS
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes.NOT
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes.NULL
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes.NUMBER
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes.REF
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes.RETURN
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes.SEMICOLON
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes.STRING
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes.SYMBOL
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes.THROW
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes.TRUE
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes.TRY
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes.TYPENAME
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes.URI
import com.intellij.lang.PsiBuilder
import com.intellij.lang.parser.GeneratedParserUtilBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

/**
 * External rules used from `Axon.bnf` as `<<name>>`.
 *
 * The Haxall parser is newline sensitive: an expression in a block ends at a newline or `;`,
 * and `(`, `[` and a trailing lambda only continue an expression on the same line.
 * Grammar-Kit skips whitespace and comments, so these helpers read the raw token stream.
 */
object AxonParserUtil : GeneratedParserUtilBase() {

    /** Tokens that can start an expression. */
    private val STARTERS = TokenSet.create(
        IDENTIFIER, TYPENAME, NUMBER, STRING, URI, REF, SYMBOL,
        DEFCOMP, DO, IF, NOT, RETURN, THROW, TRY, TRUE, FALSE, NULL,
        LPAREN, LBRACKET, LBRACE, MINUS,
    )

    /** True when the current token is on the same line as the token before it. */
    @JvmStatic
    fun sameLine(b: PsiBuilder, level: Int): Boolean = !newlineBefore(b)

    /** After `return` and `throw`: the expression must start on the same line. */
    @JvmStatic
    fun valueOnSameLine(b: PsiBuilder, level: Int): Boolean {
        if (!b.eof() && !newlineBefore(b)) return true
        addVariant(b, "expression on the same line")
        return false
    }

    /** After `a.name`: reports a `(` that starts the next line. Takes no token. */
    @JvmStatic
    fun parenOnNewLine(b: PsiBuilder, level: Int): Boolean {
        if (b.tokenType != LPAREN || !newlineBefore(b)) return false
        b.error("The '(' of a call cannot be on a new line")
        return true
    }

    /** `Parser.eos`: a newline, the end of the text, or the keyword that closes the block. */
    @JvmStatic
    fun atStmtEnd(b: PsiBuilder, level: Int): Boolean {
        if (atBoundary(b)) return true
        addVariant(b, "newline")
        return false
    }

    /** Takes one token that starts no expression and marks it as an error. Fails only at the end of the text. */
    @JvmStatic
    fun skipBad(b: PsiBuilder, level: Int): Boolean {
        if (b.eof()) return false
        val mark = b.mark()
        val text = b.tokenText
        b.advanceLexer()
        mark.error("Unexpected '$text'")
        return true
    }

    /**
     * Runs after each expression of a block. When the expression did not end at a line end
     * (its error is reported already), skips to the next place where an expression can start:
     * a new line or the `end` of this block. A nested `do` block is parsed, not skipped,
     * so its `end` does not close the outer block.
     */
    @JvmStatic
    fun syncStmt(b: PsiBuilder, level: Int): Boolean {
        if (atBoundary(b) || prevIs(b, SEMICOLON)) return true
        while (!b.eof()) {
            val t = b.tokenType
            if (t == END) break
            if (newlineBefore(b) && (t in STARTERS || t == ELSE || t == CATCH)) break
            if (t in STARTERS && prevIs(b, SEMICOLON)) break
            if (t == DO && AxonParser.doExpr(b, level + 1)) continue
            b.advanceLexer()
        }
        return true
    }

    private fun atBoundary(b: PsiBuilder): Boolean {
        if (b.eof()) return true
        val t = b.tokenType
        return t == END || t == ELSE || t == CATCH || newlineBefore(b)
    }

    private fun prevIs(b: PsiBuilder, type: IElementType): Boolean {
        var i = -1
        while (true) {
            val t = b.rawLookup(i) ?: return false
            if (!isSkipped(t)) return t == type
            i--
        }
    }

    /**
     * Walks back over the whitespace and comments before the current token and reports
     * whether any of them holds a line break. A line comment always ends at one.
     */
    private fun newlineBefore(b: PsiBuilder): Boolean {
        var i = -1
        while (true) {
            // Past the start of the text: the first token starts a new line.
            val t = b.rawLookup(i) ?: return true
            if (!isSkipped(t)) return false
            if (t == LINE_COMMENT) return true
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
        t == TokenType.WHITE_SPACE || t in AxonTokenTypes.COMMENTS
}
