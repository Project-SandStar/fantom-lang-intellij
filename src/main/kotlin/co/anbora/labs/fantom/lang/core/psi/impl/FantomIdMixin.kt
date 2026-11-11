package co.anbora.labs.fantom.lang.core.psi.impl

import co.anbora.labs.fantom.lang.core.psi.FantomCall
import co.anbora.labs.fantom.lang.core.psi.FantomDotCall
import co.anbora.labs.fantom.lang.core.psi.FantomDynCallStm
import co.anbora.labs.fantom.lang.core.psi.FantomElementImpl
import co.anbora.labs.fantom.lang.core.psi.FantomField
import co.anbora.labs.fantom.lang.core.psi.FantomLocal
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomPodSpec
import co.anbora.labs.fantom.lang.core.psi.FantomSafeDotCallStm
import co.anbora.labs.fantom.lang.core.psi.FantomSafeDynCallStm
import co.anbora.labs.fantom.lang.core.psi.FantomSimpleType
import co.anbora.labs.fantom.lang.core.psi.FantomSlotExpr
import co.anbora.labs.fantom.lang.core.psi.FantomStaticCall
import co.anbora.labs.fantom.lang.core.psi.FantomTypeLiteral
import co.anbora.labs.fantom.lang.core.psi.FantomUsingPod
import co.anbora.labs.fantom.lang.resolve.FantomReference
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiReference

/**
 * Mixin for the `id` rule. An identifier is a reference when it is a use, not a
 * declaration. The declaring rules own their own `id` child, so an id whose parent
 * is a [FantomNamedElement] is the declaration name and gets no reference.
 */
abstract class FantomIdMixin(node: ASTNode) : FantomElementImpl(node) {

    override fun getReference(): PsiReference? {
        val parent = parent ?: return null
        return when (parent) {
            is FantomNamedElement -> null                       // declaration name
            is FantomLocal -> FantomReference(this, FantomReference.Kind.BARE)
            is FantomField, is FantomCall -> slotOrBareReference(parent)
            is FantomSimpleType -> typeReference(parent)
            is FantomTypeLiteral -> FantomReference(this, FantomReference.Kind.SLOT) // `#slot` or `Type#slot`
            is FantomUsingPod, is FantomPodSpec -> null         // pods are not indexed yet
            else -> null
        }
    }

    // `x` / `x(...)` bare, or after `.`, `->`, `?.`, `?->`, or `Type.x`.
    private fun slotOrBareReference(slotPart: com.intellij.psi.PsiElement): PsiReference {
        val slotExpr = slotPart.parent as? FantomSlotExpr
            ?: return FantomReference(this, FantomReference.Kind.BARE)
        return when (val chain = slotExpr.parent) {
            is FantomStaticCall -> FantomReference(this, FantomReference.Kind.SLOT, chain.type.simpleType?.idList?.lastOrNull()?.text)
            is FantomDotCall, is FantomDynCallStm, is FantomSafeDotCallStm, is FantomSafeDynCallStm ->
                FantomReference(this, FantomReference.Kind.SLOT)
            else -> FantomReference(this, FantomReference.Kind.BARE)
        }
    }

    // `Foo` or `pod::Foo`: only the last id is the type name; the pod id has no target yet.
    private fun typeReference(type: FantomSimpleType): PsiReference? {
        val ids = type.idList
        if (ids.size == 2 && ids[0] == this) return null
        return FantomReference(this, FantomReference.Kind.TYPE)
    }
}
