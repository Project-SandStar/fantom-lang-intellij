package co.anbora.labs.fantom.ide.color

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.options.colors.AttributesDescriptor
import java.awt.Color
import java.awt.Font

/**
 * Every colour the plugin paints. Each entry is one key on the Fantom colour
 * settings page. The external key name is `co.anbora.labs.fantom.<NAME>`, so
 * never rename an entry: user colour schemes store that name.
 *
 * Lexer colours come from [co.anbora.labs.fantom.ide.highlight.textAttributesKey],
 * the rest from [co.anbora.labs.fantom.ide.annotator.HighlightingAnnotator].
 */
/** A key with a fixed default colour, used where the platform default is plain text. */
private fun temp(id: String, hex: String, style: Int = Font.PLAIN): TextAttributesKey =
    TextAttributesKey.createTempTextAttributesKey(id, TextAttributes(Color.decode(hex), null, null, null, style))

enum class FantomColors(humanName: String, default: TextAttributesKey) {

    KEY_WORD("Keywords//Keyword", DefaultLanguageHighlighterColors.KEYWORD),
    THIS_SUPER_IT("Keywords//this, super, it", DefaultLanguageHighlighterColors.KEYWORD),
    BAD_CHAR("Bad Character", HighlighterColors.BAD_CHARACTER),

    // Braces and operators (lexer).
    // The platform defaults for these are plain text in Darcula and the new UI, so Fantom
    // ships its own colours. They are still one click away in Settings | Color Scheme | Fantom.
    OPERATOR("Braces and Operators//Operator sign", temp("FANTOM_OPERATOR", "#CC7832", Font.BOLD)),
    BRACES("Braces and Operators//Braces", temp("FANTOM_BRACES", "#E8BF6A", Font.BOLD)),
    PARENTHESES("Braces and Operators//Parentheses", temp("FANTOM_PARENTHESES", "#E8BF6A")),
    BRACKETS("Braces and Operators//Brackets", temp("FANTOM_BRACKETS", "#E8BF6A")),
    COMMA("Braces and Operators//Comma", temp("FANTOM_COMMA", "#CC7832")),
    SEMICOLON("Braces and Operators//Semicolon", temp("FANTOM_SEMICOLON", "#CC7832")),
    DOT("Braces and Operators//Dot", temp("FANTOM_DOT", "#CC7832", Font.BOLD)),
    /** The `|` pair around a closure signature or a function type. */
    CLOSURE_PIPE("Braces and Operators//Closure pipe", DefaultLanguageHighlighterColors.KEYWORD),

    // Literals (lexer, plus ranged annotations inside strings).
    STRINGS("Literals//String", DefaultLanguageHighlighterColors.STRING),
    STRING_INTERPOLATION("Literals//String interpolation", DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE),
    STRING_ESCAPE("Literals//String escape", DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE),
    URI("Literals//Uri", DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE),
    CHAR("Literals//Char", DefaultLanguageHighlighterColors.STRING),
    DSL("Literals//DSL", DefaultLanguageHighlighterColors.STRING),
    NUMBERS("Literals//Number", DefaultLanguageHighlighterColors.NUMBER),
    DURATION("Literals//Duration", DefaultLanguageHighlighterColors.NUMBER),

    // Declarations (annotator, on the name of a declaration).
    CLASS_DECLARATION("Declarations//Class, mixin, enum, facet", DefaultLanguageHighlighterColors.CLASS_NAME),
    METHOD_DECLARATION("Declarations//Method", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION),
    FIELD_DECLARATION("Declarations//Field", DefaultLanguageHighlighterColors.INSTANCE_FIELD),
    /** `@Foo`: the `@` and the facet name. */
    FACET("Declarations//Facet", DefaultLanguageHighlighterColors.METADATA),
    /** `name` in `@Foo { name = 1 }`. */
    ANNOTATIONS("Declarations//Facet value name",
        TextAttributesKey.createTempTextAttributesKey(
        "FANTOM_ANNOTATIONS",
        TextAttributes(Color.decode("#BBB529"), null, null, null, Font.PLAIN)
    )),

    // Identifiers (annotator, on a use that resolves; static and const apply at the declaration too).
    LOCAL_VARIABLE("Identifiers//Local variable", temp("FANTOM_LOCAL_VARIABLE", "#9CDCFE")),
    PARAMETER("Identifiers//Parameter", temp("FANTOM_PARAMETER", "#9CDCFE", Font.ITALIC)),
    INSTANCE_FIELD("Identifiers//Field", DefaultLanguageHighlighterColors.INSTANCE_FIELD),
    STATIC_FIELD("Identifiers//Static field", DefaultLanguageHighlighterColors.STATIC_FIELD),
    CONSTANT("Identifiers//Constant (const field, enum value)", DefaultLanguageHighlighterColors.CONSTANT),
    FUNCTION_CALL("Identifiers//Method call", temp("FANTOM_FUNCTION_CALL", "#DCDCAA")),
    STATIC_METHOD("Identifiers//Static method", DefaultLanguageHighlighterColors.STATIC_METHOD),
    /** `recv.name` when the slot is not in the project or library. */
    METHOD_CALL(
        "Identifiers//Unresolved slot call",
        TextAttributesKey.createTempTextAttributesKey(
            "FANTOM_METHOD_CALL",
            TextAttributes(Color.decode("#E8C32A"), null, null, null, Font.PLAIN)
        )
    ),
    TYPE_REFERENCE("Identifiers//Type reference", DefaultLanguageHighlighterColors.CLASS_REFERENCE),
    /** A type name that does not resolve (no library model, or a typo). */
    TYPES(
        "Identifiers//Unresolved type",
        TextAttributesKey.createTempTextAttributesKey(
            "FANTOM_TYPES",
            TextAttributes(Color.decode("#507874"), null, null, null, Font.PLAIN)
        )
    ),

    // Comments.
    COMMENTS("Comments//Line Comment", DefaultLanguageHighlighterColors.LINE_COMMENT),
    DOC_COMMENTS("Comments//Doc Comment", DefaultLanguageHighlighterColors.DOC_COMMENT),
    /** `` `code` `` span inside a `**` fandoc comment. */
    FANDOC_TAG("Comments//Fandoc code span", DefaultLanguageHighlighterColors.DOC_COMMENT_TAG);


    val textAttributesKey = TextAttributesKey.createTextAttributesKey("co.anbora.labs.fantom.$name", default)
    val attributesDescriptor = AttributesDescriptor(humanName, textAttributesKey)
    val testSeverity: HighlightSeverity = HighlightSeverity(name, HighlightSeverity.INFORMATION.myVal)
}
