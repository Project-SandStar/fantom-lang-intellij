package co.anbora.labs.fantom.ide.compiler

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import java.io.File

/**
 * Compiler diagnostics in the editor.
 *
 * [collectInformation] needs a local `.fan` file inside a pod (a folder with a `build.fan`
 * that names the pod), a Fantom home, and the compile-on-save setting on. [doAnnotate]
 * asks [FantomCompilerService] for the pod's result; that is a cached answer unless a save
 * marked the pod dirty, so typing costs nothing and only a save or the first open runs
 * `fan build.fan compile`. [apply] paints each message of this file from `line:col` to the
 * end of the token there.
 */
class FantomCompilerAnnotator : ExternalAnnotator<FantomCompilerAnnotator.Input, List<FantomDiagnostic>>() {
    /** [cachedOnly]: a pod built by the Build Pod action with compile-on-save off; show, never compile. */
    class Input(
        val project: Project, val file: VirtualFile, val podRoot: File, val home: File,
        val cachedOnly: Boolean = false, val podPath: List<File> = emptyList(),
    )

    override fun collectInformation(file: PsiFile): Input? {
        val vf = file.virtualFile ?: return null
        val project = file.project
        val service = FantomCompilerService.getInstance(project)
        val env = FantomBuildTargets.defaultEnv(project) ?: return null
        val home = env.runner
        service.podRootFor(vf)?.let { return Input(project, vf, it, home, podPath = env.podPath) }
        val built = service.builtRootFor(vf) ?: return null
        return Input(project, vf, built, home, cachedOnly = true)
    }

    override fun doAnnotate(collectedInfo: Input?): List<FantomDiagnostic>? {
        val input = collectedInfo ?: return null
        if (input.project.isDisposed) return null
        val service = FantomCompilerService.getInstance(input.project)
        val result = if (input.cachedOnly) {
            // Lines move once the file changes after the build; show nothing stale.
            val builtAt = service.builtAt(input.podRoot) ?: return null
            if (input.file.timeStamp > builtAt || isUnsaved(input.file)) return null
            service.result(input.podRoot) ?: return null
        } else {
            val indicator = ProgressManager.getInstance().progressIndicator
            service.compile(input.podRoot, input.home, indicator, input.file, input.podPath) ?: return null
        }
        return result.forFile(FantomCompilerDiagnostics.canonical(input.file.path))
    }

    private fun isUnsaved(file: VirtualFile): Boolean {
        val fdm = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
        val doc = fdm.getCachedDocument(file) ?: return false
        return fdm.isDocumentUnsaved(doc)
    }

    override fun apply(file: PsiFile, annotationResult: List<FantomDiagnostic>?, holder: AnnotationHolder) {
        val diagnostics = annotationResult?.takeIf { it.isNotEmpty() } ?: return
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        for (d in diagnostics) {
            val lineIndex = d.line - 1
            if (lineIndex < 0 || lineIndex >= document.lineCount) continue
            val lineStart = document.getLineStartOffset(lineIndex)
            val lineEnd = document.getLineEndOffset(lineIndex)
            val start = (lineStart + d.column - 1).coerceIn(lineStart, lineEnd)
            val leaf = file.findElementAt(start)
            var end = if (leaf != null && leaf !is PsiWhiteSpace) leaf.textRange.endOffset else start + 1
            end = end.coerceIn(start, maxOf(lineEnd, start))
            val range = if (end > start) TextRange(start, end) else TextRange(lineStart, maxOf(lineEnd, lineStart + 1).coerceAtMost(document.textLength))
            if (range.isEmpty) continue
            holder.newAnnotation(d.severity, d.message).range(range).create()
        }
    }
}
