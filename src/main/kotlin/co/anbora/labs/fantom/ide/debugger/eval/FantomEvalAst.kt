package co.anbora.labs.fantom.ide.debugger.eval

/**
 * A Fantom expression as the debugger evaluator sees it. It holds no PSI, so it can be evaluated
 * on the debugger thread after the read action that built it has ended.
 */
sealed class FExpr

/** Int ([Long]), Float ([Double]), Bool, Str or null. */
data class FLit(val value: Any?) : FExpr()

/** `this`. */
data object FThis : FExpr()

/** A bare name: a local, else a slot on `this` or on the frame's class. */
data class FName(val name: String) : FExpr()

/**
 * `target.name`, `target?.name` or `target.name(args)`. A null [target] is an implicit-`this`
 * call `name(args)`. Null [args] means field style: no parentheses.
 */
data class FMember(val target: FExpr?, val name: String, val args: List<FExpr>?, val safe: Boolean = false) : FExpr()

/** `Type.name` or `Type.name(args)`; [type] is the source text, such as `Str` or `sys::Str`. */
data class FStatic(val type: String, val name: String, val args: List<FExpr>?) : FExpr()

/** `target[index]`: `get` on a List, Map or Str. */
data class FIndex(val target: FExpr, val index: FExpr) : FExpr()

/** Arithmetic, comparison and logic operators: `+ - * / % == != < <= > >= <=> && ||`. */
data class FBinary(val op: String, val left: FExpr, val right: FExpr) : FExpr()

/** `!x` (op `!`) or `-x` (op `-`). */
data class FUnary(val op: String, val operand: FExpr) : FExpr()

/** `left ?: right`. */
data class FElvis(val left: FExpr, val right: FExpr) : FExpr()

/** `cond ? a : b`. */
data class FTernary(val cond: FExpr, val ifTrue: FExpr, val ifFalse: FExpr) : FExpr()

/** `x is T`, `x isnot T`, `x as T`. */
data class FTypeCheck(val op: String, val operand: FExpr, val type: String) : FExpr()

/** `[a, b]`. */
data class FList(val items: List<FExpr>) : FExpr()

/** An error the user should see: a construct outside the subset, a missing name, a bad operand. */
class FantomEvalException(message: String) : Exception(message)

fun notSupported(construct: String): Nothing =
    throw FantomEvalException("Not supported in the Fantom evaluator: $construct")
