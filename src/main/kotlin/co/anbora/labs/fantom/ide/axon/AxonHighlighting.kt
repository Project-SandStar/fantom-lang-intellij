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
 * The external key name is `co.anbora.labs.axon.<NAME>`; never rename an entry, and add new ones at the end.
 *
 * The keys up to [BAD_CHAR] come from the lexer ([AxonSyntaxHighlighter]). [CONSTANT] comes from the lexer too.
 * The other keys after [BAD_CHAR] come from the PSI ([AxonHighlightingAnnotator]).
 *
 * A key with a colour of its own (not the Fantom one) is in `resources/colorSchemes/`.
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
    BAD_CHAR("Bad character", HighlighterColors.BAD_CHARACTER),

    /** `name` in `name: (a) => ...` and in `name: defcomp ... end`. */
    FUNCTION_DECLARATION("Function declaration", FantomColors.METHOD_DECLARATION.textAttributesKey),
    /** A lambda parameter, where it is declared and where it is used. */
    PARAMETER("Parameter", FantomColors.PARAMETER.textAttributesKey),
    /** `name` in `name: expr`, the variable of `catch (err)`, and their uses. */
    LOCAL_VARIABLE("Local variable", FantomColors.LOCAL_VARIABLE.textAttributesKey),
    /** `f` in `f(a)`, in `a.f(b)` and in `a.f x => x`; also a use of a function of the same file. */
    FUNCTION_CALL("Function call", FantomColors.FUNCTION_CALL.textAttributesKey),
    /** `name` in `a.name` with no arguments. Axon calls the function `name`, so it looks like a call until the user sets it. */
    DOT_NAME("Name after a dot, no arguments", FUNCTION_CALL.textAttributesKey),
    /** A dict key (`{dis: "x", site}`) and the name after `->`. */
    TAG_NAME("Tag name", FantomColors.INSTANCE_FIELD.textAttributesKey),
    /** A cell of a `defcomp`, where it is declared and where it is used. */
    DEFCOMP_CELL("Defcomp cell", FantomColors.INSTANCE_FIELD.textAttributesKey),
    /** `null`, `true`, `false`. */
    CONSTANT("null, true, false", FantomColors.CONSTANT.textAttributesKey);

    val textAttributesKey: TextAttributesKey = TextAttributesKey.createTextAttributesKey("co.anbora.labs.axon.$name", fallback)
    val attributesDescriptor = AttributesDescriptor(humanName, textAttributesKey)
}

class AxonSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = AxonLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> =
        pack(colorOf(tokenType)?.textAttributesKey)

    companion object {
        fun colorOf(tokenType: IElementType?): AxonColors? = when (tokenType) {
            null -> null
            AxonTokenTypes.TRUE, AxonTokenTypes.FALSE, AxonTokenTypes.NULL -> AxonColors.CONSTANT
            in AxonTokenTypes.KEYWORDS -> AxonColors.KEYWORD
            AxonTokenTypes.TYPENAME -> AxonColors.TYPENAME
            AxonTokenTypes.STRING -> AxonColors.STRING
            AxonTokenTypes.URI -> AxonColors.URI
            AxonTokenTypes.NUMBER -> AxonColors.NUMBER
            AxonTokenTypes.REF -> AxonColors.REF
            AxonTokenTypes.SYMBOL -> AxonColors.SYMBOL
            AxonTokenTypes.LINE_COMMENT, AxonTokenTypes.BLOCK_COMMENT -> AxonColors.LINE_COMMENT
            in AxonTokenTypes.OPERATORS, AxonTokenTypes.COLON -> AxonColors.OPERATOR
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

/**
 * Settings | Editor | Color Scheme | Axon.
 *
 * The preview has no PSI, so the demo text marks each name with a tag (`<fn>`, `<param>`, ...)
 * for the colour that [AxonHighlightingAnnotator] gives it in the editor.
 */
class AxonColorSettingsPage : ColorSettingsPage {
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> =
        AxonColors.entries.map { it.attributesDescriptor }.toTypedArray()

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getDisplayName(): String = "Axon"
    override fun getIcon(): Icon = FantomIcons.FILE
    override fun getHighlighter(): SyntaxHighlighter = AxonSyntaxHighlighter()
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = TAGS

    override fun getDemoText(): String = DEMO

    companion object {
        /** Demo tag to colour key: every key that the annotator gives. */
        val TAGS: Map<String, TextAttributesKey> = mapOf(
            "fn" to AxonColors.FUNCTION_DECLARATION,
            "param" to AxonColors.PARAMETER,
            "local" to AxonColors.LOCAL_VARIABLE,
            "call" to AxonColors.FUNCTION_CALL,
            "dot" to AxonColors.DOT_NAME,
            "tag" to AxonColors.TAG_NAME,
            "cell" to AxonColors.DEFCOMP_CELL,
        ).mapValues { it.value.textAttributesKey }

        const val DEMO = """// Daily average for a point
<fn>calcDailyAvg</fn>: (<param>point</param>, <param>dates</param>) => do
  /* no history: no result */
  <local>his</local>: <param>point</param>.<call>hisRead</call>(<param>dates</param>)
  if (<local>his</local>.<dot>isEmpty</dot> or not <param>point</param>-><tag>enabled</tag>) return null
  <local>total</local>: <local>his</local>.<call>foldCol</call>("v0", sum)
  {<tag>dis</tag>: "Average", <tag>sensor</tag>, <tag>val</tag>: <local>total</local> / <local>his</local>.<dot>size</dot>}
end

<fn>scaleAll</fn>: (<param>list</param>, <param>factor</param>: 2) => <param>list</param>.<call>map</call>(<param>x</param> => <param>x</param> * <param>factor</param>)

<fn>safeRead</fn>: (<param>id</param>) => do
  <local>limit</local>: 75°F + 2.5kW * 10min
  <local>start</local>: 2024-01-01; <local>at</local>: 10:30
  <local>fallback</local>: [@p:demo:r:2f797ee0-541a6010, ^lib:ph, `io/points.csv`, Number]
  try <call>readById</call>(<param>id</param>) catch (<local>err</local>) <call>logErr</call>(<local>err</local>, <local>fallback</local>, true)
end

<fn>addOne</fn>: defcomp
  <cell>in</cell>: {<tag>is</tag>: ^number}
  <cell>out</cell>: {<tag>is</tag>: ^number}
  do
    <cell>out</cell> = <cell>in</cell> + 1
  end
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
