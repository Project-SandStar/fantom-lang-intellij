package co.anbora.labs.fantom.lang.library

import co.anbora.labs.fantom.lang.pod.FantomPodStubs
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-project Fantom settings, stored in `.idea/fantom.xml`.
 *
 * [homePath] is the Fantom, Haxall or SkySpark home the project works against.
 * [sourcesPath] is an optional plain Fantom install that supplies `sys` and the core
 * pods when the home is Haxall or SkySpark; blank means the newest `~/fantom/fantom-*`.
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

    /** The configured home of any kind if valid, else the detected one, else null. */
    fun configuredHome(): File? {
        val configured = homePath?.let(::File)
        if (configured != null && FantomHome.isHome(configured)) return configured
        return FantomHome.detect()
    }

    /** The plain Fantom install that supplies `sys`: the configured one if valid, else the detected one. */
    fun effectiveSourcesHome(): File? {
        val configured = sourcesPath?.let(::File)
        if (configured != null && FantomHome.isSources(configured)) return configured
        return FantomHome.detectSources()
    }

    /**
     * The folder whose `bin/fan` runs builds and tests: the home when it has `bin/fan`
     * (Fantom, SkySpark), else the Fantom sources install (a Haxall checkout without
     * `bin/fan`). Null when neither has one.
     */
    fun effectiveHome(): File? {
        val home = configuredHome()
        if (home != null && FantomHome.hasLauncher(home)) return home
        return effectiveSourcesHome()?.takeIf { FantomHome.hasLauncher(it) }
    }

    /**
     * The pods to index: the home's pods, then the Fantom sources' pods that the home
     * does not have. A plain Fantom home needs no second install. SkySpark adds nothing.
     */
    fun podSources(): List<FantomHome.PodSource> {
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
     * The compiled pods of the home that have no sources, see [FantomPodStubs]: every
     * `lib/fan/<pod>.pod` with no source pod of the same name. For SkySpark that is all
     * of them except the ones the Fantom sources supply.
     */
    fun podsNeedingStubs(): List<File> {
        val names = podSources().filter { !FantomPodStubs.isStub(it) }.mapTo(HashSet()) { it.pod }
        return stubPods(configuredHome(), names)
    }

    private fun stubPods(home: File?, withSources: Set<String>): List<File> =
        home?.let(FantomPodStubs::podFiles)?.filter { FantomPodStubs.podName(it) !in withSources } ?: emptyList()

    /** The source roots to index, see [podSources]. */
    fun effectiveSources(): List<File> = podSources().flatMap { it.roots }

    companion object {
        fun getInstance(project: Project): FantomSettings = project.service()

        private val podCache = ConcurrentHashMap<String, List<FantomHome.PodSource>>()

        /** Forget the cached pod walks; call after the home or the sources change. */
        fun clearPodCache() = podCache.clear()
    }
}
