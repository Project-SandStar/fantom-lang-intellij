package co.anbora.labs.fantom.ide.refactoring

import co.anbora.labs.fantom.lang.core.psi.*
import com.intellij.codeInsight.unwrap.UnwrapDescriptorBase
import com.intellij.codeInsight.unwrap.Unwrapper
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.PsiComment

/**
 * Unwrap/Remove (Cmd+Shift+Delete) for `if`, `else`, `try`, `catch`, `while`, `for` and a
 * closure block statement `|->| { ... }`. Unwrapping keeps the body statements at the
 * indent of the removed statement.
 *
 * A bare `{ ... }` block is not a statement in the Fantom grammar, so the block case is the
 * no-argument closure written as a statement, the shape the closure surrounder makes.
 */
class FantomUnwrapDescriptor : UnwrapDescriptorBase() {

    override fun createUnwrappers(): Array<Unwrapper> = arrayOf(
        ElseUnwrapper(),
        ElseRemover(),
        IfUnwrapper(),
        CatchRemover(),
        TryUnwrapper(),
        WhileUnwrapper(),
        ForUnwrapper(),
        ClosureBlockUnwrapper(),
    )
}

/** Base: the document is edited directly, so the returned list of extracted elements is empty. */
private abstract class FantomUnwrapper(private val description: String) : Unwrapper {

    override fun getDescription(e: PsiElement): String = description

    override fun collectElementsToIgnore(element: PsiElement, result: MutableSet<PsiElement>) {}

    override fun collectAffectedElements(e: PsiElement, toExtract: MutableList<in PsiElement>): PsiElement {
        toExtract.addAll(kept(e))
        return target(e)
    }

    /** The statement that changes. */
    protected open fun target(e: PsiElement): PsiElement = e

    /** The statements that stay. */
    protected open fun kept(e: PsiElement): List<PsiElement> = emptyList()

    override fun unwrap(editor: Editor, element: PsiElement): List<PsiElement> {
        val document = editor.document
        edit(document, element)
        PsiDocumentManager.getInstance(element.project).commitDocument(document)
        return emptyList()
    }

    /** Default edit: replace [target] by the [kept] statements, re-indented to its indent. */
    protected open fun edit(document: Document, e: PsiElement) {
        replaceWithStatements(document, target(e), kept(e))
    }

    companion object {
        fun replaceWithStatements(document: Document, target: PsiElement, kept: List<PsiElement>, prefix: String = "") {
            val range = target.textRange
            if (kept.isEmpty() && prefix.isEmpty()) {
                val lines = FantomRefactoringUtil.wholeLinesRange(document, range.startOffset, range.endOffset)
                document.deleteString(lines.startOffset, lines.endOffset)
                return
            }
            val indent = FantomRefactoringUtil.lineIndent(document, range.startOffset)
            val parts = ArrayList<String>()
            if (prefix.isNotEmpty()) parts += prefix
            if (kept.isNotEmpty()) {
                val first = kept.first()
                // The indent of the line the first statement is on: for `else if (...) {` the inner
                // lines are one unit deeper than that line, not than the statement column.
                val from = FantomRefactoringUtil.lineIndent(document, first.textRange.startOffset)
                parts += FantomRefactoringUtil.reindent(FantomRefactoringUtil.rangeText(document, first, kept.last()), from, indent)
            }
            document.replaceString(range.startOffset, range.endOffset, parts.joinToString("\n$indent"))
        }

        /** Unwrap only statements in a statement list: a brace-less body cannot take several statements. */
        fun inList(e: PsiElement): Boolean =
            FantomRefactoringUtil.isStatement(e) && FantomRefactoringUtil.isInStatementList(e)

        fun elseBlock(ifStm: FantomIfStm): FantomBlock? = ifStm.blockList.getOrNull(1)
    }
}

private class IfUnwrapper : FantomUnwrapper("Unwrap 'if...'") {
    override fun isApplicableTo(e: PsiElement) = e is FantomIfStm && inList(e)
    override fun kept(e: PsiElement) = FantomRefactoringUtil.blockStatements((e as FantomIfStm).blockList.firstOrNull())
}

/** `else` block: keeps the else body (an `else if` keeps the inner `if`) and drops the rest. */
private class ElseUnwrapper : FantomUnwrapper("Unwrap 'else...'") {
    override fun isApplicableTo(e: PsiElement): Boolean {
        val ifStm = e.parent as? FantomIfStm ?: return false
        return e is FantomBlock && elseBlock(ifStm) == e && inList(ifStm)
    }
    override fun target(e: PsiElement): PsiElement = e.parent
    override fun kept(e: PsiElement) = FantomRefactoringUtil.blockStatements(e as FantomBlock)
}

