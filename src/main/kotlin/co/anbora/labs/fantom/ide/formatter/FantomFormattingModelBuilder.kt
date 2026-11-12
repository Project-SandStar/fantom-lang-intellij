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
 * Formatting model for Fantom. It follows the house style of the `sys` and `util` pods and
 * does one thing: it sets the indent of lines that start a statement, a slot, a type, a
 * brace or a comment. Two-space indent, Allman braces for types, slots and control
 * statements, `{` at the statement column for closures and it-blocks.
 *
 * It never adds or removes a line break, and it never changes spacing inside a line: the
 * pods write `i:=0`, `s[0..<n]`, `a + b` and hand-aligned declarations side by side. Lines
 * that continue an expression keep their indent. A `//` or `/* */` comment at column 0 is a
 * banner and keeps its place. See `docs/engineering/formatter-work.md` for the corpus survey.
 */
class FantomFormattingModelBuilder : FormattingModelBuilder {
    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val file = formattingContext.containingFile
        val settings = formattingContext.codeStyleSettings
        val context = FantomFormatContext(file.text, settings, settings.getCommonSettings(FantomLanguage))
        val root = FantomBlock(file.node, Indent.getNoneIndent(), context)
        return FormattingModelProvider.createFormattingModelForPsiFile(file, root, settings)
    }
}

/** Shared read-only state for one formatting run. */
class FantomFormatContext(
    val text: CharSequence,
    val settings: CodeStyleSettings,
    val common: CommonCodeStyleSettings
)
