package co.anbora.labs.fantom.lang.library

import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * One Fantom, Haxall or SkySpark install as the settings table shows it.
 *
 * [fantomPath] is the plain Fantom install that supplies `sys` sources (and, for Haxall,
 * the `bin/fan` runner). A Fantom install is paired with itself. [pairing] says how the
 * pair was found; [matchSource] says from what ("folder", "sys.pod", "SkySpark 4.0.7").
 */
data class FantomInstall(
    val kind: FantomHome.Kind,
    val path: String,
    /** The install's own version: "1.0.84", "4.0.7"; null when unknown. */
    val version: String?,
    /** The Fantom version the install needs; null when unknown (Haxall with no SkySpark). */
    val fantomVersion: String?,
    /** The paired Fantom install; null when it is missing or not chosen yet. */
    val fantomPath: String?,
    val pairing: Pairing,
    val matchSource: String,
    val enabled: Boolean = false,
    /** Added with "Add folder", not found under a root. */
    val custom: Boolean = false,
    /** For Haxall: the SkySpark of the same version, whose pods a build can borrow. */
    val skysparkPath: String? = null,
) {
    enum class Pairing {
        /** A Fantom install, paired with itself. */
        SELF,
        /** Matched from the version. */
        AUTO,
        /** Chosen by the user. */
        CHOSEN,
        /** No Fantom of the needed version, or no version to match on. */
        MISSING,
    }

    val dir: File get() = File(path)

    /** "SkySpark 4.0.7"; the folder name follows when it is not the plain `<kind>-<version>`. */
    val name: String get() = displayName(kind, version, dir.name)

    /** Text for a missing pair, or null when the install is paired. */
    val warning: String?
        get() = if (pairing != Pairing.MISSING) null
        else if (fantomVersion != null) "Fantom $fantomVersion missing"
        else "Choose a Fantom"

    /**
     * The folder whose `bin/fan` builds for this install: SkySpark and Fantom use their own,
     * Haxall uses its own when it has one, else its paired Fantom's. Null when the install
     * has no paired Fantom: builds for it are off until one is chosen.
     */
    val runner: File?
        get() {
            if (pairing == Pairing.MISSING) return null
            val own = dir
            return when (kind) {
                FantomHome.Kind.HAXALL -> if (FantomHome.hasLauncher(own)) own
                    else fantomPath?.let(::File)?.takeIf(FantomHome::hasLauncher)
                else -> own.takeIf(FantomHome::hasLauncher)
            }
        }

    companion object {
        fun displayName(kind: FantomHome.Kind, version: String?, folder: String): String {
            if (version == null) return "${kind.label} ($folder)"
            val plain = "${kind.label.lowercase()}-$version"
            return if (folder == plain) "${kind.label} $version" else "${kind.label} $version ($folder)"
        }
    }
}

/**
 * Finds installs under the three roots and custom folders, reads their versions and
 * pairs each Haxall and SkySpark install with a Fantom install.
 *
 * Rules:
 * - Fantom: its version is the first three numbers of `lib/fan/sys.pod`'s `pod.version`;
 *   when there is no `sys.pod`, the folder name `fantom-<version>`.
 * - SkySpark: `sys.pod` holds the Fantom version plus a build number (`1.0.84.4007`). The
 *   first three numbers name the Fantom; the SkySpark version is the folder name
 *   `skyspark-<x.y.z>`, else read from the build number (4007 -> 4.0.7, 29 -> 3.0.29).
 * - Haxall records only `fan.depend=1.0`, so Haxall x.y.z takes the Fantom of SkySpark
 *   x.y.z when that SkySpark is found; else the user chooses.
 * - A pair the user chose wins over the automatic one.
 */
object FantomInstalls {

    /** The default roots: `~/fantom`, `~/haxall`, `~/skyspark`. */
    fun defaultRoot(kind: FantomHome.Kind): String =
        System.getProperty("user.home") + "/" + kind.label.lowercase()

    /** What the scan found before pairing. */
    data class Found(
        val kind: FantomHome.Kind,
        val dir: File,
        val version: String?,
        /** For Fantom: its own version; for SkySpark: the Fantom of its `sys.pod`. */
        val fantomVersion: String?,
        /** "sys.pod" or "folder". */
        val versionSource: String,
        val custom: Boolean,
    )

