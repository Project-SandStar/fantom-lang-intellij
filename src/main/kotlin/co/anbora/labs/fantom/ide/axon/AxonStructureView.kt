package co.anbora.labs.fantom.ide.axon

import co.anbora.labs.fantom.lang.axon.AxonFile
import co.anbora.labs.fantom.lang.axon.psi.AxonCellDef
import co.anbora.labs.fantom.lang.axon.psi.AxonDefExpr
import co.anbora.labs.fantom.lang.axon.psi.AxonDefcompExpr
import co.anbora.labs.fantom.lang.axon.psi.AxonDoExpr
import co.anbora.labs.fantom.lang.axon.psi.AxonExpr
import co.anbora.labs.fantom.lang.axon.psi.AxonLambdaExpr
import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import javax.swing.Icon

/**
 * Structure view for Axon: functions (`name: (a, b) => ...`), components
 * (`name: defcomp ... end`) and the cells of a component. A function with no name at
 * the top of a file, as in a trio `src:` value, shows its inner items in its place.
 */
class AxonStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        if (psiFile !is AxonFile) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel =
                AxonStructureViewModel(psiFile, editor)
        }
    }
}

class AxonStructureViewModel(file: PsiFile, editor: Editor?) :
    StructureViewModelBase(file, editor, AxonStructureViewElement(file)),
    StructureViewModel.ElementInfoProvider {

    init {
        withSuitableClasses(AxonFile::class.java, AxonDefExpr::class.java, AxonCellDef::class.java)
    }

    override fun getSorters(): Array<Sorter> = arrayOf(Sorter.ALPHA_SORTER)

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false

    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean = element.value is AxonCellDef
}

class AxonStructureViewElement(private val element: NavigatablePsiElement) :
    StructureViewTreeElement, SortableTreeElement {

    override fun getValue(): Any = element

    override fun navigate(requestFocus: Boolean) = element.navigate(requestFocus)

    override fun canNavigate(): Boolean = element.canNavigate()

    override fun canNavigateToSource(): Boolean = element.canNavigateToSource()

    override fun getAlphaSortKey(): String = element.name.orEmpty()

    override fun getPresentation(): ItemPresentation = when (element) {
        is AxonDefExpr -> PresentationData(signature(element), null, icon(element), null)
        is AxonCellDef -> PresentationData(element.name, null, AllIcons.Nodes.Property, null)
        else -> element.presentation ?: PresentationData(element.name, null, null, null)
    }

    override fun getChildren(): Array<TreeElement> {
        val out = ArrayList<TreeElement>()
        when (element) {
            is AxonFile -> for (child in element.children) addItem(child, out, flatten = true)
            is AxonDefExpr -> addInner(element.expr, out)
        }
        return out.toTypedArray()
    }

    /** `name(a, b)` for a function, `name` for all others. */
    private fun signature(def: AxonDefExpr): String {
        val lambda = def.expr as? AxonLambdaExpr ?: return def.name.orEmpty()
        return def.name.orEmpty() + lambda.paramList.joinToString(", ", "(", ")") { it.name.orEmpty() }
    }

    private fun icon(def: AxonDefExpr): Icon =
        if (def.expr is AxonDefcompExpr) AllIcons.Nodes.Class else AllIcons.Nodes.Function

    private companion object {
        /** Adds [element] when it is a named function or component; a bare one adds its inner items when [flatten]. */
        fun addItem(element: PsiElement, out: MutableList<TreeElement>, flatten: Boolean) {
            if (element is AxonDefExpr) {
                val value = element.expr
                if (value is AxonLambdaExpr || value is AxonDefcompExpr) out.add(AxonStructureViewElement(element))
            } else if (flatten && (element is AxonLambdaExpr || element is AxonDefcompExpr)) {
                addInner(element as AxonExpr, out)
            }
        }

        /** The items in a function or component: cells, then the named functions of its `do` block. */
        fun addInner(value: AxonExpr?, out: MutableList<TreeElement>) {
            when (value) {
                is AxonLambdaExpr -> addInner(value.expr, out)
                is AxonDefcompExpr -> {
                    for (cell in value.cellDefList) out.add(AxonStructureViewElement(cell))
                    addInner(value.doExpr, out)
                }
                is AxonDoExpr -> for (stmt in value.exprList) addItem(stmt, out, flatten = false)
                else -> Unit
            }
        }
    }
}
