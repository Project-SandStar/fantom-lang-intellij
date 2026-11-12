package co.anbora.labs.fantom.ide.templates

import co.anbora.labs.fantom.lang.core.psi.FantomExpr
import co.anbora.labs.fantom.lang.core.psi.FantomTermExpr
import co.anbora.labs.fantom.lang.types.FanType
import co.anbora.labs.fantom.lang.types.FantomTypeInference
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplate
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplateProvider
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil

/**
 * Postfix templates for Fantom: `expr.if`, `.not`, `.each`, `.echo`, `.var`, `.return`,
 * `.null`, `.nn`. The platform removes the key (`.if`) before it calls
 * [PostfixTemplate.isApplicable] and [PostfixTemplate.expand], so the context element is
 * the last leaf of the expression. The template works on the largest expression that ends
 * at that leaf.
 */
class FantomPostfixTemplateProvider : PostfixTemplateProvider {

    private val templates: Set<PostfixTemplate> = setOf(
        IfTemplate(this),
        NotTemplate(this),
        EachTemplate(this),
        EchoTemplate(this),
        VarTemplate(this),
        ReturnTemplate(this),
        NullTemplate(this, "null", "=="),
        NullTemplate(this, "nn", "!="),
    )

    override fun getTemplates(): Set<PostfixTemplate> = templates

    override fun isTerminalSymbol(currentChar: Char): Boolean = currentChar == '.'

    override fun preExpand(file: PsiFile, editor: Editor) {}

    override fun afterExpand(file: PsiFile, editor: Editor) {}

    override fun preCheck(copyFile: PsiFile, realEditor: Editor, currentOffset: Int): PsiFile = copyFile

    companion object {
        const val BODY_INDENT = "  "

        /**
         * The largest expression that ends where [context] ends: a [FantomExpr] when one
         * ends there, else the outermost [FantomTermExpr]. Null when the caret is not
         * after an expression.
         */
        fun expressionAt(context: PsiElement?): PsiElement? {
            var leaf = context ?: return null
            if (leaf is PsiWhiteSpace) leaf = PsiTreeUtil.prevLeaf(leaf) ?: return null
            val end = leaf.textRange.endOffset
            var expr: PsiElement? = null
            var term: PsiElement? = null
            var e: PsiElement? = leaf
            while (e != null && e !is PsiFile && e.textRange.endOffset == end) {
                if (e is FantomExpr) expr = e
                if (e is FantomTermExpr) term = e
                e = e.parent
            }
            return expr ?: term
        }

        fun typeOf(expr: PsiElement): FanType? = FantomTypeInference.typeOf(expr)

        fun isBool(type: FanType?): Boolean = type is FanType.Named && type.name == "Bool"

        fun isIterable(type: FanType?): Boolean =
            type is FanType.ListOf || type is FanType.MapOf || type?.slotOwnerName == "List" || type?.slotOwnerName == "Map"

        /** Variable name for `.var`: the lowercased type name, `list` for lists, `map` for maps. */
        fun variableName(type: FanType?): String = when (type) {
            is FanType.ListOf -> "list"
            is FanType.MapOf -> "map"
            is FanType.Func -> "func"
            is FanType.Named -> type.name.replaceFirstChar { it.lowercaseChar() }
            null -> "value"
        }

        /** Leading whitespace of the line that holds [offset]. */
        fun lineIndent(document: Document, offset: Int): String {
            val line = document.getLineNumber(offset)
            val start = document.getLineStartOffset(line)
            val text = document.charsSequence
            var i = start
            while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
            return text.subSequence(start, i).toString()
        }
    }
}

/** Base for templates that replace the expression with text and put the caret at a marker. */
abstract class FantomPostfixTemplate(
    name: String,
    example: String,
    provider: PostfixTemplateProvider,
) : PostfixTemplate("fantom.postfix.$name", name, example, provider) {

    protected open fun accepts(expr: PsiElement): Boolean = true

    /** The new text; [CARET] marks where the caret goes. */
    protected abstract fun newText(expr: PsiElement, indent: String): String

    override fun isApplicable(context: PsiElement, copyDocument: Document, newOffset: Int): Boolean {
        val expr = FantomPostfixTemplateProvider.expressionAt(context) ?: return false
        return accepts(expr)
    }

    override fun expand(context: PsiElement, editor: Editor) {
        val expr = FantomPostfixTemplateProvider.expressionAt(context) ?: return
        val document = editor.document
        val range = expr.textRange
        val indent = FantomPostfixTemplateProvider.lineIndent(document, range.startOffset)
        val text = newText(expr, indent)
        val caret = text.indexOf(CARET)
        val plain = if (caret >= 0) text.removeRange(caret, caret + 1) else text
        document.replaceString(range.startOffset, range.endOffset, plain)
        editor.caretModel.moveToOffset(range.startOffset + (if (caret >= 0) caret else plain.length))
        PsiDocumentManager.getInstance(context.project).commitDocument(document)
    }

    companion object {
        const val CARET = '\u0001'
    }
}

