package co.anbora.labs.fantom.lang.trio

import co.anbora.labs.fantom.ide.color.FantomColors
import co.anbora.labs.fantom.ide.icons.FantomIcons
import co.anbora.labs.fantom.lang.text.LineLexerBase
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.LiteralTextEscaper
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.TokenType
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import javax.swing.Icon

/** Trio, the Haystack text format for records (`name: value` lines, `---` between records). */
object TrioLanguage : Language("trio_lang") {
    private fun readResolve(): Any = TrioLanguage
    override fun getDisplayName(): String = "Trio"
}

class TrioElementType(debugName: String) : IElementType(debugName, TrioLanguage) {
    override fun toString(): String = "Trio:" + super.toString()
}

object TrioTypes {
    @JvmField val COMMENT = TrioElementType("COMMENT")
    @JvmField val SEPARATOR = TrioElementType("SEPARATOR")
    @JvmField val TAG_NAME = TrioElementType("TAG_NAME")
    @JvmField val COLON = TrioElementType("COLON")
    @JvmField val VALUE = TrioElementType("VALUE")
    /** The value of a `src` tag: Axon code, one token over all its lines. */
    @JvmField val SRC_VALUE = TrioElementType("SRC_VALUE")
    /** PSI node around [SRC_VALUE]; the Axon injection host. */
    @JvmField val SRC = TrioElementType("SRC")

    @JvmField val COMMENTS = TokenSet.create(COMMENT)
}

/**
 * Line lexer for trio, after `haystack::TrioReader`:
 * - a line that starts with `-` separates records, a line that starts with `//` is a comment;
 * - `name: value` or a bare `name` (marker);
 * - a value goes on over the next indented lines, and may start on the line after `name:`.
 *
 * States: 0 line start, 1 after a tag name, 2 after a colon, 3 after `src:`, 4 after the `src` name.
 * A value is one token, so every token start is a safe restart point.
 */
class TrioLexer : LineLexerBase() {

    override fun lex(state: Int): Int {
        val c = buf[pos]
        if (c == '\n' || c == '\r') {
            if ((state == 2 || state == 3) && indentedLineFollows(pos)) {
                // `name:` then the value on the next indented lines: keep the value state.
                var end = skipNewline(pos)
                while (end < limit && (buf[end] == ' ' || buf[end] == '\t')) end++
                return token(end, TokenType.WHITE_SPACE, state)
            }
            return token(skipNewline(pos), TokenType.WHITE_SPACE, 0)
        }
        when (state) {
            0 -> {
                if (c == ' ' || c == '\t') return token(skipSpaces(pos), TokenType.WHITE_SPACE, 0)
                if (startsWith(pos, "//")) return token(lineEnd(pos), TrioTypes.COMMENT, 0)
                if (c == '-') return token(lineEnd(pos), TrioTypes.SEPARATOR, 0)
                var end = pos
                while (end < limit && buf[end] != ':' && !buf[end].isWhitespace()) end++
                if (end == pos) return token(pos + 1, TokenType.BAD_CHARACTER, 0)
                val isSrc = end - pos == 3 && startsWith(pos, "src")
                return token(end, TrioTypes.TAG_NAME, if (isSrc) 4 else 1)
            }
            1, 4 -> {
                if (c == ' ' || c == '\t') return token(skipSpaces(pos), TokenType.WHITE_SPACE, state)
                if (c == ':') return token(pos + 1, TrioTypes.COLON, if (state == 4) 3 else 2)
                return token(valueEnd(pos), TrioTypes.VALUE, 0)
            }
            else -> {
                if (c == ' ' || c == '\t') return token(skipSpaces(pos), TokenType.WHITE_SPACE, state)
                return token(valueEnd(pos), if (state == 3) TrioTypes.SRC_VALUE else TrioTypes.VALUE, 0)
            }
        }
    }

    /** End of the value at [from]: its line, plus indented lines (and blank lines between them). */
    private fun valueEnd(from: Int): Int {
        var end = lineEnd(from)
        while (end < limit && indentedLineFollows(end)) {
            end = lineEnd(skipNewline(end))
        }
        return end
    }

