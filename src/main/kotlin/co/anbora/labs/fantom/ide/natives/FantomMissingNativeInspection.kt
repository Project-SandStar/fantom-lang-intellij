package co.anbora.labs.fantom.ide.natives

import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor

/**
 * Warns on a `native` slot that has no implementation in a folder the pod's `build.fan`
 * declares: `javaDirs` (`<Type>Peer.java` or `<Type>.java`) or `jsDirs` (`<Type>Peer.js`
 * or `<Type>.js`). A pod that declares neither is not checked. The check is by file name
 * and `name(` in the file text, as [FantomNatives] finds them.
 */
class FantomMissingNativeInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val slot = element as? FantomNamedElement ?: return
                if (!FantomNatives.isNative(slot)) return
                val name = slot.nameIdentifier ?: return
                for (kind in FantomNatives.Kind.entries) {
                    if (!FantomNatives.hasDeclaredDirs(slot, kind)) continue
                    if (FantomNatives.implementations(slot, kind, declaredOnly = true).isNotEmpty()) continue
                    holder.registerProblem(
                        name,
                        "Native '${slot.name}' has no ${kind.label} implementation in the pod's ${kind.buildField}",
                    )
                }
            }
        }
}
