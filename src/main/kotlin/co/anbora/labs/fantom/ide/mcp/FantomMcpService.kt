package co.anbora.labs.fantom.ide.mcp

import co.anbora.labs.fantom.ide.compiler.FantomBuildTargets
import co.anbora.labs.fantom.ide.compiler.FantomCompilerDiagnostics
import co.anbora.labs.fantom.ide.compiler.FantomCompilerService
import co.anbora.labs.fantom.ide.compiler.FantomPodRoot
import co.anbora.labs.fantom.ide.run.FantomCommandLine
import co.anbora.labs.fantom.ide.run.FantomPodLaunch
import co.anbora.labs.fantom.ide.settings.FantomAgentSettings
import co.anbora.labs.fantom.lang.FantomLanguage
import co.anbora.labs.fantom.lang.library.FantomBuildFile
import co.anbora.labs.fantom.lang.library.FantomSettings
import co.anbora.labs.fantom.lang.library.deps.FantomDependencies
import co.anbora.labs.fantom.lang.library.deps.FantomDependencyResolver
import co.anbora.labs.fantom.lang.types.FantomTypeAt
import com.intellij.execution.ExecutionException
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * The MCP tools as suspend calls, with no MCP server types. Call them off the UI
 * thread. Each one takes short read actions for PSI work and does disk work (finding the
 * file, reading `build.fan`) between them. The read actions are the cancellable kind: a
 * write action (typing in the editor) stops one and runs it again later, and a cancelled
 * MCP call stops the search. In dumb mode each answers [FantomMcpQueries.INDEXING].
 */
object FantomMcpService {

    sealed class Answer {
        abstract val text: String
        data class Ok(override val text: String) : Answer()
        data class Fail(override val text: String) : Answer()
    }

    /** Ends a call early with [answer]. */
    private class Stop(val answer: Answer) : RuntimeException(answer.text, null, false, false)

    private fun fail(text: String): Nothing = throw Stop(Answer.Fail(text))

    suspend fun diagnostics(project: Project, path: String, limit: Int): Answer = guard(project) {
        val max = FantomMcpQueries.clampLimit(limit)
        val vf = locate(project, path)
        val (shown, psi, problems) = read(project) {
            val psi = sourceFile(project, vf, path)
            Triple(FantomMcpQueries.displayPath(project, vf), psi, FantomMcpQueries.problems(project, psi))
        }
        val compiler = FantomMcpQueries.compilerProblems(project, psi).orEmpty()
        val all = (problems + compiler).sortedWith(compareBy({ it.line }, { it.column }, { it.message }))
        Answer.Ok(list("$shown: ${count(all.size, "problem")}", all.map { it.render() }, max))
    }

    suspend fun resolve(
        project: Project, path: String, offset: Int, line: Int, column: Int, qualifiedName: String, limit: Int,
    ): Answer = guard(project) {
        val max = FantomMcpQueries.clampLimit(limit)
        val targets: List<PsiElement>
        val wantPod: String?
        if (path.isBlank()) {
            if (qualifiedName.isBlank()) fail("give path with offset or line and column, or give qualifiedName")
            val name = FantomMcpQueries.parseQualifiedName(qualifiedName)
                ?: fail("not a qualified name: $qualifiedName (use pod::Type.slot, Type.slot or Type)")
            targets = read(project) { FantomMcpQueries.findByQualifiedName(project, name) }
            wantPod = name.pod
        } else {
            val vf = locate(project, path)
            targets = read(project) {
                val psi = sourceFile(project, vf, path)
                FantomMcpQueries.targetsAt(psi, position(psi, offset, line, column))
            }
            wantPod = null
        }
        val pods = pods(project, targets)
        val lines = read(project) {
            targets.filter { it.isValid }
                .filter { e -> wantPod == null || e.containingFile?.virtualFile?.let { pods[it] } == wantPod }
                .map { "${FantomMcpQueries.describe(it, pods)}  ${FantomMcpQueries.location(project, it, FantomMcpQueries.nameOffset(it))}" }
        }
        Answer.Ok(if (lines.isEmpty()) "no declaration found" else list(count(lines.size, "target"), lines, max))
    }

