package co.anbora.labs.fantom.lang.library

import co.anbora.labs.fantom.lang.pod.FantomPodStubs
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.Tag
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-project Fantom settings, stored in `.idea/fantom.xml`.
 *
 * [homePath] is the Fantom, Haxall or SkySpark home the project works against.
 * [sourcesPath] is an optional plain Fantom install that supplies `sys` and the core
 * pods when the home is Haxall or SkySpark; blank means the newest `~/fantom/fantom-*`.
 *
 * Install table (Settings | Languages & Frameworks | Fantom): [installs] holds every row
 * the page showed, with its pairing as found then, so nothing here scans folders or opens
 * `sys.pod`. When at least one row is ticked, the ticked rows replace [homePath] and
 * [sourcesPath]: [defaultTarget] is the home, its paired Fantom the sources, and every
 * ticked install feeds the index. With no ticked row the single-home fields rule, so a
 * project set up before the table works as before. See [migrate].
 */
@Service(Service.Level.PROJECT)
@State(name = "FantomSettings", storages = [Storage("fantom.xml")])
class FantomSettings : SimplePersistentStateComponent<FantomSettings.State>(State()) {

    class State : BaseState() {
        var homePath by string()
        /** Plain Fantom install for `sys` when the home is Haxall or SkySpark. */
        var sourcesPath by string()
        /** True once the "no Fantom installation" balloon was shown for this project. */
        var noHomeNoticeShown by property(false)
        /** Run `fan build.fan compile` on save and show the compiler's messages in the editor. */
        var compileOnSave by property(true)
        /** Root folders scanned for installs; null means `~/fantom`, `~/haxall`, `~/skyspark`. */
        var fantomRoot by string()
        var haxallRoot by string()
        var skysparkRoot by string()
        /** Every row of the install table, ticked or not. */
        var installs by list<InstallEntry>()
        /** Path of the install the index and run configurations default to. */
        var defaultTarget by string()
        /** Path of the install the last Build Pod ran for. */
        var lastBuildTarget by string()
    }

    /** One persisted row of the install table, see [FantomInstall]. */
    @Tag("install")
    class InstallEntry : BaseState() {
        var path by string()
        var kind by string()
        var version by string()
        var fantomVersion by string()
        var fantomPath by string()
        var pairing by string()
        var matchSource by string()
        var enabled by property(false)
        var custom by property(false)
        var skysparkPath by string()

        fun toInstall(): FantomInstall? {
            val p = path ?: return null
            val k = runCatching { FantomHome.Kind.valueOf(kind ?: "") }.getOrNull() ?: return null
            val pr = runCatching { FantomInstall.Pairing.valueOf(pairing ?: "") }.getOrDefault(FantomInstall.Pairing.MISSING)
            return FantomInstall(k, p, version, fantomVersion, fantomPath, pr, matchSource ?: "", enabled, custom, skysparkPath)
        }

        companion object {
            fun of(i: FantomInstall) = InstallEntry().apply {
                path = i.path
                kind = i.kind.name
                version = i.version
                fantomVersion = i.fantomVersion
                fantomPath = i.fantomPath
                pairing = i.pairing.name
                matchSource = i.matchSource
                enabled = i.enabled
                custom = i.custom
                skysparkPath = i.skysparkPath
            }
        }
    }

    var homePath: String?
        get() = state.homePath
        set(value) { state.homePath = value?.takeIf { it.isNotBlank() } }

    var sourcesPath: String?
        get() = state.sourcesPath
        set(value) { state.sourcesPath = value?.takeIf { it.isNotBlank() } }

    var compileOnSave: Boolean
        get() = state.compileOnSave
        set(value) { state.compileOnSave = value }

    var fantomRoot: String
        get() = state.fantomRoot ?: FantomInstalls.defaultRoot(FantomHome.Kind.FANTOM)
        set(value) { state.fantomRoot = rootValue(value, FantomHome.Kind.FANTOM) }

    var haxallRoot: String
        get() = state.haxallRoot ?: FantomInstalls.defaultRoot(FantomHome.Kind.HAXALL)
        set(value) { state.haxallRoot = rootValue(value, FantomHome.Kind.HAXALL) }

    var skysparkRoot: String
        get() = state.skysparkRoot ?: FantomInstalls.defaultRoot(FantomHome.Kind.SKYSPARK)
        set(value) { state.skysparkRoot = rootValue(value, FantomHome.Kind.SKYSPARK) }

