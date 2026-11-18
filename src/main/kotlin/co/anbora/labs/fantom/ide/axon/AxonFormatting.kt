package co.anbora.labs.fantom.ide.axon

import co.anbora.labs.fantom.lang.axon.AxonLanguage
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes
import co.anbora.labs.fantom.lang.axon.psi.AxonTypes
import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.application.options.IndentOptionsEditor
import com.intellij.application.options.SmartIndentOptionsEditor
import com.intellij.application.options.TabbedLanguageCodeStylePanel
import com.intellij.formatting.Alignment
import com.intellij.formatting.Block
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.FormattingModelProvider
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.formatting.Wrap
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CodeStyleConfigurable
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsCustomizable
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import com.intellij.psi.formatter.common.AbstractBlock
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

/**
 * Formatting model for Axon. Like the Fantom one it only sets the indent of lines: the
 * lines of a `do ... end` or `defcomp ... end` body, and the lines of a list, dict or
 * argument list that puts its first item on a new line. Two-space indent.
 *
 * It never adds or removes a line break and never changes the spacing in a line. Lines
 * that continue an expression keep their indent, and so does a comment at column 0.
 */
class AxonFormattingModelBuilder : FormattingModelBuilder {
    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val file = formattingContext.containingFile
        val settings = formattingContext.codeStyleSettings
        val context = AxonFormatContext(file.text, settings.getCommonSettings(AxonLanguage).KEEP_BLANK_LINES_IN_CODE)
        val root = AxonBlock(file.node, Indent.getNoneIndent(), context)
        return FormattingModelProvider.createFormattingModelForPsiFile(file, root, settings)
    }
}

/** Shared read-only state for one formatting run. */
class AxonFormatContext(val text: CharSequence, val keepBlankLines: Int)

class AxonBlock(
    node: ASTNode,
    private val indent: Indent,
    private val context: AxonFormatContext
) : AbstractBlock(node, null as Wrap?, null as Alignment?) {

    /** True when this node sets the indent of its lines. */
    private val managed: Boolean = managesLayout(node, context.text)

    override fun getIndent(): Indent = indent

    override fun isLeaf(): Boolean = node.firstChildNode == null

    override fun buildChildren(): List<Block> {
        val out = ArrayList<Block>()
        var child = node.firstChildNode
        while (child != null) {
            if (child.elementType != TokenType.WHITE_SPACE && child.textLength > 0) {
                out.add(AxonBlock(child, childIndent(child.elementType), context))
            }
            child = child.treeNext
        }
        return out
    }

    private fun childIndent(child: IElementType): Indent =
        if (managed && node.elementType in BODIES && child !in BODY_EDGES) Indent.getNormalIndent() else Indent.getNoneIndent()

    override fun getSpacing(child1: Block?, child2: Block): Spacing? {
        if (child1 !is AxonBlock || child2 !is AxonBlock || !managed) return READ_ONLY
        val start = child2.textRange.startOffset
        val gap = context.text.subSequence(child1.textRange.endOffset, start)
        if (gap.indexOf('\n') < 0) return READ_ONLY
        // A comment at column 0 is a banner or commented-out code: it keeps its place.
        if (child2.node.elementType in AxonTokenTypes.COMMENTS && context.text[start - 1] == '\n') return READ_ONLY
        return Spacing.createSpacing(0, 0, 0, true, context.keepBlankLines)
    }

    override fun getChildAttributes(newChildIndex: Int): ChildAttributes {
        if (node.elementType !in BODIES) return ChildAttributes(Indent.getNoneIndent(), null)
        val blocks = subBlocks
        val closing = newChildIndex < blocks.size && (blocks[newChildIndex] as AxonBlock).node.elementType in CLOSERS
        val afterOpen = newChildIndex > 0 && (blocks[newChildIndex - 1] as AxonBlock).node.elementType in OPENERS
        return ChildAttributes(if (closing && !afterOpen) Indent.getNoneIndent() else Indent.getNormalIndent(), null)
    }

    /** A body with no closing token yet: Enter at its end stays in the body. */
    override fun isIncomplete(): Boolean =
        node.elementType in BODIES && node.lastChildNode?.elementType !in CLOSERS

    private companion object {
        val READ_ONLY: Spacing = Spacing.getReadOnlySpacing()

        val BLOCK_BODIES = TokenSet.create(AxonTypes.DO_EXPR, AxonTypes.DEFCOMP_EXPR)
        val BRACKET_BODIES = TokenSet.create(AxonTypes.LIST_EXPR, AxonTypes.DICT_EXPR, AxonTypes.ARG_LIST)
        val BODIES = TokenSet.orSet(BLOCK_BODIES, BRACKET_BODIES)
        val OPENERS = TokenSet.create(
            AxonTokenTypes.DO, AxonTokenTypes.DEFCOMP,
            AxonTokenTypes.LBRACKET, AxonTokenTypes.LBRACE, AxonTokenTypes.LPAREN
        )
        val CLOSERS = TokenSet.create(
            AxonTokenTypes.END, AxonTokenTypes.RBRACKET, AxonTokenTypes.RBRACE, AxonTokenTypes.RPAREN
        )
        val BODY_EDGES = TokenSet.orSet(OPENERS, CLOSERS)

        /**
         * The file and `do`/`defcomp` bodies always set the indent of their lines. A list,
         * dict or argument list does so only when its first item starts a new line, so
         * `[1, 2,` with hand-aligned items below it stays as written.
         */
        fun managesLayout(node: ASTNode, text: CharSequence): Boolean {
            val type = node.elementType
            if (node.treeParent == null || type in BLOCK_BODIES) return true
            if (type !in BRACKET_BODIES) return false
            var i = node.startOffset + 1
            val end = node.startOffset + node.textLength
            while (i < end && i < text.length) {
                val c = text[i]
                if (c == '\n') return true
                if (!c.isWhitespace()) return false
                i++
            }
            return false
        }
    }
}