    suspend fun usages(project: Project, path: String, offset: Int, line: Int, column: Int, limit: Int): Answer = guard(project) {
        val max = FantomMcpQueries.clampLimit(limit)
        val vf = locate(project, path)
        val target = read(project) {
            val psi = sourceFile(project, vf, path)
            FantomMcpQueries.usageTargetAt(psi, position(psi, offset, line, column))
                ?: fail("no declaration at that position")
        }
        val pods = pods(project, listOf(target))
        val text = read(project) {
            if (!target.isValid) fail("the file changed, retry")
            val refs = FantomMcpQueries.usages(project, target)
            val lines = refs.take(max).map { FantomMcpQueries.location(project, it.element, FantomMcpQueries.offsetOf(it)) }
            list("usages of ${FantomMcpQueries.describe(target, pods)}: ${refs.size}", lines, max, refs.size)
        }
        Answer.Ok(text)
    }

    // ---- type_of -----------------------------------------------------------------------

    /**
     * The inferred type at a place in a Fantom file, as Go to Type Declaration finds it: the
     * expression there, else the declaration named or referenced there. Or, with only
     * [qualifiedName], the declared type of each matching slot.
     */
    suspend fun typeOf(
        project: Project, path: String, offset: Int, line: Int, column: Int, qualifiedName: String, limit: Int,
    ): Answer = guard(project) {
        val max = FantomMcpQueries.clampLimit(limit)
        if (path.isBlank()) {
            if (qualifiedName.isBlank()) fail("give path with offset or line and column, or give qualifiedName")
            val name = FantomMcpQueries.parseQualifiedName(qualifiedName)
                ?: fail("not a qualified name: $qualifiedName (use pod::Type.slot, Type.slot or Type)")
            if (name.slot == null) fail("give a slot: pod::Type.slot or Type.slot")
            val targets = read(project) { FantomMcpQueries.findByQualifiedName(project, name) }
            val pods = pods(project, targets)
            val lines = read(project) {
                targets.filter { it.isValid }
                    .filter { e -> name.pod == null || e.containingFile?.virtualFile?.let { pods[it] } == name.pod }
                    .map { "${FantomMcpQueries.describe(it, pods)}: ${FantomTypeAt.declarationType(it)?.presentableText ?: "unknown"}" }
            }
            return@guard Answer.Ok(if (lines.isEmpty()) "no declaration found" else list(count(lines.size, "slot"), lines, max))
        }
        val vf = locate(project, path)
        val text = read(project) {
            val psi = sourceFile(project, vf, path)
            if (psi.language != FantomLanguage) fail("not a Fantom file: $path")
            val o = position(psi, offset, line, column)
            val expr = FantomTypeAt.expressionAt(psi, o)
            if (expr != null) return@read "${expr.presentableText}\nfrom: expression"
            val decl = FantomMcpQueries.usageTargetAt(psi, o) ?: fail("no expression or declaration at that position")
            val type = FantomTypeAt.declarationType(decl) ?: fail("type unknown at that position")
            "${type.presentableText}\nfrom: ${FantomMcpQueries.describe(decl, emptyMap())}"
        }
        Answer.Ok(text)
    }

    // ---- pod_deps ----------------------------------------------------------------------

    /**
     * The `depends` of a project pod from its `build.fan`, each with the source the plugin
     * resolved for the current build target. Reads the last dependency scan; never starts one.
     */
    suspend fun podDeps(project: Project, pod: String, path: String): Answer = guard(project) {
        val root = podRootVf(project, pod, path)
        val build = root.findChild(FantomBuildFile.FILE_NAME) ?: fail("no build.fan in ${root.name}")
        val parsed = read(project) {
            if (!FantomMcpQueries.isProjectFile(project, build)) fail("not a pod in the project: ${root.name}")
            FantomBuildFile.forFile(build, project)
        } ?: fail("cannot read build.fan of ${root.name}")
        val snap = FantomDependencies.getInstance(project).snapshot
        val target = FantomSettings.getInstance(project).defaultInstall()?.name
            ?: FantomSettings.getInstance(project).effectiveHome()?.name
        val projectRoots = snap.projectPods.associateWith { FantomPodLaunch.podRoot(project, it) }
        Answer.Ok(FantomMcpRun.depsReport(parsed.podName ?: root.name, parsed.depends, parsed.dependsChangedInCode,
            snap.rows, snap.projectPods, target, snap.rows.isEmpty() && snap.projectPods.isEmpty()) { p ->
            projectRoots[p]?.let { shownPath(project, it.path) }
        })
    }

    // ---- build and run_test --------------------------------------------------------------

    private val busy: MutableSet<Project> = ConcurrentHashMap.newKeySet()

