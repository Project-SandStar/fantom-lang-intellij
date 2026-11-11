package co.anbora.labs.fantom.ide.structure

import co.anbora.labs.fantom.lang.core.psi.FantomClassDef
import co.anbora.labs.fantom.lang.core.psi.FantomCtorDef
import co.anbora.labs.fantom.lang.core.psi.FantomEnumDef
import co.anbora.labs.fantom.lang.core.psi.FantomEnumValDef
import co.anbora.labs.fantom.lang.core.psi.FantomFacetDef
import co.anbora.labs.fantom.lang.core.psi.FantomFieldDef
import co.anbora.labs.fantom.lang.core.psi.FantomFile
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomMixinDef
import com.intellij.ide.structureView.StructureViewModel.ElementInfoProvider
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile

/**
 * File -> types (class, mixin, enum, facet) -> enum values, fields, ctors, methods.
 * Fantom has no nested types, so slots are always leaves.
 */
class FantomStructureViewModel(psiFile: PsiFile, editor: Editor?) :
    StructureViewModelBase(psiFile, editor, FantomStructureViewElement(psiFile)),
    ElementInfoProvider {

    init {
        withSorters(Sorter.ALPHA_SORTER)
        withSuitableClasses(
            FantomFile::class.java,
            FantomClassDef::class.java,
            FantomMixinDef::class.java,
            FantomEnumDef::class.java,
            FantomFacetDef::class.java,
            FantomEnumValDef::class.java,
            FantomFieldDef::class.java,
            FantomCtorDef::class.java,
            FantomMethodDef::class.java,
        )
    }

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean =
        element.value is FantomFile

    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean =
        when (element.value) {
            is FantomEnumValDef, is FantomFieldDef, is FantomCtorDef, is FantomMethodDef -> true
            else -> false
        }
}
