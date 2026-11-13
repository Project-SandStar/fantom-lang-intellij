package co.anbora.labs.fantom.ide.navigation

import co.anbora.labs.fantom.lang.FantomLanguage
import co.anbora.labs.fantom.lang.core.psi.FantomGroupedExpr
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomStmt
import co.anbora.labs.fantom.lang.core.psi.FantomTermChain
import co.anbora.labs.fantom.lang.core.psi.FantomTermExpr
import co.anbora.labs.fantom.lang.resolve.FantomScope
import co.anbora.labs.fantom.lang.types.FanType
import co.anbora.labs.fantom.lang.types.FantomTypeInference
import com.intellij.codeInsight.navigation.actions.TypeDeclarationPlaceAwareProvider
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Go to Type Declaration (Cmd+Shift+B).
 *
 * First the expression at the caret: its inferred type, so `Bar().me` with
 * `This me()` goes to `Bar`. Without one, the resolved declaration: a local, param,
 * field or method goes to its declared or inferred type. `Str[]` goes to `List`,
 * `[Str:Int]` to `Map`. Type declarations themselves are left alone.
 */
class FantomTypeDeclarationProvider : TypeDeclarationPlaceAwareProvider {

    override fun getSymbolTypeDeclarations(symbol: PsiElement, editor: Editor?, offset: Int): Array<PsiElement>? {
        if (!symbol.language.isKindOf(FantomLanguage)) return null
        val type = editor?.let { typeAtCaret(it, offset, symbol) } ?: declarationType(symbol) ?: return null
        return targets(type, symbol)
    }

    override fun getSymbolTypeDeclarations(symbol: PsiElement): Array<PsiElement>? {
        if (!symbol.language.isKindOf(FantomLanguage)) return null
        return declarationType(symbol)?.let { targets(it, symbol) }
    }

    private fun targets(type: FanType, symbol: PsiElement): Array<PsiElement>? =
        FantomTypeInference.typeDefinitions(typeName(type), symbol.project)
            .takeIf { it.isNotEmpty() }?.toTypedArray()

    private fun declarationType(symbol: PsiElement): FanType? = when {
        FantomScope.isTypeDef(symbol) -> null
        symbol is FantomNamedElement -> FantomTypeInference.typeOfDeclaration(symbol)
        else -> FantomTypeInference.typeOf(symbol)
    }

    /** The inferred type of the innermost expression at [offset], or null. */
    private fun typeAtCaret(editor: Editor, offset: Int, symbol: PsiElement): FanType? {
        val file = PsiDocumentManager.getInstance(symbol.project).getPsiFile(editor.document) ?: return null
        val leaf = file.findElementAt(offset) ?: return null
        // On a chain step (`.me` in `Bar().me`) the receiver decides, not the enclosing type.
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

    private fun typeName(type: FanType): String = when (type) {
        is FanType.Named -> type.name.substringAfterLast("::")
        else -> type.slotOwnerName
    }
}
