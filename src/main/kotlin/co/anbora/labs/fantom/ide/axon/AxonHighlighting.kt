package co.anbora.labs.fantom.ide.axon

import co.anbora.labs.fantom.ide.color.FantomColors
import co.anbora.labs.fantom.ide.icons.FantomIcons
import co.anbora.labs.fantom.lang.axon.AxonLexer
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes
import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import javax.swing.Icon

/**
 * Axon colour keys. Each falls back to the matching Fantom key, so Axon code
 * (in `.axon` files and injected in strings) takes the colours of the Fantom scheme.
 * The external key name is `co.anbora.labs.axon.<NAME>`; never rename an entry.
 */
enum class AxonColors(humanName: String, fallback: TextAttributesKey) {
    KEYWORD("Keyword", FantomColors.KEY_WORD.textAttributesKey),
    TYPENAME("Type name", FantomColors.TYPE_REFERENCE.textAttributesKey),
    STRING("String", FantomColors.STRINGS.textAttributesKey),
    URI("Uri", FantomColors.URI.textAttributesKey),
    NUMBER("Number, date, time", FantomColors.NUMBERS.textAttributesKey),
    REF("Ref (@id)", FantomColors.FACET.textAttributesKey),
    SYMBOL("Symbol (^name)", FantomColors.CONSTANT.textAttributesKey),
    LINE_COMMENT("Comment", FantomColors.COMMENTS.textAttributesKey),
    OPERATOR("Operator sign", FantomColors.OPERATOR.textAttributesKey),
    FN_ARROW("Lambda arrow (=>)", FantomColors.CLOSURE_PIPE.textAttributesKey),
    PARENTHESES("Parentheses", FantomColors.PARENTHESES.textAttributesKey),
    BRACKETS("Brackets", FantomColors.BRACKETS.textAttributesKey),
    BRACES("Braces", FantomColors.BRACES.textAttributesKey),
    COMMA("Comma", FantomColors.COMMA.textAttributesKey),
    SEMICOLON("Semicolon", FantomColors.SEMICOLON.textAttributesKey),
    DOT("Dot", FantomColors.DOT.textAttributesKey),
    BAD_CHAR("Bad character", HighlighterColors.BAD_CHARACTER);

    val textAttributesKey: TextAttributesKey = TextAttributesKey.createTextAttributesKey("co.anbora.labs.axon.$name", fallback)
    val attributesDescriptor = AttributesDescriptor(humanName, textAttributesKey)
}

class AxonSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = AxonLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> =
        pack(colorOf(tokenType)?.textAttributesKey)

    companion object {
        fun colorOf(tokenType: IElementType?): AxonColors? = when (tokenType) {
            AxonTokenTypes.KEYWORD -> AxonColors.KEYWORD
            AxonTokenTypes.TYPENAME -> AxonColors.TYPENAME
            AxonTokenTypes.STRING -> AxonColors.STRING
            AxonTokenTypes.URI -> AxonColors.URI
            AxonTokenTypes.NUMBER -> AxonColors.NUMBER
            AxonTokenTypes.REF -> AxonColors.REF
            AxonTokenTypes.SYMBOL -> AxonColors.SYMBOL
            AxonTokenTypes.LINE_COMMENT, AxonTokenTypes.BLOCK_COMMENT -> AxonColors.LINE_COMMENT
            AxonTokenTypes.OPERATOR, AxonTokenTypes.COLON -> AxonColors.OPERATOR
            AxonTokenTypes.FN_ARROW -> AxonColors.FN_ARROW
            AxonTokenTypes.LPAREN, AxonTokenTypes.RPAREN -> AxonColors.PARENTHESES
            AxonTokenTypes.LBRACKET, AxonTokenTypes.RBRACKET -> AxonColors.BRACKETS
            AxonTokenTypes.LBRACE, AxonTokenTypes.RBRACE -> AxonColors.BRACES
            AxonTokenTypes.COMMA -> AxonColors.COMMA
            AxonTokenTypes.SEMICOLON -> AxonColors.SEMICOLON
            AxonTokenTypes.DOT -> AxonColors.DOT
            TokenType.BAD_CHARACTER -> AxonColors.BAD_CHAR
            else -> null
        }
    }
}

class AxonSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        AxonSyntaxHighlighter()
}

/** Settings | Editor | Color Scheme | Axon. */
class AxonColorSettingsPage : ColorSettingsPage {
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> =
        AxonColors.entries.map { it.attributesDescriptor }.toTypedArray()

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getDisplayName(): String = "Axon"
    override fun getIcon(): Icon = FantomIcons.FILE
    override fun getHighlighter(): SyntaxHighlighter = AxonSyntaxHighlighter()
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

    override fun getDemoText(): String = DEMO

    companion object {
        const val DEMO = """// Find periods when an AHU heats and cools at once
(ahu, dates) => do
  /* cooling mode periods */
  coolPeriods: read(cool and equipRef==ahu->id)
       .hisRead(dates).hisFindPeriods(v => v)
  site: readById(@p:demo:r:2f797ee0-541a6010)
  if (site.has("area") and not site->area.isEmpty) return null
  limit: 75°F + 2.5kW * 10min
  start: 2024-01-01; at: 10:30
  spec: ^lib:ph
  file: `io/points.csv`
  try parseRef(r"\raw") catch (e) throw {err: e, n: Number}
  [coolPeriods, site]
end
"""
    }
}

/** Matches `( )`, `[ ]` and `{ }`. `do ... end` is not matched. */
class AxonBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> = PAIRS
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true
    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset

    private companion object {
        val PAIRS = arrayOf(
            BracePair(AxonTokenTypes.LPAREN, AxonTokenTypes.RPAREN, false),
            BracePair(AxonTokenTypes.LBRACKET, AxonTokenTypes.RBRACKET, false),
            BracePair(AxonTokenTypes.LBRACE, AxonTokenTypes.RBRACE, true),
        )
    }
}

class AxonCommenter : Commenter {
    override fun getLineCommentPrefix(): String = "//"
    override fun getBlockCommentPrefix(): String = "/*"
    override fun getBlockCommentSuffix(): String = "*/"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}
