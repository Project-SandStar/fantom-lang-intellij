package co.anbora.labs.fantom.ide.navigation

import co.anbora.labs.fantom.ide.presentation.FantomPresentation
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.index.FantomDeclarationKind
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.navigation.PsiElementNavigationItem
import com.intellij.psi.PsiElement

/**
 * A Go to Class / Go to Symbol row for one Fantom declaration.
 *
 * The PSI classes have no presentation of their own, so this wrapper adds one:
 * text from [FantomPresentation], location `File.fan` for types and
 * `Owner (File.fan)` for slots. [getName] is the bare short name; the popup
 * matches the typed pattern against it.
 */
class FantomNavigationItem(private val element: FantomNamedElement) : PsiElementNavigationItem {

    override fun getTargetElement(): PsiElement = element

    override fun getName(): String? = element.name

    override fun getPresentation(): ItemPresentation = FantomPresentation.of(element, location())

    override fun navigate(requestFocus: Boolean) {
        (element as? NavigationItem)?.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = (element as? NavigationItem)?.canNavigate() ?: false

    override fun canNavigateToSource(): Boolean = (element as? NavigationItem)?.canNavigateToSource() ?: false

    private fun location(): String? {
        val file = element.containingFile?.name ?: return null
        val isType = FantomDeclarationKind.of(element)?.isType ?: true
        if (isType) return file
        val owner = FantomDeclarationIndex.enclosingTypeName(element) ?: return file
        return "$owner ($file)"
    }

    override fun equals(other: Any?): Boolean = other is FantomNavigationItem && other.element == element

    override fun hashCode(): Int = element.hashCode()

    override fun toString(): String = "FantomNavigationItem(${element.name})"
}
