package co.anbora.labs.fantom.ide.debugger.eval

/**
 * The values the interpreter works on, as seen through the debugged VM. [V] is the runtime's
 * value type (a JDI `Value?` in the debugger, a plain object in tests). Null values are null.
 */
interface FantomEvalRuntime<V> {
    /** A VM value for an Int ([Long]), Float ([Double]), Bool, Str or null. */
    fun mirror(value: Any?): V?

    /** The Long, Double, Boolean or String in [value] (primitive or boxed), null for null, else [OPAQUE]. */
    fun unwrap(value: V?): Any?

    /** The local [name] in the current frame; [NotFound] when there is none. */
    fun local(name: String): Any?

    /** Fantom `this` of the frame; null in a static method. */
    fun thisObject(): V?

    /** Field read (`args` null) or method call on a non-null [target]. */
    fun member(target: V, name: String, args: List<V?>?): V?

    /** A slot without target: on `this`, else a static slot of the frame's class. */
    fun implicitMember(name: String, args: List<V?>?): V?

    /** A static field or method of the Fantom type [type]. */
    fun staticMember(type: String, name: String, args: List<V?>?): V?

    fun equals(a: V?, b: V?): Boolean
    fun compare(a: V?, b: V?): Long
    fun toStr(value: V?): String
    fun isInstance(value: V, type: String): Boolean
    fun newList(items: List<V?>): V?

    companion object {
        val OPAQUE = Any()
        val NotFound = Any()
    }
}

/**
 * Evaluates an [FExpr]. Int, Float, Bool and Str operators are computed here with Fantom's
 * rules (the same as `FanInt.plus`, `FanStr.plus` and so on); other operands go to the operator
 * methods (`plus`, `minus`, ...), `OpUtil.compareEQ` and `OpUtil.compare` in the VM.
 */
class FantomInterpreter<V>(private val rt: FantomEvalRuntime<V>) {

    fun eval(e: FExpr): V? = when (e) {
        is FLit -> rt.mirror(e.value)
        FThis -> rt.thisObject() ?: throw FantomEvalException("No 'this' in a static method")
        is FName -> {
            val local = rt.local(e.name)
            @Suppress("UNCHECKED_CAST")
            if (local !== FantomEvalRuntime.NotFound) local as V? else rt.implicitMember(e.name, null)
        }
        is FMember -> member(e)
        is FStatic -> rt.staticMember(e.type, e.name, e.args?.map { eval(it) })
        is FIndex -> {
            val target = eval(e.target) ?: throw FantomEvalException("Null value: cannot index it")
            rt.member(target, "get", listOf(eval(e.index)))
        }
        is FBinary -> binary(e)
        is FUnary -> unary(e)
        is FElvis -> eval(e.left) ?: eval(e.right)
        is FTernary -> if (bool(eval(e.cond), "?:")) eval(e.ifTrue) else eval(e.ifFalse)
        is FTypeCheck -> typeCheck(e)
        is FList -> rt.newList(e.items.map { eval(it) })
    }

    private fun member(e: FMember): V? {
        if (e.target == null) return rt.implicitMember(e.name, e.args?.map { eval(it) })
        val target = eval(e.target)
        if (target == null) {
            if (e.safe) return null
            throw FantomEvalException("Null value: cannot get '${e.name}' (use ?. for a null-safe call)")
        }
        return rt.member(target, e.name, e.args?.map { eval(it) })
    }

    private fun bool(v: V?, op: String): Boolean =
        rt.unwrap(v) as? Boolean ?: throw FantomEvalException("'$op' needs a Bool operand")

    private fun unary(e: FUnary): V? {
        val v = eval(e.operand)
        return when (e.op) {
            "!" -> rt.mirror(!bool(v, "!"))
            else -> when (val u = rt.unwrap(v)) {
                is Long -> rt.mirror(-u)
                is Double -> rt.mirror(-u)
                null -> throw FantomEvalException("Null value: cannot negate it")
                else -> rt.member(v!!, "negate", emptyList())
            }
        }
    }

