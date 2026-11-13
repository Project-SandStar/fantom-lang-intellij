package co.anbora.labs.fantom.ide.axon

import co.anbora.labs.fantom.lang.axon.AxonLanguage
import co.anbora.labs.fantom.lang.core.psi.impl.FantomStrInjectionHost
import co.anbora.labs.fantom.lang.trio.TrioSrcValue
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil

/**
 * Injects Axon:
 * - into a Fantom string that is the only text of the first argument of a call
 *   named `eval`, `evalAll`, `evalToFunc`, `parseAxon` or `Axon`;
 * - into a Fantom string whose line above is `// language=axon`;
 * - into the `src:` value of a `.trio` record.
 *
 * The call is found from tokens (`name ( "..." ,|)`), not from the PSI shape,
 * so `eval("x")`, `cx.eval("x")` and `Axon("x")` all match.
 */
class AxonInjector : MultiHostInjector {

    override fun elementsToInjectIn(): List<Class<out PsiElement>> =
        listOf(FantomStrInjectionHost::class.java, TrioSrcValue::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        when (context) {
            is FantomStrInjectionHost -> {
                if (!context.isValidHost || !isAxonString(context)) return
                val range = context.contentRange() ?: return
                inject(registrar, context, range)
            }
            is TrioSrcValue -> {
                if (context.textLength == 0) return
                inject(registrar, context, TextRange(0, context.textLength))
            }
        }
    }

    private fun inject(registrar: MultiHostRegistrar, host: PsiLanguageInjectionHost, range: TextRange) {
        registrar.startInjecting(AxonLanguage)
            .addPlace(null, null, host, range)
            .doneInjecting()
    }

    companion object {
        val CALL_NAMES = setOf("eval", "evalAll", "evalToFunc", "parseAxon", "Axon")
        private val LANGUAGE_COMMENT = Regex("""^\s*//\s*language\s*=\s*axon\b.*""", RegexOption.IGNORE_CASE)

        fun isAxonString(str: FantomStrInjectionHost): Boolean = isCallArgument(str) || hasLanguageComment(str)

        private fun isCallArgument(str: PsiElement): Boolean {
            val open = PsiTreeUtil.prevCodeLeaf(str) ?: return false
            if (open.text != "(") return false
            val name = PsiTreeUtil.prevCodeLeaf(open) ?: return false
            if (name.text !in CALL_NAMES) return false
            val after = PsiTreeUtil.nextCodeLeaf(str)?.text
            return after == "," || after == ")"
        }

        private fun hasLanguageComment(str: PsiElement): Boolean {
            val file = str.containingFile ?: return false
            val doc = PsiDocumentManager.getInstance(str.project).getDocument(file)
            val text = doc?.charsSequence ?: file.viewProvider.contents
            val start = str.textRange.startOffset
            val lineStart = text.lastIndexOf('\n', start - 1) + 1
            if (lineStart == 0) return false
            val prevStart = text.lastIndexOf('\n', lineStart - 2) + 1
            val prevLine = text.subSequence(prevStart, lineStart - 1).toString()
            return LANGUAGE_COMMENT.matches(prevLine)
        }

        private fun CharSequence.lastIndexOf(c: Char, from: Int): Int {
            var i = minOf(from, length - 1)
            while (i >= 0) {
                if (this[i] == c) return i
                i--
            }
            return -1
        }
    }
}
