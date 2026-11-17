package co.anbora.labs.fantom.lang.axon.copy

import co.anbora.labs.fantom.lang.axon.copy.AxonCopyException.Kind
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.RootsChangeRescanningInfo
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import org.jetbrains.annotations.TestOnly
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The Axon project copy of one IDE project: a read-only local copy of the Axon functions
 * of the SkySpark project the developer works on ([AxonProjectSettings.activeKey]).
 *
 * The functions come from the Axon server when its URL is set ([AxonServerSettings]),
 * else from the fallback `proj` folder ([AxonProjectSettings.fallbackPath]). They are
 * written to [AxonProjectCache] and shown as the library "Axon: instance/project"
 * ([AxonProjectLibraryRootsProvider]). There is one copy per IDE project: picking
 * another project, or none, deletes the old one. The copy stays on disk when the IDE
 * closes; [load] registers it again when the project opens, without any network.
 *
 * For other features: [activeKey], [cacheRoot], [functions], [findFunctionFile] and
 * [pushFunction]. The first four read memory only and are safe on any thread.
 *
 * [select], [refresh], [drop] and [listProjects] run as background tasks and report
 * errors as notifications. The `...Now` functions are their blocking cores: never call
 * those on the UI thread or in a read action.
 */
@Service(Service.Level.PROJECT)
class AxonProjectCopy(private val project: Project) {

    /** What is registered now: the copy and the VFS folder of its functions. */
    private class Registered(val copy: AxonProjectCache.Copy, val root: VirtualFile)

    @Volatile
    private var registered: Registered? = null

    /** Guards the cache folder, the chosen key and [generation]. No thread waits for another inside it. */
    private val lock = Any()

    /** Counts the changes on disk, so that a late [publish] of an older change is dropped. */
    private var generation = 0L

    // ---- the API for other features --------------------------------------------------------

    /** The chosen SkySpark project, or null. There may be no copy of it yet: see [copy]. */
    val activeKey: AxonProjectKey? get() = AxonProjectSettings.getInstance(project).activeKey

    /** The copy on disk that is registered as a library, or null. */
    val copy: AxonProjectCache.Copy? get() = registered?.copy

    /** The folder of the copy (`func/`, `index.json` and the marker are in it), or null. */
    val cacheRoot: File? get() = registered?.copy?.dir

    /** The `func` folder as the IDE sees it: the root of the library. Null without a copy. */
    val libraryRoot: VirtualFile? get() = registered?.root?.takeIf { it.isValid }

    /** The functions of the copy, sorted by name: name, doc, mod and hash. */
    fun functions(): List<AxonFunctionEntry> = registered?.copy?.functions.orEmpty()

    /** The entry of the function [name], or null. */
    fun function(name: String): AxonFunctionEntry? = registered?.copy?.entry(name)

    /** The file of the function [name] in the copy, or null. */
    fun findFunctionFile(name: String): VirtualFile? {
        val r = registered ?: return null
        val entry = r.copy.entry(name) ?: return null
        return r.root.takeIf { it.isValid }?.findChild(entry.file)
    }

    /** True for a file of the registered copy. */
    fun isInCopy(file: VirtualFile): Boolean {
        val root = libraryRoot ?: return false
        return VfsUtil.isAncestor(root, file, false)
    }

    /**
     * Sends [src] as the function [name] of the active project to the Axon server.
     * [expectedMod] is the `mod` of the copy ([function]); the server refuses the push with
     * a conflict when the function has changed since. With [doc] null the stored doc is
     * kept. The local copy is not changed: call [refresh] after. Blocks; throws
     * [AxonCopyException] (no server URL, no active project, HTTP errors).
     */
    fun pushFunction(name: String, src: String, doc: String? = null, expectedMod: String? = function(name)?.mod, check: () -> Unit = {}): AxonPushResult {
        val key = activeKey ?: throw AxonCopyException(Kind.NOT_CONFIGURED, "No Axon project is chosen.")
        val client = serverClient() ?: throw AxonCopyException(Kind.NOT_CONFIGURED, "No Axon server URL is set.")
        return client.pushFunction(key, name, src, doc, expectedMod, check)
    }

    // ---- sources ----------------------------------------------------------------------------

    @TestOnly @Volatile var testCacheRoot: File? = null

    /** Forgets the registered copy but leaves the disk and the settings: what an IDE restart does. */
    @TestOnly fun forgetForTest() = synchronized(lock) { generation++; registered = null }

    /** Replaces the server and the folder in tests. */
    @TestOnly @Volatile var testSource: AxonFunctionSource? = null

    private fun cacheDir(): File = testCacheRoot ?: AxonProjectCache.defaultRoot()

    /** The client of the configured server, or null when no URL is set. Reads the password store. */
    private fun serverClient(): AxonServerClient? {
        val url = AxonServerSettings.getInstance().url
        if (url.isEmpty()) return null
        return AxonServerClient(url, AxonServerToken.get())
    }

