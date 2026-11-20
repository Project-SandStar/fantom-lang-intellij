package co.anbora.labs.fantom.ide.mcp

import co.anbora.labs.fantom.ide.axon.AxonDocumentationProvider
import co.anbora.labs.fantom.ide.mcp.FantomMcpService.Answer
import co.anbora.labs.fantom.lang.axon.copy.AxonFallbackFolder
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectCopy
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectKey
import co.anbora.labs.fantom.lang.axon.proj.AxonProjFolders
import co.anbora.labs.fantom.lang.axon.project.AxonFunctionSet
import co.anbora.labs.fantom.lang.axon.project.AxonProjectFunctions
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * The Axon project tools of [AxonMcpToolset] (task 4.31) as suspend calls, with no MCP server
 * types. They answer what the editor knows: the project of a file comes from
 * [AxonProjectFunctions], the chain that resolve, completion and quick doc use.
 *
 * Call them off the UI thread. PSI and index work runs in short read actions; reading
 * `.sync-metadata.json` and `<name>.trio` runs between them. In dumb mode each answers
 * [FantomMcpQueries.INDEXING]. No answer names or quotes a file that is neither in the IDE
 * project content nor in the active Axon project copy.
 */
object AxonMcpService {

    /** Most characters of source that [function] returns. */
    const val MAX_SOURCE = 64 * 1024

    /** Most characters of doc that [function] returns. */
    const val MAX_DOC = 16 * 1024

    /** Most characters of the doc line in a [functions] line. */
    private const val MAX_SUMMARY = 200

    /** A function file larger than this is not read. */
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024

    private const val MAX_TRIO_BYTES = 1024L * 1024

    private class Stop(val answer: Answer) : RuntimeException(answer.text, null, false, false)

    private fun fail(text: String): Nothing = throw Stop(Answer.Fail(text))

    private enum class Kind(val label: String) { FOLDER("proj folder"), COPY("active copy") }

    /** The functions of one Axon project, and where they come from. [folder] is the project folder of a proj folder. */
    private class Target(val set: AxonFunctionSet, val kind: Kind, val folder: VirtualFile?)

    private sealed class Lookup {
        class Found(val target: Target) : Lookup()
        class None(val why: String) : Lookup()
    }

    // ---- the tools ---------------------------------------------------------------------------

    suspend fun projectOf(project: Project, path: String): Answer = guard(project) {
        val vf = locate(project, path)
        when (val l = lookup(project, vf, path)) {
            is Lookup.None -> Answer.Ok("none: ${l.why}")
            is Lookup.Found -> Answer.Ok(read(project) { describe(project, l.target) })
        }
    }

    suspend fun functions(project: Project, path: String, projectKey: String, filter: String, limit: Int): Answer = guard(project) {
        val max = FantomMcpQueries.clampLimit(limit)
        val t = target(project, path, projectKey)
        val f = filter.trim()
        val text = read(project) {
            val all = t.set.functions().filter { f.isEmpty() || it.name.contains(f, ignoreCase = true) }.sortedBy { it.name }
            val manager = PsiManager.getInstance(project)
            val lines = all.take(max).map { fn ->
                ProgressManager.checkCanceled()
                val file = fileOf(t.set, fn.name, manager)?.takeIf { allowed(project, it) }
                buildString {
                    append(fn.name).append("  ").append(file?.let { shown(project, it) } ?: "(no file)")
                    fn.summary?.let { append("  ").append(it.take(MAX_SUMMARY)) }
                }
            }
            val matching = if (f.isEmpty()) "" else " matching \"$f\""
            list("${t.set.title} (${t.kind.label}): ${count(all.size, "function")}$matching", lines, max, all.size)
        }
        Answer.Ok(text)
    }

