package co.anbora.labs.fantom.ide.color

import co.anbora.labs.fantom.ide.highlight.FantomSyntaxHighlighter
import co.anbora.labs.fantom.ide.icons.FantomIcons
import co.anbora.labs.fantom.lang.FantomLanguage.LANGUAGE_DEMO_TEXT
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

/**
 * Settings | Editor | Color Scheme | Fantom. One descriptor per [FantomColors]
 * entry; the groups come from the `Group//Name` display names. The preview is
 * `demo/JsonConverter.fan`: lexer colours come from the highlighter, annotator
 * colours from the `<tag>...</tag>` marks listed in [DEMO_TAGS].
 */
class FantomColorSettingPage: ColorSettingsPage {

    private val ATTRS = FantomColors.values().map { it.attributesDescriptor }.toTypedArray()

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = ATTRS

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getDisplayName(): String = "Fantom"

    override fun getIcon(): Icon = FantomIcons.FILE

    override fun getHighlighter(): SyntaxHighlighter = FantomSyntaxHighlighter()

    override fun getDemoText(): String = LANGUAGE_DEMO_TEXT

    override fun getAdditionalHighlightingTagToDescriptorMap(): MutableMap<String, TextAttributesKey> =
        DEMO_TAGS.mapValuesTo(LinkedHashMap()) { it.value.textAttributesKey }

    companion object {
        /** Tag name in the demo text to the colour it previews. */
        val DEMO_TAGS: Map<String, FantomColors> = linkedMapOf(
            "decl" to FantomColors.CLASS_DECLARATION,
            "method" to FantomColors.METHOD_DECLARATION,
            "field" to FantomColors.FIELD_DECLARATION,
            "facet" to FantomColors.FACET,
            "facetval" to FantomColors.ANNOTATIONS,
            "local" to FantomColors.LOCAL_VARIABLE,
            "param" to FantomColors.PARAMETER,
            "ifield" to FantomColors.INSTANCE_FIELD,
            "static" to FantomColors.STATIC_FIELD,
            "const" to FantomColors.CONSTANT,
            "call" to FantomColors.FUNCTION_CALL,
            "scall" to FantomColors.STATIC_METHOD,
            "ucall" to FantomColors.METHOD_CALL,
            "type" to FantomColors.TYPE_REFERENCE,
            "utype" to FantomColors.TYPES,
            "interp" to FantomColors.STRING_INTERPOLATION,
            "esc" to FantomColors.STRING_ESCAPE,
            "pipe" to FantomColors.CLOSURE_PIPE,
            "doctag" to FantomColors.FANDOC_TAG,
            "kw" to FantomColors.KEY_WORD,
        )
    }
}