    /**
     * Where the functions come from: the server when a URL is set, else the fallback
     * folder. A server that fails is an error; the folder is not read in its place, so
     * that an old folder never replaces a newer copy without the developer knowing.
     */
    fun source(): AxonFunctionSource {
        testSource?.let { return it }
        serverClient()?.let { return it }
        val path = AxonProjectSettings.getInstance(project).fallbackPath
        if (path.isNotEmpty()) return AxonFallbackFolder(File(path))
        throw AxonCopyException(Kind.NOT_CONFIGURED, "Set the Axon server URL or the fallback folder in Settings | Languages & Frameworks | Fantom | Axon.")
    }

    /** True when a server URL or a fallback folder is set. Reads memory only. */
    fun isConfigured(): Boolean =
        testSource != null || AxonServerSettings.getInstance().url.isNotEmpty() || AxonProjectSettings.getInstance(project).fallbackPath.isNotEmpty()

    // ---- blocking cores -----------------------------------------------------------------------

    /** The projects the source offers, sorted by key. */
    fun listProjectsNow(check: () -> Unit = {}): List<AxonProjectInfo> =
        source().listProjects(check).sortedBy { it.key.toString().lowercase() }

    /**
     * Fetches the functions of [key] and makes them the copy of this IDE project. The
     * copy of another key is deleted after the new one is in place. When the fetch or
     * the write fails, the key and the copy stay as they were.
     */
    fun selectNow(key: AxonProjectKey, sync: Boolean = false, check: () -> Unit = {}): AxonProjectCache.Copy =
        fetchAndStore(key, sync, check, onlyIfActive = false)

    private fun fetchAndStore(key: AxonProjectKey, sync: Boolean, check: () -> Unit, onlyIfActive: Boolean): AxonProjectCache.Copy {
        // the fetch can take minutes: it is outside the lock, so that load() and drop never wait for the network
        val src = source()
        val list = src.fetch(key, sync, check)
        if (list.key != key) throw AxonCopyException(Kind.BAD_REPLY, "The reply is for another project.")
        check()
        val change = synchronized(lock) {
            // a refresh that ends after the project was dropped or changed writes nothing
            if (onlyIfActive && activeKey != key) throw ProcessCanceledException()
            val written = AxonProjectCache.write(cacheDir(), list, src.id, check)
            val settings = AxonProjectSettings.getInstance(project)
            val old = settings.activeKey
            settings.activeKey = key
            Change(++generation, written, if (old != null && old != key) deleteUnlessShared(old) else null)
        }
        publish(change)
        reloadOthers(key)
        return change.copy!!
    }

    /** Fetches the active project again; with [sync] the server reads SkySpark first. */
    fun refreshNow(sync: Boolean = true, check: () -> Unit = {}): AxonProjectCache.Copy {
        val key = activeKey ?: throw AxonCopyException(Kind.NOT_CONFIGURED, "No Axon project is chosen.")
        return fetchAndStore(key, sync, check, onlyIfActive = true)
    }

    /** Forgets the active project and deletes its copy. */
    fun dropNow() {
        val change = synchronized(lock) {
            val settings = AxonProjectSettings.getInstance(project)
            val key = settings.activeKey
            settings.activeKey = null
            Change(++generation, null, key?.let(::deleteUnlessShared))
        }
        publish(change)
    }

    /**
     * Registers the copy of the active key from disk: at project open, and after another
     * IDE project wrote the same copy. Reads the marker and the index only.
     */
    fun load() {
        val change = synchronized(lock) {
            Change(++generation, activeKey?.let { AxonProjectCache.read(cacheDir(), it) }, null)
        }
        publish(change)
    }

    /** What one step did on disk: the copy that is there now (or none) and a folder that was deleted. */
    private class Change(val generation: Long, val copy: AxonProjectCache.Copy?, val deleted: File?)