    /** What the user set for one install, see [FantomSettings.InstallEntry]. */
    data class Choice(val enabled: Boolean, val chosenFantom: String?, val custom: Boolean)

    private val sysVersionCache = ConcurrentHashMap<String, Pair<Long, String?>>()

    /** `pod.version` from `lib/fan/sys.pod`'s `meta.props`, or null. Cached by file time. */
    fun sysPodVersion(dir: File): String? {
        val pod = File(dir, "lib/fan/sys.pod")
        if (!pod.isFile) return null
        val stamp = pod.lastModified()
        sysVersionCache[pod.path]?.let { if (it.first == stamp) return it.second }
        val v = runCatching {
            ZipFile(pod).use { zip ->
                val entry = zip.getEntry("meta.props") ?: return@use null
                val props = Properties()
                zip.getInputStream(entry).use { props.load(it) }
                props.getProperty("pod.version")?.trim()
            }
        }.getOrNull()
        sysVersionCache[pod.path] = stamp to v
        return v
    }

    /** The first three numbers of [podVersion]: `1.0.84.4007` -> `1.0.84`. */
    fun fantomPart(podVersion: String): String = podVersion.split('.').take(3).joinToString(".")

    /** The build number of a SkySpark `sys.pod` version as a SkySpark version: 4007 -> 4.0.7, 3111 -> 3.1.11, 29 -> 3.0.29. */
    fun skysparkFromBuild(podVersion: String): String? {
        val build = podVersion.split('.').getOrNull(3)?.toIntOrNull() ?: return null
        return if (build >= 1000) "${build / 1000}.${(build / 100) % 10}.${build % 100}" else "3.0.$build"
    }

    private fun folderVersion(kind: FantomHome.Kind, name: String): String? =
        Regex("^${kind.label.lowercase()}-(\\d+(?:\\.\\d+)*)").find(name)?.groupValues?.get(1)

    /** Reads one folder; null when it is not an install. */
    fun read(dir: File, custom: Boolean = false): Found? {
        val kind = FantomHome.kindOf(dir) ?: return null
        val sys = sysPodVersion(dir)
        return when (kind) {
            FantomHome.Kind.FANTOM -> {
                val v = sys?.let(::fantomPart) ?: folderVersion(kind, dir.name)
                Found(kind, dir, v, v, if (sys != null) "sys.pod" else "folder", custom)
            }
            FantomHome.Kind.SKYSPARK -> Found(
                kind, dir,
                folderVersion(kind, dir.name) ?: sys?.let(::skysparkFromBuild),
                sys?.let(::fantomPart),
                "sys.pod", custom,
            )
            FantomHome.Kind.HAXALL -> Found(kind, dir, folderVersion(kind, dir.name), null, "SkySpark", custom)
        }
    }

    /**
     * Every install directly under [roots] (a root that is itself an install counts too),
     * then each of [customFolders]. Zips, project folders and other non-installs are
     * skipped by [FantomHome.kindOf]. Sorted by kind (Fantom, Haxall, SkySpark), newest first.
     */
    fun scan(roots: List<File>, customFolders: List<File> = emptyList()): List<Found> {
        val seen = HashSet<String>()
        val out = ArrayList<Found>()
        fun add(dir: File, custom: Boolean) {
            val key = canonical(dir)
            if (!seen.add(key)) return
            read(dir, custom)?.let(out::add)
        }
        for (root in roots) {
            if (!root.isDirectory) continue
            if (FantomHome.kindOf(root) != null) { add(root, false); continue }
            root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.forEach { add(it, false) }
        }
        customFolders.forEach { add(it, true) }
        return out.sortedWith(compareBy<Found> { it.kind.ordinal }
            .thenComparator { a, b -> compareVersions(b.version, a.version) }
            .thenBy { it.dir.name })
    }

