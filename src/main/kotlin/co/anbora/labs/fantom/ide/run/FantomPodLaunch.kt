package co.anbora.labs.fantom.ide.run

import co.anbora.labs.fantom.ide.compiler.FantomBuildPodAction
import co.anbora.labs.fantom.ide.compiler.FantomBuildTargets
import co.anbora.labs.fantom.ide.compiler.FantomCompileResult
import co.anbora.labs.fantom.ide.compiler.FantomCompilerService
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.configurations.WrappingRunConfiguration
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.io.File

/**
 * Run and Debug of a pod against the pod built from the current sources.
 *
 * The "Build Fantom pod" before-launch step ([FantomBuildBeforeRunTaskProvider]) compiles the
 * pod of a Fantom or Fantom Test configuration into its private work folder (see
 * [FantomCompilerService.workDir]) and records that folder on the [ExecutionEnvironment]. The
 * launch then starts `fan`/`fant` in that folder. Its `fan.props` makes `Env.cur` a `PathEnv`
 * whose path is the work folder, then the `path=` folders, then the runner's home, so the
 * fresh `<workDir>/lib/fan/<pod>.pod` wins over an installed, older copy.
 */
object FantomPodLaunch {
    /** The work folder the before-launch step built the pod into, for this launch only. */
    val BUILT_WORK_DIR: Key<File> = Key.create("fantom.pod.builtWorkDir")

