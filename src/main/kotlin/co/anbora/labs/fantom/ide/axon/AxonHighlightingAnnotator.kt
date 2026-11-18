package co.anbora.labs.fantom.ide.axon

import co.anbora.labs.fantom.lang.axon.AxonTokenTypes
import co.anbora.labs.fantom.lang.axon.psi.AxonCallExpr
import co.anbora.labs.fantom.lang.axon.psi.AxonCatchVar
import co.anbora.labs.fantom.lang.axon.psi.AxonCellDef
import co.anbora.labs.fantom.lang.axon.psi.AxonDefExpr
import co.anbora.labs.fantom.lang.axon.psi.AxonDictEntry
import co.anbora.labs.fantom.lang.axon.psi.AxonDotCallExpr
import co.anbora.labs.fantom.lang.axon.psi.AxonLambdaExpr
import co.anbora.labs.fantom.lang.axon.psi.AxonNamedElement
import co.anbora.labs.fantom.lang.axon.psi.AxonParam
import co.anbora.labs.fantom.lang.axon.psi.AxonRefExpr
import co.anbora.labs.fantom.lang.axon.psi.AxonResolver
import co.anbora.labs.fantom.lang.axon.psi.AxonTrapExpr
import com.intellij.lang.ASTNode
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType

/**
 * Axon colours that the lexer cannot give: the colour of a name comes from what the name is.
 *
 * - Declarations: a function (`name: (a) => ...`, `name: defcomp ... end`), a parameter,
 *   a local (`name: expr`, `catch (err)`), a `defcomp` cell.
 * - A bare name takes the colour of its declaration in the same file. With no declaration
 *   it is a function call when `(` or a lambda follows, and it keeps the default colour
 *   otherwise: `sum` in `foldCol("v0", sum)`, `site` in `readAll(site)`.
 * - The name after a dot: a call with arguments, or a call without (`his.size`).
 * - Tag names: a dict key and the name after `->`.
 *
 * It reads only the PSI of the file at hand ([AxonResolver.resolveInFile]): no index, no
 * function source, no network. Every annotation is silent, so a name is never an error here.
 * It runs on injected Axon too, because the platform runs the annotators of the injected language.
 */
class AxonHighlightingAnnotator : Annotator, DumbAware {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when (element) {
            is AxonDefExpr -> paint(holder, element.nameIdentifier, declarationColor(element))
            is AxonParam, is AxonCatchVar, is AxonCellDef ->
                paint(holder, (element as AxonNamedElement).nameIdentifier, declarationColor(element))
            is AxonRefExpr -> paint(holder, element.identifier, useColor(element))
            is AxonDotCallExpr ->
                paint(holder, element.referenceNameElement, if (hasArguments(element)) AxonColors.FUNCTION_CALL else AxonColors.DOT_NAME)
            is AxonTrapExpr -> paint(holder, element.identifier, AxonColors.TAG_NAME)
            is AxonDictEntry -> paint(holder, dictKey(element), AxonColors.TAG_NAME)
        }
    }

    private fun paint(holder: AnnotationHolder, name: PsiElement?, color: AxonColors?) {
        if (name == null || color == null || name.textLength == 0) return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(name).textAttributes(color.textAttributesKey).create()
    }

    companion object {
        /** The colour of the name of [declaration], the same at the declaration and at each use. */
        fun declarationColor(declaration: PsiElement): AxonColors? = when (declaration) {
            is AxonParam -> AxonColors.PARAMETER
            is AxonCatchVar -> AxonColors.LOCAL_VARIABLE
            is AxonCellDef -> AxonColors.DEFCOMP_CELL
            is AxonDefExpr -> if (AxonResolver.isFunction(declaration)) AxonColors.FUNCTION_DECLARATION else AxonColors.LOCAL_VARIABLE
            else -> null
        }

        /** The colour of the bare name [ref], or null when nothing in the file tells what it is. */
        fun useColor(ref: AxonRefExpr): AxonColors? {
            val color = AxonResolver.resolveInFile(ref)?.let { declarationColor(it) }
            return when (color) {
                // A use of a function is a call or a function value (`list.map(double)`).
                AxonColors.FUNCTION_DECLARATION -> AxonColors.FUNCTION_CALL
                null -> if (isCalled(ref)) AxonColors.FUNCTION_CALL else null
                else -> color
            }
        }

        /** True for the `f` of `f(a)` and of `f(a) x => x`. */
        private fun isCalled(ref: AxonRefExpr): Boolean {
            val call = ref.parent as? AxonCallExpr ?: return false
            return call.firstChild === ref
        }

        /** True for `a.f(b)` and `a.f x => x`; false for `a.f`. */
        private fun hasArguments(dot: AxonDotCallExpr): Boolean =
            dot.argList != null || dot.exprList.any { it is AxonLambdaExpr && it.textOffset > (dot.referenceNameElement?.textOffset ?: Int.MAX_VALUE) }

        /** The key of `{key: value}`, `{key}` and `{-key}` when it is a name. A quoted key keeps the string colour. */
        private fun dictKey(entry: AxonDictEntry): PsiElement? {
            var node: ASTNode? = entry.node.firstChildNode
            while (node != null) {
                val type = node.elementType
                when {
                    type == TokenType.WHITE_SPACE || type in AxonTokenTypes.COMMENTS || type == AxonTokenTypes.MINUS -> node = node.treeNext
                    type == AxonTokenTypes.IDENTIFIER || type in AxonTokenTypes.KEYWORDS -> return node.psi
                    else -> return null
                }
            }
            return null
        }
    }
}