    private fun rootValue(value: String, kind: FantomHome.Kind): String? =
        value.trim().takeIf { it.isNotEmpty() && it != FantomInstalls.defaultRoot(kind) }

    /** The three root folders: Fantom, Haxall, SkySpark. */
    fun roots(): List<File> = listOf(fantomRoot, haxallRoot, skysparkRoot).map(::File)

    /** Every persisted row, ticked or not. Setting it replaces the whole list. */
    var installs: List<FantomInstall>
        get() = state.installs.mapNotNull { it.toInstall() }
        set(value) {
            state.installs = value.mapTo(ArrayList()) { InstallEntry.of(it) }
            clearPodCache()
        }

    var defaultTarget: String?
        get() = state.defaultTarget
        set(value) {
            state.defaultTarget = value?.takeIf { it.isNotBlank() }
            clearPodCache()
        }

    var lastBuildTarget: String?
        get() = state.lastBuildTarget
        set(value) { state.lastBuildTarget = value?.takeIf { it.isNotBlank() } }

    /** The ticked installs whose folder still exists, the default target first. */
    fun enabledInstalls(): List<FantomInstall> {
        val on = buildTargets()
        val def = on.firstOrNull { it.path == defaultTarget } ?: on.firstOrNull() ?: return emptyList()
        return listOf(def) + on.filter { it !== def }
    }

    /** The install the index and runs default to; null when no row is ticked (single-home mode). */
    fun defaultInstall(): FantomInstall? = enabledInstalls().firstOrNull()

    /** The ticked installs a pod can be built for, in table order. */
    fun buildTargets(): List<FantomInstall> = installs.filter { it.enabled && it.dir.isDirectory }

    /** The default target, or else the configured home of any kind if valid, else the detected one, else null. */
    fun configuredHome(): File? {
        defaultInstall()?.let { return it.dir }
        val configured = homePath?.let(::File)
        if (configured != null && FantomHome.isHome(configured)) return configured
        return FantomHome.detect()
    }

    /**
     * The plain Fantom install that supplies `sys`: the default target's paired Fantom, or
     * in single-home mode the configured one if valid; else the detected one.
     */
    fun effectiveSourcesHome(): File? {
        defaultInstall()?.let { def ->
            return def.fantomPath?.let(::File)?.takeIf(FantomHome::isSources) ?: FantomHome.detectSources()
        }
        val configured = sourcesPath?.let(::File)
        if (configured != null && FantomHome.isSources(configured)) return configured
        return FantomHome.detectSources()
    }

    /**
     * The folder whose `bin/fan` runs builds and tests. With ticked installs: the default
     * target's runner ([FantomInstall.runner]: SkySpark its own `bin/fan`, Haxall its paired
     * Fantom's). Else the home when it has `bin/fan` (Fantom, SkySpark), else the Fantom
     * sources install (a Haxall checkout without `bin/fan`). Null when there is none.
     */
    fun effectiveHome(): File? {
        defaultInstall()?.let { return it.runner }
        val home = configuredHome()
        if (home != null && FantomHome.hasLauncher(home)) return home
        return effectiveSourcesHome()?.takeIf { FantomHome.hasLauncher(it) }
    }

    /**
     * The pods to index. With ticked installs see [installPodSources]. Else the home's pods,
     * then the Fantom sources' pods that the home does not have, then the stubs of the
     * home's compiled pods that have no sources (see [FantomPodStubs]; only stubs already
     * generated count). A plain Fantom home needs no second install.
     */
    fun podSources(): List<FantomHome.PodSource> {
        val ticked = enabledInstalls()
        if (ticked.isNotEmpty()) return installPodSources(ticked)
        val home = configuredHome()
        val kind = home?.let(FantomHome::kindOf)
        val sources = if (kind == FantomHome.Kind.FANTOM) null else effectiveSourcesHome()
        val key = (home?.path ?: "") + "|" + (sources?.path ?: "")
        return podCache.getOrPut(key) {
            val fromHome = home?.let(FantomHome::podSources) ?: emptyList()
            val names = fromHome.mapTo(HashSet()) { it.pod }
            val fromSources = sources?.let(FantomHome::podSources)?.filter { it.pod !in names } ?: emptyList()
            fromSources.mapTo(names) { it.pod }
            val fromStubs = stubPods(home, names).mapNotNull { FantomPodStubs.podSource(it) }
            fromHome + fromSources + fromStubs
        }
    }

