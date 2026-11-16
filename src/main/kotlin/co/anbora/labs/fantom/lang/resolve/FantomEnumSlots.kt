package co.anbora.labs.fantom.lang.resolve

import co.anbora.labs.fantom.lang.core.psi.FantomEnumDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.types.FanType

/**
 * The slots the compiler adds to every enum (`compiler::InitEnum`), which no source
 * declares:
 * - `static const T[] vals`
 * - `static new fromStr(Str name, Bool checked := true)`
 *
 * A use of one resolves to the enum declaration itself. A slot the enum declares with
 * the same name wins (the compiler would report the clash).
 */
object FantomEnumSlots {

    const val VALS = "vals"
    const val FROM_STR = "fromStr"
    val NAMES = listOf(VALS, FROM_STR)

    /** [type] when it is an enum and [name] is one of its compiler-made slots. */
    fun find(type: FantomNamedElement, name: String): FantomNamedElement? =
        type.takeIf { it is FantomEnumDef && name in NAMES }

    /** True when a reference named [name] that resolved to [target] means a compiler-made enum slot. */
    fun isSynthetic(target: FantomNamedElement, name: String): Boolean =
        target is FantomEnumDef && name in NAMES && target.name != name

    /** Type of the compiler-made slot [name] of [target], or null when it is not one. */
    fun typeOf(target: FantomNamedElement, name: String): FanType? {
        if (!isSynthetic(target, name)) return null
        val enumType = FanType.Named(target.name ?: return null)
        return when (name) {
            VALS -> FanType.ListOf(enumType)
            FROM_STR -> enumType
            else -> null
        }
    }

    /** Slot type shown in completion: `T[]` or `T`. */
    fun presentableType(enumName: String, name: String): String = if (name == VALS) "$enumName[]" else enumName

    /** Tail text shown in completion: the param list of `fromStr`, nothing for `vals`. */
    fun tailText(name: String): String? = if (name == FROM_STR) "(Str name, Bool checked := true)" else null
}
