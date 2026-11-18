package co.anbora.labs.fantom.ide.axon

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Color
import java.awt.Font

/**
 * The Axon keys must have a visible look in the bundled schemes: through the Fantom key they
 * fall back to, or through an entry in `colorSchemes/Fantom*.xml`.
 */
class AxonSchemeColoursTest : BasePlatformTestCase() {

    // Only Default and Darcula are bundled in the test platform; Light and Dark share the same files.
    private fun scheme(name: String): EditorColorsScheme =
        EditorColorsManager.getInstance().allSchemes.firstOrNull { it.name.endsWith(name) || it.displayName == name }
            ?: error("no scheme $name; have " + EditorColorsManager.getInstance().allSchemes.map { it.name })

    private fun EditorColorsScheme.look(color: AxonColors): TextAttributes =
        getAttributes(color.textAttributesKey) ?: error("${color.name} has no attributes in $name")

    fun testEveryKeyHasALook() {
        for (name in listOf("Default", "Darcula")) {
            val s = scheme(name)
            for (color in AxonColors.entries) assertFalse("$name ${color.name} is empty", s.look(color).isEmpty)
        }
    }

    /** The function name has its own colour and is bold: `colorSchemes/Fantom*.xml`. */
    fun testFunctionDeclarationFromSchemeFiles() {
        val light = scheme("Default").look(AxonColors.FUNCTION_DECLARATION)
        assertEquals(Color.decode("#795E26"), light.foregroundColor)
        assertEquals(Font.BOLD, light.fontType)
        val dark = scheme("Darcula").look(AxonColors.FUNCTION_DECLARATION)
        assertEquals(Color.decode("#FFC66D"), dark.foregroundColor)
        assertEquals(Font.BOLD, dark.fontType)
    }

    /** Function name, parameter, local, call and keyword: five looks, each one different from the others. */
    fun testFiveDifferentLooks() {
        for (name in listOf("Default", "Darcula")) {
            val s = scheme(name)
            val looks = listOf(
                AxonColors.FUNCTION_DECLARATION, AxonColors.PARAMETER, AxonColors.LOCAL_VARIABLE,
                AxonColors.FUNCTION_CALL, AxonColors.KEYWORD,
            ).associateWith { s.look(it).let { a -> a.foregroundColor to a.fontType } }
            assertEquals("$name: $looks", 5, looks.values.toSet().size)
            // Each has a colour that is not the plain text colour.
            for ((color, look) in looks) {
                assertNotNull("$name ${color.name}", look.first)
                assertFalse("$name ${color.name} is the text colour", look.first == s.defaultForeground)
            }
            // A parameter is the local colour in italic, as in Java and Kotlin.
            assertEquals(looks[AxonColors.LOCAL_VARIABLE]!!.first, looks[AxonColors.PARAMETER]!!.first)
            assertEquals(Font.ITALIC, looks[AxonColors.PARAMETER]!!.second)
            // The function name and the call differ by colour, not only by weight.
            assertFalse(looks[AxonColors.FUNCTION_DECLARATION]!!.first == looks[AxonColors.FUNCTION_CALL]!!.first)
        }
    }

    /**
     * Contrast with the editor background: at least 4.5:1 (WCAG AA) for the colours that the plugin
     * sets, and at least 3:1 for the ones that come from the platform scheme.
     */
    fun testContrast() {
        val own = listOf(AxonColors.FUNCTION_DECLARATION, AxonColors.PARAMETER, AxonColors.LOCAL_VARIABLE, AxonColors.FUNCTION_CALL)
        val platform = listOf(AxonColors.KEYWORD, AxonColors.TAG_NAME, AxonColors.DEFCOMP_CELL, AxonColors.CONSTANT)
        for (name in listOf("Default", "Darcula")) {
            val s = scheme(name)
            for (color in own + platform) {
                val ratio = contrast(s.look(color).foregroundColor, s.defaultBackground)
                val min = if (color in own) 4.5 else 3.0
                assertTrue("$name ${color.name} contrast $ratio", ratio >= min)
            }
        }
    }

    /** With no entry of their own, the other semantic keys take the look of the Fantom key. */
    fun testFallbackKeysFollowFantom() {
        for (name in listOf("Default", "Darcula")) {
            val s = scheme(name)
            for (color in AxonColors.entries - AxonColors.FUNCTION_DECLARATION) {
                val fallback = color.textAttributesKey.fallbackAttributeKey!!
                assertEquals("$name ${color.name}", s.getAttributes(fallback), s.look(color))
            }
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(c: Color): Double {
        fun channel(v: Int): Double {
            val x = v / 255.0
            return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }
}
