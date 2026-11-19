package co.anbora.labs.fantom.lang.axon.proj

import co.anbora.labs.fantom.lang.axon.copy.AxonCopyException
import co.anbora.labs.fantom.lang.axon.copy.AxonCopyException.Kind
import co.anbora.labs.fantom.lang.axon.copy.AxonCopyLimits
import co.anbora.labs.fantom.lang.axon.copy.AxonFallbackFolder
import co.anbora.labs.fantom.lang.axon.copy.AxonFunction
import co.anbora.labs.fantom.lang.axon.copy.AxonFunctionList
import co.anbora.labs.fantom.lang.axon.copy.AxonJson
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectKey
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectSettings
import co.anbora.labs.fantom.lang.axon.copy.AxonServerSettings
import co.anbora.labs.fantom.lang.axon.copy.AxonSyncResult
import co.anbora.labs.fantom.lang.axon.copy.AxonSyncTarget
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/**
 * "Sync Axon Functions" in proj-folder mode: writes the functions that the Axon server
 * fetched from SkySpark (`sync=true`) into a project folder of the `proj` layout, in the
 * server's own form:
 *
 * ```
 * <project>/func/<name>.axon          the source
 * <project>/func/<name>.trio          the record: name, doc, mod, src (other tags are kept)
 * <project>/.sync-metadata.json       instance, project, lastSync, functionCount, functions
 * <project>/.versions/                old files, never deleted
 * ```
 *
 * The rules:
 * - A local `.axon` file whose hash is not the hash in `.sync-metadata.json` (or that has
 *   unsaved changes in an editor) was edited locally. It is kept; the SkySpark source goes
 *   beside it as `<name>.axon.skyspark` and the function is a conflict.
 * - Before a file is replaced, the old one is copied into `.versions/`.
 * - A function that is gone in SkySpark is moved into `.versions/`, never deleted.
 * - Every write goes through the VFS, in write actions of one batch each, all in one
 *   undo group. A project folder that does not exist yet is created.
 *
 * Nothing is written outside the project folder: a function name that is not a plain
 * Axon name stops the sync before anything is written.
 */
class AxonProjFolderSync : AxonSyncTarget {

    /**
     * A file or folder in a project folder of the layout, or the global proj folder (or a
     * connection folder in it) when a project is active: then the active project, in
     * `<global>/<connection>/<project>`, which may not exist yet. Memory and VFS only.
     */
    override fun targetFor(project: Project, file: VirtualFile): AxonSyncTarget.Target? {
        if (!file.isInLocalFileSystem) return null
        AxonProjFolders.getInstance().projectOf(file)?.let { return AxonSyncTarget.Target(it.key, File(it.folder.path)) }
        if (!file.isDirectory) return null
        val root = globalRoot() ?: return null
        val key = AxonProjectSettings.getInstance(project).activeKey ?: return null
        val atRoot = file.path == root
        val atConnection = file.parent?.path == root && file.name == key.instance
        if (!atRoot && !atConnection) return null
        return folderFor(File(root), key)?.let { AxonSyncTarget.Target(key, it) }
    }