    suspend fun function(project: Project, path: String, projectKey: String, name: String): Answer = guard(project) {
        val t: Target
        val wanted: String
        if (name.isBlank()) {
            // the function of the file at path
            val vf = locate(project, path)
            t = found(lookup(project, vf, path))
            wanted = read(project) {
                PsiManager.getInstance(project).findFile(vf)?.let { t.set.functionOf(it) }?.name
            } ?: fail("not the file of a project function: $path; give name")
        } else {
            t = target(project, path, projectKey)
            wanted = name.trim()
        }
        val (fn, vf) = read(project) {
            val fn = t.set.function(wanted) ?: fail("no function $wanted in ${t.set.title} (${t.kind.label})")
            val vf = fileOf(t.set, wanted, PsiManager.getInstance(project)) ?: fail("no file for $wanted in ${t.set.title}")
            if (!allowed(project, vf)) fail("the file of $wanted is not in the project or the Axon copy")
            fn to vf
        }
        val tooLarge = vf.length > MAX_FILE_BYTES
        val mod = modOf(project, t, wanted, vf) ?: "unknown"
        val text = read(project) {
            if (!vf.isValid) fail("the file changed, retry")
            val psi = if (tooLarge) null else PsiManager.getInstance(project).findFile(vf)
            val info = AxonDocumentationProvider.info(psi)
            val src = if (tooLarge) null else FileDocumentManager.getInstance().getCachedDocument(vf)?.text ?: psi?.text
            val doc = info?.doc ?: fn.doc
            buildString {
                append(info?.signature ?: "$wanted()").append('\n')
                append("project: ").append(t.set.title).append(" (").append(t.kind.label).append(")\n")
                append("file: ").append(shown(project, vf)).append('\n')
                append("mod: ").append(mod).append('\n')
                append("doc:")
                if (doc.isNullOrBlank()) append(" none") else append('\n').append(cut(doc.trimEnd(), MAX_DOC))
                append("\nsrc:")
                when {
                    tooLarge -> append(" (not read: ").append(vf.length).append(" bytes)")
                    src == null -> append(" (not read)")
                    else -> append('\n').append(cut(src.trimEnd(), MAX_SOURCE))
                }
            }
        }
        Answer.Ok(text)
    }

    // ---- finding the project -----------------------------------------------------------------

    /** The project of [vf], as the editor sees it. */
    private suspend fun lookup(project: Project, vf: VirtualFile, path: String): Lookup {
        read(project) { if (!allowed(project, vf)) fail("not a file in the project or the Axon copy: $path") }
        val folders = AxonProjFolders.getInstance()
        // reads .sync-metadata.json now, so the title is the real key and not the folder names
        folders.projectOfNow(vf)
        return read(project) {
            when (val place = folders.placeOf(vf)) {
                AxonProjFolders.Place.Copy -> Lookup.None("a backup or version folder; names in it resolve to nothing")
                is AxonProjFolders.Place.InProject -> {
                    val func = place.folder.findChild(AxonProjFolders.FUNC)
                    if (func != null && !ProjectFileIndex.getInstance(project).isInContent(func)) {
                        fail("the func folder of that project is not open in the IDE")
                    }
                    AxonProjectFunctions.of(project, vf)?.let { Lookup.Found(Target(it, Kind.FOLDER, place.folder)) }
                        ?: Lookup.None("no functions for that project folder")
                }
                AxonProjFolders.Place.Outside ->
                    AxonProjectFunctions.of(project, vf)?.let { Lookup.Found(Target(it, Kind.COPY, null)) }
                        ?: Lookup.None(noCopy(project))
            }
        }
    }

    private fun found(l: Lookup): Target = when (l) {
        is Lookup.Found -> l.target
        is Lookup.None -> fail("no Axon project: ${l.why}")
    }

    /** The project for a tool call: by [path], else by [key] (`connection/project`), else the active copy. */
    private suspend fun target(project: Project, path: String, key: String): Target = when {
        path.isNotBlank() -> found(lookup(project, locate(project, path), path))
        key.isNotBlank() -> byKey(project, key)
        else -> read(project) {
            AxonProjectFunctions.of(project, null)?.let { Target(it, Kind.COPY, null) } ?: fail("no Axon project: ${noCopy(project)}")
        }
    }

    /**
     * The project folder of the IDE project whose key is [text], else the active copy when it
     * has that key. Two project folders with one key are an error, never a silent pick.
     */
    private suspend fun byKey(project: Project, text: String): Target {
        val key = AxonProjectKey.parse(text.trim()) ?: fail("not a project key: $text (use connection/project)")
        val dirs = read(project) {
            FilenameIndex.getVirtualFilesByName(AxonProjFolders.METADATA, GlobalSearchScope.projectScope(project))
                .mapNotNull { it.parent }.distinct()
        }
        val folders = AxonProjFolders.getInstance()
        val matches = dirs.mapNotNull { dir -> folders.projectOfNow(dir)?.takeIf { it.folder == dir && it.key == key } }
            .distinctBy { it.folder }
        return read(project) {
            when {
                matches.size > 1 -> fail(
                    "${matches.size} project folders are $key; give a path in one of them:\n" +
                        matches.joinToString("\n") { shown(project, it.folder) }
                )
                matches.size == 1 -> {
                    val folder = matches[0].folder
                    val set = AxonProjectFunctions.of(project, folder) ?: fail("no functions for $key")
                    Target(set, Kind.FOLDER, folder)
                }
                AxonProjectCopy.getInstance(project).copy?.key == key ->
                    Target(AxonProjectFunctions.of(project, null) ?: fail("no copy of $key yet"), Kind.COPY, null)
                else -> fail("no project $key in the IDE: no open proj folder has it, and it is not the active copy")
            }
        }
    }

