package co.anbora.labs.fantom.lang.library

import java.io.File

/**
 * Finds a Fantom installation on this machine and lists its pod sources.
 *
 * Three kinds of home are accepted, see [Kind] and [kindOf]:
 * - a plain Fantom install: `bin/fan` and `src/<pod>/fan`, with `src/sys/fan`;
 * - a Haxall checkout: pods nested under `src/` (`src/core/haystack/fan`), no `sys`,
 *   and often no `bin/fan` either;
 * - a SkySpark install: `bin/fan` and compiled pods in `lib/fan`, no sources.
 *
 * Haxall and SkySpark need a second, plain Fantom install for `sys` and the core
 * pods; [FantomSettings] calls it the Fantom sources.
 */
object FantomHome {

    /** System property that names the home folder. Checked first. */
    const val PROPERTY = "fantom.home"

    /** Environment variable that names the home folder. Checked second. */
    const val ENV = "FAN_HOME"

    /** Folder scanned for `fantom-<version>` installs when neither the property nor FAN_HOME is set. */
    val DEFAULT_PARENT: String = System.getProperty("user.home") + "/fantom"

    private val versionedName = Regex("fantom-(\\d+(?:\\.\\d+)*)")
    private val anyVersionedName = Regex("(fantom|haxall|skyspark)-(\\d+(?:\\.\\d+)*)")
    private val podNameRegex = Regex("""podName\s*=\s*"([^"]+)"""")

    /** What a home folder is. */
    enum class Kind(val label: String) {
        /** Plain Fantom: `bin/fan` and `src/` (a real install also has `src/sys/fan`). */
        FANTOM("Fantom"),
        /** Haxall: `src/core`, or a `src/build.fan` that names Haxall. Pods nest under `src/`. */
        HAXALL("Haxall"),
        /** SkySpark: compiled pods only, `lib/fan/skyarcd.pod` (or `skyspark.pod`). */
        SKYSPARK("SkySpark"),
    }

    /** One pod's sources: the pod name and the folders under its pod folder that hold `.fan` files. */
    data class PodSource(val pod: String, val podDir: File, val roots: List<File>)

    private fun candidates(): Sequence<File> = sequence {
        System.getProperty(PROPERTY)?.takeIf { it.isNotBlank() }?.let { yield(File(it)) }
        System.getenv(ENV)?.takeIf { it.isNotBlank() }?.let { yield(File(it)) }
        yieldAll(versionedHomes(File(DEFAULT_PARENT)))
    }

    /**
     * The first valid home found, in this order: system property [PROPERTY],
     * env [ENV], then the newest `fantom-1.0.*` folder under [DEFAULT_PARENT].
     * Only homes with `bin/fan` count, so the result can always run builds.
     */
    fun detect(): File? = candidates().firstOrNull { isHome(it) && hasLauncher(it) }

    /**
     * A plain Fantom install with `src/sys/fan`, to supply `sys` when the home is Haxall
     * or SkySpark: [PROPERTY], [ENV], then the newest `fantom-*` under [DEFAULT_PARENT].
     */
    fun detectSources(): File? = candidates().firstOrNull { isSources(it) }

    /** True when [dir] is a home of any [Kind]. */
    fun isHome(dir: File): Boolean = kindOf(dir) != null

    /** True when [dir] can supply `sys`: it has `src/sys/fan`. */
    fun isSources(dir: File): Boolean = File(dir, "src/sys/fan").isDirectory

    /** True when [dir] has the `bin/fan` launcher that runs builds and tests. */
    fun hasLauncher(dir: File): Boolean = File(dir, "bin/fan").isFile

    /**
     * The kind of [dir], or null when it is not a home. SkySpark is checked first
     * (its install also has `bin/fan`), then Haxall, then plain Fantom.
     */
    fun kindOf(dir: File): Kind? {
        if (!dir.isDirectory) return null
        val lib = File(dir, "lib/fan")
        if (File(lib, "skyspark.pod").isFile || File(lib, "skyarcd.pod").isFile) return Kind.SKYSPARK
        val src = File(dir, "src")
        if (!src.isDirectory) return null
        if (!File(src, "sys/fan").isDirectory) {
            if (File(src, "core").isDirectory) return Kind.HAXALL
            val build = File(src, "build.fan")
            if (build.isFile && runCatching { build.readText() }.getOrDefault("").contains("haxall", ignoreCase = true)) {
                return Kind.HAXALL
            }
        }
        return if (hasLauncher(dir)) Kind.FANTOM else null
    }

    /** "Haxall 4.0.7" style text for [home]; the folder name when it carries no version. */
    fun displayName(home: File): String {
        val kind = kindOf(home)?.label ?: "Fantom"
        val version = anyVersionedName.matchEntire(home.name)?.groupValues?.get(2)
        return if (version != null) "$kind $version" else "$kind (${home.name})"
    }

    /** Every source root of [home] (for a plain Fantom home: each `src/<pod>/fan`), sorted by pod name. */
    fun sourceRoots(home: File): List<File> = podSources(home).flatMap { it.roots }

    /**
     * The pod sources of [home]:
     * - FANTOM: one entry per `src/<pod>/fan`, named after the folder;
     * - HAXALL: every folder under `src/` whose `build.fan` sets `podName`, at any depth;
     *   its roots are the sub folders that hold `.fan` files (`fan`, `test`, ...);
     * - SKYSPARK: nothing, it ships compiled pods only.
     */
    fun podSources(home: File): List<PodSource> = when (kindOf(home)) {
        Kind.FANTOM -> fantomPods(home)
        Kind.HAXALL -> nestedPods(File(home, "src"))
        Kind.SKYSPARK, null -> emptyList()
    }

    private fun fantomPods(home: File): List<PodSource> {
        val pods = File(home, "src").listFiles { f -> f.isDirectory } ?: return emptyList()
        return pods.sortedBy { it.name }
            .filter { File(it, "fan").isDirectory }
            .map { PodSource(it.name, it, listOf(File(it, "fan"))) }
    }

    /**
     * Walks [src] for pod folders: a folder whose `build.fan` sets `podName`. Group
     * scripts (a `build.fan` with no `podName`) are walked through. A pod's roots are
     * its direct sub folders that hold `.fan` files, so roots never nest and the pod's
     * own `build.fan` is not indexed.
     */
    fun nestedPods(src: File): List<PodSource> {
        val out = ArrayList<PodSource>()
        fun visit(dir: File) {
            val build = File(dir, "build.fan")
            val pod = if (build.isFile) {
                runCatching { podNameRegex.find(build.readText())?.groupValues?.get(1) }.getOrNull()
            } else null
            val children = (dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: emptyArray())
                .sortedBy { it.name }
            if (pod != null) {
                val roots = children.filter { c -> c.walkTopDown().any { it.isFile && it.extension == "fan" } }
                if (roots.isNotEmpty()) out += PodSource(pod, dir, roots)
                return
            }
            children.forEach(::visit)
        }
        if (src.isDirectory) visit(src)
        return out.sortedBy { it.pod }
    }

    /**
     * The `fantom-<version>` folders under [parent], newest version first.
     * Versions compare by their numeric parts, so `1.0.100` beats `1.0.84`.
     */
    fun versionedHomes(parent: File): List<File> {
        val dirs = parent.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val m = versionedName.matchEntire(dir.name) ?: return@mapNotNull null
            dir to m.groupValues[1].split('.').map { it.toInt() }
        }.sortedWith { a, b -> compareVersions(b.second, a.second) }
            .map { it.first }
    }

    private fun compareVersions(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}
