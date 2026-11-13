package co.anbora.labs.fantom.ide.compiler

import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomInstall
import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import java.io.File

/** Where a Haxall target's own pods come from when a pod is built for it. */
enum class HaxallPods {
    /** Search the `lib/fan` of the SkySpark of the same version. */
    BORROW,
    /** Build Haxall's pods from its sources into a temp folder first, then search that. */
    BUILD_FIRST,
    /** Use the pods an earlier [BUILD_FIRST] left in the temp folder, without building them again. */
    BUILT,
    /** Only the runner's own pods. */
    NONE,
}

/** One Build Pod run: the target, for Haxall where its pods come from, and whether to copy the pod into the target. */
data class FantomBuildRequest(
    val target: FantomInstall,
    val haxallPods: HaxallPods = HaxallPods.NONE,
    /** Copy the built pod into `<target>/lib/fan`. Off by default; the build itself never writes there. */
    val writeIntoTarget: Boolean = false,
)

/** The `bin/fan` folder to run and the extra folders searched for `lib/fan` pods (`fan.props` `path=`). */
data class FantomBuildEnv(val runner: File, val podPath: List<File>)

/** What a build for a target did. */
data class FantomBuildOutcome(
    val result: FantomCompileResult?,
    /** The pod file copied into the target, when [FantomBuildRequest.writeIntoTarget] was set and the build passed. */
    val written: File? = null,
    /** Why the build did not run, or why the copy failed. */
    val error: String? = null,
)

/**
 * Build targets: every ticked install of the settings page.
 *
 * A build compiles with the target's runner ([FantomInstall.runner]: SkySpark and Fantom
 * their own `bin/fan`, Haxall its paired Fantom's) in the pod's private work folder, so the
 * runtime's `Env` is a `PathEnv` over that folder: the pod lands in `<workDir>/lib/fan`,
 * never in an install. Pods resolve from the work folder, then [FantomBuildEnv.podPath],
 * then the runner's home, so a SkySpark target compiles against its own `lib/fan`.
 */
object FantomBuildTargets {
    private val LOG = logger<FantomBuildTargets>()

    /** Haxall builds its whole source tree: give it time. */
    const val HAXALL_TIMEOUT_MS = 15 * 60_000

    fun label(target: FantomInstall): String = "Build for ${target.name}"

    /** The environment for [target]; null when it has no runner (no paired Fantom yet). */
    fun env(project: Project, target: FantomInstall, haxallPods: HaxallPods): FantomBuildEnv? {
        val runner = target.runner ?: return null
        val path = if (target.kind != FantomHome.Kind.HAXALL) emptyList() else when (haxallPods) {
            HaxallPods.BORROW -> listOfNotNull(borrowFrom(target))
            HaxallPods.BUILD_FIRST, HaxallPods.BUILT -> listOf(FantomCompilerService.getInstance(project).haxallWorkDir(target.dir))
            HaxallPods.NONE -> emptyList()
        }
        return FantomBuildEnv(runner, path)
    }

    /** The SkySpark whose pods a Haxall target can borrow, when it exists. */
    fun borrowFrom(target: FantomInstall): File? = target.skysparkPath?.let(::File)?.takeIf { it.isDirectory }

    /** True when Haxall's pods were built before, so a build can use them without asking. */
    fun haxallPodsBuilt(project: Project, target: FantomInstall): Boolean =
        File(FantomCompilerService.getInstance(project).haxallWorkDir(target.dir), "lib/fan")
            .listFiles { f -> f.name.endsWith(".pod") }?.isNotEmpty() == true

    /** Haxall pods for a run that does not ask: built ones, else borrowed, else none. */
    fun autoHaxallPods(project: Project, target: FantomInstall): HaxallPods = when {
        target.kind != FantomHome.Kind.HAXALL -> HaxallPods.NONE
        haxallPodsBuilt(project, target) -> HaxallPods.BUILT
        borrowFrom(target) != null -> HaxallPods.BORROW
        else -> HaxallPods.NONE
    }

