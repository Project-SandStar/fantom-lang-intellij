package co.anbora.labs.fantom.lang.axon.proj

import co.anbora.labs.fantom.lang.axon.copy.AxonCopyException
import co.anbora.labs.fantom.lang.axon.copy.AxonCopyException.Kind
import co.anbora.labs.fantom.lang.axon.copy.AxonCopyLimits
import co.anbora.labs.fantom.lang.axon.copy.AxonFallbackFolder
import co.anbora.labs.fantom.lang.axon.copy.AxonFunction
import co.anbora.labs.fantom.lang.axon.copy.AxonJson
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectCopy
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectKey
import co.anbora.labs.fantom.lang.axon.copy.AxonPushResult
import co.anbora.labs.fantom.lang.axon.copy.AxonServerClient
import co.anbora.labs.fantom.lang.axon.copy.AxonServerSettings
import co.anbora.labs.fantom.lang.axon.copy.AxonServerToken
import co.anbora.labs.fantom.lang.axon.proj.AxonProjFolderSync.Companion.AXON
import co.anbora.labs.fantom.lang.axon.proj.AxonProjFolderSync.Companion.FUNC
import co.anbora.labs.fantom.lang.axon.proj.AxonProjFolderSync.Companion.METADATA
import co.anbora.labs.fantom.lang.axon.proj.AxonProjFolderSync.Companion.TRIO
import co.anbora.labs.fantom.lang.axon.proj.AxonProjFolderSync.Companion.TRIO_HASH
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * "Push to SkySpark" (task 4.34): sends one function of a project folder of the `proj`
 * layout to SkySpark through the Axon server's IDE API
 * (`POST /api/ide/projects/{instance}/{project}/functions/{name}`), with the user's access
 * key. The server commits with its own stored SkySpark connection: the plugin never holds
 * a SkySpark password.
 *
 * The push sends `expectedMod`, the `mod` the folder last got from SkySpark (from
 * `.sync-metadata.json`). When SkySpark changed the function since, the server answers
 * with a conflict and nothing is stored. After a good push the folder's entry and the
 * `.trio` get the new `mod` and hash, so the next "Sync Axon Functions" sees no local edit.
 *
 * [targetOf] and [whyNot] read memory and the VFS only. Every other call blocks on the
 * disk or the network: it throws on the UI thread.
 */
object AxonFunctionPush {

    const val NO_SERVER = "Set the Axon server URL in Settings | Languages & Frameworks | Fantom | Axon"
    const val NO_KEY = "Set the access key of the Axon server in Settings | Languages & Frameworks | Fantom | Axon"
    const val NOT_AXON = "Open an Axon function file (.axon) first"
    const val COPY = "This file is a read-only copy. Push the function file of a project folder in the proj folder"
    const val OUTSIDE = "The file is not a function of a project folder (<proj>/<connection>/<project>/func/<name>.axon)"

    /** The function names the server takes: a letter, then letters, digits or `_`. */
    private val NAME = Regex("[A-Za-z][A-Za-z0-9_]{0,127}")

    /** The function file [file] of the project [key], in the project folder [folder]. */
    data class Target(val key: AxonProjectKey, val folder: VirtualFile, val file: VirtualFile, val name: String)

    /**
     * What one push sends: the source, the doc ([doc] is null to keep the stored doc) and
     * the `mod` the folder last got from SkySpark ([expectedMod] is null for a new function).
     * [localDoc] is the doc of the `.trio`, [storedDoc] the doc of the folder's entry.
     */
    class Plan(
        val target: Target,
        val key: AxonProjectKey,
        val src: String,
        val doc: String?,
        val expectedMod: String?,
        val localDoc: String?,
        val storedDoc: String?,
        /** True when the folder has no entry for the function: SkySpark does not have it yet. */
        val isNew: Boolean,
    ) {
        val name: String get() = target.name

        /** The doc SkySpark holds after a good push. */
        val docAfterPush: String? get() = doc ?: localDoc ?: storedDoc
    }

    // ---- memory and VFS only -------------------------------------------------------------------