    /** Builds a project pod with the default build target, as Build Pod does; returns its compiler messages. */
    suspend fun build(project: Project, pod: String, path: String, limit: Int): Answer = guard(project) {
        if (!FantomAgentSettings.getInstance(project).allowAgentRun) fail(FantomMcpRun.OFF)
        exclusive(project) {
            val root = podRootOf(project, pod, path)
            val name = podNameOf(project, root)
            val result = compile(project, root)
            Answer.Ok(FantomMcpRun.buildReport(name, result, { shownPath(project, it) }, FantomMcpQueries.clampLimit(limit)))
        }
    }

    /** Builds the pod, then runs `fant pod[::Type[.method]]` in its work folder; returns pass or fail per test. */
    suspend fun runTest(project: Project, pod: String, type: String, method: String, limit: Int): Answer = guard(project) {
        if (!FantomAgentSettings.getInstance(project).allowAgentRun) fail(FantomMcpRun.OFF)
        if (!FantomMcpRun.isIdentifier(pod)) fail("pod must be a plain name: $pod")
        if (type.isNotBlank() && !FantomMcpRun.isIdentifier(type)) fail("type must be a plain name: $type")
        if (method.isNotBlank() && !FantomMcpRun.isIdentifier(method)) fail("method must be a plain name: $method")
        if (type.isBlank() && method.isNotBlank()) fail("method needs a type")
        val max = FantomMcpQueries.clampLimit(limit)
        exclusive(project) {
            val root = podRootOf(project, pod, "")
            val result = compile(project, root)
            if (!FantomPodLaunch.passed(result)) {
                return@exclusive Answer.Ok("tests not run: the build failed\n" +
                    FantomMcpRun.buildReport(pod, result, { shownPath(project, it) }, max))
            }
            val env = FantomBuildTargets.defaultEnv(project) ?: fail(FantomMcpRun.NO_HOME)
            val spec = buildString {
                append(pod)
                if (type.isNotBlank()) append("::").append(type)
                if (method.isNotBlank()) append('.').append(method)
            }
            val workDir = FantomCompilerService.getInstance(project).workDir(root)
            val cmd = try {
                FantomCommandLine.build(project, env.runner, "fant", listOf(spec), null, emptyMap(), true, workDir)
                    .withRedirectErrorStream(true)
            } catch (e: ExecutionException) {
                fail(e.message ?: FantomMcpRun.NO_HOME)
            }
            val out = cancellable { indicator ->
                FantomMcpRun.run(cmd, FantomMcpRun.TEST_TIMEOUT_MS, FantomMcpRun.OUTPUT_CAP) { indicator.isCanceled }
            }
            val files = FantomMcpRun.sourceFiles(root) { shownPath(project, it) }
            Answer.Ok(FantomMcpRun.testReport(spec, FantomMcpRun.parseFant(out.text), out, { files[it] ?: it }, max))
        }
    }

    /** One build or test run per project at a time: a second Build Pod of the same pod stops the first. */
    private inline fun exclusive(project: Project, body: () -> Answer): Answer {
        if (!busy.add(project)) fail(FantomMcpRun.BUSY)
        try {
            return body()
        } finally {
            busy.remove(project)
        }
    }

    /** Saves the editors, as Build Pod does, then compiles [root] with the default build target and waits. */
    private suspend fun compile(project: Project, root: File) = run {
        val env = FantomBuildTargets.defaultEnv(project) ?: fail(FantomMcpRun.NO_HOME)
        ApplicationManager.getApplication().invokeAndWait { FileDocumentManager.getInstance().saveAllDocuments() }
        cancellable { indicator ->
            val service = FantomCompilerService.getInstance(project)
            var result = service.buildPod(root, env.runner, indicator, env.podPath)
            // A compile on save that starts meanwhile stops this one: build again, a few times at most.
            var tries = 0
            while (result != null && !result.completed && !indicator.isCanceled && tries++ < 3) {
                Thread.sleep(500)
                result = service.buildPod(root, env.runner, indicator, env.podPath)
            }
            result
        }
    }

    /**
     * Runs blocking [body] with an indicator that is cancelled when the MCP call is cancelled,
     * so a stopped call stops the wait and the process.
     */
    private suspend fun <T> cancellable(body: (EmptyProgressIndicator) -> T): T = coroutineScope {
        val indicator = EmptyProgressIndicator()
        val watcher = launch { try { awaitCancellation() } finally { indicator.cancel() } }
        try {
            body(indicator)
        } catch (_: ProcessCanceledException) {
            fail("stopped")
        } finally {
            watcher.cancel()
        }
    }

