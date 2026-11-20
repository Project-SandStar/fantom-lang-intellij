package co.anbora.labs.fantom.lang.types

import co.anbora.labs.fantom.lang.core.psi.FantomGroupedExpr
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomStmt
import co.anbora.labs.fantom.lang.core.psi.FantomTermChain
import co.anbora.labs.fantom.lang.core.psi.FantomTermExpr
import co.anbora.labs.fantom.lang.resolve.FantomScope
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

/**
 * The type at a place in a Fantom file, as Go to Type Declaration finds it. Shared by
 * [co.anbora.labs.fantom.ide.navigation.FantomTypeDeclarationProvider] and the MCP tool
 * `fantom_type_of`, so both give the same answer. Read action.
 */
object FantomTypeAt {

    /**
     * The inferred type of the innermost expression at [offset], or null. On a chain step
     * (`.me` in `Bar().me`) the receiver decides, not the enclosing type. `This` is null: the
     * caller falls back to the declaration.
     */
    fun expressionAt(file: PsiFile, offset: Int): FanType? {
        val leaf = file.findElementAt(offset) ?: return null
        val step = PsiTreeUtil.getParentOfType(
            leaf, FantomTermChain::class.java, true, FantomTermExpr::class.java, FantomGroupedExpr::class.java, FantomStmt::class.java,
        )
        val type = if (step != null) chainStepType(step) else {
            var e: PsiElement? = leaf
            var found: FanType? = null
            while (found == null && e != null && e != file && e !is FantomNamedElement && e !is FantomStmt) {
                found = FantomTypeInference.typeOf(e)
                e = e.parent
            }
            found
        }
        return type?.takeUnless { it is FanType.Named && it.name == "This" }
    }

    /** The declared or inferred type of a declaration; null for a type definition itself. */
    fun declarationType(symbol: PsiElement): FanType? = when {
        FantomScope.isTypeDef(symbol) -> null
        symbol is FantomNamedElement -> FantomTypeInference.typeOfDeclaration(symbol)
        else -> FantomTypeInference.typeOf(symbol)
    }

    /** Type of the chain up to and including [step]: the receiver of the next step, or the whole term. */
    private fun chainStepType(step: FantomTermChain): FanType? {
        val container = step.parent
        val chains = when (container) {
            is FantomTermExpr -> container.termChainList
            is FantomGroupedExpr -> container.termChainList
            else -> return null
        }
        val i = chains.indexOf(step)
        if (i < 0) return null
        val next = chains.getOrNull(i + 1) ?: return FantomTypeInference.typeOf(container)
        return next.firstChild?.let { FantomTypeInference.receiverType(it) }
    }
}