    /** The function file [file] of a project folder, or null. Memory and VFS only. */
    fun targetOf(file: VirtualFile?): Target? {
        if (file == null || !file.isInLocalFileSystem || file.isDirectory || file.extension != "axon") return null
        val folders = AxonProjFolders.getInstance()
        val pf = folders.projectOf(file) ?: return null
        val func = file.parent ?: return null
        if (func.name != FUNC || func.parent != pf.folder) return null
        val name = file.nameWithoutExtension
        if (!NAME.matches(name)) return null
        return Target(pf.key, pf.folder, file, name)
    }

    /** Why [file] cannot be pushed now, or null. Memory and VFS only: an action's update calls it. */
    fun whyNot(project: Project, file: VirtualFile?): String? {
        if (AxonServerSettings.getInstance().url.isEmpty()) return NO_SERVER
        // the password store can be slow or ask the user: the last value read is used here
        if (AxonServerToken.storedHint() == false) return NO_KEY
        if (file == null || file.isDirectory || file.extension != "axon") return NOT_AXON
        if (AxonProjectCopy.getInstance(project).isInCopy(file)) return COPY
        if (AxonProjFolders.getInstance().placeOf(file) == AxonProjFolders.Place.Copy) return COPY
        return if (targetOf(file) == null) OUTSIDE else null
    }

    // ---- blocking ------------------------------------------------------------------------------

    /** Reads what the push of [target] sends: the file, its `.trio` and the folder's entry. Blocks. */
    fun plan(target: Target, check: () -> Unit = {}): Plan {
        noUiThread()
        // the disk first: the key and the entry come from .sync-metadata.json as it is now
        refresh(target.folder)
        val key = AxonProjFolders.getInstance().projectOfNow(target.file)?.key ?: throw AxonCopyException(Kind.IO, OUTSIDE)
        check()
        val src = textOf(target.file) ?: throw AxonCopyException(Kind.IO, "Cannot read ${target.file.name}.")
        if (src.isBlank()) throw AxonCopyException(Kind.IO, "${target.file.name} is empty: SkySpark needs a source.")
        val entry = AxonJson.obj(AxonJson.obj(readMeta(target.folder)?.get("functions"))?.get(target.name))
        val trio = trioFile(target)?.let(::textOf)
        val localDoc = trio?.let(::trioDoc)
        val storedDoc = AxonJson.str(entry?.get("doc"))
        // the doc goes along only when the .trio was edited and says something else than SkySpark gave the folder
        val storedTrioHash = AxonJson.str(entry?.get(TRIO_HASH))
        val trioUnchanged = trio != null && storedTrioHash != null && AxonProjFolderSync.sameHash(storedTrioHash, AxonCopyLimits.sha256(trio))
        val doc = if (trio != null && !trioUnchanged && localDoc.orEmpty() != storedDoc.orEmpty()) localDoc.orEmpty() else null
        // the server compares the mod to the millisecond: only its own text (or modIso, with milliseconds) will do
        val expectedMod = AxonJson.str(entry?.get("mod")) ?: AxonJson.str(entry?.get("modIso"))
        if (entry != null && expectedMod == null)
            throw AxonCopyException(Kind.BAD_REPLY, "The folder does not know when ${target.name} last changed in SkySpark. Run Sync Axon Functions first, then push again.")
        return Plan(target, key, src, doc, expectedMod, localDoc, storedDoc, isNew = entry == null)
    }

    /**
     * The `doc` tag of one trio record: a quoted value (it may hold raw line breaks, as the
     * server writes them) or indented lines. Null when there is none.
     */
    internal fun trioDoc(trio: String): String? {
        val block = AxonProjFolderSync.trioBlocks(trio)?.firstOrNull { it.first == "doc" }?.second ?: return null
        val value = block.substring(block.indexOf(':') + 1)
        val head = value.trimStart(' ', '\t')
        if (head.startsWith("\"")) {
            val sb = StringBuilder()
            var i = 1
            while (i < head.length) {
                val c = head[i++]
                if (c == '"') break
                if (c != '\\' || i >= head.length) { if (c != '\r') sb.append(c); continue }
                when (val e = head[i++]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    else -> sb.append(e)
                }
            }
            return sb.toString()
        }
        if (head.isNotBlank() && !head.startsWith("\n")) return head.trim()
        return value.split('\n').drop(1).joinToString("\n") { l -> if (l.startsWith("  ")) l.substring(2) else l.trimStart() }.trimEnd()
    }