    /** The key in `.sync-metadata.json` of the folder, read now. Background thread. */
    override fun prepare(project: Project, target: AxonSyncTarget.Target, check: () -> Unit): AxonSyncTarget.Target {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target.folder) ?: return target
        val now = AxonProjFolders.getInstance().projectOfNow(vf) ?: return target
        return if (File(now.folder.path) == target.folder) AxonSyncTarget.Target(now.key, target.folder) else target
    }

    override fun write(project: Project, target: AxonSyncTarget.Target, list: AxonFunctionList, check: () -> Unit): AxonSyncResult {
        checkNames(list.functions.map { it.name })
        val folder = target.folder.absoluteFile.toPath().normalize().toFile()
        for (f in listOf(folder, File(folder, FUNC), File(folder, VERSIONS), File(folder, METADATA))) {
            if (Files.isSymbolicLink(f.toPath())) throw AxonCopyException(Kind.IO, "${f.name} is a link; the sync writes into real files only.")
        }
        if (folder.exists() && !folder.isDirectory) throw AxonCopyException(Kind.IO, "${folder.name} is not a folder.")

        // ---- read the folder as it is: background thread, no read action ----
        val lfs = LocalFileSystem.getInstance()
        val dirVf = lfs.refreshAndFindFileByIoFile(folder)
        if (dirVf != null) VfsUtil.markDirtyAndRefresh(false, true, true, dirVf)
        check()
        val metaText = dirVf?.findChild(METADATA)?.takeIf { !it.isDirectory }?.let(::readText)
        val oldMeta = metaText?.let { runCatching { AxonJson.parseObject(it) }.getOrNull() }
        val oldFns = AxonJson.obj(oldMeta?.get("functions")).orEmpty()
        val funcVf = dirVf?.findChild(FUNC)?.takeIf { it.isDirectory }
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val nowIso = JS_ISO.format(now)

        fun file(name: String): VirtualFile? {
            val vf = funcVf?.findChild(name) ?: return null
            // a link could lead out of the project folder: stop before anything is written
            if (Files.isSymbolicLink(File(vf.path).toPath())) throw AxonCopyException(Kind.IO, "$name is a link; the sync writes into real files only.")
            return vf.takeIf { !it.isDirectory }
        }

        val steps = ArrayList<Step>(list.functions.size)
        for (f in list.functions) {
            check()
            val entry = AxonJson.obj(oldFns[f.name])
            val axon = file("${f.name}$AXON")
            val local = axon?.let(::localOf)
            val trioVf = file("${f.name}$TRIO")
            val trioLocal = trioVf?.let(::localOf)
            file("${f.name}$SKYSPARK"); file("${f.name}$TRIO_SKYSPARK")
            val state = when {
                local == null -> State.ADDED
                local.text == f.src -> State.UNCHANGED
                local.unsaved -> State.CONFLICT
                sameHash(AxonJson.str(entry?.get("hash")), AxonCopyLimits.sha256(local.text)) -> State.CHANGED
                else -> State.CONFLICT
            }
            val trio = if (state == State.CONFLICT) null else trioText(trioLocal?.text, f)
            val trioEdited = trioLocal != null && trio != trioLocal.text && trioEdited(trioLocal, entry)
            steps += Step(f, state, axon, axon?.modificationStamp ?: -1, trio, trioVf, trioVf?.modificationStamp ?: -1, trioEdited)
        }
        // on a disk that ignores case, a gone "Foo" can find the file of a listed "foo": that file is not Foo's
        val listedFiles = steps.flatMap { listOfNotNull(it.axon, it.trioVf) }.toSet()
        val gone = oldFns.keys.filter { NAME.matches(it) && list.functions.none { f -> f.name == it } }.sorted().map { name ->
            check()
            val entry = AxonJson.obj(oldFns[name])
            fun own(ext: String) = file(name + ext)?.takeIf { it !in listedFiles }
            val files = listOf(AXON, TRIO, SKYSPARK, TRIO_SKYSPARK).mapNotNull(::own)
            val axon = own(AXON)?.let(::localOf)
            val trio = own(TRIO)?.let(::localOf)
            val edited = (axon != null && (axon.unsaved || !sameHash(AxonJson.str(entry?.get("hash")), AxonCopyLimits.sha256(axon.text)))) ||
                (trio != null && trioEdited(trio, entry))
            Gone(name, files.map { it to it.modificationStamp }, edited)
        }

        // ---- write: one write action per batch, all in one undo group ----
        val writer = Writer(project, now)
        var complete = false
        try {
            for (batch in steps.chunked(BATCH)) {
                check()
                writer.command {
                    val func = writer.dir(writer.root(folder), FUNC)
                    for (s in batch) {
                        writeStep(writer, folder, func, s)
                        s.done = true
                    }
                }
            }
            check()
            writer.command {
                val func = writer.root(folder).findChild(FUNC)
                // on a disk that ignores case, a gone "Foo" can be the file of a listed "foo"
                val listed = steps.flatMap { s -> listOf(AXON, TRIO).mapNotNull { func?.findChild(s.f.name + it) } }.toSet()
                for (g in gone) {
                    g.done = true
                    val own = g.files.filter { it.first.isValid && it.first !in listed }
                    if (g.edited || own.any { (vf, stamp) -> !fresh(vf, stamp) }) { g.kept = true; continue }
                    own.forEach { writer.moveToVersions(folder, it.first) }
                }
            }
            complete = true
        } finally {
            // also after an error or a cancel: the metadata must know the files that were written,
            // or the next sync takes them for local edits
            if (complete || steps.any { it.done } || gone.any { it.done }) {
                val text = metadata(target.key, list, oldMeta, oldFns, steps, gone, nowIso, complete)
                if (complete) {
                    writer.command { writer.put(folder, writer.root(folder), METADATA, text, keep = false) }
                } else {
                    runCatching { writer.command { writer.put(folder, writer.root(folder), METADATA, text, keep = false) } }
                }
            }
            (lfs.findFileByIoFile(folder) ?: dirVf)?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }
        }

        return AxonSyncResult(
            target.key,
            added = steps.filter { it.state == State.ADDED }.map { it.f.name }.sorted(),
            changed = steps.filter { it.state == State.CHANGED }.map { it.f.name }.sorted(),
            removed = gone.filter { !it.kept }.map { it.name },
            total = list.functions.size,
            conflicts = steps.filter { it.state == State.CONFLICT || it.trioConflict }.map { it.f.name }.sorted(),
            unchanged = steps.filter { it.state == State.UNCHANGED }.map { it.f.name }.sorted(),
            keptGone = gone.filter { it.kept }.map { it.name },
        )
    }

    /** The files of one function. Write action. */
    private fun writeStep(writer: Writer, folder: File, func: VirtualFile, s: Step) {
        val name = s.f.name
        var state = s.state
        // changed since the plan, or an unsaved change now: a local edit
        if (state == State.CHANGED && !fresh(s.axon, s.stamp)) state = State.CONFLICT
        // a file that appeared since the plan is kept: it is a conflict
        if (state == State.ADDED && func.findChild("$name$AXON") != null) state = State.CONFLICT
        s.state = state
        when (state) {
            State.ADDED, State.CHANGED -> writer.put(folder, func, "$name$AXON", s.f.src)
            State.UNCHANGED -> {}
            State.CONFLICT -> { writer.put(folder, func, "$name$SKYSPARK", s.f.src); return }
        }
        writer.fixCase(s.axon, "$name$AXON")
        val trio = s.trio ?: return
        val trioNow = func.findChild("$name$TRIO")
        if (!s.trioConflict && (if (s.trioVf == null) trioNow != null else !fresh(s.trioVf, s.trioStamp))) s.trioConflict = true
        if (s.trioConflict) writer.put(folder, func, "$name$TRIO_SKYSPARK", trio)
        else { writer.put(folder, func, "$name$TRIO", trio); writer.fixCase(s.trioVf, "$name$TRIO") }
    }

    // ---- the plan --------------------------------------------------------------------------------

    private enum class State { ADDED, CHANGED, UNCHANGED, CONFLICT }

    /**
     * What the sync does with one function. [stamp] and [trioStamp] are the VFS stamps of the
     * `.axon` and `.trio` files when they were read; [trio] is the SkySpark trio, null for a
     * conflict. [trioConflict]: the local trio was edited and is kept. [done]: written.
     */
    private class Step(
        val f: AxonFunction, var state: State, val axon: VirtualFile?, val stamp: Long,
        val trio: String?, val trioVf: VirtualFile?, val trioStamp: Long, var trioConflict: Boolean,
    ) {
        @Volatile var done = false
    }

    /** A function gone in SkySpark: its files with their stamps, and whether one was edited locally. */
    private class Gone(val name: String, val files: List<Pair<VirtualFile, Long>>, val edited: Boolean) {
        @Volatile var done = false
        @Volatile var kept = false
    }

    /** The text of a local file: the editor's text when it has unsaved changes. */
    private class Local(val text: String, val unsaved: Boolean)

    private fun localOf(vf: VirtualFile): Local? {
        val fdm = FileDocumentManager.getInstance()
        val unsaved = ReadAction.computeBlocking<String?, RuntimeException> {
            fdm.getCachedDocument(vf)?.takeIf { fdm.isDocumentUnsaved(it) }?.text
        }
        if (unsaved != null) return Local(unsaved, true)
        return readText(vf)?.let { Local(it, false) }
    }

    /**
     * A trio was edited locally: it has unsaved changes, or it is not the trio the folder last
     * got from SkySpark ([TRIO_HASH] in the entry). A trio written by the server has no such
     * hash; it is replaced, and the old one is kept in `.versions/`.
     */
    private fun trioEdited(trio: Local, entry: Map<String, Any?>?): Boolean {
        if (trio.unsaved) return true
        val stored = AxonJson.str(entry?.get(TRIO_HASH)) ?: return false
        return !sameHash(stored, AxonCopyLimits.sha256(trio.text))
    }

    /** [vf] is as it was read, with no unsaved change. Write action. */
    private fun fresh(vf: VirtualFile?, stamp: Long): Boolean {
        if (vf == null) return true
        if (!vf.isValid || vf.modificationStamp != stamp) return false
        val fdm = FileDocumentManager.getInstance()
        return fdm.getCachedDocument(vf)?.let { !fdm.isDocumentUnsaved(it) } ?: true
    }

    private fun readText(vf: VirtualFile): String? =
        if (vf.length > AxonCopyLimits.MAX_BYTES) null
        else runCatching { String(vf.contentsToByteArray(), Charsets.UTF_8) }.getOrNull()

    /**
     * `.sync-metadata.json` in the server's form. A function the folder did not take (a
     * conflict, or not written before an error) keeps its old entry. [complete] is false
     * after an error or a cancel: then `lastSync` stays and the count is the entries'.
     */
    private fun metadata(
        key: AxonProjectKey,
        list: AxonFunctionList,
        old: Map<String, Any?>?,
        oldFns: Map<String, Any?>,
        steps: List<Step>,
        gone: List<Gone>,
        nowIso: String,
        complete: Boolean,
    ): String {
        val out = LinkedHashMap<String, Any?>()
        out["instance"] = key.instance
        out["project"] = key.project
        out["lastSync"] = if (!complete) old?.get("lastSync") ?: nowIso
            else list.syncedAt?.let { AxonFallbackFolder.instantOf(it) }?.let { JS_ISO.format(it) } ?: nowIso
        out["functionCount"] = list.functions.size
        val fns = LinkedHashMap<String, Any?>()
        for (s in steps.sortedBy { it.f.name }) {
            val before = AxonJson.obj(oldFns[s.f.name])
            if (!s.done || s.state == State.CONFLICT) {
                // the folder did not get this version: the entry stays as it was
                if (before != null) fns[s.f.name] = before
                continue
            }
            val e = LinkedHashMap<String, Any?>()
            val modIso = AxonFallbackFolder.instantOf(s.f.mod)?.let { JS_ISO.format(it) }
            e["name"] = s.f.name
            e["hash"] = shortHash(s.f.src)
            // the server writes lastModified in the zone it runs in: keep its text while the mod is the same
            e["lastModified"] = before?.get("lastModified")?.takeIf { modIso != null && before["modIso"] == modIso }
                ?: AxonFallbackFolder.folderTimeOf(s.f.mod) ?: before?.get("lastModified")
            e["synced"] = nowIso
            e["modIso"] = modIso
            e["doc"] = s.f.doc
            // the trio the folder got from SkySpark: a local trio that differs from it is an edit
            s.trio?.let { e[TRIO_HASH] = shortHash(it) }
            // the server's other keys of an unchanged function are still right
            if (before != null && s.state == State.UNCHANGED) for ((k, v) in before) if (k !in e) e[k] = v
            fns[s.f.name] = e
        }
        for (g in gone) if (!g.done || g.kept) AxonJson.obj(oldFns[g.name])?.let { fns[g.name] = it }
        out["functions"] = fns
        if (!complete) out["functionCount"] = fns.size
        old?.forEach { (k, v) -> if (k !in out) out[k] = v }
        return AxonJson.write(out)
    }

    // ---- the writes ----------------------------------------------------------------------------

    /** The VFS writes of one sync. Every method but [command] runs in a write action. */
    private class Writer(private val project: Project, private val now: Instant) {
        private val group = "AxonProjFolderSync-" + UUID.randomUUID()
        private val stamp = STAMP.format(now)

        /** [work] in a write action on the UI thread, in the sync's undo group. Blocks. */
        fun command(work: () -> Unit) {
            var error: Throwable? = null
            ApplicationManager.getApplication().invokeAndWait({
                try {
                    WriteCommandAction.writeCommandAction(project)
                        .withName("Sync Axon Functions")
                        .withGroupId(group)
                        .withGlobalUndo()
                        .run<Exception> { work() }
                } catch (e: Throwable) {
                    error = e
                }
            }, ModalityState.defaultModalityState())
            when (val e = error) {
                null -> {}
                is AxonCopyException -> throw e
                is java.io.IOException -> throw AxonCopyException(Kind.IO, "Cannot write the project folder: ${e.message}", e)
                else -> throw e
            }
        }

        /** The project folder, created with its parents when it does not exist. */
        fun root(folder: File): VirtualFile =
            VfsUtil.createDirectoryIfMissing(folder.path) ?: throw AxonCopyException(Kind.IO, "Cannot create ${folder.path}.")

        fun dir(parent: VirtualFile, name: String): VirtualFile {
            val d = parent.findChild(name)
            if (d != null && d.isDirectory) return d
            if (d != null) throw AxonCopyException(Kind.IO, "$name is not a folder.")
            return parent.createChildDirectory(this, name)
        }

        /**
         * Writes [text] into [dir]/[name]. With [keep], the old file goes to `.versions/` first
         * when it differs.
         */
        fun put(folder: File, dir: VirtualFile, name: String, text: String, keep: Boolean = true) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val old = dir.findChild(name)
            if (old != null) {
                if (old.isDirectory) throw AxonCopyException(Kind.IO, "$name is a folder.")
                if (old.length == bytes.size.toLong() && old.contentsToByteArray().contentEquals(bytes)) return
                if (keep) copyToVersions(folder, old)
            }
            (old ?: dir.createChildData(this, name)).setBinaryContent(bytes)
        }

        /** On a disk that ignores case, [vf] found as [name] may have another case: give it [name]. */
        fun fixCase(vf: VirtualFile?, name: String) {
            if (vf != null && vf.isValid && vf.name != name && vf.name.equals(name, ignoreCase = true)) vf.rename(this, name)
        }

        fun copyToVersions(folder: File, file: VirtualFile) {
            val versions = dir(root(folder), VERSIONS)
            VfsUtilCore.copyFile(this, file, versions, versionName(versions, file.name))
        }

        fun moveToVersions(folder: File, file: VirtualFile) {
            val versions = dir(root(folder), VERSIONS)
            val target = versionName(versions, file.name)
            if (versions.findChild(file.name) == null) {
                file.move(this, versions)
                file.rename(this, target)
            } else {
                file.rename(this, target)
                file.move(this, versions)
            }
        }

        /** `siteArea.axon` becomes `siteArea_2026-05-06T07-08-09-123Z.axon`, as the server names them. */
        private fun versionName(versions: VirtualFile, name: String): String {
            val dot = name.indexOf('.', 1).let { if (it < 0) name.length else it }
            val base = name.substring(0, dot) + "_" + stamp
            val ext = name.substring(dot)
            var candidate = base + ext
            var n = 2
            while (versions.findChild(candidate) != null || versions.parent?.findChild(FUNC)?.findChild(candidate) != null) {
                candidate = "$base~${n++}$ext"
            }
            return candidate
        }
    }

    companion object {
        const val METADATA = AxonProjFolders.METADATA
        const val FUNC = AxonProjFolders.FUNC
        const val VERSIONS = ".versions"
        const val AXON = ".axon"
        const val TRIO = ".trio"
        const val SKYSPARK = ".axon.skyspark"
        const val TRIO_SKYSPARK = ".trio.skyspark"

        /** The plugin's key in a function's entry: the short hash of the trio the folder got from SkySpark. */
        const val TRIO_HASH = "trioHash"
        private const val BATCH = 50
        private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,199}")
        /** An instant as JavaScript's `toISOString` writes it, as the server does: always with milliseconds. */
        private val JS_ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss-SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

        private fun globalRoot(): String? =
            AxonServerSettings.getInstance().projFolder.takeIf { it.isNotBlank() }?.let { AxonProjFolders.normalize(it) }

        /**
         * `<root>/<instance>/<project>`, or null when a name could leave [root] or is not a
         * plain folder name. On Windows ([windows]) also no `<>:"|?*`, no trailing dot and no
         * device name such as `con`.
         */
        fun folderFor(root: File, key: AxonProjectKey, windows: Boolean = SystemInfo.isWindows): File? {
            fun ok(s: String) = s.isNotBlank() && s.trim() == s && !s.startsWith(".") && s.none { it == '/' || it == '\\' || it < ' ' || it == '\u007f' } &&
                !AxonProjFolders.isCopyFolder(s) &&
                (!windows || (s.none { it in WINDOWS_BAD } && !s.endsWith(".") && !isDevice(s.substringBefore('.'))))
            if (!ok(key.instance) || !ok(key.project)) return null
            return File(File(root, key.instance), key.project)
        }

        /**
         * Every name must be a plain Axon name, and no two may differ in case only (one file
         * on a disk that ignores case). Throws before anything is written.
         */
        fun checkNames(names: List<String>, windows: Boolean = SystemInfo.isWindows) {
            val seen = HashSet<String>()
            for (n in names) {
                if (!NAME.matches(n) || (windows && isDevice(n))) throw AxonCopyException(Kind.BAD_REPLY, "The function name \"${n.take(80)}\" cannot be a file name.")
                if (!seen.add(n.lowercase(Locale.ROOT))) throw AxonCopyException(Kind.BAD_REPLY, "Two functions differ in case only: $n.")
            }
        }

        private const val WINDOWS_BAD = "<>:\"|?*"
        private val DEVICE = Regex("(con|prn|aux|nul|com[0-9\u00b9\u00b2\u00b3]|lpt[0-9\u00b9\u00b2\u00b3]|conin\\$|conout\\$)", RegexOption.IGNORE_CASE)

        /** A Windows device name: a file of that name is the device, in any folder. */
        private fun isDevice(base: String) = DEVICE.matches(base.trimEnd(' ', '.'))

        /** The hash the server keeps in `.sync-metadata.json`: the first 16 hex digits of the sha256 of the source. */
        fun shortHash(src: String): String = AxonCopyLimits.sha256(src).substring(0, 16)

        /** A stored hash (short or full) is the hash [full]. */
        fun sameHash(stored: String?, full: String): Boolean {
            val s = stored?.trim()?.lowercase(Locale.ROOT) ?: return false
            return s.length >= 16 && full.startsWith(s)
        }

        // ---- the trio record ---------------------------------------------------------------

        /**
         * The trio record of [f]: `name`, `doc` (when there is one), `mod` and `src`, in the
         * server's form. The other tags of [old] (`dis`, markers, ...) are kept in their place.
         */
        fun trioText(old: String?, f: AxonFunction): String {
            val blocks = old?.let(::trioBlocks)?.toMutableList() ?: mutableListOf()
            val fresh = linkedMapOf(
                "name" to "name:" + quote(f.name),
                "doc" to f.doc?.takeIf { it.isNotBlank() }?.let { d -> if ('\n' in d) "doc:\n" + indent(d) else "doc:" + quote(d) },
                "mod" to AxonFallbackFolder.folderTimeOf(f.mod)?.let { "mod:" + quote(it) },
                "src" to "src:\n" + indent(f.src),
            )
            for ((tag, text) in fresh) {
                val at = blocks.indexOfFirst { it.first == tag }
                if (text == null) { if (at >= 0) blocks.removeAt(at); continue }
                if (at >= 0) { blocks[at] = tag to text; continue }
                val where = when (tag) {
                    "name" -> 0
                    "doc" -> blocks.indexOfFirst { it.first == "name" } + 1
                    "mod" -> blocks.indexOfFirst { it.first == "src" }.let { if (it < 0) blocks.size else it }
                    else -> blocks.size
                }
                blocks.add(where, tag to text)
            }
            return blocks.joinToString("\n") { it.second }
        }

        private fun indent(text: String) = text.split('\n').joinToString("\n") { "  " + it.trimEnd('\r') }

        private fun quote(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '$' -> sb.append("\\$")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
            return sb.append('"').toString()
        }

        /**
         * The top-level tags of one trio record, in order: the tag name (null for a comment
         * or for text before the first tag) and the text of the tag with its indented lines,
         * with no blank lines at the end. A quoted value may span lines. Null for a file
         * with more than one record.
         */
        fun trioBlocks(text: String): List<Pair<String?, String>>? {
            val out = ArrayList<Pair<String?, ArrayList<String>>>()
            var inQuote = false
            for (raw in text.split('\n')) {
                val line = raw.trimEnd('\r')
                val starts = !inQuote && line.isNotEmpty() && line[0] != ' ' && line[0] != '\t'
                if (starts) {
                    if (line.startsWith("---")) { if (out.all { it.first == null }) continue else return null }
                    val colon = line.indexOf(':')
                    val tag = when {
                        line.startsWith("//") -> null
                        colon > 0 -> line.substring(0, colon).trim()
                        else -> line.trim()
                    }
                    out += tag to arrayListOf(line)
                    val value = if (colon > 0 && tag != null) line.substring(colon + 1).trimStart() else ""
                    inQuote = value.startsWith("\"") && opensQuote(value, false)
                } else {
                    if (out.isEmpty()) out += null to arrayListOf()
                    out.last().second += line
                    if (inQuote) inQuote = opensQuote(line, true)
                }
            }
            return out.map { (tag, lines) ->
                while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
                tag to lines.joinToString("\n")
            }.filter { it.first != null || it.second.isNotEmpty() }
        }

        /** Whether a quote is still open at the end of [line], given whether it was open at the start. */
        private fun opensQuote(line: String, open: Boolean): Boolean {
            var inside = open
            var i = 0
            while (i < line.length) {
                val c = line[i++]
                if (inside) {
                    if (c == '\\') i++ else if (c == '"') inside = false
                } else if (c == '"') inside = true
            }
            return inside
        }
    }
}
