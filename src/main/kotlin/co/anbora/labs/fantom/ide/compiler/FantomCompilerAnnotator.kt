package co.anbora.labs.fantom.ide.compiler

import co.anbora.labs.fantom.lang.library.FantomSettings
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
    class Input(val project: Project, val file: VirtualFile, val podRoot: File, val home: File)

    override fun collectInformation(file: PsiFile): Input? {
        val vf = file.virtualFile ?: return null
        val project = file.project
        val podRoot = FantomCompilerService.getInstance(project).podRootFor(vf) ?: return null
        val home = FantomSettings.getInstance(project).effectiveHome() ?: return null
        return Input(project, vf, podRoot, home)
    }

    override fun doAnnotate(collectedInfo: Input?): List<FantomDiagnostic>? {
        val input = collectedInfo ?: return null
        if (input.project.isDisposed) return null
        val indicator = ProgressManager.getInstance().progressIndicator
        val result = FantomCompilerService.getInstance(input.project).compile(input.podRoot, input.home, indicator, input.file) ?: return null
        return result.forFile(FantomCompilerDiagnostics.canonical(input.file.path))
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
