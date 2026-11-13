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

    // ---- sys slots whose declared result is weaker than what they return ------------

    /** How [bindResult] computes the result of a sys generic slot. */
    enum class Rule {
        /** Written with `V`, `K`, `L` or `M`: plain [substitute] from the receiver. */
        DECLARED,
        /** `R[]`, `R` the closure's return type. */
        LIST_OF_RETURN,
        /** `R[]` with `R` made non-null. */
        LIST_OF_RETURN_NOT_NULL,
        /** The closure returns a list; the result is that list type. */
        RETURN_LIST,
        /** `[K:R]` on a map receiver. */
        MAP_OF_RETURN,
        /** `[K:R]` with `R` made non-null. */
        MAP_OF_RETURN_NOT_NULL,
        /** `[R:L]`: groups of the receiver list keyed by the closure's return. */
        GROUP_BY,
        /** `R?`: the closure's return, or null when the walk ends. */
        RETURN_NULLABLE,
        /** The type of the `init` argument (unless `null`), else the closure's return. */
        REDUCE,
        /** `T[]` where the first argument is the type literal `T#`. */
        LIST_OF_TYPE_ARG,
    }

    /** One row of [TABLE]: the sys slot, its declaration in sys (fantom 1.0.84), and its rule. */
    class Binding(val owner: String, val name: String, val sysLine: String, val rule: Rule)

    /**
     * The sys `List` and `Map` slots that are generic in practice. Rows with [Rule.DECLARED]
     * need no extra rule (their written type already says `V`, `L` or `M`); they are listed
     * so this table is the one place that says how each of these slots is typed.
     */
    val TABLE: Map<Pair<String, String>, Binding> = listOf(
        Binding("List", "map", "List.fan:527 Obj?[] map(|V item, Int index->Obj?| c)", Rule.LIST_OF_RETURN),
        Binding("List", "mapNotNull", "List.fan:541 Obj[] mapNotNull(|V item, Int index->Obj?| c)", Rule.LIST_OF_RETURN_NOT_NULL),
        Binding("List", "flatMap", "List.fan:556 Obj?[] flatMap(|V item, Int index->Obj?[]| c)", Rule.RETURN_LIST),
        Binding("List", "groupBy", "List.fan:571 Obj:L groupBy(|V item, Int index->Obj| c)", Rule.GROUP_BY),
        Binding("List", "eachWhile", "List.fan:402 Obj? eachWhile(|V item, Int index->Obj?| c)", Rule.RETURN_NULLABLE),
        Binding("List", "eachrWhile", "List.fan:411 Obj? eachrWhile(|V item, Int index->Obj?| c)", Rule.RETURN_NULLABLE),
        Binding("List", "reduce", "List.fan:591 Obj? reduce(Obj? init, |Obj? reduction, V item, Int index->Obj?| c)", Rule.REDUCE),
        Binding("List", "findType", "List.fan:464 L findType(Type t)", Rule.LIST_OF_TYPE_ARG),
        Binding("List", "getSafe", "List.fan:117 V? getSafe(Int index, V? def := null)", Rule.DECLARED),
        Binding("List", "first", "List.fan:188 V? first()", Rule.DECLARED),
        Binding("List", "last", "List.fan:194 V? last()", Rule.DECLARED),
        Binding("List", "min", "List.fan:605 V? min(|V a, V b->Int|? c := null)", Rule.DECLARED),
        Binding("List", "max", "List.fan:619 V? max(|V a, V b->Int|? c := null)", Rule.DECLARED),
        Binding("List", "dup", "List.fan:201 L dup()", Rule.DECLARED),
        Binding("List", "ro", "List.fan:846 L ro()", Rule.DECLARED),
        Binding("List", "rw", "List.fan:853 L rw()", Rule.DECLARED),
        Binding("Map", "map", "Map.fan:375 Obj:Obj? map(|V val, K key->Obj?| c)", Rule.MAP_OF_RETURN),
        Binding("Map", "mapNotNull", "Map.fan:384 Obj:Obj? mapNotNull(|V val, K key->Obj?| c)", Rule.MAP_OF_RETURN_NOT_NULL),
        Binding("Map", "eachWhile", "Map.fan:295 Obj? eachWhile(|V val, K key->Obj?| c)", Rule.RETURN_NULLABLE),
        Binding("Map", "reduce", "Map.fan:360 Obj? reduce(Obj? init, |Obj? reduction, V val, K key->Obj?| c)", Rule.REDUCE),
        Binding("Map", "dup", "Map.fan:107 M dup()", Rule.DECLARED),
        Binding("Map", "ro", "Map.fan:415 M ro()", Rule.DECLARED),
        Binding("Map", "rw", "Map.fan:422 M rw()", Rule.DECLARED),
    ).associateBy { it.owner to it.name }

    /**
     * Result type of sys slot [owner].[name] by its [TABLE] rule, or null when the slot is
     * not in the table, its rule is [Rule.DECLARED], or the inputs the rule needs are
     * unknown (the caller then substitutes the declared type).
     *
     * [closureReturn] is the return type of the closure handed to the call; [firstArg] is
     * the type of the first argument; [typeArg] is `T` when the first argument is `T#`.
     */
    fun bindResult(
        owner: String?, name: String?, receiver: FanType?,
        closureReturn: FanType?, firstArg: FanType? = null, typeArg: FanType? = null,
    ): FanType? {
        val row = TABLE[(owner ?: return null) to (name ?: return null)] ?: return null
        val r = closureReturn?.takeIf { it != FanType.VOID }
        return when (row.rule) {
            Rule.DECLARED -> null
            Rule.LIST_OF_RETURN -> r?.let { FanType.ListOf(it) }
            Rule.LIST_OF_RETURN_NOT_NULL -> r?.let { FanType.ListOf(it.withNullable(false)) }
            Rule.RETURN_LIST -> r as? FanType.ListOf
            Rule.MAP_OF_RETURN -> if (receiver is FanType.MapOf && r != null) FanType.MapOf(receiver.key, r) else null
            Rule.MAP_OF_RETURN_NOT_NULL ->
                if (receiver is FanType.MapOf && r != null) FanType.MapOf(receiver.key, r.withNullable(false)) else null
            Rule.GROUP_BY ->
                if (receiver is FanType.ListOf && r != null) FanType.MapOf(r.withNullable(false), receiver.withNullable(false)) else null
            Rule.RETURN_NULLABLE -> r?.withNullable(true)
            Rule.REDUCE -> firstArg?.takeIf { it != FanType.NULL } ?: r
            Rule.LIST_OF_TYPE_ARG -> typeArg?.let { FanType.ListOf(it.withNullable(false)) }
        }
    }
}
