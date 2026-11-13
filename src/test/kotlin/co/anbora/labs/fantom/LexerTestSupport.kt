package co.anbora.labs.fantom

import com.intellij.lexer.Lexer
import com.intellij.psi.TokenType
import junit.framework.TestCase

/** Small helpers shared by the Axon, trio, fandoc and props lexer tests. */
object LexerTestSupport {

    data class Tok(val type: String, val text: String, val start: Int, val state: Int)

    fun tokens(lexer: Lexer, text: CharSequence, start: Int = 0, state: Int = 0): List<Tok> {
        lexer.start(text, start, text.length, state)
        val out = ArrayList<Tok>()
        while (lexer.tokenType != null) {
            TestCase.assertTrue("empty token at ${lexer.tokenStart}", lexer.tokenEnd > lexer.tokenStart)
            out += Tok(lexer.tokenType.toString(), text.substring(lexer.tokenStart, lexer.tokenEnd), lexer.tokenStart, lexer.state)
            lexer.advance()
        }
        return out
    }

    /** Non-whitespace tokens as `TYPE 'text'`. */
    fun visible(lexer: Lexer, text: String): List<String> =
        tokens(lexer, text).filter { it.type != TokenType.WHITE_SPACE.toString() }.map { "${it.type} '${it.text}'" }

    /**
     * The invariants every editor lexer needs: the tokens join back to the input,
     * there is no bad character, and a restart at any token with its state gives the same tokens.
     */
    fun checkInvariants(newLexer: () -> Lexer, text: String, allowBad: Boolean = false) {
        val all = tokens(newLexer(), text)
        TestCase.assertEquals("tokens must cover the input", text, all.joinToString("") { it.text })
        if (!allowBad) {
            val bad = all.filter { it.type == TokenType.BAD_CHARACTER.toString() }
            TestCase.assertTrue("bad characters: $bad", bad.isEmpty())
        }
        for (i in all.indices step maxOf(1, all.size / 40)) {
            val t = all[i]
            val again = tokens(newLexer(), text, t.start, t.state)
            TestCase.assertEquals("restart at ${t.start}", all.drop(i).map { it.type to it.text }, again.map { it.type to it.text })
        }
    }
}