/** `else` block: removes `else { ... }` and keeps the `if`. */
private class ElseRemover : FantomUnwrapper("Remove 'else...'") {
    override fun isApplicableTo(e: PsiElement): Boolean {
        val ifStm = e.parent as? FantomIfStm ?: return false
        return e is FantomBlock && elseBlock(ifStm) == e
    }
    override fun target(e: PsiElement): PsiElement = e.parent
    override fun edit(document: Document, e: PsiElement) {
        val ifStm = e.parent as FantomIfStm
        val then = ifStm.blockList.first()
        document.deleteString(then.textRange.endOffset, e.textRange.endOffset)
    }
}

/** `try`: keeps the try body and the finally body, drops the catch clauses. */
private class TryUnwrapper : FantomUnwrapper("Unwrap 'try...'") {
    override fun isApplicableTo(e: PsiElement) = e is FantomTryStm && inList(e)
    override fun kept(e: PsiElement): List<PsiElement> {
        val tryStm = e as FantomTryStm
        val finally = tryStm.stmtList.filterIsInstance<FantomFinallyStm>().firstOrNull()
        return FantomRefactoringUtil.blockStatements(tryStm.block) + FantomRefactoringUtil.blockStatements(finally?.block)
    }

    override fun edit(document: Document, e: PsiElement) {
        // Try and finally bodies may have different indents: join them as text.
        val tryStm = e as FantomTryStm
        val finally = tryStm.stmtList.filterIsInstance<FantomFinallyStm>().firstOrNull()
        val finallyBody = FantomRefactoringUtil.blockStatements(finally?.block)
        if (finallyBody.isEmpty()) return replaceWithStatements(document, e, FantomRefactoringUtil.blockStatements(tryStm.block))
        val indent = FantomRefactoringUtil.lineIndent(document, e.textRange.startOffset)
        fun text(stmts: List<PsiElement>): String? {
            if (stmts.isEmpty()) return null
            val from = FantomRefactoringUtil.lineIndent(document, stmts.first().textRange.startOffset)
            return FantomRefactoringUtil.reindent(FantomRefactoringUtil.rangeText(document, stmts.first(), stmts.last()), from, indent)
        }
        val parts = listOfNotNull(text(FantomRefactoringUtil.blockStatements(tryStm.block)), text(finallyBody))
        document.replaceString(e.textRange.startOffset, e.textRange.endOffset, parts.joinToString("\n$indent"))
    }
}

/** `catch`: removes the clause. Not offered for the only clause of a `try` without `finally`. */
private class CatchRemover : FantomUnwrapper("Remove 'catch...'") {
    override fun isApplicableTo(e: PsiElement): Boolean {
        if (e !is FantomCatchStm) return false
        val tryStm = e.parent as? FantomTryStm ?: return false
        return tryStm.stmtList.size > 1
    }
    override fun edit(document: Document, e: PsiElement) {
        var prev = e.prevSibling
        while (prev is PsiWhiteSpace || prev is PsiComment) prev = prev.prevSibling
        val start = prev?.textRange?.endOffset ?: e.textRange.startOffset
        document.deleteString(start, e.textRange.endOffset)
    }
}

private class WhileUnwrapper : FantomUnwrapper("Unwrap 'while...'") {
    override fun isApplicableTo(e: PsiElement) = e is FantomWhileStm && inList(e)
    override fun kept(e: PsiElement) = FantomRefactoringUtil.blockStatements((e as FantomWhileStm).block)
}

/** `for`: keeps the init (`i := 0`) as a statement, then the body. */
private class ForUnwrapper : FantomUnwrapper("Unwrap 'for...'") {
    override fun isApplicableTo(e: PsiElement) = e is FantomForStm && inList(e)
    override fun kept(e: PsiElement) = FantomRefactoringUtil.blockStatements((e as FantomForStm).block)
    override fun edit(document: Document, e: PsiElement) {
        val init = (e as FantomForStm).forInit?.text?.trim().orEmpty()
        replaceWithStatements(document, e, kept(e), init)
    }
}

/** A statement that is only a no-argument closure `|->| { ... }`: keeps its body. */
private class ClosureBlockUnwrapper : FantomUnwrapper("Unwrap closure block") {
    override fun isApplicableTo(e: PsiElement): Boolean = closureOf(e) != null && inList(e)
    override fun kept(e: PsiElement) = FantomRefactoringUtil.statementsOf(closureOf(e)!!.statementBlock)

    private fun closureOf(e: PsiElement): FantomClosure? {
        if (e !is FantomStmt) return null
        val expr = e.exprStmt?.takeIf { it.localDef == null && it.itAdd == null }?.expr ?: return null
        val core = FantomRefactoringUtil.core(expr) as? FantomTermExpr ?: return null
        if (core.termChainList.isNotEmpty()) return null
        val closure = core.termBase?.typeBase?.closure ?: return null
        if (closure.closureSig?.closureParams?.closureParamList?.isNotEmpty() == true) return null
        return closure
    }
}
