package co.anbora.labs.fantom.lang.text

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

/**
 * Base for small hand-written lexers (trio, fandoc, props). A subclass reads
 * `buf[pos]` in [lex] and returns `token(end, type, nextState)`.
 *
 * The state after each token is stored, so the editor can restart the lexer
 * at any token start with that token's state. [lex] must always move forward.
 */
abstract class LineLexerBase : LexerBase() {

    protected var buf: CharSequence = ""
    protected var pos = 0
    protected var limit = 0

    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null
    private var stateBefore = 0
    private var stateAfter = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        buf = buffer
        limit = endOffset
        tokenEnd = startOffset
        stateAfter = initialState
        advance()
    }

    override fun advance() {
        tokenStart = tokenEnd
        stateBefore = stateAfter
        if (tokenStart >= limit) {
            tokenType = null
            return
        }
        pos = tokenStart
        val next = lex(stateBefore)
        stateAfter = next
    }

    /** Lexes one token at [pos] and returns the state after it, through [token]. */
    protected abstract fun lex(state: Int): Int

    protected fun token(end: Int, type: IElementType, nextState: Int): Int {
        tokenEnd = if (end <= tokenStart) tokenStart + 1 else minOf(end, limit)
        tokenType = type
        return nextState
    }

    override fun getState(): Int = stateBefore
    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buf
    override fun getBufferEnd(): Int = limit

    protected fun lineEnd(from: Int): Int {
        var p = from
        while (p < limit && buf[p] != '\n' && buf[p] != '\r') p++
        return p
    }

    protected fun skipNewline(at: Int): Int = when {
        at < limit && buf[at] == '\r' && at + 1 < limit && buf[at + 1] == '\n' -> at + 2
        at < limit && (buf[at] == '\r' || buf[at] == '\n') -> at + 1
        else -> at
    }

    protected fun skipSpaces(from: Int): Int {
        var p = from
        while (p < limit && (buf[p] == ' ' || buf[p] == '\t')) p++
        return p
    }

    protected fun startsWith(at: Int, s: String): Boolean {
        if (at + s.length > limit) return false
        for (i in s.indices) if (buf[at + i] != s[i]) return false
        return true
    }
}
