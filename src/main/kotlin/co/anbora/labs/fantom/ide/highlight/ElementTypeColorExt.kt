package co.anbora.labs.fantom.ide.highlight

import co.anbora.labs.fantom.ide.color.FantomColors
import co.anbora.labs.fantom.lang.FantomParserDefinition.Companion.C_STYLE_COMMENT
import co.anbora.labs.fantom.lang.FantomParserDefinition.Companion.END_OF_LINE_COMMENT
import co.anbora.labs.fantom.lang.FantomParserDefinition.Companion.FAN_SYS_TYPE
import co.anbora.labs.fantom.lang.FantomParserDefinition.Companion.SHABENG
import co.anbora.labs.fantom.lang.core.KEYWORDS
import co.anbora.labs.fantom.lang.core.NUMERIC_LITERALS
import co.anbora.labs.fantom.lang.core.STRING_LITERALS
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.*
import co.anbora.labs.fantom.lang.core.tokenSetOf
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

fun IElementType?.textAttributesKey(): TextAttributesKey? = map(this)?.textAttributesKey

/** Every operator sign. `|` is here too; the annotator repaints the closure pair. */
val OPERATORS = tokenSetOf(
    PLUS, MINUS, MULT, DIV, PERC, PLUSPLUS, MINUSMINUS,
    PLUSEQ, MINUSEQ, MULTEQ, DIVEQ, PERCEQ,
    EQ, COLON_EQ, EQEQ, EQEQEQ, NE, NEEQ, LT, GT, LE, GE, COMPARE,
    AND, OR, XOR, TILDE, ANDAND, OROR, EXCL, ANDEQ, OREQ, XOREQ,
    LTLT, GTGT, LTLTEQ, GTGTEQ,
    QUEST, QUEST_COLON, COLON, COLON_COLON, AT, SHARP,
    DYN_CALL, SAFE_DYN_CALL, SAFE_DOT, RANGE_SEP_INCL, RANGE_SEP_EXCL,
)

// First match wins: the single tokens sit above the sets they also belong to.
private fun map(tokenType: IElementType?): FantomColors? {
    return when (tokenType) {
        THIS, SUPER, IT -> FantomColors.THIS_SUPER_IT
        in KEYWORDS -> FantomColors.KEY_WORD
        URI_LITERAL -> FantomColors.URI
        CHAR_LITERAL -> FantomColors.CHAR
        DSL_STRING -> FantomColors.DSL
        in STRING_LITERALS -> FantomColors.STRINGS
        DURATION_LITERAL -> FantomColors.DURATION
        in NUMERIC_LITERALS -> FantomColors.NUMBERS
        FAN_SYS_TYPE -> FantomColors.TYPES
        LBRACE, RBRACE -> FantomColors.BRACES
        LPAR, RPAR -> FantomColors.PARENTHESES
        LBRACKET, RBRACKET -> FantomColors.BRACKETS
        COMMA -> FantomColors.COMMA
        SEMICOLON -> FantomColors.SEMICOLON
        DOT -> FantomColors.DOT
        in OPERATORS -> FantomColors.OPERATOR
        SHABENG, FANDOC_LINE_COMMENT -> FantomColors.DOC_COMMENTS
        END_OF_LINE_COMMENT -> FantomColors.COMMENTS
        C_STYLE_COMMENT -> FantomColors.COMMENTS
        TokenType.BAD_CHARACTER -> FantomColors.BAD_CHAR
        else -> null
    }
}
