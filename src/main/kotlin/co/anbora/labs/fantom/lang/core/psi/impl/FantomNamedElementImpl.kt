package co.anbora.labs.fantom.lang.core.psi.impl

import co.anbora.labs.fantom.lang.core.psi.FantomCatchDef
import co.anbora.labs.fantom.lang.core.psi.FantomClosure
import co.anbora.labs.fantom.lang.core.psi.FantomClosureParam
import co.anbora.labs.fantom.lang.core.psi.FantomCtorDef
import co.anbora.labs.fantom.lang.core.psi.FantomElementImpl
import co.anbora.labs.fantom.lang.core.psi.FantomFieldDef
import co.anbora.labs.fantom.lang.core.psi.FantomId
import co.anbora.labs.fantom.lang.core.psi.FantomLocalDef
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomParam
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.IDENTIFIER
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.ItemPresentationProviders
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException

/**
 * Grammar-Kit mixin for every named declaration. Rules opt in through the
 * `mixin(...)` pattern in Fantom.bnf, so one class serves class, method, field,
 * param, local and the rest.
 */
abstract class FantomNamedElementImpl(node: ASTNode) : FantomElementImpl(node), FantomNamedElement {

    /** The IDENTIFIER leaf inside the rule's own `id` child, never one nested inside a type. */
    override fun getNameIdentifier(): PsiElement? {
        val id = PsiTreeUtil.getChildOfType(this, FantomId::class.java) ?: return null
        return id.node.findChildByType(IDENTIFIER)?.psi
    }

    override fun getName(): String? = nameIdentifier?.text

    /** From the `itemPresentationProvider` registered for [FantomNamedElement] (ide/navigation). */
    override fun getPresentation(): ItemPresentation? = ItemPresentationProviders.getItemPresentation(this)

    @Throws(IncorrectOperationException::class)
    override fun setName(name: String): PsiElement {
        val leaf = nameIdentifier?.node as? LeafElement
            ?: throw IncorrectOperationException("No name identifier to rename")
        leaf.replaceWithText(name)
        return this
    }

    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()

    /**
     * Where uses of this declaration can be. A closure param is visible in its closure;
     * a local, param or catch param in its method, ctor or field initializer. That keeps
     * find usages and rename out of the word index for body names. Slots and types keep
     * the default project-wide scope.
     */
    override fun getUseScope(): SearchScope = when (this) {
        is FantomClosureParam -> LocalSearchScope(PsiTreeUtil.getParentOfType(this, FantomClosure::class.java) ?: containingFile)
        is FantomLocalDef, is FantomParam, is FantomCatchDef -> LocalSearchScope(
            PsiTreeUtil.getParentOfType(this, FantomMethodDef::class.java, FantomCtorDef::class.java, FantomFieldDef::class.java)
                ?: containingFile,
        )
        else -> super.getUseScope()
    }
}