    /**
     * Pairs every found install with a Fantom install, then applies the user's [choices]
     * (keyed by path): ticks, chosen Fantoms, custom marks.
     */
    fun pair(found: List<Found>, choices: Map<String, Choice> = emptyMap()): List<FantomInstall> {
        val fantoms = found.filter { it.kind == FantomHome.Kind.FANTOM }
        fun fantomFor(version: String?): Found? = version?.let { v -> fantoms.firstOrNull { it.version == v } }
        val skysparks = found.filter { it.kind == FantomHome.Kind.SKYSPARK }
        return found.map { f ->
            val choice = choices[f.dir.path]
            val custom = f.custom || choice?.custom == true
            val chosen = choice?.chosenFantom?.let(::File)?.takeIf { FantomHome.kindOf(it) == FantomHome.Kind.FANTOM }
            var skyspark: Found? = null
            val auto: FantomInstall = when (f.kind) {
                FantomHome.Kind.FANTOM -> FantomInstall(f.kind, f.dir.path, f.version, f.version, f.dir.path,
                    FantomInstall.Pairing.SELF, f.versionSource)
                FantomHome.Kind.SKYSPARK -> {
                    val match = fantomFor(f.fantomVersion)
                    FantomInstall(f.kind, f.dir.path, f.version, f.fantomVersion, match?.dir?.path,
                        if (match != null) FantomInstall.Pairing.AUTO else FantomInstall.Pairing.MISSING, "sys.pod")
                }
                FantomHome.Kind.HAXALL -> {
                    skyspark = skysparks.firstOrNull { it.version != null && it.version == f.version && it.fantomVersion != null }
                    val need = skyspark?.fantomVersion
                    val match = fantomFor(need)
                    FantomInstall(f.kind, f.dir.path, f.version, need, match?.dir?.path,
                        if (match != null) FantomInstall.Pairing.AUTO else FantomInstall.Pairing.MISSING,
                        skyspark?.let { FantomInstall.displayName(it.kind, it.version, it.dir.name) } ?: "no SkySpark match")
                }
            }
            val sameAsAuto = chosen != null && auto.fantomPath != null && canonical(chosen) == canonical(File(auto.fantomPath))
            val paired = if (chosen != null && f.kind != FantomHome.Kind.FANTOM && !sameAsAuto) {
                auto.copy(fantomPath = chosen.path, pairing = FantomInstall.Pairing.CHOSEN,
                    fantomVersion = read(chosen)?.version ?: auto.fantomVersion)
            } else auto
            paired.copy(enabled = choice?.enabled ?: false, custom = custom, skysparkPath = skyspark?.dir?.path)
        }
    }

    /**
     * The install table for the settings page: a fresh scan of [roots], the custom folders of
     * [persisted], and [migration]'s custom folder. Ticks, chosen Fantoms and custom marks come
     * from [persisted]; when no row was ever saved they come from [migration] instead, so the
     * single home the project had shows as the one ticked row.
     */
    fun rows(roots: List<File>, persisted: List<FantomInstall>, migration: FantomSettings.Migration?): List<FantomInstall> =
        pair(scan(roots, customFolders(persisted, migration)), choices(persisted, migration))

    /** The custom folders to scan: the persisted custom rows, else the migrated home outside the roots. */
    fun customFolders(persisted: List<FantomInstall>, migration: FantomSettings.Migration?): List<File> =
        persisted.filter { it.custom }.map { it.dir } + listOfNotNull(migration?.custom)

    /** The user's choices by path: from [persisted] rows, else from [migration] when nothing was saved yet. */
    fun choices(persisted: List<FantomInstall>, migration: FantomSettings.Migration?): Map<String, Choice> =
        if (persisted.isNotEmpty()) {
            persisted.associate { p ->
                p.path to Choice(p.enabled, p.fantomPath.takeIf { p.pairing == FantomInstall.Pairing.CHOSEN }, p.custom)
            }
        } else migration?.choices ?: emptyMap()

    /** The Fantom installs among [installs], for the pairing dropdown. */
    fun fantomChoices(installs: List<FantomInstall>): List<FantomInstall> =
        installs.filter { it.kind == FantomHome.Kind.FANTOM }

    fun canonical(dir: File): String = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)

    /** Compares dotted versions by their numbers; null sorts last. */
    fun compareVersions(a: String?, b: String?): Int {
        if (a == null || b == null) return if (a == b) 0 else if (a == null) -1 else 1
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}
