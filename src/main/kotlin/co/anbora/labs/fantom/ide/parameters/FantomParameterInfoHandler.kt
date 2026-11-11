package co.anbora.labs.fantom.ide.parameters

import co.anbora.labs.fantom.lang.core.psi.FantomArgs
import co.anbora.labs.fantom.lang.core.psi.FantomCall
import co.anbora.labs.fantom.lang.core.psi.FantomCallOp
import co.anbora.labs.fantom.lang.core.psi.FantomCtorDef
import co.anbora.labs.fantom.lang.core.psi.FantomId
import co.anbora.labs.fantom.lang.core.psi.FantomItemParameterList
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomSimple
import co.anbora.labs.fantom.lang.core.psi.FantomSlotExpr
import co.anbora.labs.fantom.lang.core.psi.FantomTermBase
import co.anbora.labs.fantom.lang.core.psi.FantomTermChain
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.COMMA
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.LPAR
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.RPAR
import co.anbora.labs.fantom.lang.resolve.FantomReference
import co.anbora.labs.fantom.lang.resolve.FantomScope
import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil

/**
 * Parameter Info (Cmd+P) for Fantom calls. The owner is the `args` node between
 * `(` and `)` of a call (`f(...)`, `x.f(...)`, `Type.f(...)`) or of a `Type(...)`
 * construction. Every resolved method or ctor is one item; the current parameter
 * is the number of top-level commas before the caret.
 */
class FantomParameterInfoHandler : ParameterInfoHandler<FantomArgs, FantomNamedElement> {

    override fun findElementForParameterInfo(context: CreateParameterInfoContext): FantomArgs? {
        val args = argsAt(context.file, context.offset) ?: return null
        val items = callables(args)
        if (items.isEmpty()) return null
        context.itemsToShow = items.toTypedArray()
        return args
    }

    override fun showParameterInfo(element: FantomArgs, context: CreateParameterInfoContext) {
        context.showHint(element, element.textRange.startOffset, this)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): FantomArgs? =
        argsAt(context.file, context.offset)

    override fun updateParameterInfo(parameterOwner: FantomArgs, context: UpdateParameterInfoContext) {
        context.setCurrentParameter(currentParameterIndex(parameterOwner, context.offset))
    }

    override fun updateUI(p: FantomNamedElement?, context: ParameterInfoUIContext) {
        if (p == null) {
            context.setUIComponentEnabled(false)
            return
        }
        val params = paramTexts(parameterList(p))
        if (params.isEmpty()) {
            context.setupUIComponentPresentation(
                "<no parameters>", -1, -1, !context.isUIComponentEnabled, false, false, context.defaultParameterColor,
            )
            return
        }
        val text = params.joinToString(", ")
        val index = context.currentParameterIndex
        var start = -1
        var end = -1
        if (index in params.indices) {
            start = params.take(index).sumOf { it.length + 2 }
            end = start + params[index].length
        }
        context.setupUIComponentPresentation(
            text, start, end, !context.isUIComponentEnabled, false, false, context.defaultParameterColor,
        )
    }

    companion object {

        /** The `args` node whose `(` `)` enclose [offset], innermost first, or null. */
        fun argsAt(file: PsiFile, offset: Int): FantomArgs? {
            var leaf: PsiElement? = file.findElementAt(offset)
            if (leaf == null && offset > 0) leaf = file.findElementAt(offset - 1)
            var cur: PsiElement? = leaf
            while (cur != null && cur !is PsiFile) {
                val args = when (cur) {
                    is FantomCall -> cur.args
                    is FantomCallOp -> cur.args
                    is FantomSimple -> cur.args
                    else -> null
                }
                if (args != null && encloses(cur, offset)) return args
                cur = cur.parent
            }
            return null
        }

        /** Methods and ctors the call before [args] resolves to. */
        fun callables(args: FantomArgs): List<FantomNamedElement> {
            val owner = args.parent ?: return emptyList()
            val id = calleeId(owner) ?: return emptyList()
            val ref = id.reference as? FantomReference ?: return emptyList()
            val out = ArrayList<FantomNamedElement>()
            for (target in ref.resolveAll()) {
                when {
                    target is FantomMethodDef || target is FantomCtorDef -> out += target
                    FantomScope.isTypeDef(target) -> out += FantomScope.ownSlots(target).filterIsInstance<FantomCtorDef>()
                }
            }
            return out.distinct()
        }

        /** Top-level commas of [args] that start before [offset]. */
        fun currentParameterIndex(args: FantomArgs, offset: Int): Int =
            args.node.getChildren(null).count { it.elementType == COMMA && it.startOffset < offset }

        fun parameterList(decl: FantomNamedElement): FantomItemParameterList? = when (decl) {
            is FantomMethodDef -> decl.itemParameterList
            is FantomCtorDef -> decl.itemParameterList
            else -> null
        }

        /** `Type name` or `Type name := default` for each parameter. */
        fun paramTexts(list: FantomItemParameterList?): List<String> =
            list?.paramsList.orEmpty().mapNotNull { it.param }.map { p ->
                val base = "${p.type.text.trim()} ${p.id.text}"
                p.expr?.let { "$base := ${it.text.trim()}" } ?: base
            }

        private fun encloses(callNode: PsiElement, offset: Int): Boolean {
            val lpar = callNode.node.findChildByType(LPAR) ?: return false
            val rpar = callNode.node.findChildByType(RPAR)
            if (offset <= lpar.startOffset) return false
            return rpar == null || offset <= rpar.startOffset
        }

        // The id the call names: `f` in `f(..)`, `foo` in `x.foo(..)`, `f` in `A.f(..)`, `A` in `A(..)`.
        private fun calleeId(owner: PsiElement): FantomId? = when (owner) {
            is FantomCall -> owner.id
            is FantomSimple -> owner.type.simpleType?.idList?.lastOrNull()
            is FantomCallOp -> {
                var prev = owner.parent?.prevSibling
                while (prev is PsiWhiteSpace) prev = prev.prevSibling
                when (prev) {
                    is FantomTermChain -> PsiTreeUtil.findChildOfType(prev, FantomSlotExpr::class.java)?.let(::idOf)
                    is FantomTermBase -> prev.idExpr?.local?.id
                        ?: prev.idExpr?.slotExpr?.let(::idOf)
                        ?: prev.typeBase?.staticCall?.slotExpr?.let(::idOf)
                    else -> null
                }
            }
            else -> null
        }

        private fun idOf(slotExpr: FantomSlotExpr): FantomId? = slotExpr.field?.id ?: slotExpr.call?.id
    }
}
