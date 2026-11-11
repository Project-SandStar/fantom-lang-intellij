package co.anbora.labs.fantom.ide.refactoring

import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.psi.PsiElement

/** Enables in-place rename for Fantom named declarations. Safe delete is off. */
class FantomRefactoringSupportProvider : RefactoringSupportProvider() {

    override fun isMemberInplaceRenameAvailable(element: PsiElement, context: PsiElement?): Boolean =
        element is FantomNamedElement

    override fun isSafeDeleteAvailable(element: PsiElement): Boolean = false
}
