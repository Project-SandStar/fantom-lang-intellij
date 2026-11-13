package co.anbora.labs.fantom.lang.library.deps

/**
 * Picks the git tag of a project pod for a build target.
 *
 * Tags look like `<pod>/<SkySpark version>.<API level>` (`bassgCommon/4.0.6.2.2`: SkySpark
 * 4.0.6, API level 2.2) or bare (`3.0.27.1` in older repos). Tags with another pod's prefix
 * are ignored (one repo can hold several pods). The tag's version is the pod version.
 *
 * Rule, for target SkySpark version T:
 * 1. tags of exactly T, highest API level first (a prefixed tag beats a bare one of the same version);
 * 2. else the nearest LOWER SkySpark version, highest API level, with a warning;
 * 3. else none: the caller uses the working copy.
 * A tag whose version does not meet the `depends` constraint is skipped.
 */
object FantomTagChoice {

    data class Tag(
        val name: String,
        /** The first three numbers: `4.0.6`. */
        val skyspark: List<Int>,
        /** The rest: `[2, 2]`; empty for `3.0.27`. */
        val api: List<Int>,
        val prefixed: Boolean,
    ) {
        /** The pod version the tag stands for: `4.0.6.2.2`. */
        val version: String get() = (skyspark + api).joinToString(".")
        val skysparkText: String get() = skyspark.joinToString(".")
    }

    enum class Kind { EXACT, NEAREST_LOWER }

    data class Choice(val tag: Tag, val kind: Kind, val warning: String?)

    /** Reads [name] as a tag of [pod], or null when it is another pod's or not a version. */
    fun parse(name: String, pod: String): Tag? {
        val slash = name.lastIndexOf('/')
        val prefix = if (slash >= 0) name.substring(0, slash) else null
        if (prefix != null && !prefix.equals(pod, ignoreCase = true)) return null
        val v = name.substring(slash + 1).removePrefix("v")
        val nums = FantomDepend.parseVersion(v) ?: return null
        if (nums.size < 3) return null
        return Tag(name, nums.take(3), nums.drop(3), prefix != null)
    }

    private val ORDER = Comparator<Tag> { a, b -> FantomDepend.compare(a.skyspark, b.skyspark) }
        .thenComparator { a, b -> FantomDepend.compare(a.api, b.api) }
        .thenComparator { a, b -> a.prefixed.compareTo(b.prefixed) }

    /**
     * The tag of [pod] among [tags] for a target of SkySpark version [target], meeting
     * [constraint]; null when none is at or below [target].
     */
    fun choose(tags: Collection<String>, pod: String, target: String, constraint: String = ""): Choice? {
        val t = FantomDepend.parseVersion(target) ?: return null
        val usable = tags.mapNotNull { parse(it, pod) }.filter { FantomDepend.matches(it.version, constraint) }
        usable.filter { FantomDepend.compare(it.skyspark, t) == 0 }.maxWithOrNull(ORDER)?.let { return Choice(it, Kind.EXACT, null) }
        val lower = usable.filter { FantomDepend.compare(it.skyspark, t) < 0 }.maxWithOrNull(ORDER) ?: return null
        return Choice(lower, Kind.NEAREST_LOWER, "no $target tag; using ${lower.name}")
    }

    /** All usable tags of [pod], newest first, for the pin dropdown. */
    fun all(tags: Collection<String>, pod: String): List<Tag> =
        tags.mapNotNull { parse(it, pod) }.sortedWith(ORDER.reversed())
}
