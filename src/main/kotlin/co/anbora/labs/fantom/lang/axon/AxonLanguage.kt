package co.anbora.labs.fantom.lang.axon

import co.anbora.labs.fantom.ide.icons.FantomIcons
import co.anbora.labs.fantom.lang.axon.parser.AxonParser
import co.anbora.labs.fantom.lang.axon.psi.AxonTypes
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
import javax.swing.Icon

/** Axon, the SkySpark and Haxall scripting language. */
object AxonLanguage : Language("axon_lang") {
    private fun readResolve(): Any = AxonLanguage
    override fun getDisplayName(): String = "Axon"
}

/**
 * An Axon token. The debug name is what a parser error message shows, in quotes:
 * `IDENTIFIER` for a token class, `do` or `(` for a fixed text.
 */
class AxonTokenType(debugName: String) : IElementType(debugName, AxonLanguage)

/** A composite node type of the Axon PSI tree (see `AxonTypes`). */
class AxonElementType(debugName: String) : IElementType(debugName, AxonLanguage)

/**
 * Token types used by the generated `_AxonLexer` (from `src/main/grammar/Axon.flex`)
 * and by the generated `AxonParser` (from `src/main/grammar/Axon.bnf`).
 * The grammar has `generateTokens=false`: this object is the only place that declares tokens.
 */
object AxonTokenTypes {
    @JvmField val LINE_COMMENT = AxonTokenType("LINE_COMMENT")
    @JvmField val BLOCK_COMMENT = AxonTokenType("BLOCK_COMMENT")
    @JvmField val IDENTIFIER = AxonTokenType("IDENTIFIER")
    @JvmField val TYPENAME = AxonTokenType("TYPENAME")
    @JvmField val NUMBER = AxonTokenType("NUMBER")
    @JvmField val STRING = AxonTokenType("STRING")
    @JvmField val URI = AxonTokenType("URI")
    @JvmField val REF = AxonTokenType("REF")
    @JvmField val SYMBOL = AxonTokenType("SYMBOL")

    // keywords
    @JvmField val AND = AxonTokenType("and")
    @JvmField val CATCH = AxonTokenType("catch")
    @JvmField val DEFCOMP = AxonTokenType("defcomp")
    @JvmField val DEFLINKS = AxonTokenType("deflinks")
    @JvmField val DO = AxonTokenType("do")
    @JvmField val ELSE = AxonTokenType("else")
    @JvmField val END = AxonTokenType("end")
    @JvmField val IF = AxonTokenType("if")
    @JvmField val NOT = AxonTokenType("not")
    @JvmField val OR = AxonTokenType("or")
    @JvmField val RETURN = AxonTokenType("return")
    @JvmField val THROW = AxonTokenType("throw")
    @JvmField val TRY = AxonTokenType("try")
    @JvmField val TRUE = AxonTokenType("true")
    @JvmField val FALSE = AxonTokenType("false")
    @JvmField val NULL = AxonTokenType("null")

    // punctuation
    @JvmField val FN_ARROW = AxonTokenType("=>")
    @JvmField val LPAREN = AxonTokenType("(")
    @JvmField val RPAREN = AxonTokenType(")")
    @JvmField val LBRACKET = AxonTokenType("[")
    @JvmField val RBRACKET = AxonTokenType("]")
    @JvmField val LBRACE = AxonTokenType("{")
    @JvmField val RBRACE = AxonTokenType("}")
    @JvmField val COMMA = AxonTokenType(",")
    @JvmField val SEMICOLON = AxonTokenType(";")
    @JvmField val DOT = AxonTokenType(".")
    @JvmField val COLON = AxonTokenType(":")

    // operators
    @JvmField val COLON2 = AxonTokenType("::")
    @JvmField val ARROW = AxonTokenType("->")
    @JvmField val DOTDOT = AxonTokenType("..")
    @JvmField val CMP = AxonTokenType("<=>")
    @JvmField val EQ = AxonTokenType("==")
    @JvmField val NE = AxonTokenType("!=")
    @JvmField val LE = AxonTokenType("<=")
    @JvmField val GE = AxonTokenType(">=")
    @JvmField val LT = AxonTokenType("<")
    @JvmField val GT = AxonTokenType(">")
    @JvmField val ASSIGN = AxonTokenType("=")
    @JvmField val PLUS = AxonTokenType("+")
    @JvmField val MINUS = AxonTokenType("-")
    @JvmField val STAR = AxonTokenType("*")
    @JvmField val SLASH = AxonTokenType("/")
    @JvmField val UNDERSCORE = AxonTokenType("_")

    /** A sign the tokenizer knows but the grammar does not use: `!`, `^`, `?`, `&`, `|`. */
    @JvmField val OPERATOR = AxonTokenType("OPERATOR")

    @JvmField val COMMENTS = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT)
    @JvmField val STRINGS = TokenSet.create(STRING)
    @JvmField val KEYWORDS = TokenSet.create(
        AND, CATCH, DEFCOMP, DEFLINKS, DO, ELSE, END, IF, NOT, OR, RETURN, THROW, TRY, TRUE, FALSE, NULL,
    )
    @JvmField val OPERATORS = TokenSet.create(
        COLON2, ARROW, DOTDOT, CMP, EQ, NE, LE, GE, LT, GT, ASSIGN, PLUS, MINUS, STAR, SLASH, UNDERSCORE, OPERATOR,
    )
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

/** Axon parser definition: `_AxonLexer` tokens, the generated `AxonParser`, PSI from `AxonTypes`. */
class AxonParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = AxonLexer()
    override fun createParser(project: Project?): PsiParser = AxonParser()
    override fun getFileNodeType(): IFileElementType = FILE
    override fun getCommentTokens(): TokenSet = AxonTokenTypes.COMMENTS
    override fun getStringLiteralElements(): TokenSet = AxonTokenTypes.STRINGS
    override fun createElement(node: ASTNode): PsiElement = AxonTypes.Factory.createElement(node)
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
