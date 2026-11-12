package co.anbora.labs.fantom.lang.library

import java.io.File

/**
 * Finds a Fantom installation on this machine and lists its source roots.
 *
 * A Fantom home has `bin/fan` and a `src/` folder with one sub folder per pod.
 * The `.fan` sources of a pod are in `src/<pod>/fan`.
 *
 * Planned use: a later step adds an `AdditionalLibraryRootsProvider` that
 * returns one `SyntheticLibrary` per [sourceRoots] entry of [detect]. The
 * roots then join the project model, so [co.anbora.labs.fantom.lang.index.FantomDeclarationIndex]
 * covers `sys`, `concurrent`, `util` and the other pods, and Go to Class,
 * Go to Symbol and the reference resolver see the whole installation.
 * This object does not register anything yet; it is pure file lookup.
 */
object FantomHome {

    /** System property that names the home folder. Checked first. */
    const val PROPERTY = "fantom.home"

    /** Environment variable that names the home folder. Checked second. */
    const val ENV = "FAN_HOME"

    /** Default parent folder searched for `fantom-1.0.N` installs. Checked last. */
    /** Folder scanned for `fantom-<version>` installs when neither the property nor FAN_HOME is set. */
    val DEFAULT_PARENT: String = System.getProperty("user.home") + "/fantom"

    private val versionedName = Regex("fantom-(\\d+(?:\\.\\d+)*)")

    /**
     * The first valid home found, in this order: system property [PROPERTY],
     * env [ENV], then the newest `fantom-1.0.*` folder under [DEFAULT_PARENT].
     * Returns null when none of them is a Fantom home.
     */
    fun detect(): File? {
        val candidates = sequence {
            System.getProperty(PROPERTY)?.takeIf { it.isNotBlank() }?.let { yield(File(it)) }
            System.getenv(ENV)?.takeIf { it.isNotBlank() }?.let { yield(File(it)) }
            yieldAll(versionedHomes(File(DEFAULT_PARENT)))
        }
        return candidates.firstOrNull { isHome(it) }
    }

    /** True when [dir] has `bin/fan` and a `src/` folder. */
    fun isHome(dir: File): Boolean =
        dir.isDirectory && File(dir, "bin/fan").isFile && File(dir, "src").isDirectory

    /** Every `src/<pod>/fan` folder of [home] that exists, sorted by pod name. */
    fun sourceRoots(home: File): List<File> {
        val src = File(home, "src")
        val pods = src.listFiles { f -> f.isDirectory } ?: return emptyList()
        return pods.sortedBy { it.name }
            .map { File(it, "fan") }
            .filter { it.isDirectory }
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