    /**
     * Sends [plan] with [expectedMod]. On a good push the folder's entry and the `.trio` are
     * updated. A conflict changes nothing. Blocks; throws [AxonCopyException] (no URL, no key,
     * 401, read-only key, other HTTP errors).
     */
    fun push(project: Project, plan: Plan, expectedMod: String? = plan.expectedMod, check: () -> Unit = {}): AxonPushResult {
        noUiThread()
        val r = client().pushFunction(plan.key, plan.name, plan.src, plan.doc, expectedMod, check)
        if (r.ok) {
            // SkySpark has the new source now: the folder must learn it, even after a cancel
            val fn = AxonFunction(plan.name, plan.src, plan.docAfterPush, r.mod, AxonCopyLimits.sha256(plan.src))
            writeBack(project, plan.key, plan.target, fn, writeSource = false)
        }
        return r
    }

    /** The function as SkySpark holds it now (the server syncs the project first), or null when it is gone. Blocks. */
    fun serverVersion(plan: Plan, check: () -> Unit = {}): AxonFunction? {
        noUiThread()
        return client().fetch(plan.key, true, check).functions.firstOrNull { it.name == plan.name }
    }

    /** "Take SkySpark version": writes [fn] into the function file, the `.trio` and the folder's entry. Blocks. */
    fun takeServer(project: Project, plan: Plan, fn: AxonFunction) {
        noUiThread()
        writeBack(project, plan.key, plan.target, fn, writeSource = true)
    }

    // ---- the flow ------------------------------------------------------------------------------

    /** What the developer picks in the conflict dialog. */
    enum class Choice { OVERWRITE, TAKE_SKYSPARK, CANCEL }

    /** The questions of one push. The calls block until the developer answers. */
    interface Ui {
        /** The function is not in SkySpark yet: create it? */
        fun confirmNew(plan: Plan): Boolean

        /** SkySpark changed the function since the folder got it; [server] is its source there, null when it is gone. */
        fun conflict(plan: Plan, server: AxonFunction?): Choice
    }

    /** How one push ended. */
    sealed interface Outcome {
        data class Pushed(val plan: Plan, val result: AxonPushResult) : Outcome
        data class TookSkySpark(val plan: Plan) : Outcome
        data object Cancelled : Outcome
    }

    /**
     * The whole push of [target]: plan, confirm a new function, push, and on a conflict ask
     * [ui] and overwrite (with the server's `mod`) or take the SkySpark version. Blocks: call
     * it on a background thread.
     */
    fun run(project: Project, target: Target, ui: Ui, check: () -> Unit = {}): Outcome {
        val plan = plan(target, check)
        if (plan.isNew && !ui.confirmNew(plan)) return Outcome.Cancelled
        var expected = plan.expectedMod
        repeat(MAX_TRIES) {
            check()
            val r = push(project, plan, expected, check)
            if (r.ok) return Outcome.Pushed(plan, r)
            val server = serverVersion(plan, check)
            when (ui.conflict(plan, server)) {
                Choice.CANCEL -> return Outcome.Cancelled
                Choice.TAKE_SKYSPARK -> {
                    if (server == null) throw AxonCopyException(Kind.UNKNOWN_PROJECT, "${plan.name} is not in SkySpark any more: there is no SkySpark version to take.")
                    takeServer(project, plan, server)
                    return Outcome.TookSkySpark(plan)
                }
                // the server's mod: a function that is gone is created again (null)
                Choice.OVERWRITE -> expected = server?.mod ?: r.mod
            }
        }
        throw AxonCopyException(Kind.IO, "${plan.name} keeps changing in SkySpark. Try again later.")
    }

    private const val MAX_TRIES = 3

    /** The text of the notification after a good push. */
    fun message(plan: Plan, r: AxonPushResult): String {
        val sb = StringBuilder("Pushed ${plan.name} to ${plan.key} in SkySpark.")
        if (plan.isNew) sb.append(" It is a new function there.")
        val removed = r.removedDuplicates
        if (removed.isNotEmpty()) {
            sb.append(" The server removed ").append(removed.size)
                .append(if (removed.size == 1) " older record" else " older records")
                .append(" with the same name and kept a backup: ")
                .append(removed.joinToString(", ") { it.backup ?: it.id ?: "?" }).append('.')
        }
        return sb.toString()
    }

    // ---- the folder ----------------------------------------------------------------------------

