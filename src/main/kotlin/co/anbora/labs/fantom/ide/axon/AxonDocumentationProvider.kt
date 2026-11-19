package co.anbora.labs.fantom.ide.axon

import co.anbora.labs.fantom.lang.axon.AxonFile
import co.anbora.labs.fantom.lang.axon.project.AxonProjectFunctions
import co.anbora.labs.fantom.lang.axon.psi.AxonDefExpr
import co.anbora.labs.fantom.lang.axon.psi.AxonLambdaExpr
import co.anbora.labs.fantom.lang.axon.psi.AxonResolver
import co.anbora.labs.fantom.lang.fandoc.FandocRenderer
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.psi.PsiElement

/**
 * Quick Documentation (Ctrl+Q, F1) for Axon functions: `name(a, b)`, the project it comes
 * from, and its doc (fandoc). A project function is its `.axon` file; its doc is the one the
 * [AxonFunctionSet][co.anbora.labs.fantom.lang.axon.project.AxonFunctionSet] holds in memory
 * (the index of the copy). Nothing is read from disk here: the doc runs in a read action. A
 * provider that reads `<name>.trio` files (the proj folder, task 4.38) puts their `doc` in its
 * set when it builds its index. A function defined in the file shows its parameters only.
 */
class AxonDocumentationProvider : AbstractDocumentationProvider() {

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
        val info = info(element) ?: return null
        val project = info.project?.let { "  [$it]" }.orEmpty()
        return FandocRenderer.escape(info.signature + project)
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val info = info(element) ?: return null
        val sb = StringBuilder(DocumentationMarkup.DEFINITION_START)
        info.project?.let { sb.append(DocumentationMarkup.GRAYED_START).append(FandocRenderer.escape(it)).append(DocumentationMarkup.GRAYED_END).append("<br>") }
        sb.append(FandocRenderer.escape(info.signature)).append(DocumentationMarkup.DEFINITION_END)
        val html = info.doc?.let { FandocRenderer.render(it) }.orEmpty()
        if (html.isNotEmpty()) sb.append(DocumentationMarkup.CONTENT_START).append(html).append(DocumentationMarkup.CONTENT_END)
        return sb.toString()
    }

    /** What the doc shows: `name(a, b)`, the doc in fandoc, and the project, or null for a non-function. */
    class Info(val signature: String, val doc: String?, val project: String?)

    companion object {

        fun info(element: PsiElement?): Info? = when (element) {
            is AxonFile -> {
                val set = AxonProjectFunctions.of(element)
                val fn = set?.functionOf(element)
                if (set == null || fn == null) null
                else Info(fn.name + params(topLambda(element)), fn.doc, set.title)
            }
            is AxonDefExpr ->
                if (AxonResolver.isFunction(element)) Info((element.name ?: "?") + params(element.expr as? AxonLambdaExpr), null, null) else null
            else -> null
        }

        /** The function of a function file: `(a, b) => ...`, or `name: (a, b) => ...`. */
        private fun topLambda(file: AxonFile): AxonLambdaExpr? {
            for (c in file.children) {
                if (c is AxonLambdaExpr) return c
                if (c is AxonDefExpr && c.expr is AxonLambdaExpr) return c.expr as AxonLambdaExpr
            }
            return null
        }

        private fun params(lambda: AxonLambdaExpr?): String =
            lambda?.paramList?.joinToString(", ", "(", ")") { it.text } ?: "()"
    }
}
