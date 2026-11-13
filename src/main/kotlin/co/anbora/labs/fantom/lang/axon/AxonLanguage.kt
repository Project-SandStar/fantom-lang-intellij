package co.anbora.labs.fantom.lang.axon

import co.anbora.labs.fantom.ide.icons.FantomIcons
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.FlexAdapter
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.extapi.psi.ASTWrapperPsiElement
import javax.swing.Icon

/** Axon, the SkySpark and Haxall scripting language. */
object AxonLanguage : Language("axon_lang") {
    private fun readResolve(): Any = AxonLanguage
    override fun getDisplayName(): String = "Axon"
}

class AxonTokenType(debugName: String) : IElementType(debugName, AxonLanguage) {
    override fun toString(): String = "Axon:" + super.toString()
}

/** Token types used by the generated `_AxonLexer` (from `src/main/grammar/Axon.flex`). */
object AxonTokenTypes {
    @JvmField val LINE_COMMENT = AxonTokenType("LINE_COMMENT")
    @JvmField val BLOCK_COMMENT = AxonTokenType("BLOCK_COMMENT")
    @JvmField val KEYWORD = AxonTokenType("KEYWORD")
    @JvmField val IDENTIFIER = AxonTokenType("IDENTIFIER")
    @JvmField val TYPENAME = AxonTokenType("TYPENAME")
    @JvmField val NUMBER = AxonTokenType("NUMBER")
    @JvmField val STRING = AxonTokenType("STRING")
    @JvmField val URI = AxonTokenType("URI")
    @JvmField val REF = AxonTokenType("REF")
    @JvmField val SYMBOL = AxonTokenType("SYMBOL")
    @JvmField val FN_ARROW = AxonTokenType("FN_ARROW")
    @JvmField val LPAREN = AxonTokenType("LPAREN")
    @JvmField val RPAREN = AxonTokenType("RPAREN")
    @JvmField val LBRACKET = AxonTokenType("LBRACKET")
    @JvmField val RBRACKET = AxonTokenType("RBRACKET")
    @JvmField val LBRACE = AxonTokenType("LBRACE")
    @JvmField val RBRACE = AxonTokenType("RBRACE")
    @JvmField val COMMA = AxonTokenType("COMMA")
    @JvmField val SEMICOLON = AxonTokenType("SEMICOLON")
    @JvmField val DOT = AxonTokenType("DOT")
    @JvmField val COLON = AxonTokenType("COLON")
    @JvmField val OPERATOR = AxonTokenType("OPERATOR")

    @JvmField val COMMENTS = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT)
    @JvmField val STRINGS = TokenSet.create(STRING)
}

class AxonLexer : FlexAdapter(_AxonLexer())

object AxonFileType : LanguageFileType(AxonLanguage) {
    override fun getName(): String = "Axon"
    override fun getDescription(): String = "Axon script (SkySpark, Haxall)"
    override fun getDefaultExtension(): String = "axon"
    override fun getIcon(): Icon = FantomIcons.FILE
}

class AxonFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, AxonLanguage) {
    override fun getFileType(): FileType = AxonFileType
    override fun toString(): String = "Axon File"
}

/**
 * Axon has no PSI tree yet: the parser puts every token under the file node.
 * That is enough for colours, brace matching, commenting and injection.
 */
class AxonParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = AxonLexer()
    override fun createParser(project: Project?): PsiParser = FlatPsiParser
    override fun getFileNodeType(): IFileElementType = FILE
    override fun getCommentTokens(): TokenSet = AxonTokenTypes.COMMENTS
    override fun getStringLiteralElements(): TokenSet = AxonTokenTypes.STRINGS
    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = AxonFile(viewProvider)

    companion object {
        @JvmField val FILE = IFileElementType(AxonLanguage)
    }
}

/** A parser that adds every token to the root node and builds no inner nodes. */
object FlatPsiParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val mark = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        mark.done(root)
        return builder.treeBuilt
    }
}
