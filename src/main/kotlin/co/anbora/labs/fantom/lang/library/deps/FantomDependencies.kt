package co.anbora.labs.fantom.lang.library.deps

import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.RootsChangeRescanningInfo
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.messages.Topic
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The dependencies of the open project and the source picked for each, for the current
 * build target ([FantomSettings.defaultTarget]).
 *
 * [refresh] runs in the background: it reads the project's `build.fan` files (its own pods
 * and their `depends`), scans the source roots ([FantomSourceScan], cached), resolves each
 * dependency ([FantomDependencyResolver]), exports the chosen tags ([FantomTagExport]),
 * then stores the picks in [FantomSettings.dependencyOverrides] and fires a roots change so
 * the libraries are read again. Nothing here runs on the UI thread.
 */
@Service(Service.Level.PROJECT)
class FantomDependencies(private val project: Project) {

    /** What the last refresh found: rows, and the scan it used. */
    data class Snapshot(
        val rows: List<FantomDependencyResolver.Row>,
        val projectPods: Set<String>,
        val scan: FantomSourceScan.Result?,
        val target: String?,
    )

    @Volatile
    var snapshot: Snapshot = Snapshot(emptyList(), emptySet(), null, null)
        private set

    private val running = AtomicBoolean(false)
    private val again = AtomicBoolean(false)

    /**
     * Schedules a background refresh; [rescan] drops the cached source-root scan first.
     * Does nothing in unit tests, which call [refreshNow].
     */
    fun refresh(rescan: Boolean = false) {
        if (rescan) FantomSourceScan.clear()
        if (ApplicationManager.getApplication().isUnitTestMode) return
        if (!running.compareAndSet(false, true)) { again.set(true); return }
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Resolving Fantom dependencies", true) {
            override fun run(indicator: ProgressIndicator) {
                do {
                    again.set(false)
                    if (project.isDisposed) return
                    val snap = compute(projectRoots(), indicator)
                    publish(snap)
                } while (again.get())
            }

            override fun onFinished() { running.set(false) }
        })
    }

    /** [refresh] on the calling thread, for tests. */
    fun refreshNow(roots: List<File> = projectRoots(), cacheRoot: File = FantomTagExport.defaultCacheRoot()): Snapshot =
        compute(roots, null, cacheRoot).also(::publish)

    private fun projectRoots(): List<File> = listOfNotNull(project.basePath?.let(::File))

    /**
     * Computes the snapshot for [roots] (the project folders) and exports the chosen tags.
     * Also sets [FantomSettings.projectPods]. Background thread only.
     */
    fun compute(roots: List<File>, indicator: ProgressIndicator? = null, cacheRoot: File = FantomTagExport.defaultCacheRoot()): Snapshot {
        val settings = FantomSettings.getInstance(project)
        indicator?.text = "Reading build.fan files"
        val own = FantomSourceScan.scanNow(roots)
        val projectPods = own.podNames()
        val deps = own.pods.flatMap { it.depends }.filter { it.pod !in projectPods }.distinctBy { it.pod }
        settings.projectPods = projectPods
        indicator?.text = "Scanning source roots for project pods"
        val sourceRoots = settings.sourceRoots.map(::File)
        val scanned = FantomSourceScan.scan(sourceRoots)
        // copies inside the project are the project's, not source-root copies
        val scan = scanned.copy(pods = scanned.pods.filter { p -> roots.none { p.dir == it || p.dir.path.startsWith(it.path + "/") } })
        val target = settings.defaultInstall()
        val rows = FantomDependencyResolver.resolve(
            deps, target, settings.enabledInstalls(), projectPods, settings.basePodSources(), scan, settings.pins,
        ).map { row -> exported(row, cacheRoot, indicator) }
        return Snapshot(rows, projectPods, scan, target?.path)
    }

    private fun exported(row: FantomDependencyResolver.Row, cacheRoot: File, indicator: ProgressIndicator?): FantomDependencyResolver.Row {
        val ex = row.export ?: return row
        indicator?.text = "Exporting ${ex.repo} ${ex.tag}"
        val out = FantomTagExport.export(ex.clone, ex.repo, ex.tag, cacheRoot)
            ?: return row.copy(warning = listOfNotNull(row.warning, "git archive of ${ex.tag} failed").joinToString("; "))
        val src = FantomDependencyResolver.podAt(File(out.dir, ex.relPath), row.pod)
            ?: return row.copy(warning = listOfNotNull(row.warning, "${ex.tag} has no ${row.pod} pod").joinToString("; "))
        return row.copy(podSource = src)
    }

    /**
     * Stores [snap]: its picks become [FantomSettings.dependencyOverrides], the exported
     * folders are refreshed into the VFS, and a roots change fires when the pods changed.
     */
    fun publish(snap: Snapshot) {
        snapshot = snap
        val settings = FantomSettings.getInstance(project)
        val overrides = LinkedHashMap<String, FantomHome.PodSource>()
        for (r in snap.rows) if (!r.fromProject) r.podSource?.let { overrides[r.pod] = it }
        val fs = LocalFileSystem.getInstance()
        for (src in overrides.values) src.roots.forEach { fs.refreshAndFindFileByIoFile(it) }
        val before = settings.podSources().map { it.podDir.path }
        settings.dependencyOverrides = overrides
        val after = settings.podSources().map { it.podDir.path }
        project.messageBus.syncPublisher(TOPIC).dependenciesChanged(snap)
        if (before == after) return
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            WriteAction.run<RuntimeException> {
                ProjectRootManagerEx.getInstanceEx(project)
                    .makeRootsChange(EmptyRunnable.getInstance(), RootsChangeRescanningInfo.TOTAL_RESCAN)
            }
        }, project.disposed)
    }

    /**
     * Makes [path] the build target: the index default, compile on save and runs. Every
     * dependency is resolved again for it (Haxall pairing, tag choice) in the background.
     */
    fun switchTarget(path: String) {
        val settings = FantomSettings.getInstance(project)
        if (settings.buildTargets().none { it.path == path }) return
        if (settings.defaultTarget == path) return
        settings.defaultTarget = path
        project.messageBus.syncPublisher(TOPIC).targetChanged(path)
        refresh()
    }

    fun interface Listener {
        fun dependenciesChanged(snapshot: Snapshot)
        fun targetChanged(path: String) {}
    }

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<Listener> = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)

        fun getInstance(project: Project): FantomDependencies = project.service()
    }
}