    /**
     * The environment of compile-on-save and the default build: the default target's, or
     * with no ticked install [FantomSettings.effectiveHome] with nothing extra.
     */
    fun defaultEnv(project: Project): FantomBuildEnv? {
        val settings = FantomSettings.getInstance(project)
        val def = settings.defaultInstall() ?: return settings.effectiveHome()?.let { FantomBuildEnv(it, emptyList()) }
        return env(project, def, autoHaxallPods(project, def))
    }

    /** The target the menu offers first: the last one used, else the default target. */
    fun preferred(project: Project): FantomInstall? {
        val settings = FantomSettings.getInstance(project)
        val targets = settings.buildTargets()
        return targets.firstOrNull { it.path == settings.lastBuildTarget }
            ?: targets.firstOrNull { it.path == settings.defaultTarget }
            ?: targets.firstOrNull()
    }

    /**
     * Builds the pod at [podRoot] for [request]'s target and waits. Remembers the target as
     * the last one. With [FantomBuildRequest.writeIntoTarget] and a passing build, copies
     * `<podName>.pod` into `<target>/lib/fan`; otherwise nothing outside the temp folders
     * is written.
     */
    fun build(project: Project, podRoot: File, podName: String, request: FantomBuildRequest, indicator: ProgressIndicator?): FantomBuildOutcome {
        val target = request.target
        FantomSettings.getInstance(project).lastBuildTarget = target.path
        val env = env(project, target, request.haxallPods)
            ?: return FantomBuildOutcome(null, error = "${target.name} has no Fantom to build with: ${target.warning ?: "choose one"} " +
                "in Settings | Languages & Frameworks | Fantom.")
        if (target.kind == FantomHome.Kind.HAXALL && request.haxallPods == HaxallPods.BUILD_FIRST) {
            buildHaxallPods(project, target, env.runner, indicator)?.let { return FantomBuildOutcome(null, error = it) }
        }
        val service = FantomCompilerService.getInstance(project)
        val result = service.buildPod(podRoot, env.runner, indicator, env.podPath)
        if (!request.writeIntoTarget || result == null || !result.completed || result.exitCode != 0) return FantomBuildOutcome(result)
        val built = File(service.workDir(podRoot), "lib/fan/$podName.pod")
        if (!built.isFile) return FantomBuildOutcome(result, error = "No $podName.pod in ${built.parent}")
        val dest = File(target.dir, "lib/fan/$podName.pod")
        return runCatching {
            dest.parentFile.mkdirs()
            built.copyTo(dest, overwrite = true)
            FantomBuildOutcome(result, written = dest)
        }.getOrElse { FantomBuildOutcome(result, error = "Could not write $dest: ${it.message}") }
    }

    /**
     * Builds every pod of the Haxall [target] from `src/build.fan` into its temp folder
     * ([FantomCompilerService.haxallWorkDir]). Returns an error text, or null on success.
     */
    fun buildHaxallPods(project: Project, target: FantomInstall, runner: File, indicator: ProgressIndicator?): String? {
        val script = File(target.dir, "src/build.fan")
        if (!script.isFile) return "${target.name} has no src/build.fan"
        val dir = FantomCompilerService.getInstance(project).haxallWorkDir(target.dir)
        if (!dir.isDirectory && !dir.mkdirs()) return "Cannot create $dir"
        if (!FantomCompilerDiagnostics.writeProps(dir, emptyList())) return "Cannot write $dir/fan.props"
        indicator?.text = "Building ${target.name} pods"
        val cmd = FantomCompilerDiagnostics.commandLine(runner, script, dir)
        val handler = CapturingProcessHandler(cmd)
        val out = if (indicator != null) handler.runProcessWithProgressIndicator(indicator, HAXALL_TIMEOUT_MS)
            else handler.runProcess(HAXALL_TIMEOUT_MS, true)
        if (LOG.isDebugEnabled) LOG.debug("haxall build -> ${out.exitCode}\n${out.stdout}\n${out.stderr}")
        return when {
            out.isCancelled -> "Build of ${target.name} pods cancelled"
            out.isTimeout -> "Build of ${target.name} pods timed out"
            out.exitCode != 0 -> "Build of ${target.name} pods failed: " +
                ((out.stdout + "\n" + out.stderr).lineSequence().lastOrNull { it.isNotBlank() && !it.startsWith(" ") } ?: "exit ${out.exitCode}")
            else -> null
        }
    }
}
