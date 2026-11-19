package co.anbora.labs.fantom.lang.axon.proj

import co.anbora.labs.fantom.lang.axon.copy.AxonCopyLimits
import co.anbora.labs.fantom.lang.axon.copy.AxonJson
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectKey
import co.anbora.labs.fantom.lang.axon.copy.AxonServerSettings
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.messages.Topic
import org.jetbrains.annotations.TestOnly
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * One project folder of an Axon `proj` folder: the key of the SkySpark project and the
 * folder that holds its `func/` folder (and `.sync-metadata.json`, when there is one).
 */
data class AxonProjFolder(val key: AxonProjectKey, val folder: VirtualFile)

/**
 * Maps a file to its Axon project in a `proj` folder of the Axon server (task 4.38):
 *
 * ```
 * proj/<connection>/<project>/.sync-metadata.json   {instance, project, ...}
 * proj/<connection>/<project>/func/<name>.axon
 * proj/<connection>/<project>/func/<name>.trio
 * ```
 *
 * - Under the global proj folder ([AxonServerSettings.projFolder]) the project folder comes
 *   from the path: `<global>/<connection>/<project>/...`.
 * - Elsewhere, the nearest folder with `.sync-metadata.json`, at most 4 levels up, is the
 *   project folder.
 * - A file under `backup`, `.versions`, `.backups` or `.sidecar-*` is a [Place.Copy]: it has
 *   no project, and its names resolve to nothing.
 *
 * The key comes from `.sync-metadata.json` (`instance`, `project`); until that file is read,
 * and when it is missing, from the folder names (`<connection>/<project>`). The file is read
 * once on a pooled thread, and again when it changes.
 *
 * [placeOf] and [projectOf] read memory and the VFS only, so they are safe on the UI thread
 * and in a read action. The answer is cached per folder and dropped when a folder or a
 * `.sync-metadata.json` changes, and when the global folder setting changes.
 */
@Service(Service.Level.APP)
class AxonProjFolders : Disposable {

    /** Where a folder is in an Axon `proj` layout. */
    sealed interface Place {
        /** In the project folder [folder]. */
        class InProject(val folder: VirtualFile) : Place
        /** In a backup or version folder of a `proj` folder: a copy, never real code. */
        data object Copy : Place
        /** Not in a `proj` layout. */
        data object Outside : Place
    }

    private val places = ConcurrentHashMap<VirtualFile, Place>()
    private val metaKeys = ConcurrentHashMap<VirtualFile, AxonProjectKey>()
    private val pending: MutableSet<VirtualFile> = ConcurrentHashMap.newKeySet()
    private val functionMaps = ConcurrentHashMap<VirtualFile, Map<String, VirtualFile>>()

    /** Counts the times the caches were dropped, so a late read of an older state writes nothing. */
    private val generation = AtomicLong()

    @Volatile
    private var cachedRoot: String? = null

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                // a deleted folder is still a folder in before(); after() catches what is new
                @Volatile private var layoutChange = false

                override fun before(events: List<VFileEvent>) {
                    if (events.any(::changesLayout)) layoutChange = true
                }