    /** Writes [fn] into the `.trio` (and the `.axon` with [writeSource]) and the folder's entry. */
    private fun writeBack(project: Project, key: AxonProjectKey, target: Target, fn: AxonFunction, writeSource: Boolean) {
        val folderFile = File(target.folder.path)
        refresh(target.folder)
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val nowIso = AxonProjFolderSync.JS_ISO.format(now)
        val meta = readMeta(target.folder)
        val out = LinkedHashMap<String, Any?>()
        // the key the metadata names wins; the folder names are only a guess
        out["instance"] = AxonJson.str(meta?.get("instance")) ?: key.instance
        out["project"] = AxonJson.str(meta?.get("project")) ?: key.project
        meta?.forEach { (k, v) -> if (k !in out) out[k] = v }
        val fns = LinkedHashMap(AxonJson.obj(out["functions"]).orEmpty())
        val before = AxonJson.obj(fns[fn.name])
        val oldTrio = trioFile(target)?.let(::textOf)
        val trio = AxonProjFolderSync.trioText(oldTrio, fn)

        val e = LinkedHashMap<String, Any?>(before.orEmpty())
        e["name"] = fn.name
        e["hash"] = AxonProjFolderSync.shortHash(fn.src)
        e["lastModified"] = AxonFallbackFolder.folderTimeOf(fn.mod) ?: before?.get("lastModified")
        e["synced"] = nowIso
        e["mod"] = fn.mod
        e["modIso"] = AxonFallbackFolder.instantOf(fn.mod)?.let { AxonProjFolderSync.JS_ISO.format(it) }
        e["doc"] = fn.doc?.takeIf { it.isNotEmpty() }
        e[TRIO_HASH] = AxonProjFolderSync.shortHash(trio)
        fns[fn.name] = e
        out["functions"] = fns
        if (before == null) out["functionCount"] = ((meta?.get("functionCount") as? Number)?.toInt() ?: (fns.size - 1)) + 1
        if (meta == null) out["lastSync"] = nowIso

        val writer = AxonProjFolderSync.Writer(project, now, "Push to SkySpark")
        writer.command {
            val func = writer.dir(writer.root(folderFile), FUNC)
            // the source the folder had before goes to .versions, as the sync does
            if (writeSource) writer.put(folderFile, func, fn.name + AXON, fn.src, keep = true)
            writer.put(folderFile, func, fn.name + TRIO, trio, keep = writeSource)
            writer.put(folderFile, writer.root(folderFile), METADATA, AxonJson.write(out), keep = false)
        }
    }

    /** Reads the folder again from the disk, so a file another process wrote is seen. */
    private fun refresh(folder: VirtualFile) {
        if (folder.isValid) com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(false, true, true, folder)
    }

    private fun trioFile(target: Target): VirtualFile? =
        target.folder.findChild(FUNC)?.findChild(target.name + TRIO)?.takeIf { !it.isDirectory }

    private fun readMeta(folder: VirtualFile): Map<String, Any?>? =
        folder.findChild(METADATA)?.takeIf { !it.isDirectory }?.let(::textOf)
            ?.let { runCatching { AxonJson.parseObject(it) }.getOrNull() }

    /** The text of [vf]: the editor's text when it has unsaved changes. */
    private fun textOf(vf: VirtualFile): String? {
        val fdm = FileDocumentManager.getInstance()
        val unsaved = ReadAction.computeBlocking<String?, RuntimeException> {
            fdm.getCachedDocument(vf)?.takeIf { fdm.isDocumentUnsaved(it) }?.text
        }
        if (unsaved != null) return unsaved
        if (!vf.isValid || vf.length > AxonCopyLimits.MAX_BYTES) return null
        return runCatching { String(vf.contentsToByteArray(), Charsets.UTF_8) }.getOrNull()
    }

    private fun client(): AxonServerClient {
        val url = AxonServerSettings.getInstance().url
        if (url.isEmpty()) throw AxonCopyException(Kind.NOT_CONFIGURED, "$NO_SERVER.")
        val token = AxonServerToken.get() ?: throw AxonCopyException(Kind.NOT_CONFIGURED, "$NO_KEY.")
        return AxonServerClient(url, token)
    }

    private fun noUiThread() {
        val app = ApplicationManager.getApplication()
        if (app != null && (app.isDispatchThread || app.isReadAccessAllowed))
            throw IllegalStateException("No disk or network work on the UI thread or in a read action")
    }
}
