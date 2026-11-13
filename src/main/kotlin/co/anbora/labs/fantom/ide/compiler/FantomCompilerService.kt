package co.anbora.labs.fantom.ide.compiler

import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the Fantom compiler per pod and keeps the last result.
 *
 * One process per pod at a time. A request for a pod that already compiles joins that run
 * when the run started after the last [markDirty]; otherwise the old process is killed and
 * a new one starts. Without a dirty mark the cached result is returned, so the daemon can
 * ask as often as it likes and only a save or an open costs a compile.
 */
@Service(Service.Level.PROJECT)
class FantomCompilerService(private val project: Project) : Disposable {
    private class PodState {
        @Volatile var result: FantomCompileResult? = null
        @Volatile var dirtyAt: Long = 0
        var startedAt: Long = 0
        var future: CompletableFuture<FantomCompileResult>? = null
        var handler: CapturingProcessHandler? = null
    }

    private val pods = ConcurrentHashMap<String, PodState>()
    private val workRoot: File by lazy { File(PathManager.getTempPath(), "fantom-compile/${project.locationHash}") }

    private fun state(podRoot: File) = pods.computeIfAbsent(FantomCompilerDiagnostics.canonical(podRoot.path)) { PodState() }

    /** The last result for the pod, or null when it was never compiled. */
    fun result(podRoot: File): FantomCompileResult? = state(podRoot).result

    /** The next [compile] for this pod runs the compiler again. */
    fun markDirty(podRoot: File) {
        state(podRoot).dirtyAt = System.currentTimeMillis()
    }

    /** The pod root of a local `.fan` file, when compile-on-save applies to it. */
    fun podRootFor(file: VirtualFile): File? {
        if (!FantomSettings.getInstance(project).compileOnSave) return null
        if (file.fileSystem !is LocalFileSystem || file.extension != "fan") return null
        val root = FantomPodRoot.find(file) ?: return null
        return VfsUtilCore.virtualToIoFile(root)
    }

    /**
     * True when the compiler has spoken about this file since it was last saved. The
     * resolver inspection stays quiet then; its "cannot resolve" would only repeat or
     * contradict the compiler.
     */
    fun isFresh(file: PsiFile): Boolean {
        val vf = file.virtualFile ?: return false
        val podRoot = podRootFor(vf) ?: return false
        val st = state(podRoot)
        val res = st.result ?: return false
        if (!res.completed || res.time < st.dirtyAt || res.time < vf.timeStamp) return false
        val doc = FileDocumentManager.getInstance().getCachedDocument(vf)
        return doc == null || !FileDocumentManager.getInstance().isDocumentUnsaved(doc)
    }

    /**
     * The result for the pod: cached when nothing changed, else a compile. Blocks the
     * caller; [indicator] cancellation stops the wait but not the process.
     */
    fun compile(podRoot: File, home: File, indicator: ProgressIndicator?, requester: VirtualFile? = null): FantomCompileResult? {
        val st = state(podRoot)
        val future: CompletableFuture<FantomCompileResult>
        synchronized(st) {
            val cur = st.result
            val inFlight = st.future
            if (inFlight == null && cur != null && cur.time >= st.dirtyAt) return cur
            if (inFlight != null && st.startedAt >= st.dirtyAt) {
                future = inFlight
            } else {
                st.handler?.destroyProcess()
                val startedAt = System.currentTimeMillis()
                st.startedAt = startedAt
                val f = CompletableFuture<FantomCompileResult>()
                st.future = f
                AppExecutorUtil.getAppExecutorService().execute {
                    val res = try {
                        run(st, podRoot, home, startedAt)
                    } catch (t: Throwable) {
                        LOG.warn("Fantom compile failed for $podRoot", t)
                        FantomCompileResult(podRoot, startedAt, emptyMap(), -1, false)
                    }
                    synchronized(st) {
                        if (st.future === f) {
                            st.future = null
                            st.handler = null
                            st.result = res
                        }
                    }
                    f.complete(res)
                    restartOtherEditors(podRoot, requester)
                }
                future = f
            }
        }
        return if (indicator != null) ProgressIndicatorUtils.awaitWithCheckCanceled(future, indicator) else future.get()
    }

    private fun run(st: PodState, podRoot: File, home: File, startedAt: Long): FantomCompileResult {
        val buildFile = File(podRoot, "build.fan")
        val workDir = FantomCompilerDiagnostics.prepareWorkDir(workRoot, podRoot)
            ?: return FantomCompileResult(podRoot, startedAt, emptyMap(), -1, false)
        val cmd = FantomCompilerDiagnostics.commandLine(home, buildFile, workDir)
        val handler = CapturingProcessHandler(cmd)
        synchronized(st) { st.handler = handler }
        val output = handler.runProcess(TIMEOUT_MS, true)
        val text = output.stdout + "\n" + output.stderr
        val completed = !output.isTimeout && !output.isCancelled && text.contains("BUILD ")
        if (LOG.isDebugEnabled) LOG.debug("fan ${buildFile.path} compile -> exit ${output.exitCode}\n$text")
        return FantomCompileResult(podRoot, startedAt, FantomCompilerDiagnostics.parse(text), output.exitCode, completed)
    }

    /**
     * Other open files of the pod re-run their highlighting so they pick up the new result.
     * The [requester] gets it in its own running pass and is left alone.
     */
    private fun restartOtherEditors(podRoot: File, requester: VirtualFile?) {
        if (project.isDisposed) return
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            val rootPath = FantomCompilerDiagnostics.canonical(podRoot.path)
            val daemon = DaemonCodeAnalyzer.getInstance(project)
            val psiManager = PsiManager.getInstance(project)
            val files = ReadAction.computeBlocking<List<PsiFile>, RuntimeException> {
                FileEditorManager.getInstance(project).openFiles.mapNotNull { vf ->
                    if (vf == requester) return@mapNotNull null
                    val root = podRootFor(vf) ?: return@mapNotNull null
                    if (FantomCompilerDiagnostics.canonical(root.path) != rootPath) return@mapNotNull null
                    psiManager.findFile(vf)
                }
            }
            for (file in files) daemon.restart(file, "Fantom compiler result for ${podRoot.name}")
        }, project.disposed)
    }

    override fun dispose() {
        for (st in pods.values) synchronized(st) { st.handler?.destroyProcess() }
        pods.clear()
    }

    companion object {
        private val LOG = logger<FantomCompilerService>()
        const val TIMEOUT_MS = 60_000
        fun getInstance(project: Project): FantomCompilerService = project.service()
    }
}