    /**
     * The same, by execution id. The platform hands each before-launch step a copy of the
     * environment (same execution id), so user data put there never reaches the launch.
     */
    private val builtByExecution = java.util.Collections.synchronizedMap(object : LinkedHashMap<Long, File>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, File>?) = size > 32
    })

    /** Records [workDir] for the launch of [environment], or forgets it when null. */
    fun recordBuilt(environment: ExecutionEnvironment, workDir: File?) {
        environment.putUserData(BUILT_WORK_DIR, workDir)
        if (workDir == null) builtByExecution.remove(environment.executionId)
        else builtByExecution[environment.executionId] = workDir
    }

    /** `FAN_ENV` is checked before any `fan.props`; both would bring back the install's own env. */
    private val ENV_OVERRIDES = listOf("FAN_ENV", "FAN_ENV_PATH")

    /** The outcome of the before-launch build. */
    sealed class Outcome {
        /** No pod to build: a script path, or no `build.fan` for the pod in the project. */
        object NotApplicable : Outcome()
        data class Built(val workDir: File, val result: FantomCompileResult) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    /** The Fantom configuration behind [profile]; a Rerun Failed Tests profile gives its peer. */
    fun configurationOf(profile: RunProfile?): RunProfile? = when (profile) {
        is FantomRunConfiguration, is FantomTestRunConfiguration -> profile
        is WrappingRunConfiguration<*> -> profile.peer.takeIf { it is FantomTestRunConfiguration }
        else -> null
    }

    /** The pod name the configuration runs, or null for a script path or an empty target. */
    fun podNameOf(profile: RunProfile?): String? = when (val cfg = configurationOf(profile)) {
        is FantomTestRunConfiguration -> cfg.pod.trim().takeIf { it.isNotEmpty() }
        is FantomRunConfiguration -> podOfTarget(cfg.target)
        else -> null
    }

    /** `pod` of `pod`, `pod::Type` or `pod::Type.slot`; null for a `.fan` path or anything with a path separator. */
    fun podOfTarget(target: String): String? {
        val t = target.trim()
        if (t.isEmpty() || t.endsWith(".fan") || t.contains('/') || t.contains('\\')) return null
        val pod = t.substringBefore("::").trim()
        return pod.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '_' } }
    }

    private val podNameRegex = Regex("""podName\s*=\s*"([^"]+)"""")

    /** The folder of the project's `build.fan` whose `podName` is [podName], or null. Project files only, never an install. */
    fun podRoot(project: Project, podName: String): File? = ReadAction.computeBlocking<File?, RuntimeException> {
        if (project.isDisposed) return@computeBlocking null
        val builds: Collection<VirtualFile> = try {
            FilenameIndex.getVirtualFilesByName("build.fan", GlobalSearchScope.projectScope(project))
        } catch (_: IndexNotReadyException) {
            shallowBuildFiles(project)
        }
        builds.asSequence()
            .filter { !it.isDirectory }
            .firstOrNull { vf ->
                val text = runCatching { VfsUtilCore.loadText(vf) }.getOrNull() ?: return@firstOrNull false
                podNameRegex.find(text)?.groupValues?.get(1) == podName
            }
            ?.parent?.let(VfsUtilCore::virtualToIoFile)
    }

    /** `build.fan` files in the content roots and two levels below them, for dumb mode. */
    private fun shallowBuildFiles(project: Project): List<VirtualFile> = buildList {
        fun visit(dir: VirtualFile, depth: Int) {
            dir.findChild("build.fan")?.let(::add)
            if (depth < 2) dir.children.filter { it.isDirectory && !it.name.startsWith(".") }.forEach { visit(it, depth + 1) }
        }
        ProjectRootManager.getInstance(project).contentRoots.forEach { visit(it, 0) }
    }

    /**
     * Builds the pod of [profile] with the current build target (see [FantomBuildTargets.defaultEnv]).
     * Blocks the caller. Nothing is written into any install: the pod lands in the work folder.
     */
    fun build(project: Project, profile: RunProfile?, indicator: ProgressIndicator?): Outcome {
        val podName = podNameOf(profile) ?: return Outcome.NotApplicable
        val root = podRoot(project, podName) ?: return Outcome.NotApplicable
        val env = FantomBuildTargets.defaultEnv(project)
            ?: return Outcome.Failed("Build pod '$podName': no Fantom home with bin/fan. Set it in Settings | Languages & Frameworks | Fantom.")
        indicator?.text = "Building pod $podName"
        val service = FantomCompilerService.getInstance(project)
        val result = service.buildPod(root, env.runner, indicator, env.podPath)
        if (!passed(result)) return Outcome.Failed(FantomBuildPodAction.summary(podName, result).first)
        return Outcome.Built(service.workDir(root), result!!)
    }

    /** Completed, exit code 0 and no error message. */
    fun passed(result: FantomCompileResult?): Boolean =
        result != null && result.completed && result.exitCode == 0 &&
            result.byFile.values.none { list -> list.any { it.severity == HighlightSeverity.ERROR } }

    /** The work folder recorded for this launch, when the before-launch step built the pod. */
    fun builtWorkDir(environment: ExecutionEnvironment?): File? {
        if (environment == null || !stepEnabled(environment.runProfile)) return null
        val dir = environment.getUserData(BUILT_WORK_DIR) ?: builtByExecution[environment.executionId]
        return dir?.takeIf { File(it, "fan.props").isFile }
    }

    /** True when the configuration behind [profile] has the "Build Fantom pod" step switched on. */
    fun stepEnabled(profile: RunProfile?): Boolean =
        (configurationOf(profile) as? com.intellij.execution.configurations.RunConfiguration)
            ?.beforeRunTasks?.any { it is FantomBuildBeforeRunTask && it.isEnabled } == true

    /**
     * Makes [cmd] start in [workDir] and drops `FAN_ENV` / `FAN_ENV_PATH` from both the
     * configuration's and the inherited environment, so the runtime picks up `<workDir>/fan.props`.
     */
    fun applyWorkDir(cmd: GeneralCommandLine, workDir: File): GeneralCommandLine {
        if (cmd.parentEnvironmentType != GeneralCommandLine.ParentEnvironmentType.NONE) {
            val inherited = cmd.parentEnvironment.filterKeys { it !in ENV_OVERRIDES }
            val own = LinkedHashMap(cmd.environment)
            cmd.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
            cmd.environment.clear()
            cmd.environment.putAll(inherited)
            cmd.environment.putAll(own)
        }
        ENV_OVERRIDES.forEach { cmd.environment.remove(it) }
        cmd.withWorkDirectory(workDir)
        return cmd
    }
}