    /** Read action. */
    private fun noCopy(project: Project): String =
        AxonProjectCopy.getInstance(project).activeKey?.let { "the active Axon project $it has no copy yet; refresh it from the status bar" }
            ?: "not in a proj folder, and no Axon project is active"

    /** Read action. */
    private fun describe(project: Project, t: Target): String {
        val sb = StringBuilder(t.set.title).append(" (").append(t.kind.label).append(')')
        val folder = when (t.kind) {
            Kind.FOLDER -> t.folder?.takeIf { ProjectFileIndex.getInstance(project).isInContent(it) }?.let { shown(project, it) }
            Kind.COPY -> AxonProjectCopy.getInstance(project).libraryRoot?.path
        }
        folder?.let { sb.append("\nfolder: ").append(it) }
        sb.append("\nfunctions: ").append(t.set.functions().size)
        return sb.toString()
    }

    // ---- files -------------------------------------------------------------------------------

    /** Read action. The `.axon` file of the function [name] of [set]. */
    private fun fileOf(set: AxonFunctionSet, name: String, manager: PsiManager): VirtualFile? =
        (set.declaration(name, manager) as? PsiFile)?.virtualFile

    /** Read action. A file in the IDE project content, or in the active Axon project copy. */
    private fun allowed(project: Project, vf: VirtualFile): Boolean =
        ProjectFileIndex.getInstance(project).isInContent(vf) || AxonProjectCopy.getInstance(project).isInCopy(vf)

    /** Read action. The path the other tools take back: short for project files, absolute for the copy. */
    private fun shown(project: Project, vf: VirtualFile): String =
        if (ProjectFileIndex.getInstance(project).isInContent(vf)) FantomMcpQueries.displayPath(project, vf) else vf.path

    /**
     * The `mod` of the function: from the copy's index, or from `<name>.trio` beside a proj
     * folder file. The folder holds `11/10/2023, 7:07:07 PM UTC`; both give ISO, so an agent
     * sees one form. A value that is not a time is kept as it is. Reads the disk.
     */
    private fun modOf(project: Project, t: Target, name: String, vf: VirtualFile): String? = when (t.kind) {
        Kind.COPY -> AxonProjectCopy.getInstance(project).copy?.entry(name)?.mod
        Kind.FOLDER -> vf.parent?.findChild("$name.trio")
            ?.takeIf { !it.isDirectory && it.length <= MAX_TRIO_BYTES }
            ?.let { trio -> runCatching { AxonFallbackFolder.trioTags(String(trio.contentsToByteArray(), trio.charset))["mod"] }.getOrNull() }
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { AxonFallbackFolder.isoOf(it) ?: it }
    }

    // ---- helpers -----------------------------------------------------------------------------

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

    /** A cancellable read action that stops with [FantomMcpQueries.INDEXING] in dumb mode. [body] must have no side effects. */
    private suspend fun <T> read(project: Project, body: () -> T): T = readAction {
        if (project.isDisposed) fail("project closed")
        if (DumbService.isDumb(project)) fail(FantomMcpQueries.INDEXING)
        body()
    }

    /** Outside a read action: the file or folder for [path]. [allowed] then checks it. */
    private suspend fun locate(project: Project, path: String): VirtualFile {
        if (path.isBlank()) fail("path is empty")
        val roots = read(project) { ProjectRootManager.getInstance(project).contentRoots.toList() }
        return FantomMcpQueries.findFile(path, project.basePath, roots) ?: fail("file not found: $path")
    }

    private fun cut(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "\n... cut at $max of ${text.length} characters"

    private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

    private fun list(header: String, lines: List<String>, max: Int, total: Int): String {
        val sb = StringBuilder(header)
        for (l in lines) sb.append('\n').append(l)
        if (total > max) sb.append("\n... ").append(total - max).append(" more; raise limit (max ")
            .append(FantomMcpQueries.MAX_LIMIT).append(") or give a name filter")
        return sb.toString()
    }
}
