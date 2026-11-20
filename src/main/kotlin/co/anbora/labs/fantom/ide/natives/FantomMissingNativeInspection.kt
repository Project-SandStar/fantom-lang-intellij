package co.anbora.labs.fantom.ide.natives

import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor

/**
 * Warns on a `native` slot whose peer file has no method for it. The peer file is
 * `<Type>Peer.java` / `<Type>.java` in a folder of the pod's `javaDirs`, or
 * `<Type>Peer.js` / `<Type>.js` in a folder of its `jsDirs`.
 *
 * Only a peer file that exists is checked: a type with no peer file in a folder is taken
 * as not built for that language (many `@Js` types have no Java peer). A pod that declares
 * neither field is not checked. The check is by file text, as [FantomNatives] finds it.
 */
class FantomMissingNativeInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val slot = element as? FantomNamedElement ?: return
                if (!FantomNatives.isNative(slot)) return
                val name = slot.nameIdentifier ?: return
                for (kind in FantomNatives.Kind.entries) {
                    val peers = FantomNatives.peerFiles(slot, kind, declaredOnly = true)
                    if (peers.isEmpty()) continue
                    if (FantomNatives.implementations(slot, kind, declaredOnly = true).isNotEmpty()) continue
                    holder.registerProblem(
                        name,
                        "Native '${slot.name}' has no method in ${peers.first().name}",
                    )
                }
            }
        }
}
