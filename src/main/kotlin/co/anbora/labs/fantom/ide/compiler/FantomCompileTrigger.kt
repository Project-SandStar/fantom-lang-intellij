package co.anbora.labs.fantom.ide.compiler

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager

/**
 * When a `.fan` file of a pod is saved, the pod is marked dirty and the file's highlighting
 * restarts, so [FantomCompilerAnnotator] runs the compiler once. Typing does not trigger a
 * compile; only the save does. Opening a file needs no extra work: the first highlighting
 * pass compiles the pod when it has no result yet.
 */
class FantomCompileOnSaveListener : FileDocumentManagerListener {
    override fun beforeDocumentSaving(document: Document) {
        val vf = FileDocumentManager.getInstance().getFile(document) ?: return
        if (vf.extension != "fan") return
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            val service = FantomCompilerService.getInstance(project)
            val podRoot = service.podRootFor(vf) ?: continue
            service.markDirty(podRoot)
            if (FileEditorManager.getInstance(project).isFileOpen(vf)) {
                PsiManager.getInstance(project).findFile(vf)?.let {
                    DaemonCodeAnalyzer.getInstance(project).restart(it, "Fantom file saved")
                }
            }
        }
    }
}

/** A file open with an unsaved-on-disk sibling: nothing to do, the daemon's first pass compiles. */
class FantomCompileOnOpenListener : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        val project = source.project
        val service = FantomCompilerService.getInstance(project)
        val podRoot = service.podRootFor(file) ?: return
        // A pod with no result yet compiles on the first highlighting pass; a stale one is
        // marked dirty here so an edit made outside the IDE shows up on open.
        val res = service.result(podRoot) ?: return
        if (res.time < file.timeStamp) service.markDirty(podRoot)
    }
}
