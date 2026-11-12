package co.anbora.labs.fantom.ide.run

import co.anbora.labs.fantom.lang.core.psi.FantomClassDef
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.IDENTIFIER
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement

/**
 * Green run arrow on `static Void main`, on `Test` subclasses and on their `testXxx`
 * methods. The producers decide what the arrow runs.
 */
class FantomRunLineMarkerContributor : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        if (element.node.elementType != IDENTIFIER) return null
        val owner = element.parent?.parent ?: return null
        val isTest = when (owner) {
            is FantomMethodDef -> {
                if (owner.nameIdentifier != element) return null
                when {
                    FantomRunnable.isMain(owner) -> false
                    FantomRunnable.isTestMethod(owner) -> true
                    else -> return null
                }
            }
            is FantomClassDef -> {
                if (owner.nameIdentifier != element) return null
                when {
                    FantomRunnable.isTestClass(owner) -> true
                    FantomRunnable.mainOf(owner) != null -> false
                    else -> return null
                }
            }
            else -> return null
        }
        val icon = if (isTest) AllIcons.RunConfigurations.TestState.Run else AllIcons.RunConfigurations.TestState.Run_run
        return Info(icon, ExecutorAction.getActions(), null)
    }
}
