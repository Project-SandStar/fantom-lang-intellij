package co.anbora.labs.fantom.lang.library.deps

/**
 * Fantom `depends` constraints, as `sys::Depend` reads them: `"bassgCommon 3.0+"` is pod
 * `bassgCommon`, constraint `3.0+`.
 *
 * A constraint is a comma separated list; a version matches when it meets any part:
 * - `1.2` matches 1.2 and every 1.2.x;
 * - `1.2+` matches 1.2 and everything higher;
 * - `1.2-1.4` matches from 1.2 up to and including every 1.4.x.
 * A blank constraint matches every version.
 */
object FantomDepend {

    fun parseVersion(v: String): List<Int>? {
        val parts = v.trim().split('.')
        if (parts.isEmpty() || parts.any { it.isEmpty() }) return null
        return parts.map { it.toIntOrNull() ?: return null }
    }

    /** Compares two versions by their numbers; a missing part counts as 0. */
    fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    fun compare(a: String, b: String): Int =
        compare(parseVersion(a) ?: emptyList(), parseVersion(b) ?: emptyList())

    private fun startsWith(v: List<Int>, prefix: List<Int>): Boolean =
        v.size >= prefix.size && prefix.indices.all { v[it] == prefix[it] }

    /** True when [version] meets [constraint]; an unreadable version never does, a blank constraint always does. */
    fun matches(version: String?, constraint: String): Boolean {
        if (constraint.isBlank()) return true
        val v = version?.let(::parseVersion) ?: return false
        return constraint.split(',').map { it.trim() }.filter { it.isNotEmpty() }.any { part ->
            when {
                part.endsWith("+") -> parseVersion(part.dropLast(1))?.let { compare(v, it) >= 0 } ?: false
                part.contains('-') -> {
                    val lo = parseVersion(part.substringBefore('-'))
                    val hi = parseVersion(part.substringAfter('-'))
                    lo != null && hi != null && compare(v, lo) >= 0 && (compare(v, hi) <= 0 || startsWith(v, hi))
                }
                else -> parseVersion(part)?.let { startsWith(v, it) } ?: false
            }
        }
    }
}
