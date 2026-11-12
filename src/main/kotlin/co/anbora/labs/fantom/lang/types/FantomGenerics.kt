package co.anbora.labs.fantom.lang.types

/**
 * Generic type variables of the sys generics, bound from a receiver type:
 * - `V[]` (List): `V` is the element, `L` is the list itself.
 * - `[K:V]` (Map): `K` and `V` are key and value, `M` is the map itself.
 * - `|A,B..->R|` (Func): `R` is the return, `A`..`H` are the params in order.
 * - `This` is the receiver, for every receiver.
 *
 * A variable with no binding makes the whole type unknown (null): a `Named("V")` is
 * never handed to a caller.
 */
object FantomGenerics {
    private val CONTAINER_VARS = setOf("V", "K", "L", "M", "R")
    private val FUNC_VARS = listOf("A", "B", "C", "D", "E", "F", "G", "H")
    private const val THIS = "This"

    /** Short names of the sys types whose slots are written with the generic variables. */
    val GENERIC_OWNERS = setOf("List", "Map", "Func")

    /** The variables [receiver] binds. */
    fun bindings(receiver: FanType?): Map<String, FanType> {
        receiver ?: return emptyMap()
        val self = receiver.withNullable(false)
        val out = HashMap<String, FanType>()
        out[THIS] = self
        when (receiver) {
            is FanType.ListOf -> { out["V"] = receiver.element; out["L"] = self }
            is FanType.MapOf -> { out["K"] = receiver.key; out["V"] = receiver.value; out["M"] = self }
            is FanType.Func -> {
                receiver.returns?.let { out["R"] = it }
                FUNC_VARS.forEachIndexed { i, v -> receiver.params.getOrNull(i)?.let { out[v] = it } }
            }
            is FanType.Named -> {}
        }
        return out
    }

    /**
     * [type] with its generic variables replaced by what [receiver] binds, plus [extra]
     * bindings (for example `R` from a closure argument). Null when a variable stays
     * unbound. With [genericOwner] false only `This` is replaced: the slot belongs to a
     * user type, where a one-letter name is an ordinary type.
     */
    fun substitute(
        type: FanType,
        receiver: FanType?,
        extra: Map<String, FanType> = emptyMap(),
        genericOwner: Boolean = true,
    ): FanType? {
        val vars: Set<String> = when {
            !genericOwner -> setOf(THIS)
            receiver is FanType.Func -> CONTAINER_VARS + FUNC_VARS + THIS
            else -> CONTAINER_VARS + THIS
        }
        if (!mentions(type, vars)) return type
        return subst(type, bindings(receiver) + extra, vars)
    }

    private fun mentions(t: FanType, vars: Set<String>): Boolean = when (t) {
        is FanType.Named -> t.name in vars
        is FanType.ListOf -> mentions(t.element, vars)
        is FanType.MapOf -> mentions(t.key, vars) || mentions(t.value, vars)
        is FanType.Func -> t.params.any { mentions(it, vars) } || (t.returns?.let { mentions(it, vars) } ?: false)
    }

    private fun subst(t: FanType, b: Map<String, FanType>, vars: Set<String>): FanType? = when (t) {
        is FanType.Named ->
            if (t.name in vars) b[t.name]?.let { it.withNullable(it.nullable || t.nullable) } else t
        is FanType.ListOf -> subst(t.element, b, vars)?.let { FanType.ListOf(it, t.nullable) }
        is FanType.MapOf -> {
            val k = subst(t.key, b, vars)
            val v = subst(t.value, b, vars)
            if (k == null || v == null) null else FanType.MapOf(k, v, t.nullable)
        }
        is FanType.Func -> {
            val params = t.params.map { subst(it, b, vars) }
            val returns = t.returns?.let { subst(it, b, vars) }
            if (params.any { it == null } || (t.returns != null && returns == null)) null
            else FanType.Func(params.map { it!! }, returns, t.nullable)
        }
    }
}