/** `if (expr) { | }` on a Bool. */
class IfTemplate(provider: PostfixTemplateProvider) : FantomPostfixTemplate("if", "if (expr) { }", provider) {
    override fun accepts(expr: PsiElement) = FantomPostfixTemplateProvider.isBool(FantomPostfixTemplateProvider.typeOf(expr))
    override fun newText(expr: PsiElement, indent: String) =
        "if (${expr.text}) {\n$indent${FantomPostfixTemplateProvider.BODY_INDENT}$CARET\n$indent}"
}

/** `!expr` on a Bool; a compound expression gets parentheses. */
class NotTemplate(provider: PostfixTemplateProvider) : FantomPostfixTemplate("not", "!expr", provider) {
    override fun accepts(expr: PsiElement) = FantomPostfixTemplateProvider.isBool(FantomPostfixTemplateProvider.typeOf(expr))

    override fun newText(expr: PsiElement, indent: String): String {
        val term = PsiTreeUtil.findChildOfType(expr, FantomTermExpr::class.java, false)
        val simple = expr is FantomTermExpr || term?.textRange == expr.textRange
        return if (simple) "!${expr.text}$CARET" else "!(${expr.text})$CARET"
    }
}

/** `expr.each |v| { | }` on a List or Map. */
class EachTemplate(provider: PostfixTemplateProvider) : FantomPostfixTemplate("each", "expr.each |v| { }", provider) {
    override fun accepts(expr: PsiElement) = FantomPostfixTemplateProvider.isIterable(FantomPostfixTemplateProvider.typeOf(expr))
    override fun newText(expr: PsiElement, indent: String) =
        "${expr.text}.each |v| {\n$indent${FantomPostfixTemplateProvider.BODY_INDENT}$CARET\n$indent}"
}

/** `echo(expr)`. */
class EchoTemplate(provider: PostfixTemplateProvider) : FantomPostfixTemplate("echo", "echo(expr)", provider) {
    override fun newText(expr: PsiElement, indent: String) = "echo(${expr.text})$CARET"
}

/** `return expr`. */
class ReturnTemplate(provider: PostfixTemplateProvider) : FantomPostfixTemplate("return", "return expr", provider) {
    override fun newText(expr: PsiElement, indent: String) = "return ${expr.text}$CARET"
}

/** `if (expr == null) { | }` (`.null`) and `if (expr != null) { | }` (`.nn`). */
class NullTemplate(provider: PostfixTemplateProvider, name: String, private val op: String) :
    FantomPostfixTemplate(name, "if (expr $op null) { }", provider) {
    override fun newText(expr: PsiElement, indent: String) =
        "if (${expr.text} $op null) {\n$indent${FantomPostfixTemplateProvider.BODY_INDENT}$CARET\n$indent}"
}

/** `name := expr`; the name comes from the type and is a live template stop, so the user can rename it. */
class VarTemplate(provider: PostfixTemplateProvider) : PostfixTemplate("fantom.postfix.var", "var", "name := expr", provider) {

    override fun isApplicable(context: PsiElement, copyDocument: Document, newOffset: Int): Boolean =
        FantomPostfixTemplateProvider.expressionAt(context) != null

    override fun expand(context: PsiElement, editor: Editor) {
        val expr = FantomPostfixTemplateProvider.expressionAt(context) ?: return
        val project = context.project
        val name = FantomPostfixTemplateProvider.variableName(FantomPostfixTemplateProvider.typeOf(expr))
        val exprText = expr.text
        val range = expr.textRange
        val document = editor.document
        document.deleteString(range.startOffset, range.endOffset)
        editor.caretModel.moveToOffset(range.startOffset)
        PsiDocumentManager.getInstance(project).commitDocument(document)

        val manager = TemplateManager.getInstance(project)
        val template = manager.createTemplate("", "")
        template.addVariable("NAME", ConstantNode(name), ConstantNode(name), true)
        template.addTextSegment(" := $exprText")
        template.addEndVariable()
        manager.startTemplate(editor, template)
    }
}
