package co.anbora.labs.fantom.ide.parameters

import co.anbora.labs.fantom.lang.core.psi.FantomArgs
import co.anbora.labs.fantom.lang.core.psi.FantomCtorDef
import co.anbora.labs.fantom.lang.core.psi.FantomExpr
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomParam
import co.anbora.labs.fantom.lang.core.psi.FantomSimple

/**
 * Maps the arguments of a call to the parameters of the method or ctor it resolves to.
 * Fantom has no varargs and no overloads by arity, so the mapping is by position; the
 * only choice is between several ctors of `Type(...)`, where `make` wins.
 *
 * Shared by Parameter Info (Cmd+P) and the parameter name inlay hints.
 */
object FantomArgMapping {

    /** One argument and the parameter it fills. */
    class Mapped(val arg: FantomExpr, val param: FantomParam, val callee: FantomNamedElement)

    /** The method or ctor [args] is passed to, or null when the call does not resolve or is ambiguous. */
    fun callee(args: FantomArgs): FantomNamedElement? {
        val candidates = FantomParameterInfoHandler.callables(args)
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0]
        val argCount = args.exprList.size
        val fitting = candidates.filter { fits(it, argCount) }
        if (fitting.size == 1) return fitting[0]
        val pool = fitting.ifEmpty { candidates }
        if (args.parent is FantomSimple) pool.firstOrNull { it is FantomCtorDef && it.name == "make" }?.let { return it }
        return null
    }

    /** Parameters of [decl] in order; empty when it has none or is not a method or ctor. */
    fun params(decl: FantomNamedElement): List<FantomParam> =
        FantomParameterInfoHandler.parameterList(decl)?.paramsList.orEmpty().mapNotNull { it.param }

    /** Argument to parameter pairs by position; stops at the shorter list. */
    fun map(args: FantomArgs): List<Mapped> {
        val callee = callee(args) ?: return emptyList()
        val params = params(callee)
        return args.exprList.zip(params) { arg, param -> Mapped(arg, param, callee) }
    }

    /** True when [argCount] arguments can fill [decl]'s parameters: not more than declared, not fewer than required. */
    private fun fits(decl: FantomNamedElement, argCount: Int): Boolean {
        val params = params(decl)
        val required = params.count { it.expr == null }
        return argCount in required..params.size
    }
}
