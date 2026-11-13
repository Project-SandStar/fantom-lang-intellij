package co.anbora.labs.fantom.ide.hints

import co.anbora.labs.fantom.ide.parameters.FantomArgMapping
import co.anbora.labs.fantom.lang.core.psi.FantomArgs
import co.anbora.labs.fantom.lang.core.psi.FantomExpr
import co.anbora.labs.fantom.lang.core.psi.FantomTermExpr
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

/**
 * Parameter name hints before call arguments (`addLine(`sku:`"A-1", `qty:`2)`), like
 * Kotlin and Java. A hint is shown for a literal, a call or an arithmetic argument of a
 * resolved method or ctor with two or more parameters. No hint when:
 *
 * - the argument already names the parameter: `sku` or `line.sku` for a param `sku`;
 * - the argument is a closure `|x| { }`;
 * - the callee has fewer than two parameters;
 * - the callee is an operator-like slot (`get`, `set`, `plus`, `equals`, `echo`, ...);
 * - the call does not resolve, or resolves to several candidates with different names.
 *
 * A trailing closure block (`list.each |x| { }`) is not inside `args`, so it never gets
 * a hint. Fantom has no varargs; default-valued trailing parameters map by position.
 *
 * Declarative inlay API, registered as `codeInsight.declarativeInlayProvider` with id
 * `fantom.parameters` in plugin.xml, in the same settings group as the type hints.
 */
class FantomParameterHintsProvider : InlayHintsProvider {

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector = Collector()

    private class Collector : SharedBypassCollector {
        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            val args = element as? FantomArgs ?: return
            for ((arg, name) in hints(args)) {
                sink.addPresentation(
                    InlineInlayPosition(arg.textRange.startOffset, relatedToPrevious = false),
                    hintFormat = HintFormat.default,
                ) {
                    text("$name:")
                }
            }
        }
    }

    companion object {
        /** Slots that read as operators or are too common to label; their arguments get no hint. */
        val OPERATOR_LIKE: Set<String> = setOf(
            "get", "set", "plus", "minus", "mult", "div", "mod", "equals", "compare", "echo",
        )

        /** The argument and parameter name of every hint to show for [args]. */
        fun hints(args: FantomArgs): List<Pair<FantomExpr, String>> {
            val mapped = FantomArgMapping.map(args)
            if (mapped.isEmpty()) return emptyList()
            val callee = mapped[0].callee
            if (callee.name in OPERATOR_LIKE) return emptyList()
            if (FantomArgMapping.params(callee).size < 2) return emptyList()
            return mapped.mapNotNull { m ->
                val name = m.param.id.text
                if (name.isEmpty() || isNamedLike(m.arg, name) || isClosure(m.arg)) null else m.arg to name
            }
        }

        private val LAST_NAME = Regex("""(?:^|[.>])([A-Za-z_]\w*)$""")

        /** True when the argument text is `name` or ends in `.name`, `?.name` or `->name`. */
        private fun isNamedLike(arg: FantomExpr, name: String): Boolean =
            LAST_NAME.find(arg.text.trim())?.groupValues?.get(1) == name

        /** True when the whole argument is a closure `|...| { }`. */
        private fun isClosure(arg: FantomExpr): Boolean {
            val term = PsiTreeUtil.findChildrenOfType(arg, FantomTermExpr::class.java)
                .firstOrNull { it.textRange == arg.textRange } ?: return false
            return term.termChainList.isEmpty() && term.termBase?.typeBase?.closure != null
        }
    }
}
