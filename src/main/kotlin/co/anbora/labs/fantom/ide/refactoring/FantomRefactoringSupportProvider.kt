package co.anbora.labs.fantom.ide.refactoring

import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.psi.PsiElement
import com.intellij.refactoring.RefactoringActionHandler

/** In-place rename for Fantom named declarations, Introduce Variable and Extract Method. Safe delete is off. */
class FantomRefactoringSupportProvider : RefactoringSupportProvider() {

    override fun isMemberInplaceRenameAvailable(element: PsiElement, context: PsiElement?): Boolean =
        element is FantomNamedElement

    override fun isSafeDeleteAvailable(element: PsiElement): Boolean = false

    override fun getIntroduceVariableHandler(): RefactoringActionHandler = FantomIntroduceVariableHandler()

    override fun getExtractMethodHandler(): RefactoringActionHandler = FantomExtractMethodHandler()
}
