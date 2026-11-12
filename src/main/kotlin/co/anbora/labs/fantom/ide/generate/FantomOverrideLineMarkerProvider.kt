package co.anbora.labs.fantom.ide.generate

import co.anbora.labs.fantom.ide.presentation.FantomPresentation
import co.anbora.labs.fantom.lang.core.psi.FantomFieldDef
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.IDENTIFIER
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement

/**
 * Gutter icons for inheritance. An `override` slot gets the overriding arrow, which
 * jumps to the base slot on the type chain. A `virtual` or `abstract` slot gets the
 * overridden arrow when a project type overrides it, which jumps to those overrides.
 *
 * The icon sits on the name token, as the platform wants leaf elements.
 */
class FantomOverrideLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.node.elementType != IDENTIFIER) return null
        val slot = element.parent?.parent as? FantomNamedElement ?: return null
        if (slot !is FantomMethodDef && slot !is FantomFieldDef) return null
        if (slot.nameIdentifier != element) return null
        val mods = FantomPresentation.modifiers(slot)
        if ("override" in mods) {
            val base = FantomOverrideCandidates.baseSlot(slot) ?: return null
            return NavigationGutterIconBuilder.create(AllIcons.Gutter.OverridingMethod)
                .setTargets(base)
                .setTooltipText("Overrides ${ownerName(base)}.${base.name}")
                .createLineMarkerInfo(element)
        }
        if (FantomOverrideCandidates.overridability(slot) == null) return null
        val overriding = FantomOverrideCandidates.overridingSlots(slot)
        if (overriding.isEmpty()) return null
        return NavigationGutterIconBuilder.create(AllIcons.Gutter.OverridenMethod)
            .setTargets(overriding)
            .setTooltipText(if (overriding.size == 1) "Overridden in ${ownerName(overriding.single())}" else "Overridden in ${overriding.size} types")
            .createLineMarkerInfo(element)
    }

    private fun ownerName(slot: FantomNamedElement): String =
        co.anbora.labs.fantom.lang.resolve.FantomScope.enclosingType(slot)?.name ?: "?"
}