                override fun after(events: List<VFileEvent>) {
                    val layout = layoutChange || events.any(::changesLayout)
                    layoutChange = false
                    if (layout) clear()
                    else if (events.any(::changesFunctions)) { generation.incrementAndGet(); functionMaps.clear() }
                }
            })
    }

    override fun dispose() {}

    // ---- the API -----------------------------------------------------------------------------

    /** Where [file] (a file or a folder) is in an Axon `proj` layout. Memory and VFS only. */
    fun placeOf(file: VirtualFile): Place {
        val dir = if (file.isDirectory) file else file.parent ?: return Place.Outside
        val root = globalRoot()
        if (root != cachedRoot) { cachedRoot = root; clear() }
        places[dir]?.let { return it }
        val gen = generation.get()
        val place = compute(dir, root)
        // a clear() while computing means the VFS changed: answer, but do not keep the answer
        if (generation.get() == gen) places.putIfAbsent(dir, place)
        return place
    }

    /**
     * The project folder and key of [file] (a file or a folder), or null when it is not in a
     * project folder (outside a `proj` layout, or in a backup or version folder). Memory and
     * VFS only: safe on the UI thread and in a read action. The key may come from the folder
     * names while `.sync-metadata.json` is read; [projectOfNow] waits for that file.
     */
    fun projectOf(file: VirtualFile): AxonProjFolder? {
        val place = placeOf(file) as? Place.InProject ?: return null
        return AxonProjFolder(keyOf(place.folder), place.folder)
    }

    /**
     * Like [projectOf], but reads `.sync-metadata.json` now when it was not read yet. Blocks
     * on the disk: call it off the UI thread and outside a read action (the sync of task 4.39).
     */
    fun projectOfNow(file: VirtualFile): AxonProjFolder? {
        val place = placeOf(file) as? Place.InProject ?: return null
        val key = metaKeys[place.folder] ?: readKey(place.folder).also { metaKeys[place.folder] = it }
        return AxonProjFolder(key, place.folder)
    }

    /** The key of the project folder [folder]: from `.sync-metadata.json` when it was read, else from the folder names. */
    fun keyOf(folder: VirtualFile): AxonProjectKey {
        metaKeys[folder]?.let { return it }
        scheduleRead(folder)
        return metaKeys[folder] ?: nameKey(folder)
    }

    /**
     * The functions of the project folder [folder]: `func/<name>.axon` by name. Read from the
     * VFS once and kept until a function file or folder is added, removed or renamed.
     */
    fun functionFiles(folder: VirtualFile): Map<String, VirtualFile> {
        functionMaps[folder]?.let { return it }
        val gen = generation.get()
        val func = folder.findChild(FUNC)?.takeIf { it.isDirectory }
        val map = HashMap<String, VirtualFile>()
        for (f in func?.children.orEmpty()) {
            val n = f.name
            if (!f.isDirectory && n.endsWith(AXON_EXT) && n.length > AXON_EXT.length) map[n.removeSuffix(AXON_EXT)] = f
        }
        if (generation.get() == gen) functionMaps.putIfAbsent(folder, map)
        return map
    }

    /**
     * The global proj folder changed: drops every cached answer and highlights the open files
     * of every IDE project again, so names resolve against their new project at once. A VFS
     * change needs no such step: its PSI events restart the highlighting already. UI thread.
     */
    fun settingChanged() {
        clear()
        for (p in ProjectManager.getInstance().openProjects) {
            if (!p.isDisposed) DaemonCodeAnalyzer.getInstance(p).restart("Global proj folder changed")
        }
    }

    /** Drops every cached answer. */
    fun clear() {
        generation.incrementAndGet()
        places.clear()
        metaKeys.clear()
        pending.clear()
        functionMaps.clear()
        changed()
    }

    // ---- finding the project folder ------------------------------------------------------------

    private fun compute(dir: VirtualFile, root: String?): Place {
        if (root != null) {
            val path = dir.path
            if (path == root || path.startsWith("$root/")) return underGlobal(dir, path.substring(root.length).trimStart('/'))
        }
        return detect(dir)
    }

    /** [dir] is the folder [rel] under the global proj folder: `<connection>/<project>/...`. */
    private fun underGlobal(dir: VirtualFile, rel: String): Place {
        val segments = if (rel.isEmpty()) emptyList() else rel.split('/')
        if (segments.any(::isCopyFolder)) return Place.Copy
        if (segments.size < 2 || segments[0].startsWith(".") || segments[1].startsWith(".")) return Place.Outside
        var folder = dir
        repeat(segments.size - 2) { folder = folder.parent ?: return Place.Outside }
        return Place.InProject(folder)
    }

    /**
     * Without the global folder: the nearest folder with `.sync-metadata.json`, at most
     * [MAX_UP] folders up. A copy folder below it, or up to two folders above it
     * (`<connection>/backup/<date>/<project>`), makes the file a copy.
     */
    private fun detect(dir: VirtualFile): Place {
        val chain = ArrayList<VirtualFile>(MAX_UP + 2)
        var d: VirtualFile? = dir
        while (d != null && chain.size < MAX_UP + 2) { chain += d; d = d.parent }
        val copyAt = chain.indexOfFirst { isCopyFolder(it.name) }
        val metaAt = (0 until minOf(MAX_UP, chain.size)).firstOrNull { hasMeta(chain[it]) }
        if (metaAt != null && (copyAt < 0 || copyAt > metaAt + 2)) return Place.InProject(chain[metaAt])
        if (copyAt >= 0 && (metaAt != null || chain.any(::hasMeta) || nextToProjects(chain, copyAt))) return Place.Copy
        return Place.Outside
    }

    /**
     * A copy folder with no metadata on the way up (`<connection>/.sidecar-x/<project>/func`,
     * `<connection>/backup/<n>/<project>/func`) is in a `proj` layout when it, or the folder
     * that holds it (the connection), holds a project folder. Only these two folders are
     * listed, so a `backup` folder in the home folder lists the home folder at most.
     */
    private fun nextToProjects(chain: List<VirtualFile>, copyAt: Int): Boolean =
        (copyAt..minOf(copyAt + 1, chain.lastIndex)).any { i -> chain[i].children.any { it.isDirectory && hasMeta(it) } }

    private fun hasMeta(dir: VirtualFile): Boolean = dir.findChild(METADATA)?.isDirectory == false

    private fun globalRoot(): String? =
        AxonServerSettings.getInstance().projFolder.takeIf { it.isNotEmpty() }?.let(::normalize)

    // ---- the key -------------------------------------------------------------------------------

    private fun nameKey(folder: VirtualFile): AxonProjectKey = AxonProjectKey(folder.parent?.name ?: folder.name, folder.name)

    /** Reads `.sync-metadata.json` of [folder] on a pooled thread (inline in tests), once. */
    private fun scheduleRead(folder: VirtualFile) {
        if (!pending.add(folder)) return
        val gen = generation.get()
        val app = ApplicationManager.getApplication()
        val task = Runnable {
            val key = readKey(folder)
            if (generation.get() == gen) {
                metaKeys[folder] = key
                if (key != nameKey(folder)) changed()
            }
        }
        if (app.isUnitTestMode) task.run() else app.executeOnPooledThread(task)
    }

    /** The key in `.sync-metadata.json` of [folder], or the folder names. Reads the disk. */
    private fun readKey(folder: VirtualFile): AxonProjectKey {
        val meta = folder.findChild(METADATA)?.takeIf { !it.isDirectory && it.length < AxonCopyLimits.MAX_BYTES }
            ?: return nameKey(folder)
        val json = runCatching { AxonJson.parseObject(String(meta.contentsToByteArray(), Charsets.UTF_8)) }.getOrNull()
        val instance = AxonJson.str(json?.get("instance"))?.trim()?.takeIf { it.isNotEmpty() }
        val project = AxonJson.str(json?.get("project"))?.trim()?.takeIf { it.isNotEmpty() }
        if (instance == null || project == null) return nameKey(folder)
        return AxonProjectKey(instance, project)
    }

    private fun changed() {
        val app = ApplicationManager.getApplication()
        app.invokeLater { if (!app.isDisposed) app.messageBus.syncPublisher(TOPIC).projFoldersChanged() }
    }

    /** Folders that are created, deleted, moved or renamed, and `.sync-metadata.json` files, change the map. */
    private fun changesLayout(e: VFileEvent): Boolean {
        if (e.path.endsWith("/$METADATA")) return true
        if (e is VFileContentChangeEvent) return false
        if (e is VFileCreateEvent) return e.isDirectory
        if (e is VFileCopyEvent) return e.file.isDirectory
        return e.file?.isDirectory == true
    }

    /** An `.axon` file added, removed, moved or renamed changes the function list of its folder. */
    private fun changesFunctions(e: VFileEvent): Boolean {
        if (e is VFileContentChangeEvent) return false
        if (e is VFilePropertyChangeEvent) return e.propertyName == VirtualFile.PROP_NAME &&
            (e.oldValue.toString().endsWith(AXON_EXT) || e.newValue.toString().endsWith(AXON_EXT))
        return e.path.endsWith(AXON_EXT) || (e is VFileMoveEvent && e.file.name.endsWith(AXON_EXT))
    }

    fun interface Listener {
        /** A project folder or its key changed: status bar entries show the new key. Called on the UI thread. */
        fun projFoldersChanged()
    }

    companion object {
        const val METADATA = ".sync-metadata.json"
        const val FUNC = "func"
        private const val AXON_EXT = ".axon"

        /** At most this many folders up from a file's folder to its project folder. */
        private const val MAX_UP = 4

        val TOPIC: Topic<Listener> = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)

        fun getInstance(): AxonProjFolders = service()

        /**
         * The project folder and key of [file] (a file or a folder in a `proj` folder), or null.
         * Memory and VFS only. See [AxonProjFolders.projectOf]; for the Sync menu of task 4.39.
         */
        fun projectFolderOf(file: VirtualFile): AxonProjFolder? = getInstance().projectOf(file)

        /** A folder that holds copies, never real code: `backup`, `.versions`, `.backups`, `.sidecar-*`. */
        fun isCopyFolder(name: String): Boolean =
            name == "backup" || name == ".versions" || name == ".backups" || name.startsWith(".sidecar-")

        /** [path] with `/` only, a leading `~` replaced by the home folder, and no `/` at the end. */
        fun normalize(path: String): String {
            var p = path.trim()
            if (p == "~" || p.startsWith("~/") || p.startsWith("~\\")) p = System.getProperty("user.home") + p.substring(1)
            return p.replace('\\', '/').trimEnd('/').ifEmpty { "/" }
        }

        /** How many connections and projects a global proj folder holds. */
        data class Count(val connections: Int, val projects: Int)

        /**
         * Counts the connections and projects in the proj folder [root]: a project is a folder
         * `<connection>/<project>` with `.sync-metadata.json`; a connection holds one or more.
         * Copy and dot folders are skipped. Null when [root] is not a folder. Reads the disk:
         * call it off the UI thread.
         */
        fun count(root: File, check: () -> Unit = {}): Count? {
            if (!root.isDirectory) return null
            var connections = 0
            var projects = 0
            for (c in dirs(root)) {
                check()
                val n = dirs(c).count { File(it, METADATA).isFile }
                if (n > 0) { connections++; projects += n }
            }
            return Count(connections, projects)
        }

        private fun dirs(dir: File): List<File> =
            (dir.listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.startsWith(".") && !isCopyFolder(it.name) }

        @TestOnly
        fun clearForTest() = getInstance().clear()
    }
}
