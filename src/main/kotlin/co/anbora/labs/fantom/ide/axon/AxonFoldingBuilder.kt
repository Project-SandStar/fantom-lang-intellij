package co.anbora.labs.fantom.ide.axon

import co.anbora.labs.fantom.lang.axon.AxonTokenTypes
import co.anbora.labs.fantom.lang.axon.psi.AxonDefcompExpr
import co.anbora.labs.fantom.lang.axon.psi.AxonDictExpr
import co.anbora.labs.fantom.lang.axon.psi.AxonDoExpr
import co.anbora.labs.fantom.lang.axon.psi.AxonListExpr
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil

/**
 * Folds `do ... end`, `defcomp ... end`, lists, dicts, block comments and runs of
 * line comments. A region folds only when it covers more than one line.
 */
class AxonFoldingBuilder : FoldingBuilderEx(), DumbAware {

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val out = ArrayList<FoldingDescriptor>()
        var lastCommentEnd = -1
        for (element in PsiTreeUtil.collectElements(root) { true }) {
            when (element) {
                is AxonDoExpr, is AxonDefcompExpr, is AxonListExpr, is AxonDictExpr ->
                    add(out, element.node, element.textRange, document)
                is PsiComment -> {
                    if (element.textRange.startOffset < lastCommentEnd) continue
                    val range = if (element.node.elementType == AxonTokenTypes.LINE_COMMENT) lineCommentRun(element) else element.textRange
                    lastCommentEnd = range.endOffset
                    add(out, element.node, range, document)
                }
            }
        }
        return out.toTypedArray()
    }

    private fun add(out: MutableList<FoldingDescriptor>, node: ASTNode, range: TextRange, document: Document) {
        if (range.length < 2 || range.endOffset > document.textLength) return
        if (document.getLineNumber(range.startOffset) == document.getLineNumber(range.endOffset)) return
        out.add(FoldingDescriptor(node, range))
    }

    /** The range from [first] to the last line comment that follows it with no blank line and no code between. */
    private fun lineCommentRun(first: PsiComment): TextRange {
        var last: PsiElement = first
        var next = first.nextSibling
        while (next != null) {
            if (next is PsiWhiteSpace) {
                if (next.text.count { it == '\n' } > 1) break
            } else if (next is PsiComment && next.node.elementType == AxonTokenTypes.LINE_COMMENT) {
                last = next
            } else {
                break
            }
            next = next.nextSibling
        }
        return TextRange(first.textRange.startOffset, last.textRange.endOffset)
    }

    override fun getPlaceholderText(node: ASTNode): String = when (node.psi) {
        is AxonDoExpr -> "do...end"
        is AxonDefcompExpr -> "defcomp...end"
        is AxonListExpr -> "[...]"
        is AxonDictExpr -> "{...}"
        else -> if (node.elementType == AxonTokenTypes.LINE_COMMENT) "//..." else "/*...*/"
    }

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}