    /**
     * Tells the IDE about [change]. The file system refresh waits for the UI thread, so
     * it is never done inside [lock]: a thread that holds the lock must not wait for
     * another thread. When a newer change was made in the meantime, this one is dropped.
     */
    private fun publish(change: Change) {
        val fs = LocalFileSystem.getInstance()
        val root = change.copy?.let { fs.refreshAndFindFileByIoFile(it.funcDir) }
        // the folder was replaced on disk: read its files again
        root?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }
        change.deleted?.let { fs.findFileByIoFile(it) }?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }
        val was: Registered?
        val now: Registered?
        synchronized(lock) {
            if (change.generation != generation) return
            was = registered
            now = if (change.copy != null && root != null) Registered(change.copy, root) else null
            registered = now
        }
        if (was != null && was.root != now?.root) VfsUtil.markDirtyAndRefresh(false, true, true, was.root)
        fireChanged(rootsChanged = was?.root != now?.root)
    }

    /**
     * Deletes the copy of [key] on disk, unless another open IDE project has the same key.
     * Returns the folder that was deleted, or null.
     */
    private fun deleteUnlessShared(key: AxonProjectKey): File? {
        val shared = ProjectManager.getInstance().openProjects.any {
            it !== project && !it.isDisposed && !it.isDefault && AxonProjectSettings.getInstance(it).activeKey == key
        }
        if (shared) return null
        val dir = AxonProjectCache.dirFor(cacheDir(), key)
        AxonProjectCache.delete(cacheDir(), key)
        return dir
    }

    /** Other open IDE projects with the same key read the new index. */
    private fun reloadOthers(key: AxonProjectKey) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        for (p in ProjectManager.getInstance().openProjects) {
            if (p === project || p.isDisposed || p.isDefault) continue
            if (AxonProjectSettings.getInstance(p).activeKey == key) runCatching { getInstance(p).load() }
        }
    }

    private fun onEdt(action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) action()
        else app.invokeLater({ if (!project.isDisposed) action() }, project.disposed)
    }

    private fun fireChanged(rootsChanged: Boolean) = onEdt {
        if (rootsChanged) {
            WriteAction.run<RuntimeException> {
                ProjectRootManagerEx.getInstanceEx(project)
                    .makeRootsChange(EmptyRunnable.getInstance(), RootsChangeRescanningInfo.TOTAL_RESCAN)
            }
        }
        project.messageBus.syncPublisher(TOPIC).copyChanged()
    }

    // ---- background tasks ---------------------------------------------------------------------

    /** Fetches [key] in the background and makes it the copy. Errors are notifications. */
    fun select(key: AxonProjectKey, sync: Boolean = false) =
        background("Fetching Axon functions of $key") { indicator ->
            val c = cancellable(indicator) { check -> selectNow(key, sync, check) }
            notify("Axon: $key, ${count(c.functions.size)}.", NotificationType.INFORMATION)
        }

    /** Fetches the active project again, with `sync=true`. */
    fun refresh() {
        val key = activeKey ?: return
        background("Refreshing Axon functions of $key") { indicator ->
            val c = cancellable(indicator) { check -> fetchAndStore(key, true, check, onlyIfActive = true) }
            notify("Axon: $key refreshed, ${count(c.functions.size)}.", NotificationType.INFORMATION)
        }
    }

    /** Forgets the active project and deletes its copy, in the background. */
    fun drop() = background("Dropping the Axon copy") { dropNow() }

    /** Lists the projects in the background, then calls [onDone] on the UI thread. */
    fun listProjects(onDone: (List<AxonProjectInfo>) -> Unit) =
        background("Listing Axon projects") { indicator ->
            val list = cancellable(indicator) { check -> listProjectsNow(check) }
            onEdt { onDone(list) }
        }

    private fun count(n: Int) = if (n == 1) "1 function" else "$n functions"

    /**
     * In unit tests the work runs inline, and only with a [testSource] (no network on the
     * test's UI thread); other tests call the blocking cores.
     */
    private fun background(title: String, work: (ProgressIndicator) -> Unit) {
        if (ApplicationManager.getApplication().isUnitTestMode) {
            if (testSource != null) work(EmptyProgressIndicator())
            return
        }
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    work(indicator)
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: AxonCopyException) {
                    notify(e.message ?: "The Axon copy failed.", NotificationType.ERROR)
                } catch (e: Exception) {
                    notify("The Axon copy failed: ${e.message ?: e.javaClass.simpleName}", NotificationType.ERROR)
                }
            }
        })
    }

    /**
     * Runs [work] on another thread and waits for it, so that Cancel ends the task at
     * once even while a socket read blocks. After a cancel, [work] stops at its next
     * check, or at its timeout, and its result is dropped.
     */
    private fun <T> cancellable(indicator: ProgressIndicator, work: (check: () -> Unit) -> T): T {
        if (ApplicationManager.getApplication().isUnitTestMode) return work { indicator.checkCanceled() }
        val future = AppExecutorUtil.getAppExecutorService().submit(Callable { work { indicator.checkCanceled() } })
        while (true) {
            try {
                return future.get(100, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                if (indicator.isCanceled) future.cancel(true)
                indicator.checkCanceled()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
    }

    private fun notify(text: String, type: NotificationType) {
        if (project.isDisposed) return
        NotificationGroupManager.getInstance().getNotificationGroup("Fantom")
            ?.createNotification("Axon project copy", text, type)
            ?.notify(project)
    }

    fun interface Listener {
        /** The key, the copy or its functions changed. Called on the UI thread. */
        fun copyChanged()
    }

    companion object {
        val TOPIC: Topic<Listener> = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)

        fun getInstance(project: Project): AxonProjectCopy = project.service()
    }
}
