package co.anbora.labs.fantom.ide.axon

import co.anbora.labs.fantom.lang.axon.AxonLanguage
import co.anbora.labs.fantom.lang.core.psi.impl.FantomStrInjectionHost
import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Hides Axon syntax errors in an injected fragment that is only a part of an Axon
 * expression: a Fantom string with `$x` or `${x}` interpolation, or a string that is
 * joined to other values with `+`. The text of such a string is a template, so the parser
 * cannot tell if the final expression is correct.
 *
 * Complete fragments (a plain string in `eval("...")`, a trio `src:` value) and `.axon`
 * files keep their errors.
 */
class AxonInjectedErrorFilter : HighlightErrorFilter() {

    override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean {
        val file = element.containingFile ?: return true
        if (file.language != AxonLanguage) return true
        val host = InjectedLanguageManager.getInstance(file.project).getInjectionHost(file) ?: return true
        return !isTemplate(host)
    }

    companion object {
        /** True when [host] is a Fantom string that holds only a part of the Axon text. */
        fun isTemplate(host: PsiElement): Boolean {
            if (host !is FantomStrInjectionHost) return false
            if (hasInterpolation(host.text)) return true
            return PsiTreeUtil.prevCodeLeaf(host)?.text == "+" || PsiTreeUtil.nextCodeLeaf(host)?.text == "+"
        }

        /** True when the string literal [text] has a `$` with no backslash before it. A raw string `r".."` has none. */
        fun hasInterpolation(text: CharSequence): Boolean {
            if (text.startsWith("r\"")) return false
            var i = 0
            while (i < text.length) {
                when (text[i]) {
                    '\\' -> i++
                    '$' -> return true
                }
                i++
            }
            return false
        }
    }
}
