package co.anbora.labs.fantom.lang.trio

import co.anbora.labs.fantom.lang.trio.psi.TrioDictVal
import co.anbora.labs.fantom.lang.trio.psi.TrioListVal
import co.anbora.labs.fantom.lang.trio.psi.TrioRecord
import co.anbora.labs.fantom.lang.trio.psi.TrioSrc
import co.anbora.labs.fantom.lang.trio.psi.TrioTag
import co.anbora.labs.fantom.lang.trio.psi.TrioTextBlock
import co.anbora.labs.fantom.lang.trio.psi.TrioTrioBlock
import co.anbora.labs.fantom.lang.trio.psi.TrioTypes
import co.anbora.labs.fantom.lang.trio.psi.TrioZincBlock
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.ASTNode
import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil

/** Folds each record of more than one line, and each value of more than one line. */
class TrioFoldingBuilder : FoldingBuilderEx(), DumbAware {

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val out = ArrayList<FoldingDescriptor>()
        for (record in PsiTreeUtil.findChildrenOfType(root, TrioRecord::class.java)) {
            add(out, record, record.textRange, document)
            for (tag in PsiTreeUtil.getChildrenOfTypeAsList(record, TrioTag::class.java)) {
                val value = (tag as TrioTagMixin).tagValue() ?: continue
                if (!isFoldable(value)) continue
                // A value that starts on a later line folds from the end of the tag line,
                // so `src:` stays in view and the fold mark is on that line.
                val tagLineEnd = document.getLineEndOffset(document.getLineNumber(tag.textRange.startOffset))
                val range = value.textRange
                add(out, value, TextRange(minOf(tagLineEnd, range.startOffset), range.endOffset), document)
            }
        }
        return out.toTypedArray()
    }

    private fun isFoldable(value: PsiElement): Boolean =
        value is TrioSrc || value is TrioTextBlock || value is TrioZincBlock || value is TrioTrioBlock ||
            value is TrioListVal || value is TrioDictVal

    private fun add(out: MutableList<FoldingDescriptor>, element: PsiElement, range: TextRange, document: Document) {
        if (range.length < 2) return
        if (document.getLineNumber(range.startOffset) == document.getLineNumber(range.endOffset)) return
        out.add(FoldingDescriptor(element.node, range))
    }

    override fun getPlaceholderText(node: ASTNode): String = when (val psi = node.psi) {
        is TrioRecordMixin -> psi.displayName()?.let { "$it ..." } ?: "..."
        is TrioListVal -> "[...]"
        is TrioDictVal -> "{...}"
        else -> " ..."
    }

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}

class TrioCommenter : Commenter {
    override fun getLineCommentPrefix(): String = "// "
    override fun getBlockCommentPrefix(): String? = null
    override fun getBlockCommentSuffix(): String? = null
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

class TrioBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> = PAIRS
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true
    override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int): Int = openingBraceOffset

    companion object {
        private val PAIRS = arrayOf(
            BracePair(TrioTypes.LBRACKET, TrioTypes.RBRACKET, false),
            BracePair(TrioTypes.LBRACE, TrioTypes.RBRACE, false),
            BracePair(TrioTypes.LPAREN, TrioTypes.RPAREN, false),
        )
    }
}

/** One node for each record, named by its `name`, `dis` or `id` tag; the tags are its children. */
class TrioStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        if (psiFile !is TrioFile) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel = TrioStructureViewModel(psiFile, editor)
        }
    }
}

class TrioStructureViewModel(file: PsiFile, editor: Editor?) :
    StructureViewModelBase(file, editor, TrioStructureViewElement(file)), StructureViewModel.ElementInfoProvider {

    init {
        withSuitableClasses(TrioRecord::class.java, TrioTag::class.java)
    }

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false
    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean = element.value is TrioTag
}

class TrioStructureViewElement(private val element: PsiElement) : StructureViewTreeElement {
    override fun getValue(): Any = element

    override fun getPresentation(): ItemPresentation =
        (element as? NavigationItem)?.presentation ?: object : ItemPresentation {
            override fun getPresentableText(): String = element.text
            override fun getIcon(unused: Boolean) = null
        }

    override fun getChildren(): Array<TreeElement> = when (element) {
        is TrioFile -> element.records().map { TrioStructureViewElement(it) }.toTypedArray()
        is TrioRecordMixin -> element.tags().map { TrioStructureViewElement(it) }.toTypedArray()
        else -> TreeElement.EMPTY_ARRAY
    }

    override fun navigate(requestFocus: Boolean) {
        (element as? NavigationItem)?.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = (element as? NavigationItem)?.canNavigate() == true
    override fun canNavigateToSource(): Boolean = (element as? NavigationItem)?.canNavigateToSource() == true
}