/** Code style defaults for Axon: two-space indent, no tabs. */
class AxonLanguageCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {

    override fun getLanguage(): Language = AxonLanguage

    override fun customizeDefaults(commonSettings: CommonCodeStyleSettings, indentOptions: CommonCodeStyleSettings.IndentOptions) {
        indentOptions.INDENT_SIZE = 2
        indentOptions.CONTINUATION_INDENT_SIZE = 2
        indentOptions.TAB_SIZE = 2
        indentOptions.USE_TAB_CHARACTER = false
        commonSettings.KEEP_BLANK_LINES_IN_CODE = 3
    }

    override fun customizeSettings(consumer: CodeStyleSettingsCustomizable, settingsType: SettingsType) {
        if (settingsType == SettingsType.BLANK_LINES_SETTINGS) {
            consumer.showStandardOptions("KEEP_BLANK_LINES_IN_CODE")
        }
    }

    override fun getIndentOptionsEditor(): IndentOptionsEditor = SmartIndentOptionsEditor()

    override fun getCodeSample(settingsType: SettingsType): String = SAMPLE

    override fun createConfigurable(baseSettings: CodeStyleSettings, modelSettings: CodeStyleSettings): CodeStyleConfigurable {
        return object : CodeStyleAbstractConfigurable(baseSettings, modelSettings, AxonLanguage.displayName) {
            override fun createPanel(settings: CodeStyleSettings): CodeStyleAbstractPanel =
                AxonCodeStylePanel(currentSettings, settings)
        }
    }

    private class AxonCodeStylePanel(current: CodeStyleSettings, model: CodeStyleSettings) :
        TabbedLanguageCodeStylePanel(AxonLanguage, current, model) {
        override fun initTabs(settings: CodeStyleSettings) {
            addIndentOptionsTab(settings)
            addBlankLinesTab(settings)
        }
    }

    private companion object {
        val SAMPLE = """
            // Daily average of a point
            calcDailyAvg: (point, dates: lastWeek()) => do
              his: point.hisRead(dates)
              if (his.isEmpty) return null


              tags: {
                dis: "Daily average",
                unit: point->unit,
                -temp
              }
              his.hisRollup(avg, 1day).addMeta(tags)
            end
        """.trimIndent()
    }
}
