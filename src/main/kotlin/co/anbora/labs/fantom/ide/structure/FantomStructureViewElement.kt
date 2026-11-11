package co.anbora.labs.fantom.ide.structure

import co.anbora.labs.fantom.ide.icons.FantomIcons
import co.anbora.labs.fantom.lang.core.psi.FantomClassDef
import co.anbora.labs.fantom.lang.core.psi.FantomCtorDef
import co.anbora.labs.fantom.lang.core.psi.FantomEnumDef
import co.anbora.labs.fantom.lang.core.psi.FantomEnumValDef
import co.anbora.labs.fantom.lang.core.psi.FantomFacetDef
import co.anbora.labs.fantom.lang.core.psi.FantomFieldDef
import co.anbora.labs.fantom.lang.core.psi.FantomFile
import co.anbora.labs.fantom.lang.core.psi.FantomItemParameterList
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomMixinDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomSlotDefs
import co.anbora.labs.fantom.lang.core.psi.FantomType
import co.anbora.labs.fantom.lang.core.psi.FantomTypeDef
import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.Icon

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

    override fun getPresentation(): ItemPresentation = FantomPresentation(presentableText(), icon())

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

    private fun presentableText(): String {
        val name = (element as? FantomNamedElement)?.name
        return when (element) {
            is FantomFile -> element.name
            is FantomFieldDef -> "${name ?: "?"}: ${typeText(element.type)}"
            is FantomMethodDef ->
                "${name ?: "?"}(${paramTypes(element.itemParameterList)}): ${typeText(element.type)}"
            is FantomCtorDef -> "new ${name ?: "?"}(${paramTypes(element.itemParameterList)})"
            else -> name ?: element.text.lineSequence().first()
        }
    }

    private fun paramTypes(list: FantomItemParameterList?): String =
        list?.paramsList.orEmpty().mapNotNull { it.param }.joinToString(", ") { typeText(it.type) }

    private fun typeText(type: FantomType?): String = type?.text?.trim() ?: "?"

    private fun icon(): Icon? = when (element) {
        is FantomFile -> FantomIcons.FILE
        is FantomClassDef -> AllIcons.Nodes.Class
        is FantomMixinDef -> AllIcons.Nodes.Interface
        is FantomEnumDef -> AllIcons.Nodes.Enum
        is FantomFacetDef -> AllIcons.Nodes.Annotationtype
        is FantomEnumValDef, is FantomFieldDef -> AllIcons.Nodes.Field
        is FantomCtorDef, is FantomMethodDef -> AllIcons.Nodes.Method
        else -> null
    }

    private class FantomPresentation(private val text: String, private val icon: Icon?) : ItemPresentation {
        override fun getPresentableText(): String = text
        override fun getLocationString(): String? = null
        override fun getIcon(unused: Boolean): Icon? = icon
    }
}
