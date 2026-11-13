package co.anbora.labs.fantom.lang.core.psi.impl

import co.anbora.labs.fantom.lang.core.psi.FantomTypes
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.LiteralTextEscaper
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.impl.source.tree.LeafElement

/**
 * The `str` PSI node, made a [PsiLanguageInjectionHost] so other languages
 * (Axon) can be injected into Fantom string literals.
 *
 * `FantomParserDefinition.createElement` builds this class for `FantomTypes.STR`
 * instead of the generated [FantomStrImpl]. A `mixin("str")` in `Fantom.bnf`
 * would do the same, but the grammar file is kept unchanged.
 */
class FantomStrInjectionHost(node: ASTNode) : FantomStrImpl(node), PsiLanguageInjectionHost {

    private val literal: ASTNode? get() = node.firstChildNode

    /** Only `"..."` and `"""..."""` hold text; `'c'` char literals do not. */
    override fun isValidHost(): Boolean =
        literal?.elementType == FantomTypes.STRING_LITERAL && contentRange() != null

    override fun updateText(text: String): PsiLanguageInjectionHost {
        (literal as? LeafElement)?.replaceWithText(text)
        return this
    }

    override fun createLiteralTextEscaper(): LiteralTextEscaper<out PsiLanguageInjectionHost> =
        FantomStringEscaper(this)

    /** The text between the quotes, relative to this element; null when the literal is not closed. */
    fun contentRange(): TextRange? {
        val text = text
        val quote = if (text.startsWith("\"\"\"")) 3 else 1
        if (text.length < 2 * quote || !text.endsWith("\"".repeat(quote))) return null
        if (!text.startsWith("\"")) return null
        return TextRange(quote, text.length - quote)
    }

    /** Same text as the generated class, so PSI dumps do not change. */
    override fun toString(): String = "FantomStrImpl(" + node.elementType + ")"
}

/**
 * Decodes Fantom escapes (`\n`, `\t`, `\"`, `\\`, `\$`, `` \` ``, `\uXXXX` ...) so the
 * injected language sees the string value. `$name` interpolation is kept as plain text.
 */
class FantomStringEscaper(host: FantomStrInjectionHost) : LiteralTextEscaper<FantomStrInjectionHost>(host) {

    private var offsets: IntArray = IntArray(0)

    override fun decode(rangeInsideHost: TextRange, outChars: StringBuilder): Boolean {
        val text = rangeInsideHost.substring(myHost.text)
        val map = IntArray(text.length + 1)
        var i = 0
        var ok = true
        val start = outChars.length
        while (i < text.length) {
            map[outChars.length - start] = i
            val c = text[i]
            if (c != '\\' || i + 1 >= text.length) {
                outChars.append(c); i++; continue
            }
            val n = text[i + 1]
            val decoded = when (n) {
                'b' -> '\b'; 'f' -> '\u000C'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                '"', '\'', '`', '$', '\\' -> n
                else -> null
            }
            if (decoded != null) {
                outChars.append(decoded); i += 2; continue
            }
            if (n == 'u' && i + 6 <= text.length) {
                val code = text.substring(i + 2, i + 6).toIntOrNull(16)
                if (code != null) {
                    outChars.append(code.toChar()); i += 6; continue
                }
            }
            ok = false
            outChars.append(c); i++
        }
        map[outChars.length - start] = text.length
        offsets = map.copyOf(outChars.length - start + 1)
        return ok
    }

    override fun getOffsetInHost(offsetInDecoded: Int, rangeInsideHost: TextRange): Int {
        if (offsetInDecoded < 0 || offsetInDecoded >= offsets.size) return -1
        return rangeInsideHost.startOffset + offsets[offsetInDecoded]
    }

    override fun getRelevantTextRange(): TextRange = myHost.contentRange() ?: TextRange.EMPTY_RANGE

    override fun isOneLine(): Boolean = !myHost.text.startsWith("\"\"\"")
}
