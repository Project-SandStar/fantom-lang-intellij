package co.anbora.labs.fantom.ide.hints

import co.anbora.labs.fantom.lang.core.psi.FantomClosureParam
import co.anbora.labs.fantom.lang.core.psi.FantomCtorBlock
import co.anbora.labs.fantom.lang.core.psi.FantomLiteral
import co.anbora.labs.fantom.lang.core.psi.FantomLocalDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomSimple
import co.anbora.labs.fantom.lang.core.psi.FantomTermExpr
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.IT
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.SUPER
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.THIS
import co.anbora.labs.fantom.lang.types.FanType
import co.anbora.labs.fantom.lang.types.FantomTypeInference
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
 * Inlay hints with the inferred type of a local (`x := count` shows `: Int`) and of an
 * untyped closure parameter, after the name. Like Kotlin, the hint is hidden when the
 * initializer already says the type: a plain literal (`1`, `"s"`, `true`, `[,]`) or a
 * construction call `Foo(...)` / `Foo { }` whose type is the inferred type.
 *
 * Declarative inlay API (`com.intellij.codeInsight.hints.declarative`), registered as
 * `codeInsight.declarativeInlayProvider` with id `fantom.types` in plugin.xml.
 */
class FantomTypeHintsProvider : InlayHintsProvider {

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector = Collector()

    private class Collector : SharedBypassCollector {
        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            val decl = element as? FantomNamedElement ?: return
            val type = hintType(decl) ?: return
            val nameId = decl.nameIdentifier ?: return
            sink.addPresentation(
                InlineInlayPosition(nameId.textRange.endOffset, relatedToPrevious = true),
                hintFormat = HintFormat.default,
            ) {
                text(": " + type.presentableText)
            }
        }
    }

    companion object {
        /** The type to show after [decl]'s name, or null when there is none or it is obvious. */
        fun hintType(decl: FantomNamedElement): FanType? = when (decl) {
            is FantomLocalDef -> if (decl.type != null) null else {
                val expr = decl.expr
                val type = expr?.let { FantomTypeInference.typeOf(it) }
                if (type == null || isObvious(expr, type)) null else type
            }
            is FantomClosureParam -> if (decl.type != null) null else FantomTypeInference.typeOfDeclaration(decl)
            else -> null
        }

        /**
         * True when the initializer text says the type already: a literal with no chain
         * after it, or `Type(...)` / `Type { }` with the same type as [type].
         */
        private fun isObvious(expr: PsiElement, type: FanType): Boolean {
            // Walk down the single-child wrapper chain to the term expression that spans the whole initializer.
            val term = PsiTreeUtil.findChildrenOfType(expr, FantomTermExpr::class.java)
                .firstOrNull { it.textRange == expr.textRange } ?: return false
            if (term.termChainList.isNotEmpty()) return false
            val base = term.termBase ?: return false
            base.literal?.let { return isValueLiteral(it) }
            val typeBase = base.typeBase ?: return false
            val written = when {
                typeBase.simple != null -> (typeBase.simple as FantomSimple).type
                typeBase.ctorBlock != null -> (typeBase.ctorBlock as FantomCtorBlock).type
                else -> return false
            }
            return FantomTypeInference.fromPsiType(written)?.withNullable(false) == type
        }

        /** Literals that name their own type; `this`, `super` and `it` do not. */
        private fun isValueLiteral(literal: FantomLiteral): Boolean =
            when (literal.node.firstChildNode?.elementType) {
                THIS, SUPER, IT -> false
                else -> true
            }
    }
}
