package co.anbora.labs.fantom.ide.navigation

import co.anbora.labs.fantom.ide.presentation.FantomPresentation
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.impl.FantomNamedElementImpl
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.index.FantomDeclarationKind
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.ItemPresentationProvider
import com.intellij.navigation.NavigationItem
import com.intellij.navigation.PsiElementNavigationItem
import com.intellij.psi.PsiElement

/**
 * A Go to Class / Go to Symbol row for one Fantom declaration.
 *
 * Text from [FantomPresentation], location `File.fan` for types and
 * `Owner (File.fan)` for slots. [getName] is the bare short name; the popup
 * matches the typed pattern against it.
 *
 * A [PsiElementNavigationItem]: Search Everywhere and the MCP server's
 * `search_symbol` unwrap it to its PSI (the MCP tool drops items that are neither
 * PSI nor this), and render the PSI through its `getPresentation`, which
 * [FantomItemPresentationProvider] gives the same text and location.
 */
class FantomNavigationItem(val element: FantomNamedElement) : PsiElementNavigationItem {

    override fun getName(): String? = element.name

    override fun getTargetElement(): PsiElement = element

    override fun getPresentation(): ItemPresentation = presentationOf(element)

    override fun navigate(requestFocus: Boolean) {
        (element as? NavigationItem)?.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = (element as? NavigationItem)?.canNavigate() ?: false

    override fun canNavigateToSource(): Boolean = (element as? NavigationItem)?.canNavigateToSource() ?: false

    override fun equals(other: Any?): Boolean = other is FantomNavigationItem && other.element == element

    override fun hashCode(): Int = element.hashCode()

    override fun toString(): String = "FantomNavigationItem(${element.name})"

    companion object {
        fun presentationOf(element: FantomNamedElement): ItemPresentation = FantomPresentation.of(element, location(element))

        private fun location(element: FantomNamedElement): String? {
            val file = element.containingFile?.name ?: return null
            val isType = FantomDeclarationKind.of(element)?.isType ?: true
            if (isType) return file
            val owner = FantomDeclarationIndex.enclosingTypeName(element) ?: return file
            return "$owner ($file)"
        }
    }
}

/** The presentation of a Fantom declaration PSI, as Go to Symbol and usage lists show it. */
class FantomItemPresentationProvider : ItemPresentationProvider<FantomNamedElementImpl> {
    override fun getPresentation(item: FantomNamedElementImpl): ItemPresentation = FantomNavigationItem.presentationOf(item)
}
