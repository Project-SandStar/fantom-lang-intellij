package co.anbora.labs.fantom.lang.axon.copy

import co.anbora.labs.fantom.lang.axon.copy.AxonCopyException.Kind
import com.intellij.openapi.application.PathManager
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

/**
 * The local copies of Axon projects, outside every IDE project:
 *
 * ```
 * <system>/fantom-axon-cache/<instance>/<project>/func/<name>.axon   read-only
 * <system>/fantom-axon-cache/<instance>/<project>/index.json         name, file, doc, mod, hash
 * <system>/fantom-axon-cache/<instance>/<project>/.axon-copy.json    the marker, written last
 * ```
 *
 * A copy is written whole into a temp folder beside its place, the marker last, and
 * then renamed into place; the copy it replaces is moved aside first and deleted after.
 * So a fetch that fails, or an IDE that stops midway, leaves the old copy as it was. A
 * folder without a marker that fits its index is not a copy ([read] gives null).
 *
 * Instance, project and function names are encoded ([safeName]) before they become
 * file names, and every path is checked to be inside the cache before it is written.
 */
object AxonProjectCache {

    const val MARKER = ".axon-copy.json"
    const val INDEX = "index.json"
    const val FUNC = "func"
    const val EXT = ".axon"
    private const val VERSION = 1
    private const val TMP = ".tmp-"
    private const val OLD = ".old-"

    /** `<system>/fantom-axon-cache`. */
    fun defaultRoot(): File = PathManager.getSystemDir().resolve("fantom-axon-cache").toFile()

    /** A copy on disk: what the marker and the index say. */
    class Copy(
        val key: AxonProjectKey,
        val dir: File,
        /** `server` or `folder`. */
        val source: String,
        /** When the source last read SkySpark, or null. */
        val syncedAt: String?,
        /** When this copy was written. */
        val writtenAt: String?,
        val functions: List<AxonFunctionEntry>,
    ) {
        val funcDir: File get() = File(dir, FUNC)
        private val byName: Map<String, AxonFunctionEntry> = functions.associateBy { it.name }
        fun entry(name: String): AxonFunctionEntry? = byName[name]
        fun file(name: String): File? = byName[name]?.let { File(funcDir, it.file) }
    }

