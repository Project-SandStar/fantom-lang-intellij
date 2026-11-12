package co.anbora.labs.fantom.lang.types

/**
 * A Fantom type as the plugin knows it: enough to find the slots that apply to a
 * receiver and to print a readable name. Types are by short name; pods are not
 * modelled yet. `Str?[]` is `ListOf(Named("Str", nullable=true))`.
 */
sealed class FanType {
    abstract val nullable: Boolean
    abstract fun withNullable(nullable: Boolean): FanType

    /** Short name of the type whose slots a receiver of this type has (`List`, `Map`, `Func`, or the name). */
    abstract val slotOwnerName: String

    /** How the type prints in hints and docs. */
    abstract val presentableText: String

    data class Named(val name: String, override val nullable: Boolean = false) : FanType() {
        override val slotOwnerName get() = name
        override val presentableText get() = name + q()
        override fun withNullable(nullable: Boolean) = copy(nullable = nullable)
    }

    data class ListOf(val element: FanType, override val nullable: Boolean = false) : FanType() {
        override val slotOwnerName get() = "List"
        override val presentableText get() = element.presentableText + "[]" + q()
        override fun withNullable(nullable: Boolean) = copy(nullable = nullable)
    }

    data class MapOf(val key: FanType, val value: FanType, override val nullable: Boolean = false) : FanType() {
        override val slotOwnerName get() = "Map"
        override val presentableText get() = "[" + key.presentableText + ":" + value.presentableText + "]" + q()
        override fun withNullable(nullable: Boolean) = copy(nullable = nullable)
    }

    data class Func(val params: List<FanType>, val returns: FanType?, override val nullable: Boolean = false) : FanType() {
        override val slotOwnerName get() = "Func"
        override val presentableText get() =
            "|" + params.joinToString(",") { it.presentableText } + (returns?.let { "->" + it.presentableText } ?: "") + "|" + q()
        override fun withNullable(nullable: Boolean) = copy(nullable = nullable)
    }

    protected fun q() = if (nullable) "?" else ""

    companion object {
        val OBJ = Named("Obj")
        val BOOL = Named("Bool")
        val INT = Named("Int")
        val FLOAT = Named("Float")
        val DECIMAL = Named("Decimal")
        val STR = Named("Str")
        val DURATION = Named("Duration")
        val URI = Named("Uri")
        val RANGE = Named("Range")
        val TYPE = Named("Type")
        val VOID = Named("Void")
        val NULL = Named("Obj", nullable = true)
    }
}
