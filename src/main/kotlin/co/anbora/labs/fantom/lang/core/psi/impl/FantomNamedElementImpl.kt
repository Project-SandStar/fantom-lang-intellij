package co.anbora.labs.fantom.lang.core.psi.impl

import co.anbora.labs.fantom.lang.core.psi.FantomElementImpl
import co.anbora.labs.fantom.lang.core.psi.FantomId
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.IDENTIFIER
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafElement
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

    @Throws(IncorrectOperationException::class)
    override fun setName(name: String): PsiElement {
        val leaf = nameIdentifier?.node as? LeafElement
            ?: throw IncorrectOperationException("No name identifier to rename")
        leaf.replaceWithText(name)
        return this
    }

    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()
}
