package co.anbora.labs.fantom.ide.formatter

import co.anbora.labs.fantom.lang.FantomParserDefinition.Companion.C_STYLE_COMMENT
import co.anbora.labs.fantom.lang.FantomParserDefinition.Companion.END_OF_LINE_COMMENT
import co.anbora.labs.fantom.lang.core.FANTOM_COMMENTS
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.BLOCK
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.BREAK_STM
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.CASE_BLOCK
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.CONTINUE_STM
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.EXPR_STMT
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.LOCAL_DEF
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.RETURN_STM
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.THROW_STM
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.CASE_LABEL
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.CATCH
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.CATCH_STM
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.CLASS
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.CLASS_DEF
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.CLOSURE
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.COLON
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.CTOR_DEF
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.DEFAULT
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.DEFAULT_BLOCK
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.DOC
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.ELSE
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.ENUM_BLOCK
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.ENUM_DEF
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.ENUM_VAL_DEF
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.ENUM_VAL_DEFS
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.FACETS
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.FACET_DEF
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.FACET_VAL
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.FACET_VALS
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.FIELD_DEF
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.FIELD_DEF_BLOCK
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.FIELD_GETTER
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.FIELD_SETTER
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.FINALLY
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.FINALLY_STM
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.FOR_STM
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.HIGH_ORDER_BLOCK
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.IF_STM
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.IT_BLOCK
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.LBRACE
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.METHOD_DEF
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.MIXIN
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.MIXIN_DEF
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.RBRACE
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.SLOT_DEF
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.SLOT_DEFS
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.STATEMENT_BLOCK
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.STATIC_INIT
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.STMT
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.STMTS
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.SWITCH_STM
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.SWITCH_STM_BLOCK
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.TRY_STM
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.TYPE_DEF
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.TYPE_HEADER
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.USING_DEF
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.WHILE_STM
import com.intellij.formatting.Alignment
import com.intellij.formatting.Block
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.formatting.Wrap
import com.intellij.lang.ASTNode
import com.intellij.psi.TokenType
import com.intellij.psi.formatter.common.AbstractBlock
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

/**
 * One formatting block per AST node. Only indent is managed; see
 * [FantomFormattingModelBuilder] for the rules.
 *
 * Indent of a child is relative to the nearest ancestor block that starts a line. That
 * gives the Fantom closure shape for free: in `list.each |v| {` the brace does not start a
 * line, so the body indents from the statement, and `}` goes back to the statement column.
 *
 * `STMTS` and `SLOT_DEFS` are spliced into their parent. Otherwise a column-0 banner
 * comment that happens to be the first child of such a list would become the anchor of
 * every slot or statement after it.
 */
