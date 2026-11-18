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

    /** The parameter has its own colour, in italic: `colorSchemes/Fantom*.xml`. */
    fun testParameterFromSchemeFiles() {
        val light = scheme("Default").look(AxonColors.PARAMETER)
        assertEquals(Color.decode("#A8400A"), light.foregroundColor)
        assertEquals(Font.ITALIC, light.fontType)
        val dark = scheme("Darcula").look(AxonColors.PARAMETER)
        assertEquals(Color.decode("#B5D86B"), dark.foregroundColor)
        assertEquals(Font.ITALIC, dark.fontType)
    }

    /** Darcula only: tag name, defcomp cell and constant get a lighter purple than the platform one. */
    fun testDarculaTagAndConstant() {
        val s = scheme("Darcula")
        for (color in listOf(AxonColors.TAG_NAME, AxonColors.DEFCOMP_CELL, AxonColors.CONSTANT)) {
            assertEquals(color.name, Color.decode("#B392D1"), s.look(color).foregroundColor)
        }
        assertEquals(Font.ITALIC, s.look(AxonColors.CONSTANT).fontType)
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
            // The five differ by colour, not only by weight or slant.
            assertEquals("$name: $looks", 5, looks.values.map { it.first }.toSet().size)
            assertEquals(Font.ITALIC, looks[AxonColors.PARAMETER]!!.second)
            // The function name and the call differ by colour, not only by weight.
            assertFalse(looks[AxonColors.FUNCTION_DECLARATION]!!.first == looks[AxonColors.FUNCTION_CALL]!!.first)
        }
    }

    /**
     * Contrast with the editor background: at least 4.5:1 (WCAG AA) for every key that the annotator
     * gives and for the constant. The keyword colour is the platform one and is not ours to set:
     * in Darcula it is cc7832, with a contrast of 4.25.
     */
    fun testContrast() {
        val names = listOf(
            AxonColors.FUNCTION_DECLARATION, AxonColors.PARAMETER, AxonColors.LOCAL_VARIABLE, AxonColors.FUNCTION_CALL,
            AxonColors.DOT_NAME, AxonColors.TAG_NAME, AxonColors.DEFCOMP_CELL, AxonColors.CONSTANT,
        )
        for (name in listOf("Default", "Darcula")) {
            val s = scheme(name)
            for (color in names) {
                val ratio = contrast(s.look(color).foregroundColor, s.defaultBackground)
                assertTrue("$name ${color.name} contrast $ratio", ratio >= 4.5)
            }
            assertTrue("$name keyword", contrast(s.look(AxonColors.KEYWORD).foregroundColor, s.defaultBackground) >= 4.0)
        }
    }

    /**
     * The test platform loads only Default and Darcula, so this reads the four files. Each colour of an
     * Axon key, and each Fantom colour that an Axon name falls back to, has a contrast of at least 4.5:1
     * with the editor background of its scheme, and the parameter is not the local colour.
     */
    fun testTheFourSchemeFiles() {
        val backgrounds = mapOf("Default" to "ffffff", "Light" to "ffffff", "Darcula" to "2b2b2b", "Dark" to "1e1f22")
        val fallbacks = listOf("LOCAL_VARIABLE", "PARAMETER", "FUNCTION_CALL").map { "co.anbora.labs.fantom.$it" }
        for ((name, background) in backgrounds) {
            val text = javaClass.getResource("/colorSchemes/Fantom$name.xml")!!.readText()
            val entry = Regex("<option name=\"(co\\.anbora\\.labs\\.[a-z]+\\.[A-Z_]+)\">\\s*<value>\\s*<option name=\"FOREGROUND\" value=\"([0-9a-f]{6})\"/>")
            val colors = entry.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
            val axon = colors.filterKeys { it.startsWith("co.anbora.labs.axon.") }
            assertTrue("$name: $axon", axon.keys.containsAll(listOf("FUNCTION_DECLARATION", "PARAMETER").map { "co.anbora.labs.axon.$it" }))
            // Each Axon entry is a key that exists.
            assertEquals(name, emptySet<String>(), axon.keys - AxonColors.entries.map { it.textAttributesKey.externalName }.toSet())
            for (key in axon.keys + fallbacks) {
                val ratio = contrast(Color.decode("#" + colors.getValue(key)), Color.decode("#$background"))
                assertTrue("$name $key contrast $ratio", ratio >= 4.5)
            }
            val five = listOf("axon.FUNCTION_DECLARATION", "axon.PARAMETER", "fantom.LOCAL_VARIABLE", "fantom.FUNCTION_CALL")
                .map { colors.getValue("co.anbora.labs.$it") }
            assertEquals("$name: $five", 4, five.toSet().size)
        }
    }

    /** With no entry of their own, the other keys take the look of the Fantom key. */
    fun testFallbackKeysFollowFantom() {
        // The keys with an entry in `colorSchemes/Fantom<scheme>.xml`.
        val own = mapOf(
            "Default" to setOf(AxonColors.FUNCTION_DECLARATION, AxonColors.PARAMETER),
            "Darcula" to setOf(
                AxonColors.FUNCTION_DECLARATION, AxonColors.PARAMETER,
                AxonColors.TAG_NAME, AxonColors.DEFCOMP_CELL, AxonColors.CONSTANT,
            ),
        )
        for (name in listOf("Default", "Darcula")) {
            val s = scheme(name)
            for (color in own.getValue(name)) {
                val fallback = color.textAttributesKey.fallbackAttributeKey!!
                assertFalse("$name ${color.name}", s.getAttributes(fallback) == s.look(color))
            }
            for (color in AxonColors.entries - own.getValue(name)) {
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
