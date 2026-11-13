package co.anbora.labs.fantom.lang.types

import co.anbora.labs.fantom.lang.core.psi.FantomArgs
import co.anbora.labs.fantom.lang.core.psi.FantomCall
import co.anbora.labs.fantom.lang.core.psi.FantomCallOp
import co.anbora.labs.fantom.lang.core.psi.FantomClosure
import co.anbora.labs.fantom.lang.core.psi.FantomClosureParam
import co.anbora.labs.fantom.lang.core.psi.FantomCtorBlock
import co.anbora.labs.fantom.lang.core.psi.FantomCtorDef
import co.anbora.labs.fantom.lang.core.psi.FantomExpr
import co.anbora.labs.fantom.lang.core.psi.FantomFieldDef
import co.anbora.labs.fantom.lang.core.psi.FantomGroupedExpr
import co.anbora.labs.fantom.lang.core.psi.FantomId
import co.anbora.labs.fantom.lang.core.psi.FantomItBlock
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomSafeDotCallStm
import co.anbora.labs.fantom.lang.core.psi.FantomSimple
import co.anbora.labs.fantom.lang.core.psi.FantomTermChain
import co.anbora.labs.fantom.lang.core.psi.FantomTermExpr
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.resolve.FantomReference
import co.anbora.labs.fantom.lang.resolve.FantomScope
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Types that a closure or an it-block gets from the call it is handed to.
 *
 * `list.each |x, i| { }` parses as the chain steps `.each` and `|x, i| { }`; `each`
 * resolves to `Void each(|V item, Int index| c)`, so the closure's untyped params take
 * the func type's params with `V` bound from the receiver. `it` in an it-block
 * (`Foo { }`, `recv.call { }`) or a zero-param closure is the first func param when the
 * call takes a func, else the receiver.
 *
 * Construction calls work the same way: `Foo(a) |x| { }` and `Foo(a, |x| { })` match
 * the closure against the func param of `Foo.make` (else Foo's only ctor), with `This`
 * bound to `Foo`. In `Foo { }` the block is an it-block whose `it` is `Foo`.
 */
object FantomItScope {

    /** The slot a block is handed to: its name node, the receiver it is called on, and the block's arg position. */
    private class Callee(val id: FantomId, val receiver: FanType?, val argIndex: Int, val ctorOf: String? = null)

    /** The func type a closure is matched against, with what is needed to bind its variables. */
    private class Site(val func: FanType.Func, val receiver: FanType?, val genericOwner: Boolean) {
        fun paramType(i: Int): FanType? =
            func.params.getOrNull(i)?.let { FantomGenerics.substitute(it, receiver, genericOwner = genericOwner) }
    }

    /** Type of the `it` literal, or null when no enclosing block binds it. */
    fun typeOfIt(literal: PsiElement): FanType? {
        var p: PsiElement? = literal.parent
        while (p != null) {
            when (p) {
                is FantomItBlock -> return typeOfItBlock(p)
                is FantomClosure -> if (p.closureSig?.closureParams?.closureParamList.isNullOrEmpty()) return typeOfItClosure(p)
                is FantomMethodDef, is FantomCtorDef, is FantomFieldDef -> return null
            }
            if (FantomScope.isTypeDef(p)) return null
            p = p.parent
        }
        return null
    }

    /** Type of an untyped closure param, from the func param of the called method. */
    fun typeOfClosureParam(param: FantomClosureParam): FanType? {
        val closure = PsiTreeUtil.getParentOfType(param, FantomClosure::class.java) ?: return null
        val index = closure.closureSig?.closureParams?.closureParamList?.indexOf(param) ?: return null
        if (index < 0) return null
        val callee = calleeOfClosure(closure) ?: return null
        return site(callee)?.paramType(index)
    }

    private fun typeOfItBlock(block: FantomItBlock): FanType? {
        when (val parent = block.parent) {
            is FantomCtorBlock -> return FantomTypeInference.fromPsiType(parent.type)?.withNullable(false)
            is FantomTermChain -> {
                calleeOfStep(parent, 0)?.let { c -> itOf(c)?.let { return it } }
                return FantomTypeInference.receiverType(block)?.withNullable(false)
            }
        }
        return null
    }

    private fun typeOfItClosure(closure: FantomClosure): FanType? = calleeOfClosure(closure)?.let { itOf(it) }

    // `it` for a block handed to [callee]: the first param of the func the method takes.
    // `with` is special-cased: `Obj.with(|This| f)` may not resolve without the sys library.
    private fun itOf(callee: Callee): FanType? {
        site(callee)?.let { return it.paramType(0) }
        if (callee.id.text == "with") return callee.receiver?.withNullable(false)
        return null
    }

    // The slot a closure is handed to, trailing (`f |x| { }`, `f(a) |x| { }`) or as an argument.
    private fun calleeOfClosure(closure: FantomClosure): Callee? {
        when (val parent = closure.parent) {
            is FantomCallOp -> return calleeOfStep(parent.parent as? FantomTermChain ?: return null, parent.args?.exprList?.size ?: 0)
            is FantomCall -> return Callee(parent.id ?: return null, enclosingTypeOf(parent), parent.args?.exprList?.size ?: 0)
            is FantomSimple -> return ctorCallee(parent, parent.args?.exprList?.size ?: 0)
        }
        val expr = PsiTreeUtil.getParentOfType(closure, FantomExpr::class.java) ?: return null
        if (expr.textRange != closure.textRange) return null
        val args = expr.parent as? FantomArgs ?: return null
        val at = args.exprList.indexOf(expr)
        return when (val owner = args.parent) {
            is FantomCallOp -> calleeOfStep(owner.parent as? FantomTermChain ?: return null, at)
            is FantomCall -> Callee(owner.id ?: return null, enclosingTypeOf(owner), at)
            is FantomSimple -> ctorCallee(owner, at)
            else -> null
        }
    }

    /**
     * The slot named just before chain step [step] (which holds a block): the `.x` step
     * before it, or the bare / static name at the base. An args step between
     * (`recv.x(a) { }`) supplies the block's arg position.
     */
    private fun calleeOfStep(step: FantomTermChain, argIndex: Int): Callee? {
        val container = step.parent ?: return null
        val chains = when (container) {
            is FantomTermExpr -> container.termChainList
            is FantomGroupedExpr -> container.termChainList
            else -> return null
        }
        var i = chains.indexOf(step) - 1
        var at = argIndex
        if (i >= 0 && step.itBlock != null) {
            chains[i].callOp?.let { op -> at = op.args?.exprList?.size ?: 0; i-- }
        }
        if (i >= 0) {
            val prev = chains[i]
            val slotExpr = prev.dotCall?.slotExpr ?: (prev.stmt as? FantomSafeDotCallStm)?.slotExpr ?: return null
            val id = slotExpr.field?.id ?: slotExpr.call?.id ?: return null
            val op = prev.dotCall ?: prev.stmt ?: return null
            return Callee(id, FantomTypeInference.receiverType(op), at)
        }
        val base = (container as? FantomTermExpr)?.termBase ?: return null
        base.idExpr?.let { ie ->
            val id = ie.local?.id ?: ie.slotExpr?.field?.id ?: ie.slotExpr?.call?.id ?: return null
            return Callee(id, enclosingTypeOf(id), at)
        }
        base.typeBase?.staticCall?.let { sc ->
            val id = sc.slotExpr?.field?.id ?: sc.slotExpr?.call?.id ?: return null
            return Callee(id, FantomTypeInference.fromPsiType(sc.type), at)
        }
        return null
    }

    /**
     * `Foo(a) |x| { }` and `Foo(a, |x| { })`: the construction call `Foo.make(...)`. The
     * callee is the type name; [site] looks up the type's `make` (ctor or static method),
     * else its only ctor.
     */
    private fun ctorCallee(simple: FantomSimple, at: Int): Callee? {
        val id = simple.type.simpleType?.idList?.lastOrNull() ?: return null
        return Callee(id, FanType.Named(id.text), at, ctorOf = id.text)
    }

    // The ctor-like slots `Foo(...)` may call: `make` first, else the type's ctors.
    private fun constructors(typeName: String, context: PsiElement): List<FantomNamedElement> {
        val slots = FantomTypeInference.typeDefinitions(typeName, context.project).flatMap { FantomScope.ownSlots(it) }
        val make = slots.filter { it.name == "make" && (it is FantomCtorDef || it is FantomMethodDef) }
        return make.ifEmpty { slots.filterIsInstance<FantomCtorDef>() }
    }

    private fun enclosingTypeOf(e: PsiElement): FanType? = FantomScope.enclosingType(e)?.name?.let { FanType.Named(it) }

    // The func-typed param at the block's position of the method [callee] resolves to; the targets must agree.
    private fun site(callee: Callee): Site? {
        val targets = callee.ctorOf?.let { constructors(it, callee.id) }
            ?: (callee.id.reference as? FantomReference ?: return null).resolveAll().filterIsInstance<FantomMethodDef>()
        val sites = targets.mapNotNull { m ->
            val list = when (m) {
                is FantomMethodDef -> m.itemParameterList
                is FantomCtorDef -> m.itemParameterList
                else -> null
            }
            val params = list?.paramsList?.mapNotNull { it.param } ?: return@mapNotNull null
            val p = params.getOrNull(callee.argIndex) ?: return@mapNotNull null
            val f = FantomTypeInference.fromPsiType(p.type) as? FanType.Func ?: return@mapNotNull null
            val generic = FantomDeclarationIndex.enclosingTypeName(m) in FantomGenerics.GENERIC_OWNERS
            Triple(f, generic, m.name)
        }.distinctBy { it.first to it.second }
        val one = sites.singleOrNull() ?: return null
        return Site(one.first, callee.receiver, one.second)
    }
}
