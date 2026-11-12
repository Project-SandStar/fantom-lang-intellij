package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.color.FantomColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Color
import java.awt.Font

/** The shipped colorSchemes/Fantom*.xml files must reach the bundled schemes. */
class FantomSchemeDefaultsTest : BasePlatformTestCase() {

    private fun scheme(name: String) =
        // Bundled schemes are named "_@user_Darcula" and the like in tests.
        EditorColorsManager.getInstance().allSchemes.firstOrNull { it.name.endsWith(name) || it.displayName == name }
            ?: error("no scheme $name; have " + EditorColorsManager.getInstance().allSchemes.map { it.name })

    fun testDarkSchemesColourOperatorsAndLocals() {
        // Only Default and Darcula are bundled in the test platform; Dark/Light share the same files.
        for (name in listOf("Darcula")) {
            val s = scheme(name)
            val op = s.getAttributes(FantomColors.OPERATOR.textAttributesKey)
            assertEquals("$name OPERATOR", Color.decode("#CC7832"), op.foregroundColor)
            assertEquals("$name OPERATOR bold", Font.BOLD, op.fontType)
            assertEquals("$name BRACES", Color.decode("#E8BF6A"), s.getAttributes(FantomColors.BRACES.textAttributesKey).foregroundColor)
            assertEquals("$name LOCAL", Color.decode("#9CDCFE"), s.getAttributes(FantomColors.LOCAL_VARIABLE.textAttributesKey).foregroundColor)
            assertEquals("$name CALL", Color.decode("#DCDCAA"), s.getAttributes(FantomColors.FUNCTION_CALL.textAttributesKey).foregroundColor)
        }
    }

    fun testLightSchemesColourOperatorsAndLocals() {
        for (name in listOf("Default")) {
            val s = scheme(name)
            assertEquals("$name OPERATOR", Color.decode("#0033B3"), s.getAttributes(FantomColors.OPERATOR.textAttributesKey).foregroundColor)
            assertEquals("$name LOCAL", Color.decode("#1E66A5"), s.getAttributes(FantomColors.LOCAL_VARIABLE.textAttributesKey).foregroundColor)
        }
    }

    fun testKeysStillInheritFromPlatformKeys() {
        assertEquals("DEFAULT_OPERATION_SIGN", FantomColors.OPERATOR.textAttributesKey.fallbackAttributeKey?.externalName)
        assertEquals("DEFAULT_LOCAL_VARIABLE", FantomColors.LOCAL_VARIABLE.textAttributesKey.fallbackAttributeKey?.externalName)
    }
}
