package co.anbora.labs.fantom.ide.axon

import co.anbora.labs.fantom.lang.axon.AxonFile
import co.anbora.labs.fantom.lang.axon.copy.AxonFallbackFolder
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
 * from, and its doc (fandoc). A project function is its `.axon` file; its doc comes from
 * the project (the index of the copy) or, when the project has none, from the `doc` tag of
 * the `<name>.trio` record beside the file. A function defined in the file shows its
 * parameters only.
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
                else Info(fn.name + params(topLambda(element)), fn.doc ?: trioDoc(element, fn.name), set.title)
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

        /** The `doc` of `<name>.trio` beside [file], or null. Small files only; no network. */
        private fun trioDoc(file: AxonFile, name: String): String? {
            val trio = file.virtualFile?.parent?.findChild("$name.trio") ?: return null
            if (trio.length > MAX_TRIO) return null
            val text = runCatching { String(trio.contentsToByteArray(), trio.charset) }.getOrNull() ?: return null
            return AxonFallbackFolder.trioTags(text)["doc"]?.takeIf { it.isNotBlank() }
        }

        private const val MAX_TRIO = 1024L * 1024
    }
}
