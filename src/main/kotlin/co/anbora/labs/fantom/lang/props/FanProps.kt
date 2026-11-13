package co.anbora.labs.fantom.lang.props

import co.anbora.labs.fantom.ide.color.FantomColors
import co.anbora.labs.fantom.ide.icons.FantomIcons
import co.anbora.labs.fantom.lang.axon.FlatPsiParser
import co.anbora.labs.fantom.lang.text.LineLexerBase
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.Commenter
import com.intellij.lang.Language
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.fileTypes.ex.FileTypeIdentifiableByVirtualFile
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

/** Fantom props files: `fan.props` and `*.props` under an `etc/` folder (`key=value`, `//` comments). */
object FanPropsLanguage : Language("fan_props") {
    private fun readResolve(): Any = FanPropsLanguage
    override fun getDisplayName(): String = "Fantom props"
}

class FanPropsElementType(debugName: String) : IElementType(debugName, FanPropsLanguage) {
    override fun toString(): String = "FanProps:" + super.toString()
}

object FanPropsTypes {
    @JvmField val COMMENT = FanPropsElementType("COMMENT")
    @JvmField val KEY = FanPropsElementType("KEY")
    @JvmField val EQ = FanPropsElementType("EQ")
    @JvmField val VALUE = FanPropsElementType("VALUE")
    @JvmField val COMMENTS = TokenSet.create(COMMENT)
}

/**
 * State 0: line start, 1: after the key, 2: after `=`. A value ending in `\` goes
 * on to the next line, as in `sys::InStream.readProps`.
 */
class FanPropsLexer : LineLexerBase() {
    override fun lex(state: Int): Int {
        val c = buf[pos]
        if (c == '\n' || c == '\r') return token(skipNewline(pos), TokenType.WHITE_SPACE, 0)
        if (c == ' ' || c == '\t') return token(skipSpaces(pos), TokenType.WHITE_SPACE, state)
        when (state) {
            0 -> {
                if (startsWith(pos, "//") || c == '#') return token(lineEnd(pos), FanPropsTypes.COMMENT, 0)
                var end = pos
                while (end < limit && buf[end] != '=' && buf[end] != '\n' && buf[end] != '\r') end++
                // Trailing spaces before `=` belong to the whitespace, not the key.
                var keyEnd = end
                while (keyEnd > pos && (buf[keyEnd - 1] == ' ' || buf[keyEnd - 1] == '\t')) keyEnd--
                return token(keyEnd, FanPropsTypes.KEY, 1)
            }
            1 -> {
                if (c == '=') return token(pos + 1, FanPropsTypes.EQ, 2)
                return token(lineEnd(pos), TokenType.BAD_CHARACTER, 0)
            }
            else -> {
                var end = lineEnd(pos)
                while (end < limit && end > pos && buf[end - 1] == '\\') end = lineEnd(skipNewline(end))
                return token(end, FanPropsTypes.VALUE, 0)
            }
        }
    }
}

object FanPropsFileType : LanguageFileType(FanPropsLanguage), FileTypeIdentifiableByVirtualFile {
    override fun getName(): String = "Fantom props"
    override fun getDescription(): String = "Fantom props file (fan.props, etc/**/*.props)"
    override fun getDefaultExtension(): String = "props"
    override fun getIcon(): Icon = FantomIcons.FILE

    override fun isMyFileType(file: VirtualFile): Boolean {
        if (file.isDirectory) return false
        val name = file.name
        if (name == "fan.props") return true
        if (!name.endsWith(".props")) return false
        var dir = file.parent
        while (dir != null) {
            if (dir.name == "etc") return true
            dir = dir.parent
        }
        return false
    }
}

class FanPropsFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, FanPropsLanguage) {
    override fun getFileType(): FileType = FanPropsFileType
    override fun toString(): String = "Fantom props File"
}

class FanPropsParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = FanPropsLexer()
    override fun createParser(project: Project?): PsiParser = FlatPsiParser
    override fun getFileNodeType(): IFileElementType = FILE
    override fun getCommentTokens(): TokenSet = FanPropsTypes.COMMENTS
    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY
    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = FanPropsFile(viewProvider)

    companion object {
        @JvmField val FILE = IFileElementType(FanPropsLanguage)
    }
}

class FanPropsSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = FanPropsLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = pack(
        when (tokenType) {
            FanPropsTypes.COMMENT -> COMMENT
            FanPropsTypes.KEY -> KEY
            FanPropsTypes.EQ -> EQ
            FanPropsTypes.VALUE -> VALUE
            TokenType.BAD_CHARACTER -> FantomColors.BAD_CHAR.textAttributesKey
            else -> null
        }
    )

    companion object {
        val COMMENT = TextAttributesKey.createTextAttributesKey("co.anbora.labs.fanprops.COMMENT", FantomColors.COMMENTS.textAttributesKey)
        val KEY = TextAttributesKey.createTextAttributesKey("co.anbora.labs.fanprops.KEY", FantomColors.INSTANCE_FIELD.textAttributesKey)
        val EQ = TextAttributesKey.createTextAttributesKey("co.anbora.labs.fanprops.EQ", FantomColors.OPERATOR.textAttributesKey)
        val VALUE = TextAttributesKey.createTextAttributesKey("co.anbora.labs.fanprops.VALUE", FantomColors.STRINGS.textAttributesKey)
    }
}

class FanPropsSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = FanPropsSyntaxHighlighter()
}

class FanPropsCommenter : Commenter {
    override fun getLineCommentPrefix(): String = "//"
    override fun getBlockCommentPrefix(): String? = null
    override fun getBlockCommentSuffix(): String? = null
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}
