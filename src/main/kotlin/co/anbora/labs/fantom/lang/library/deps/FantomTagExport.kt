package co.anbora.labs.fantom.lang.library.deps

import com.intellij.openapi.application.PathManager
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Exports a tagged tree of a git clone, read-only, into a cache folder:
 * `<cacheRoot>/<repo>/<tag>/`. The clone's working copy, index and branch are never
 * touched: the export runs `git rev-parse <tag>^{commit}` and `git archive <commit>`,
 * then `tar -x` into a temp folder that is renamed into place. The commit marker
 * [MARKER] is written last, so a cut-off export is redone. A tag is exported again only
 * when its commit changes.
 */
object FantomTagExport {

    const val MARKER = ".fantom-tag-commit"

    /** `<system>/fantom-tag-cache`. */
    fun defaultCacheRoot(): File = PathManager.getSystemDir().resolve("fantom-tag-cache").toFile()

    data class Export(val dir: File, val commit: String, val reused: Boolean)

    /** The commit [tag] points to (peeled for an annotated tag), or null. */
    fun commitOf(clone: File, tag: String): String? =
        run(clone, listOf("git", "rev-parse", "--verify", "--quiet", "refs/tags/$tag^{commit}"))?.trim()?.takeIf { it.length >= 40 }

    /** The cache folder of [tag] of the repo named [repo]. */
    fun dirFor(cacheRoot: File, repo: String, tag: String): File =
        File(File(cacheRoot, safe(repo)), safe(tag))

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** The export of [tag] from [clone], reused when its marker names the same commit. Null when git fails. */
    fun export(clone: File, repo: String, tag: String, cacheRoot: File = defaultCacheRoot()): Export? {
        val commit = commitOf(clone, tag) ?: return null
        val dest = dirFor(cacheRoot, repo, tag)
        val marker = File(dest, MARKER)
        if (marker.isFile && runCatching { marker.readText().trim() }.getOrNull() == commit) return Export(dest, commit, true)
        dest.parentFile.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".tmp-" + System.nanoTime())
        val tar = File(dest.parentFile, dest.name + ".tar-" + System.nanoTime())
        try {
            if (!tmp.mkdirs()) return null
            run(clone, listOf("git", "archive", "--format=tar", "-o", tar.path, commit)) ?: return null
            run(tmp, listOf("tar", "-xf", tar.path)) ?: return null
            if (dest.exists()) dest.deleteRecursively()
            if (!tmp.renameTo(dest)) return null
            makeReadOnly(dest)
            dest.setWritable(true, true)
            File(dest, MARKER).writeText(commit)
            return Export(dest, commit, false)
        } finally {
            tar.delete()
            if (tmp.exists()) tmp.deleteRecursively()
        }
    }

    /** Marks the exported files read-only, so an edit in the IDE does not change the cache by accident. */
    private fun makeReadOnly(dir: File) {
        dir.walkTopDown().filter { it.isFile }.forEach { it.setWritable(false, false) }
    }

    /** Runs [cmd] in [dir]; its stdout, or null on a non-zero exit or after 60 s. */
    fun run(dir: File, cmd: List<String>): String? = runCatching {
        val p = ProcessBuilder(cmd).directory(dir).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
        if (p.exitValue() != 0) null else out
    }.getOrNull()
}
