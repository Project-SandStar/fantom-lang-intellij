package co.anbora.labs.fantom.ide.mcp

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * The three MCP tools as plain blocking calls, with no MCP server types. Call them off the
 * UI thread. Each one takes short read actions for PSI work and does disk work (finding the
 * file, reading `build.fan`) between them. In dumb mode each answers [FantomMcpQueries.INDEXING].
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

    fun diagnostics(project: Project, path: String, limit: Int): Answer = guard(project) {
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

    fun resolve(
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

    fun usages(project: Project, path: String, offset: Int, line: Int, column: Int, limit: Int): Answer = guard(project) {
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

    /** A read action that stops with [FantomMcpQueries.INDEXING] in dumb mode. */
    private fun <T> read(project: Project, body: () -> T): T = ReadAction.compute<T, RuntimeException> {
        if (project.isDisposed) fail("project closed")
        if (DumbService.isDumb(project)) fail(FantomMcpQueries.INDEXING)
        body()
    }

    /** Outside a read action: the file for [path]. [sourceFile] then checks that it is a project file. */
    private fun locate(project: Project, path: String): VirtualFile {
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
    private fun pods(project: Project, elements: List<PsiElement>): Map<VirtualFile, String?> {
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
