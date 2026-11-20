package co.anbora.labs.fantom.ide.natives

import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.IDENTIFIER
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.PsiElement

/**
 * A gutter icon on a `native` slot that jumps to where it is written: the method of
 * `<Type>Peer.java` in the pod's Java folder and the one in `<Type>Peer.js` in its JS folder.
 * The targets are found only when the icon is clicked, so highlighting reads no files.
 */
class FantomNativeLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.node.elementType != IDENTIFIER) return null
        val slot = element.parent?.parent as? FantomNamedElement ?: return null
        if (slot.nameIdentifier != element || !FantomNatives.isNative(slot)) return null
        val targets = NotNullLazyValue.lazy<Collection<PsiElement>> {
            FantomNatives.implementations(slot, FantomNatives.Kind.JAVA) +
                FantomNatives.implementations(slot, FantomNatives.Kind.JS)
        }
        return NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
            .setTargets(targets)
            .setTooltipText("Native: go to the Java or JS implementation")
            .setEmptyPopupText("No implementation in the pod's java/ or js/ folder")
            .createLineMarkerInfo(element)
    }
}