    /**
     * [name] as one file name that is safe on every file system: `A-Z a-z 0-9 _ -` stay,
     * every other byte becomes `%XX`. So the result holds no dot, slash, backslash, colon
     * or space: `..` is `%2E%2E`, `a/b` is `a%2Fb`. A Windows device name (`con`, `nul`,
     * ...) and an empty name get a `~`; a very long name is cut and ends in a hash.
     */
    fun safeName(name: String): String {
        val sb = StringBuilder()
        for (b in name.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            if (b >= 0 && (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-')) sb.append(c)
            else sb.append('%').append("%02X".format(b.toInt() and 0xff))
        }
        var s = sb.toString()
        if (s.length > 120) s = s.substring(0, 100) + "~" + AxonCopyLimits.sha256(name).substring(0, 16)
        if (s.isEmpty() || s.lowercase() in RESERVED) s += "~"
        return s
    }

    private val RESERVED = setOf("con", "prn", "aux", "nul") + (1..9).flatMap { listOf("com$it", "lpt$it") }

    /** The folder of [key] under [root]. Throws when it would not be inside [root]. */
    fun dirFor(root: File, key: AxonProjectKey): File =
        inside(root, File(File(root, safeName(key.instance)), safeName(key.project)))

    private fun inside(root: File, file: File): File {
        val r = root.absoluteFile.toPath().normalize()
        val f = file.absoluteFile.toPath().normalize()
        if (f == r || !f.startsWith(r)) throw AxonCopyException(Kind.IO, "A cache path is outside the Axon cache folder.")
        return file
    }

    /**
     * Writes [list] as the copy of its key and returns it. The copy that was there is
     * replaced only when every file of the new one is on disk. [check] is called per
     * function; it throws to cancel. Throws [AxonCopyException].
     */
    fun write(root: File, list: AxonFunctionList, source: String, check: () -> Unit = {}): Copy {
        AxonCopyLimits.validate(list)
        val dest = dirFor(root, list.key)
        val parent = dest.parentFile
        val tmp = inside(root, File(parent, dest.name + TMP + System.nanoTime()))
        try {
            val func = File(tmp, FUNC)
            if (!func.mkdirs()) throw IOException("cannot create ${tmp.name}")
            sweep(parent, dest.name, tmp)
            val entries = ArrayList<AxonFunctionEntry>(list.functions.size)
            val used = HashSet<String>()
            for (f in list.functions.sortedBy { it.name }) {
                check()
                // Two names that differ by case only cannot share a folder on macOS or Windows.
                var base = safeName(f.name)
                var n = 2
                while (!used.add(base.lowercase())) base = safeName(f.name) + "~" + n++
                val file = inside(func, File(func, base + EXT))
                file.writeText(f.src)
                file.setWritable(false, false)
                entries += AxonFunctionEntry(f.name, file.name, f.doc, f.mod, f.hash)
            }
            val writtenAt = Instant.now().toString()
            val index = linkedMapOf(
                "version" to VERSION,
                "key" to list.key.toString(),
                "functions" to entries.map { linkedMapOf("name" to it.name, "file" to it.file, "doc" to it.doc, "mod" to it.mod, "hash" to it.hash) },
            )
            File(tmp, INDEX).apply { writeText(AxonJson.write(index)); setWritable(false, false) }
            val marker = linkedMapOf(
                "version" to VERSION,
                "key" to list.key.toString(),
                "instance" to list.key.instance,
                "project" to list.key.project,
                "source" to source,
                "syncedAt" to list.syncedAt,
                "writtenAt" to writtenAt,
                "count" to entries.size,
                "functions" to entries.associateTo(LinkedHashMap()) { it.name to linkedMapOf("mod" to it.mod, "hash" to it.hash) },
            )
            check()
            // the marker is the last file: a folder without it is not a copy
            File(tmp, MARKER).apply { writeText(AxonJson.write(marker)); setWritable(false, false) }
            swap(tmp, dest)
            return Copy(list.key, dest, source, list.syncedAt, writtenAt, entries)
        } catch (e: IOException) {
            throw AxonCopyException(Kind.IO, "Cannot write the Axon copy: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            if (tmp.exists()) deleteTree(tmp)
        }
    }

    /** Puts [tmp] in the place of [dest]; when that fails, [dest] is as it was. */
    private fun swap(tmp: File, dest: File) = synchronized(this) {
        var old: File? = null
        if (dest.exists()) {
            old = File(dest.parentFile, dest.name + OLD + System.nanoTime())
            move(dest, old)
        }
        try {
            move(tmp, dest)
        } catch (e: IOException) {
            if (old != null) runCatching { move(old, dest) }
            throw e
        }
        if (old != null) deleteTree(old)
    }

    private fun move(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath())
        }
    }

    /**
     * Leftovers of a write that was cut off: an `.old-` folder with no copy beside it is
     * the copy (it is put back); other `.old-` and `.tmp-` folders older than an hour go.
     */
    private fun sweep(parent: File, name: String, keep: File?) = synchronized(this) {
        val dest = File(parent, name)
        val left = (parent.listFiles() ?: emptyArray()).filter {
            it != keep && it.isDirectory && (it.name.startsWith(name + OLD) || it.name.startsWith(name + TMP))
        }
        val olds = left.filter { it.name.startsWith(name + OLD) }.sortedByDescending { it.lastModified() }
        var restored: File? = null
        if (!dest.exists() && olds.isNotEmpty() && File(olds[0], MARKER).isFile) {
            restored = olds[0]
            runCatching { move(olds[0], dest) }
        }
        val hourAgo = System.currentTimeMillis() - 3_600_000
        for (f in left) if (f != restored && f.lastModified() < hourAgo) deleteTree(f)
    }

    /** The copy of [key] under [root], or null when there is none, or it is not whole. */
    fun read(root: File, key: AxonProjectKey): Copy? {
        val dir = runCatching { dirFor(root, key) }.getOrNull() ?: return null
        if (!dir.exists() && dir.parentFile.isDirectory) sweep(dir.parentFile, dir.name, null)
        val markerFile = File(dir, MARKER)
        val indexFile = File(dir, INDEX)
        if (!markerFile.isFile || !indexFile.isFile || !File(dir, FUNC).isDirectory) return null
        if (markerFile.length() > AxonCopyLimits.MAX_BYTES || indexFile.length() > AxonCopyLimits.MAX_BYTES) return null
        return runCatching {
            val marker = AxonJson.parseObject(markerFile.readText())
            if (AxonJson.str(marker["instance"]) != key.instance || AxonJson.str(marker["project"]) != key.project) return null
            val index = AxonJson.parseObject(indexFile.readText())
            val entries = (AxonJson.list(index["functions"]) ?: return null).map { row ->
                val o = AxonJson.obj(row) ?: return null
                val name = AxonJson.str(o["name"]) ?: return null
                val file = AxonJson.str(o["file"]) ?: return null
                // the index is our own file, but a file name from it is still checked
                if (file != File(file).name || !file.endsWith(EXT) || file.startsWith(".")) return null
                AxonFunctionEntry(name, file, AxonJson.str(o["doc"]), AxonJson.str(o["mod"]), AxonJson.str(o["hash"]).orEmpty())
            }
            if ((marker["count"] as? Number)?.toInt() != entries.size) return null
            Copy(key, dir, AxonJson.str(marker["source"]) ?: "", AxonJson.str(marker["syncedAt"]), AxonJson.str(marker["writtenAt"]), entries)
        }.getOrNull()
    }

    /** Deletes the copy of [key]. True when nothing of it is left. */
    fun delete(root: File, key: AxonProjectKey): Boolean {
        val dir = runCatching { dirFor(root, key) }.getOrNull() ?: return false
        synchronized(this) {
            // the marker goes first: what is left when the delete is cut off is not a copy
            File(dir, MARKER).let { if (it.exists()) { it.setWritable(true); it.delete() } }
            deleteTree(dir)
            val parent = dir.parentFile
            (parent.listFiles() ?: emptyArray())
                .filter { it.isDirectory && (it.name.startsWith(dir.name + OLD) || it.name.startsWith(dir.name + TMP)) }
                .forEach(::deleteTree)
            if (parent != root && parent.list()?.isEmpty() == true) parent.delete()
        }
        return !dir.exists()
    }

    /** Deletes [dir] and what is in it. Links are removed, never followed. */
    fun deleteTree(dir: File) {
        if (!Files.exists(dir.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        runCatching {
            Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    // Windows does not delete a read-only file
                    if (!attrs.isSymbolicLink) file.toFile().setWritable(true)
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult {
                    Files.deleteIfExists(d)
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }
}
