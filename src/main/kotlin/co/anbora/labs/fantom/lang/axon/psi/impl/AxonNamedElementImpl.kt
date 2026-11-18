package co.anbora.labs.fantom.lang.axon.psi.impl

import co.anbora.labs.fantom.ide.icons.FantomIcons
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes
import co.anbora.labs.fantom.lang.axon.psi.AxonCatchVar
import co.anbora.labs.fantom.lang.axon.psi.AxonCellDef
import co.anbora.labs.fantom.lang.axon.psi.AxonDefExpr
import co.anbora.labs.fantom.lang.axon.psi.AxonDefcompExpr
import co.anbora.labs.fantom.lang.axon.psi.AxonElementImpl
import co.anbora.labs.fantom.lang.axon.psi.AxonLambdaExpr
import co.anbora.labs.fantom.lang.axon.psi.AxonNamedElement
import co.anbora.labs.fantom.lang.axon.psi.AxonParam
import co.anbora.labs.fantom.lang.axon.psi.AxonResolver
import co.anbora.labs.fantom.lang.axon.psi.AxonTryExpr
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import javax.swing.Icon

/** Grammar-Kit mixin for the named rules of Axon.bnf: `defExpr`, `param`, `cellDef`, `catchVar`. */
abstract class AxonNamedElementImpl(node: ASTNode) : AxonElementImpl(node), AxonNamedElement {

    /** The name is the first IDENTIFIER of the rule. */
    override fun getNameIdentifier(): PsiElement? = node.findChildByType(AxonTokenTypes.IDENTIFIER)?.psi

    override fun getName(): String? = nameIdentifier?.text

    @Throws(IncorrectOperationException::class)
    override fun setName(name: String): PsiElement {
        val leaf = nameIdentifier?.node as? LeafElement
            ?: throw IncorrectOperationException("No name identifier to rename")
        leaf.replaceWithText(name)
        return this
    }

    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()

    /**
     * A parameter is visible in its lambda, a catch variable in its `try`, a cell in its `defcomp`,
     * a local in its function. A top-level definition keeps the default scope: a later task
     * finds its uses in other files.
     */
    override fun getUseScope(): SearchScope = when (this) {
        is AxonParam -> LocalSearchScope(PsiTreeUtil.getParentOfType(this, AxonLambdaExpr::class.java) ?: containingFile)
        is AxonCatchVar -> LocalSearchScope(PsiTreeUtil.getParentOfType(this, AxonTryExpr::class.java) ?: containingFile)
        is AxonCellDef -> LocalSearchScope(PsiTreeUtil.getParentOfType(this, AxonDefcompExpr::class.java) ?: containingFile)
        else -> if (parent is PsiFile) super.getUseScope() else LocalSearchScope(AxonResolver.functionOf(this) ?: containingFile)
    }

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = presentableName()
        override fun getIcon(unused: Boolean): Icon = this@AxonNamedElementImpl.getIcon(0)
    }

    override fun getIcon(flags: Int): Icon = when (this) {
        is AxonParam -> FantomIcons.PARAMETER
        is AxonDefExpr -> if (AxonResolver.isFunction(this)) FantomIcons.FUNCTION else FantomIcons.VARIABLE
        else -> FantomIcons.VARIABLE
    }

    /** `name(a, b)` for a function definition, the plain name for the rest. */
    private fun presentableName(): String {
        val name = name ?: "?"
        val lambda = (this as? AxonDefExpr)?.expr as? AxonLambdaExpr ?: return name
        return name + lambda.paramList.joinToString(", ", "(", ")") { it.name ?: "?" }
    }
}