    private fun typeCheck(e: FTypeCheck): V? {
        val v = eval(e.operand)
        val match = v != null && rt.isInstance(v, e.type)
        return when (e.op) {
            "is" -> rt.mirror(match)
            "isnot" -> rt.mirror(!match)
            else -> if (match) v else null
        }
    }

    private fun binary(e: FBinary): V? {
        when (e.op) {
            "&&" -> return rt.mirror(bool(eval(e.left), "&&") && bool(eval(e.right), "&&"))
            "||" -> return rt.mirror(bool(eval(e.left), "||") || bool(eval(e.right), "||"))
        }
        val l = eval(e.left)
        val r = eval(e.right)
        val a = rt.unwrap(l)
        val b = rt.unwrap(r)
        val opaque = a === FantomEvalRuntime.OPAQUE || b === FantomEvalRuntime.OPAQUE
        return when (e.op) {
            "==" -> rt.mirror(if (opaque) rt.equals(l, r) else valueEquals(a, b))
            "!=" -> rt.mirror(!(if (opaque) rt.equals(l, r) else valueEquals(a, b)))
            "<", "<=", ">", ">=", "<=>" -> {
                val c = if (opaque) rt.compare(l, r) else compare(a, b)
                rt.mirror(when (e.op) {
                    "<" -> c < 0
                    "<=" -> c <= 0
                    ">" -> c > 0
                    ">=" -> c >= 0
                    else -> c
                })
            }
            "+", "-", "*", "/", "%" -> arithmetic(e.op, l, r, a, b)
            else -> notSupported("operator ${e.op}")
        }
    }

    /** Fantom `==`: Int and Float are never equal to each other; null equals only null. */
    private fun valueEquals(a: Any?, b: Any?): Boolean = a == b

    /** Fantom `<=>`: -1, 0 or 1; null sorts first. */
    private fun compare(a: Any?, b: Any?): Long {
        if (a == null || b == null) return if (a == null && b == null) 0 else if (a == null) -1 else 1
        val c = when {
            a is Long && b is Long -> a.compareTo(b)
            a is Number && b is Number -> a.toDouble().compareTo(b.toDouble())
            a is String && b is String -> a.compareTo(b)
            a is Boolean && b is Boolean -> a.compareTo(b)
            else -> throw FantomEvalException("Cannot compare ${typeName(a)} and ${typeName(b)}")
        }
        return Integer.signum(c).toLong()
    }

    private fun arithmetic(op: String, l: V?, r: V?, a: Any?, b: Any?): V? {
        if (a is String && op == "+") return rt.mirror(a + (if (b is String) b else rt.toStr(r)))
        if (a is Long && b is Long) return rt.mirror(when (op) {
            "+" -> a + b
            "-" -> a - b
            "*" -> a * b
            "/" -> if (b == 0L) throw FantomEvalException("Division by zero") else a / b
            else -> if (b == 0L) throw FantomEvalException("Division by zero") else a % b
        })
        if ((a is Long || a is Double) && (b is Long || b is Double)) {
            val x = (a as Number).toDouble()
            val y = (b as Number).toDouble()
            return rt.mirror(when (op) {
                "+" -> x + y
                "-" -> x - y
                "*" -> x * y
                "/" -> x / y
                else -> x % y
            })
        }
        if (l == null) throw FantomEvalException("Null value: cannot apply '$op'")
        val method = when (op) {
            "+" -> "plus"
            "-" -> "minus"
            "*" -> "mult"
            "/" -> "div"
            else -> "mod"
        }
        return rt.member(l, method, listOf(r))
    }

    private fun typeName(v: Any?): String = when (v) {
        is Long -> "Int"
        is Double -> "Float"
        is String -> "Str"
        is Boolean -> "Bool"
        else -> "Obj"
    }
}
