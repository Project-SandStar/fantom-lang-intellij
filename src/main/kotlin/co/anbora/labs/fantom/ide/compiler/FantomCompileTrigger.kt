package co.anbora.labs.fantom.ide.compiler

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.annotations.TestOnly
import org.jetbrains.concurrency.CancellablePromise
import java.io.File

/**
 * When a `.fan` file of a pod is saved, the pod is marked dirty and the file's highlighting
 * restarts, so [FantomCompilerAnnotator] runs the compiler once. Typing does not trigger a
 * compile; only the save does. Opening a file needs no extra work: the first highlighting
 * pass compiles the pod when it has no result yet.
 *
 * Both listeners run on the EDT. Finding the pod root walks the folders up to a `build.fan`
 * and reads it, so that lookup runs in a background read action ([FantomCompileTrigger]).
 */
class FantomCompileOnSaveListener : FileDocumentManagerListener {
    override fun beforeDocumentSaving(document: Document) {
        val vf = FileDocumentManager.getInstance().getFile(document) ?: return
        if (vf.extension != "fan") return
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            FantomCompileTrigger.withPodRoot(project, vf) { service, podRoot ->
                service.markDirty(podRoot)
                if (FileEditorManager.getInstance(project).isFileOpen(vf)) {
                    PsiManager.getInstance(project).findFile(vf)?.let {
                        DaemonCodeAnalyzer.getInstance(project).restart(it, "Fantom file saved")
                    }
                }
            }
        }
    }
}

/** A file opened in an editor whose pod has an older result than the file on disk. */
class FantomCompileOnOpenListener : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        // A pod with no result yet compiles on the first highlighting pass; a stale one is
        // marked dirty here so an edit made outside the IDE shows up on open.
        FantomCompileTrigger.withPodRoot(source.project, file) { service, podRoot ->
            val res = service.result(podRoot) ?: return@withPodRoot
            if (res.time < file.timeStamp) service.markDirty(podRoot)
        }
    }
}

/** Finds a file's pod root off the EDT, then acts on it on the EDT. */
object FantomCompileTrigger {

    @Volatile
    private var lookupOnEdt: Boolean? = null

    /** True when the last pod-root lookup ran on the EDT, null when none ran yet. */
    @TestOnly
    fun lastLookupRanOnEdt(): Boolean? = lookupOnEdt

    @TestOnly
    fun resetLookupRecord() { lookupOnEdt = null }

    fun withPodRoot(
        project: Project,
        file: VirtualFile,
        action: (FantomCompilerService, File) -> Unit,
    ): CancellablePromise<File?> {
        val service = FantomCompilerService.getInstance(project)
        return ReadAction.nonBlocking<File?> {
            lookupOnEdt = ApplicationManager.getApplication().isDispatchThread
            if (file.isValid) service.podRootFor(file) else null
        }
            .expireWith(service)
            .finishOnUiThread(ModalityState.nonModal()) { podRoot -> if (podRoot != null) action(service, podRoot) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }
}