    /**
     * The compiled pods that have no sources, see [FantomPodStubs]: every `lib/fan/<pod>.pod`
     * with no source pod of the same name. With ticked installs, each install is checked
     * against its own and its paired Fantom's sources. For SkySpark that is all of its pods
     * except the ones the Fantom sources supply.
     */
    fun podsNeedingStubs(): List<File> {
        val ticked = enabledInstalls()
        if (ticked.isNotEmpty()) {
            return ticked.flatMap { i -> stubPods(i.dir, ownSources(i).mapTo(HashSet()) { it.pod }) }.distinctBy { it.path }
        }
        val names = podSources().filter { !FantomPodStubs.isStub(it) }.mapTo(HashSet()) { it.pod }
        return stubPods(configuredHome(), names)
    }

    private fun stubPods(home: File?, withSources: Set<String>): List<File> =
        home?.let(FantomPodStubs::podFiles)?.filter { FantomPodStubs.podName(it) !in withSources } ?: emptyList()

    /** The paired Fantom of [install] when it can supply `sys`, else null. */
    private fun pairedSources(install: FantomInstall): File? =
        if (install.kind == FantomHome.Kind.FANTOM) null
        else install.fantomPath?.let(::File)?.takeIf(FantomHome::isSources)

    /** The pods [install] brings with sources: its own, then its paired Fantom's it lacks. */
    private fun ownSources(install: FantomInstall): List<FantomHome.PodSource> {
        val fromHome = FantomHome.podSources(install.dir)
        val names = fromHome.mapTo(HashSet()) { it.pod }
        val fromPair = pairedSources(install)?.let(FantomHome::podSources)?.filter { it.pod !in names } ?: emptyList()
        return fromHome + fromPair
    }

    /**
     * The pods of every ticked install, each with its paired Fantom's sources and the stubs
     * of its own compiled pods. Pods are de-duplicated by name; the default target comes
     * first, so its copy wins.
     */
    private fun installPodSources(ticked: List<FantomInstall>): List<FantomHome.PodSource> {
        val key = "installs|" + ticked.joinToString("|") { it.path + ">" + (it.fantomPath ?: "") }
        return podCache.getOrPut(key) {
            val out = ArrayList<FantomHome.PodSource>()
            val names = HashSet<String>()
            for (install in ticked) {
                val own = ownSources(install)
                val ownNames = own.mapTo(HashSet()) { it.pod }
                val stubs = stubPods(install.dir, ownNames).mapNotNull { FantomPodStubs.podSource(it) }
                for (pod in own + stubs) if (names.add(pod.pod)) out += pod
            }
            out
        }
    }

    /** The source roots to index, see [podSources]. */
    fun effectiveSources(): List<File> = podSources().flatMap { it.roots }

    /** The single-home settings as install table choices, see the companion [migrate]. */
    fun migrate(): Migration? = migrate(homePath, sourcesPath, roots())

    /**
     * Install table choices made from the single-home settings: [choices] by path, the
     * [defaultTarget], and [custom] when the home is outside the roots and needs its own row.
     */
    data class Migration(val choices: Map<String, FantomInstalls.Choice>, val defaultTarget: String, val custom: File?)

    companion object {
        fun getInstance(project: Project): FantomSettings = project.service()

        private val podCache = ConcurrentHashMap<String, List<FantomHome.PodSource>>()

        /** Forget the cached pod walks; call after the home or the sources change. */
        fun clearPodCache() = podCache.clear()

        /**
         * Turns the single-home settings into install table choices, so a project set up
         * before the table keeps what it had: [homePath] (or the detected home when blank)
         * becomes one ticked row and the default target; [sourcesPath], when valid and the
         * home is Haxall or SkySpark, becomes that row's chosen Fantom. A home that is not a
         * root or directly under one is marked custom. Null when there is no home.
         */
        fun migrate(homePath: String?, sourcesPath: String?, roots: List<File>): Migration? {
            val home = homePath?.let(::File)?.takeIf(FantomHome::isHome) ?: FantomHome.detect() ?: return null
            val kind = FantomHome.kindOf(home) ?: return null
            val sources = sourcesPath?.let(::File)?.takeIf(FantomHome::isSources)?.takeIf { kind != FantomHome.Kind.FANTOM }
            val canon = FantomInstalls.canonical(home)
            val parent = home.parentFile?.let(FantomInstalls::canonical)
            val underRoot = roots.map(FantomInstalls::canonical).any { it == canon || it == parent }
            val choices = mapOf(home.path to FantomInstalls.Choice(true, sources?.path, !underRoot))
            return Migration(choices, home.path, if (underRoot) null else home)
        }
    }
}
