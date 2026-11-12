package co.anbora.labs.fantom.ide.inspections

import co.anbora.labs.fantom.ide.imports.FantomUsingUsage
import co.anbora.labs.fantom.lang.core.psi.FantomUsingDef
import co.anbora.labs.fantom.lang.resolve.FantomUsingScope
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor

/**
 * Greys out a `using` line nothing in the file refers to, as judged by
 * [FantomUsingUsage]. The fix removes the line.
 */
class FantomUnusedUsingInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val unused by lazy { FantomUsingUsage.unused(holder.file) }
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is FantomUsingDef || element !in unused) return
                val pod = FantomUsingScope.podName(element) ?: return
                val type = FantomUsingScope.typeName(element)
                val shown = if (type == null) pod else "$pod::$type"
                holder.registerProblem(element, "Unused using '$shown'", ProblemHighlightType.LIKE_UNUSED_SYMBOL, RemoveUsingFix)
            }
        }
    }

    /** Deletes the `using` line. When it was the only one, the blank lines after it go too. */
    object RemoveUsingFix : LocalQuickFix {
        override fun getFamilyName(): String = "Remove unused using"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val using = descriptor.psiElement as? FantomUsingDef ?: return
            val file = using.containingFile ?: return
            val manager = PsiDocumentManager.getInstance(project)
            val document = manager.getDocument(file) ?: return
            val text = document.charsSequence
            val range = using.textRange
            val start = text.lastIndexOf('\n', range.startOffset - 1) + 1
            var end = text.indexOf('\n', range.endOffset).let { if (it < 0) text.length else it + 1 }
            if (FantomUsingScope.usingDefs(file).size == 1) {
                while (end < text.length && text[end] == '\n') end++
            }
            document.deleteString(start, end)
            manager.commitDocument(document)
        }
    }
}

private fun CharSequence.lastIndexOf(ch: Char, from: Int): Int {
    var i = minOf(from, length - 1)
    while (i >= 0) { if (this[i] == ch) return i; i-- }
    return -1
}

private fun CharSequence.indexOf(ch: Char, from: Int): Int {
    var i = maxOf(from, 0)
    while (i < length) { if (this[i] == ch) return i; i++ }
    return -1
}