    /** True when, after the newline at [nl] and any blank lines, an indented non-blank line follows. */
    private fun indentedLineFollows(nl: Int): Boolean {
        var p = skipNewline(nl)
        while (p < limit) {
            val lineStart = p
            p = skipSpaces(p)
            if (p >= limit) return false
            if (buf[p] == '\n' || buf[p] == '\r') {
                p = skipNewline(p); continue
            }
            return p > lineStart
        }
        return false
    }
}

class TrioFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, TrioLanguage) {
    override fun getFileType(): FileType = TrioFileType
    override fun toString(): String = "Trio File"
}

object TrioFileType : LanguageFileType(TrioLanguage) {
    override fun getName(): String = "Trio"
    override fun getDescription(): String = "Haystack trio records"
    override fun getDefaultExtension(): String = "trio"
    override fun getIcon(): Icon = FantomIcons.FILE
}

class TrioParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = TrioLexer()
    override fun createParser(project: Project?): PsiParser = TrioParser
    override fun getFileNodeType(): IFileElementType = FILE
    override fun getCommentTokens(): TokenSet = TrioTypes.COMMENTS
    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY
    override fun createElement(node: ASTNode): PsiElement =
        if (node.elementType == TrioTypes.SRC) TrioSrcValue(node) else ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = TrioFile(viewProvider)

    companion object {
        @JvmField val FILE = IFileElementType(TrioLanguage)
    }
}

/** Flat tree, except each `src` value gets a [TrioTypes.SRC] node around it. */
object TrioParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val mark = builder.mark()
        while (!builder.eof()) {
            if (builder.tokenType == TrioTypes.SRC_VALUE) {
                val src = builder.mark()
                builder.advanceLexer()
                src.done(TrioTypes.SRC)
            } else {
                builder.advanceLexer()
            }
        }
        mark.done(root)
        return builder.treeBuilt
    }
}

/** The value of a `src:` tag. Axon is injected here by `AxonInjector`. */
class TrioSrcValue(node: ASTNode) : ASTWrapperPsiElement(node), PsiLanguageInjectionHost {
    override fun isValidHost(): Boolean = true

    override fun updateText(text: String): PsiLanguageInjectionHost {
        (node.firstChildNode as? LeafElement)?.replaceWithText(text)
        return this
    }

    override fun createLiteralTextEscaper(): LiteralTextEscaper<out PsiLanguageInjectionHost> =
        object : LiteralTextEscaper<TrioSrcValue>(this) {
            override fun decode(rangeInsideHost: TextRange, outChars: StringBuilder): Boolean {
                outChars.append(rangeInsideHost.subSequence(myHost.text))
                return true
            }

            override fun getOffsetInHost(offsetInDecoded: Int, rangeInsideHost: TextRange): Int {
                val offset = rangeInsideHost.startOffset + offsetInDecoded
                return if (offset > rangeInsideHost.endOffset) -1 else offset
            }

            override fun isOneLine(): Boolean = false
        }
}

class TrioSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = TrioLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = pack(
        when (tokenType) {
            TrioTypes.COMMENT -> COMMENT
            TrioTypes.SEPARATOR -> SEPARATOR
            TrioTypes.TAG_NAME -> TAG_NAME
            TrioTypes.COLON -> COLON
            TrioTypes.VALUE -> VALUE
            TokenType.BAD_CHARACTER -> FantomColors.BAD_CHAR.textAttributesKey
            else -> null
        }
    )

    companion object {
        val COMMENT = TextAttributesKey.createTextAttributesKey("co.anbora.labs.trio.COMMENT", FantomColors.COMMENTS.textAttributesKey)
        val SEPARATOR = TextAttributesKey.createTextAttributesKey("co.anbora.labs.trio.SEPARATOR", FantomColors.KEY_WORD.textAttributesKey)
        val TAG_NAME = TextAttributesKey.createTextAttributesKey("co.anbora.labs.trio.TAG_NAME", FantomColors.INSTANCE_FIELD.textAttributesKey)
        val COLON = TextAttributesKey.createTextAttributesKey("co.anbora.labs.trio.COLON", FantomColors.OPERATOR.textAttributesKey)
        val VALUE = TextAttributesKey.createTextAttributesKey("co.anbora.labs.trio.VALUE", FantomColors.STRINGS.textAttributesKey)
    }
}

class TrioSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = TrioSyntaxHighlighter()
}
