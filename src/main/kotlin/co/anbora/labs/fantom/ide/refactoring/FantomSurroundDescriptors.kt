package co.anbora.labs.fantom.ide.refactoring

import co.anbora.labs.fantom.lang.core.psi.FantomLocalDef
import co.anbora.labs.fantom.lang.types.FantomTypeInference
import com.intellij.lang.surroundWith.SurroundDescriptor
import com.intellij.lang.surroundWith.Surrounder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

/**
 * Surround With (Cmd+Alt+T) for whole statements: `if`, `if/else`, `try/catch`, `while`
 * and a closure `|->| { }`. The statements must sit in a braced statement list.
 */
class FantomStatementSurroundDescriptor : SurroundDescriptor {

    override fun getElementsToSurround(file: PsiFile, startOffset: Int, endOffset: Int): Array<PsiElement> {
        val stmts = FantomRefactoringUtil.statementsInRange(file, startOffset, endOffset)
        if (stmts.isEmpty() || !FantomRefactoringUtil.isInStatementList(stmts.first())) return PsiElement.EMPTY_ARRAY
        return stmts.toTypedArray()
    }

    override fun getSurrounders(): Array<Surrounder> = SURROUNDERS

    override fun isExclusive(): Boolean = false

    companion object {
        private val SURROUNDERS: Array<Surrounder> = arrayOf(
            StatementSurrounder("if", "if (", "true", ") {", "}"),
            StatementSurrounder("if / else", "if (", "true", ") {", "} else {\n\n\$INDENT\$}"),
            StatementSurrounder("try / catch", "try", "", " {", "} catch (Err e) {\n\$INDENT\$\$UNIT\$\$CARET\$\n\$INDENT\$}"),
            StatementSurrounder("while", "while (", "true", ") {", "}"),
            StatementSurrounder("closure |->| { }", "|->|", "", " {", "}"),
        )
    }
}

/**
 * Wraps statements as `<head><selected><headEnd>\n body \n<indent><tail>`. [selected] is
 * the text selected after the edit (the condition); with none, the caret goes to
 * `$CARET$` in the tail, or after the head.
 */
private class StatementSurrounder(
    private val description: String,
    private val head: String,
    private val selected: String,
    private val headEnd: String,
    private val tail: String,
) : Surrounder {

    override fun getTemplateDescription(): String = description

    /** Not applicable when a local declared in the statements is used after them: it would go out of scope. */
    override fun isApplicable(elements: Array<PsiElement>): Boolean {
        if (elements.isEmpty()) return false
        val last = elements.last()
        val scope = last.parent ?: return true
        val after = last.textRange.endOffset
        for (e in elements) {
            val locals = PsiTreeUtil.findChildrenOfType(e, FantomLocalDef::class.java) + listOfNotNull(e as? FantomLocalDef)
            for (local in locals) {
                if (ReferencesSearch.search(local, LocalSearchScope(scope)).findAll()
                        .any { it.element.textRange.startOffset >= after }) return false
            }
        }
        return true
    }

    override fun surroundElements(project: Project, editor: Editor, elements: Array<PsiElement>): TextRange? {
        val document = editor.document
        val first = elements.first()
        val last = elements.last()
        val start = first.textRange.startOffset
        val end = last.textRange.endOffset
        val indent = FantomRefactoringUtil.lineIndent(document, start)
        val unit = FantomRefactoringUtil.indentUnit(first.containingFile)
        val body = FantomRefactoringUtil.reindent(FantomRefactoringUtil.rangeText(document, first, last), indent, indent + unit)

        val tailText = tail.replace("\$INDENT\$", indent).replace("\$UNIT\$", unit)
        val caretInTail = tailText.indexOf("\$CARET\$")
        val text = head + selected + headEnd + "\n" + indent + unit + body + "\n" + indent + tailText.replace("\$CARET\$", "")
        document.replaceString(start, end, text)
        PsiDocumentManager.getInstance(project).commitDocument(document)

        val headStart = start + head.length
        return when {
            selected.isNotEmpty() -> TextRange(headStart, headStart + selected.length)
            caretInTail >= 0 -> {
                val tailStart = start + text.length - tailText.replace("\$CARET\$", "").length
                TextRange.from(tailStart + caretInTail, 0)
            }
            else -> TextRange.from(headStart, 0)
        }
    }
}

/** Surround With for one expression: `(expr)` and `(expr as Type)`. */
class FantomExpressionSurroundDescriptor : SurroundDescriptor {

    override fun getElementsToSurround(file: PsiFile, startOffset: Int, endOffset: Int): Array<PsiElement> {
        if (startOffset == endOffset) return PsiElement.EMPTY_ARRAY
        val expr = FantomRefactoringUtil.expressionInRange(file, startOffset, endOffset) ?: return PsiElement.EMPTY_ARRAY
        return arrayOf(expr)
    }

    override fun getSurrounders(): Array<Surrounder> = SURROUNDERS

    override fun isExclusive(): Boolean = false

    companion object {
        private val SURROUNDERS: Array<Surrounder> = arrayOf(ParenSurrounder(), CastSurrounder())
    }
}

private class ParenSurrounder : Surrounder {
    override fun getTemplateDescription(): String = "(expr)"
    override fun isApplicable(elements: Array<PsiElement>): Boolean = elements.size == 1

    override fun surroundElements(project: Project, editor: Editor, elements: Array<PsiElement>): TextRange {
        val range = elements[0].textRange
        val text = "(" + elements[0].text + ")"
        editor.document.replaceString(range.startOffset, range.endOffset, text)
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        return TextRange.from(range.startOffset + text.length, 0)
    }
}

/** `(expr as Type)`: the type is the inferred one, or `Obj`; it is selected for editing. */
private class CastSurrounder : Surrounder {
    override fun getTemplateDescription(): String = "(expr as Type)"
    override fun isApplicable(elements: Array<PsiElement>): Boolean = elements.size == 1

    override fun surroundElements(project: Project, editor: Editor, elements: Array<PsiElement>): TextRange {
        val expr = elements[0]
        val range = expr.textRange
        val type = FantomTypeInference.typeOf(expr)?.presentableText ?: "Obj"
        val prefix = "(" + expr.text + " as "
        editor.document.replaceString(range.startOffset, range.endOffset, "$prefix$type)")
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        val typeStart = range.startOffset + prefix.length
        return TextRange(typeStart, typeStart + type.length)
    }
}
