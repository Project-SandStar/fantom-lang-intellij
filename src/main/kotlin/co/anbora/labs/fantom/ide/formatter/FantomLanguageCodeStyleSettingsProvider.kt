package co.anbora.labs.fantom.ide.formatter

import co.anbora.labs.fantom.lang.FantomLanguage
import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.application.options.IndentOptionsEditor
import com.intellij.application.options.SmartIndentOptionsEditor
import com.intellij.application.options.TabbedLanguageCodeStylePanel
import com.intellij.lang.Language
import com.intellij.psi.codeStyle.CodeStyleConfigurable
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsCustomizable
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider

/** Code style page for Fantom: indent options and the blank-line limit. Defaults match the `sys` pod. */
class FantomLanguageCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {

    override fun getLanguage(): Language = FantomLanguage

    override fun customizeDefaults(commonSettings: CommonCodeStyleSettings, indentOptions: CommonCodeStyleSettings.IndentOptions) {
        indentOptions.INDENT_SIZE = 2
        indentOptions.CONTINUATION_INDENT_SIZE = 2
        indentOptions.TAB_SIZE = 2
        indentOptions.USE_TAB_CHARACTER = false
        commonSettings.KEEP_BLANK_LINES_IN_CODE = 3
        commonSettings.KEEP_BLANK_LINES_IN_DECLARATIONS = 3
    }

    override fun customizeSettings(consumer: CodeStyleSettingsCustomizable, settingsType: SettingsType) {
        if (settingsType == SettingsType.BLANK_LINES_SETTINGS) {
            consumer.showStandardOptions("KEEP_BLANK_LINES_IN_CODE", "KEEP_BLANK_LINES_IN_DECLARATIONS")
        }
    }

    override fun getIndentOptionsEditor(): IndentOptionsEditor = SmartIndentOptionsEditor()

    override fun getCodeSample(settingsType: SettingsType): String = SAMPLE

    override fun createConfigurable(baseSettings: CodeStyleSettings, modelSettings: CodeStyleSettings): CodeStyleConfigurable {
        return object : CodeStyleAbstractConfigurable(baseSettings, modelSettings, FantomLanguage.LANGUAGE_NAME) {
            override fun createPanel(settings: CodeStyleSettings): CodeStyleAbstractPanel =
                FantomCodeStylePanel(currentSettings, settings)
        }
    }

    private class FantomCodeStylePanel(current: CodeStyleSettings, model: CodeStyleSettings) :
        TabbedLanguageCodeStylePanel(FantomLanguage, current, model) {
        override fun initTabs(settings: CodeStyleSettings) {
            addIndentOptionsTab(settings)
            addBlankLinesTab(settings)
        }
    }

    companion object {
        private val SAMPLE = """
            using concurrent

            **
            ** A counter
            **
            class Counter
            {
              Int count := 0

              Void add(Int[] items)
              {
                items.each |item|
                {
                  if (item > 0)
                    count += item
                  else
                    echo("skip")
                }
                items.each |item| { echo(item) }
              }
            }
        """.trimIndent()
    }
}
