package co.anbora.labs.fantom.lang.trio

import co.anbora.labs.fantom.ide.color.FantomColors
import co.anbora.labs.fantom.ide.icons.FantomIcons
import co.anbora.labs.fantom.lang.trio.parser.TrioParser
import co.anbora.labs.fantom.lang.trio.psi.TrioTypes
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.FlexAdapter
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import javax.swing.Icon

/** Trio, the Haystack text format for records (`name: value` lines, `---` between records). */
object TrioLanguage : Language("trio_lang") {
    private fun readResolve(): Any = TrioLanguage
    override fun getDisplayName(): String = "Trio"
}

/** A node of the Trio tree (record, tag, value). The grammar is `src/main/grammar/Trio.bnf`. */
class TrioElementType(debugName: String) : IElementType(debugName, TrioLanguage)

/** A token of the Trio lexer. Its name is the text that a syntax error shows ("string expected"). */
class TrioTokenType(debugName: String) : IElementType(debugName, TrioLanguage)

/** The Trio lexer, generated from `src/main/grammar/Trio.flex`. */
class TrioLexer : FlexAdapter(_TrioLexer())

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
    override fun createParser(project: Project?): PsiParser = TrioParser()
    override fun getFileNodeType(): IFileElementType = FILE
    override fun getCommentTokens(): TokenSet = COMMENTS
    override fun getStringLiteralElements(): TokenSet = STRINGS
    override fun createElement(node: ASTNode): PsiElement = TrioTypes.Factory.createElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = TrioFile(viewProvider)

    companion object {
        @JvmField val FILE = IFileElementType(TrioLanguage)
        @JvmField val COMMENTS = TokenSet.create(TrioTypes.COMMENT)
        @JvmField val STRINGS = TokenSet.create(TrioTypes.STRING)
    }
}

class TrioSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = TrioLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = pack(
        when (tokenType) {
            TrioTypes.COMMENT -> COMMENT
            TrioTypes.SEPARATOR -> SEPARATOR
            TrioTypes.TAG_NAME, TrioTypes.ID -> TAG_NAME
            TrioTypes.COLON -> COLON
            TrioTypes.COMMA -> COMMA
            TrioTypes.UNQUOTED, TrioTypes.BLOCK_TEXT, TrioTypes.STRING -> VALUE
            TrioTypes.URI -> URI
            TrioTypes.REF, TrioTypes.SYMBOL -> REF
            TrioTypes.NUMBER, TrioTypes.DATE, TrioTypes.TIME, TrioTypes.DATE_TIME -> NUMBER
            TrioTypes.BOOL, TrioTypes.NA, TrioTypes.NULL, TrioTypes.MARKER, TrioTypes.REMOVE,
            TrioTypes.ZINC_KW, TrioTypes.TRIO_KW, TrioTypes.COORD_KW, TrioTypes.TYPE_NAME -> KEYWORD
            TrioTypes.LBRACKET, TrioTypes.RBRACKET -> BRACKETS
            TrioTypes.LBRACE, TrioTypes.RBRACE -> BRACES
            TrioTypes.LPAREN, TrioTypes.RPAREN -> PARENTHESES
            TokenType.BAD_CHARACTER -> FantomColors.BAD_CHAR.textAttributesKey
            // SRC_VALUE has no colour of its own: the injected Axon colours it.
            else -> null
        }
    )

    companion object {
        private fun key(name: String, fallback: FantomColors): TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("co.anbora.labs.trio.$name", fallback.textAttributesKey)

        val COMMENT = key("COMMENT", FantomColors.COMMENTS)
        val SEPARATOR = key("SEPARATOR", FantomColors.KEY_WORD)
        val TAG_NAME = key("TAG_NAME", FantomColors.INSTANCE_FIELD)
        val COLON = key("COLON", FantomColors.OPERATOR)
        val VALUE = key("VALUE", FantomColors.STRINGS)
        val COMMA = key("COMMA", FantomColors.COMMA)
        val URI = key("URI", FantomColors.URI)
        val REF = key("REF", FantomColors.CONSTANT)
        val NUMBER = key("NUMBER", FantomColors.NUMBERS)
        val KEYWORD = key("KEYWORD", FantomColors.KEY_WORD)
        val BRACKETS = key("BRACKETS", FantomColors.BRACKETS)
        val BRACES = key("BRACES", FantomColors.BRACES)
        val PARENTHESES = key("PARENTHESES", FantomColors.PARENTHESES)
    }
}

class TrioSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = TrioSyntaxHighlighter()
}