    /** The folder of a project pod on disk, by pod name or by a file in it. Never an install. */
    private suspend fun podRootOf(project: Project, pod: String, path: String): File =
        VfsUtilCore.virtualToIoFile(podRootVf(project, pod, path))

    /** The folder of a project pod, by pod name or by a file in it. Never an install. */
    private suspend fun podRootVf(project: Project, pod: String, path: String): VirtualFile {
        if (pod.isNotBlank()) {
            if (!FantomMcpRun.isIdentifier(pod)) fail("pod must be a plain name: $pod")
            val dir = FantomPodLaunch.podRoot(project, pod) ?: fail("no pod $pod in the project")
            return LocalFileSystem.getInstance().findFileByIoFile(dir) ?: fail("no pod $pod in the project")
        }
        if (path.isBlank()) fail("give pod or path")
        val vf = locate(project, path)
        val root = FantomPodRoot.find(vf) ?: fail("no build.fan with a podName above $path")
        if (!read(project) { com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).isInContent(root) }) {
            fail("not a pod in the project: $path")
        }
        return root
    }

    private suspend fun podNameOf(project: Project, root: File): String {
        val build = LocalFileSystem.getInstance().findFileByIoFile(File(root, FantomBuildFile.FILE_NAME)) ?: return root.name
        return read(project) { FantomBuildFile.forFile(build, project)?.podName } ?: root.name
    }

    /** A path for output: relative to the project folder when inside it, else the file name. */
    private fun shownPath(project: Project, path: String): String {
        val base = project.basePath?.let { FantomCompilerDiagnostics.canonical(it) }
        val p = FantomCompilerDiagnostics.canonical(path)
        return if (base != null && p.startsWith("$base/")) p.removePrefix("$base/") else File(p).name
    }

    // ---- helpers -----------------------------------------------------------------------

    private inline fun guard(project: Project, body: () -> Answer): Answer {
        if (project.isDisposed) return Answer.Fail("project closed")
        if (DumbService.isDumb(project)) return Answer.Fail(FantomMcpQueries.INDEXING)
        return try {
            body()
        } catch (e: Stop) {
            e.answer
        } catch (_: IndexNotReadyException) {
            Answer.Fail(FantomMcpQueries.INDEXING)
        }
    }

    /**
     * A read action that stops with [FantomMcpQueries.INDEXING] in dumb mode. It gives way
     * to write actions and runs [body] again after them, so [body] must have no side effects.
     */
    private suspend fun <T> read(project: Project, body: () -> T): T = readAction {
        if (project.isDisposed) fail("project closed")
        if (DumbService.isDumb(project)) fail(FantomMcpQueries.INDEXING)
        body()
    }

    /** Outside a read action: the file for [path]. [sourceFile] then checks that it is a project file. */
    private suspend fun locate(project: Project, path: String): VirtualFile {
        if (path.isBlank()) fail("path is empty")
        val roots = read(project) { ProjectRootManager.getInstance(project).contentRoots.toList() }
        return FantomMcpQueries.findFile(path, project.basePath, roots) ?: fail("file not found: $path")
    }

    /** Read action. */
    private fun sourceFile(project: Project, vf: VirtualFile, path: String): PsiFile {
        if (!FantomMcpQueries.isProjectFile(project, vf)) fail("not a file in the project: $path")
        return FantomMcpQueries.psiFile(project, vf) ?: fail("not a Fantom or Axon file: $path")
    }

    /** Read action. */
    private fun position(psi: PsiFile, offset: Int, line: Int, column: Int): Int =
        FantomMcpQueries.offsetOf(psi, offset, line, column) ?: fail("position outside the file")

    /** The pods of the Fantom files of [elements], found outside a read action. */
    private suspend fun pods(project: Project, elements: List<PsiElement>): Map<VirtualFile, String?> {
        val files = read(project) { FantomMcpQueries.fantomFiles(elements.filter { it.isValid }) }
        return files.associateWith { FantomMcpQueries.podName(project, it) }
    }

    private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

    private fun list(header: String, lines: List<String>, max: Int, total: Int = lines.size): String {
        val sb = StringBuilder(header)
        for (l in lines.take(max)) sb.append('\n').append(l)
        if (total > max) sb.append("\n... ").append(total - max).append(" more; raise limit (max ")
            .append(FantomMcpQueries.MAX_LIMIT).append(')')
        return sb.toString()
    }
}