class FantomBlock(
    node: ASTNode,
    private val indent: Indent,
    private val context: FantomFormatContext
) : AbstractBlock(node, null as Wrap?, null as Alignment?) {

    override fun getIndent(): Indent = indent

    override fun isLeaf(): Boolean = subBlocks.isEmpty()

    override fun buildChildren(): List<Block> {
        val out = ArrayList<Block>()
        addChildren(node, out)
        return out
    }

    private fun addChildren(parent: ASTNode, out: MutableList<Block>) {
        var child = parent.firstChildNode
        while (child != null) {
            val type = child.elementType
            when {
                type == TokenType.WHITE_SPACE || child.textLength == 0 -> Unit
                type in SPLICED -> addChildren(child, out)
                else -> out.add(FantomBlock(child, childIndent(node.elementType, child), context))
            }
            child = child.treeNext
        }
    }

    override fun getSpacing(child1: Block?, child2: Block): Spacing? {
        if (child1 !is FantomBlock || child2 !is FantomBlock) return READ_ONLY
        if (!managesLayout(node.elementType)) return READ_ONLY
        val gap = context.text.subSequence(child1.textRange.endOffset, child2.textRange.startOffset)
        if (gap.indexOf('\n') < 0) return READ_ONLY
        val right = child2.node
        if (isBannerComment(right, child2.textRange.startOffset)) return READ_ONLY
        val left = child1.node.elementType
        if (right.elementType !in LINE_STARTERS && left != FACETS && left != DOC) return READ_ONLY
        return Spacing.createSpacing(0, 0, 0, true, context.common.KEEP_BLANK_LINES_IN_CODE)
    }

    /** A `//` or `/* */` comment at column 0 is a section banner; it keeps its place. */
    private fun isBannerComment(node: ASTNode, start: Int): Boolean {
        if (node.elementType != END_OF_LINE_COMMENT && node.elementType != C_STYLE_COMMENT) return false
        return start == 0 || context.text[start - 1] == '\n'
    }

    override fun getChildAttributes(newChildIndex: Int): ChildAttributes {
        val type = node.elementType
        val blocks = subBlocks
        if (type in BRACE_BODIES) {
            val closing = newChildIndex >= blocks.size || (blocks[newChildIndex] as FantomBlock).node.elementType == RBRACE
            val afterOpen = newChildIndex > 0 && (blocks[newChildIndex - 1] as FantomBlock).node.elementType == LBRACE
            return ChildAttributes(if (closing && !afterOpen) Indent.getNoneIndent() else Indent.getNormalIndent(), null)
        }
        if (type == CASE_BLOCK || type == DEFAULT_BLOCK) return ChildAttributes(Indent.getNormalIndent(), null)
        if (type in CONTROL_STMS) return ChildAttributes(Indent.getNormalIndent(), null)
        return ChildAttributes(Indent.getNoneIndent(), null)
    }

    companion object {
        private val READ_ONLY: Spacing = Spacing.getReadOnlySpacing()

        private val SPLICED = TokenSet.create(STMTS, SLOT_DEFS)

        /** Nodes whose children sit between `{` and `}` one level deeper. */
        private val BRACE_BODIES = TokenSet.create(
            HIGH_ORDER_BLOCK, ENUM_BLOCK, STATEMENT_BLOCK, SWITCH_STM_BLOCK, FIELD_DEF_BLOCK, FACET_VALS
        )

        private val CONTROL_STMS = TokenSet.create(IF_STM, WHILE_STM, FOR_STM, TRY_STM, CATCH_STM, FINALLY_STM)

        /**
         * Parents whose line breaks the formatter owns. Inside any other parent (an
         * expression, a parameter list, a type) a line break is a continuation and keeps
         * its indent.
         */
        private val STRUCTURAL = TokenSet.create(
            TYPE_DEF, CLASS_DEF, MIXIN_DEF, FACET_DEF, ENUM_DEF, TYPE_HEADER, USING_DEF,
            HIGH_ORDER_BLOCK, ENUM_BLOCK, ENUM_VAL_DEFS, SLOT_DEF, METHOD_DEF, FIELD_DEF, CTOR_DEF, STATIC_INIT,
            FIELD_DEF_BLOCK, FIELD_GETTER, FIELD_SETTER, FACET_VALS,
            STATEMENT_BLOCK, STMT, BLOCK, IF_STM, WHILE_STM, FOR_STM, TRY_STM, CATCH_STM, FINALLY_STM,
            SWITCH_STM, SWITCH_STM_BLOCK, CASE_BLOCK, DEFAULT_BLOCK, CLOSURE, IT_BLOCK
        )

        /** Children that may start a line whose indent the formatter sets. */
        private val LINE_STARTERS = TokenSet.orSet(
            TokenSet.create(
                LBRACE, RBRACE, ELSE, CATCH, FINALLY, CLASS, MIXIN, DEFAULT,
                STMT, BLOCK, STATEMENT_BLOCK, SLOT_DEF, METHOD_DEF, FIELD_DEF, CTOR_DEF, STATIC_INIT,
                IF_STM, WHILE_STM, FOR_STM, TRY_STM, SWITCH_STM, RETURN_STM, THROW_STM, BREAK_STM, CONTINUE_STM,
                EXPR_STMT, LOCAL_DEF,
                TYPE_DEF, USING_DEF, CASE_BLOCK, CASE_LABEL, DEFAULT_BLOCK, CATCH_STM, FINALLY_STM,
                FIELD_GETTER, FIELD_SETTER, FIELD_DEF_BLOCK, ENUM_VAL_DEF, ENUM_VAL_DEFS, FACET_VAL,
                HIGH_ORDER_BLOCK, ENUM_BLOCK, SWITCH_STM_BLOCK, TYPE_HEADER, FACETS, DOC
            ),
            FANTOM_COMMENTS
        )

        private fun managesLayout(type: IElementType): Boolean = type is IFileElementType || type in STRUCTURAL

        private fun childIndent(parent: IElementType, child: ASTNode): Indent {
            val type = child.elementType
            return when {
                parent in BRACE_BODIES -> if (type == LBRACE || type == RBRACE) Indent.getNoneIndent() else Indent.getNormalIndent()
                parent == CASE_BLOCK -> if (type == CASE_LABEL) Indent.getNoneIndent() else Indent.getNormalIndent()
                parent == DEFAULT_BLOCK -> if (type == DEFAULT || type == COLON) Indent.getNoneIndent() else Indent.getNormalIndent()
                parent in CONTROL_STMS && type == BLOCK ->
                    if (startsWithBrace(child) || isElseIf(child)) Indent.getNoneIndent() else Indent.getNormalIndent()
                else -> Indent.getNoneIndent()
            }
        }

        private fun startsWithBrace(block: ASTNode): Boolean = firstChild(block)?.elementType == STATEMENT_BLOCK

        /** `else if (...)`: the nested `if` and its own `else` stay at the column of the first `if`. */
        private fun isElseIf(block: ASTNode): Boolean {
            if (firstChild(block)?.elementType != IF_STM) return false
            var prev = block.treePrev
            while (prev != null && (prev.elementType == TokenType.WHITE_SPACE || prev.textLength == 0)) prev = prev.treePrev
            return prev?.elementType == ELSE
        }

        private fun firstChild(node: ASTNode): ASTNode? {
            var c = node.firstChildNode
            while (c != null && (c.elementType == TokenType.WHITE_SPACE || c.textLength == 0)) c = c.treeNext
            return c
        }
    }
}
