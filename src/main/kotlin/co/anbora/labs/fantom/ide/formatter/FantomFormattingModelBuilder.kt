package co.anbora.labs.fantom.ide.formatter

import co.anbora.labs.fantom.lang.FantomLanguage
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.FormattingModelProvider
import com.intellij.formatting.Indent
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CommonCodeStyleSettings

/**
 * Formatting model for Fantom. The rules follow the house style of the `sys` and `util`
 * pods: two-space indent, Allman braces for types, slots and control statements, `{` on the
 * same line for closures and it-blocks, one space around operators, `, ` in lists, no space
 * before `(` in a call. Line breaks are never added or removed; runs of more than one space
 * inside a line are kept, because `sys` aligns some initializers by hand.
 */
class FantomFormattingModelBuilder : FormattingModelBuilder {
    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val file = formattingContext.containingFile
        val settings = formattingContext.codeStyleSettings
        val context = FantomFormatContext(file.text, settings, settings.getCommonSettings(FantomLanguage))
        val root = FantomBlock(file.node, null, null, Indent.getNoneIndent(), context)
        return FormattingModelProvider.createFormattingModelForPsiFile(file, root, settings)
    }
}

/** Shared read-only state for one formatting run. */
class FantomFormatContext(
    val text: CharSequence,
    val settings: CodeStyleSettings,
    val common: CommonCodeStyleSettings
)
