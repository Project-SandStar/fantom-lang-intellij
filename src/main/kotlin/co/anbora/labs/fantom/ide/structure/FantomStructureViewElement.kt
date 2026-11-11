package co.anbora.labs.fantom.ide.structure

import co.anbora.labs.fantom.ide.icons.FantomIcons
import co.anbora.labs.fantom.ide.presentation.FantomPresentation
import co.anbora.labs.fantom.lang.core.psi.FantomClassDef
import co.anbora.labs.fantom.lang.core.psi.FantomEnumDef
import co.anbora.labs.fantom.lang.core.psi.FantomFacetDef
import co.anbora.labs.fantom.lang.core.psi.FantomFile
import co.anbora.labs.fantom.lang.core.psi.FantomMixinDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomSlotDefs
import co.anbora.labs.fantom.lang.core.psi.FantomTypeDef
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/** One node of the Fantom structure tree. Navigation goes to the wrapped PSI element. */
class FantomStructureViewElement(private val element: PsiElement) :
    StructureViewTreeElement, SortableTreeElement {

    override fun getValue(): Any = element

    override fun navigate(requestFocus: Boolean) {
        (element as? NavigationItem)?.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = (element as? NavigationItem)?.canNavigate() ?: false

    override fun canNavigateToSource(): Boolean =
        (element as? NavigationItem)?.canNavigateToSource() ?: false

    override fun getAlphaSortKey(): String = (element as? FantomNamedElement)?.name ?: ""

    override fun getPresentation(): ItemPresentation = when (element) {
        is FantomNamedElement -> FantomPresentation.of(element)
        is FantomFile -> FantomPresentation.Simple(element.name, null, FantomIcons.FILE)
        else -> FantomPresentation.Simple(element.text.lineSequence().first(), null, null)
    }

    override fun getChildren(): Array<TreeElement> =
        childElements().map { FantomStructureViewElement(it) }.toTypedArray()

    private fun childElements(): List<PsiElement> = when (element) {
        is FantomFile -> PsiTreeUtil.getChildrenOfTypeAsList(element, FantomTypeDef::class.java)
            .mapNotNull { it.classDef ?: it.mixinDef ?: it.facetDef ?: it.enumDef }
        is FantomClassDef -> slots(element.highOrderBlock?.slotDefs)
        is FantomMixinDef -> slots(element.highOrderBlock?.slotDefs)
        is FantomFacetDef -> slots(element.highOrderBlock?.slotDefs)
        is FantomEnumDef -> {
            val block = element.enumBlock
            val values: List<PsiElement> = block?.enumValDefs?.enumValDefList.orEmpty()
            values + slots(block?.slotDefs)
        }
        else -> emptyList()
    }

    /** Fields, ctors and methods in source order. `static {}` blocks are skipped. */
    private fun slots(slotDefs: FantomSlotDefs?): List<PsiElement> =
        slotDefs?.slotDefList.orEmpty().mapNotNull { it.fieldDef ?: it.ctorDef ?: it.methodDef }
}
