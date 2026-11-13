package co.anbora.labs.fantom.ide.refactoring

import co.anbora.labs.fantom.lang.core.psi.*
import co.anbora.labs.fantom.lang.resolve.FantomScope
import co.anbora.labs.fantom.lang.types.FanType
import co.anbora.labs.fantom.lang.types.FantomTypeInference
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Pass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.IntroduceTargetChooser
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.introduce.inplace.OccurrencesChooser
import com.intellij.refactoring.rename.inplace.VariableInplaceRenamer
import com.intellij.refactoring.util.CommonRefactoringUtil

/**
 * Introduce Variable (Cmd+Alt+V): puts `name := expr` before the statement that holds the
 * expression and replaces the expression with `name`. With no selection it offers the
 * chain of expressions around the caret. Equal expressions in the same block can be
 * replaced too. The new local then goes into in-place rename.
 */
class FantomIntroduceVariableHandler : RefactoringActionHandler {

    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext?) {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val selection = editor.selectionModel
        if (selection.hasSelection()) {
            val expr = FantomRefactoringUtil.expressionInRange(file, selection.selectionStart, selection.selectionEnd)
                ?: return error(project, editor, "Select an expression.")
            introduce(project, editor, file, expr)
            return
        }
        val candidates = FantomRefactoringUtil.expressionsAt(file, editor.caretModel.offset)
        when {
            candidates.isEmpty() -> error(project, editor, "Put the caret on an expression.")
            candidates.size == 1 || ApplicationManager.getApplication().isUnitTestMode ->
                introduce(project, editor, file, candidates.first())
            else -> IntroduceTargetChooser.showChooser(
                editor, candidates,
                object : Pass<PsiElement>() {
                    override fun pass(e: PsiElement) = introduce(project, editor, file, e)
                },
                { e -> oneLine(e.text) },
                "Expressions",
            )
        }
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        // Only the editor form is supported.
    }

    private fun introduce(project: Project, editor: Editor, file: PsiFile, expr: PsiElement) {
        val reason = refusal(expr)
        if (reason != null) return error(project, editor, reason)
        val occurrences = occurrences(expr)
        if (occurrences.size <= 1 || ApplicationManager.getApplication().isUnitTestMode) {
            perform(project, editor, file, expr, occurrences)
            return
        }
        val choices = linkedMapOf(
            OccurrencesChooser.ReplaceChoice.NO to listOf(expr),
            OccurrencesChooser.ReplaceChoice.ALL to occurrences,
        )
        OccurrencesChooser.simpleChooser<PsiElement>(editor).showChooser(choices) { choice ->
            perform(project, editor, file, expr, if (choice == OccurrencesChooser.ReplaceChoice.ALL) occurrences else listOf(expr))
        }
    }

    /** Why [expr] cannot be extracted, or null when it can. */
    private fun refusal(expr: PsiElement): String? {
        val stmt = FantomRefactoringUtil.enclosingStatement(expr)
            ?: return "Cannot introduce a variable here: the expression is not inside a statement."
        if (!FantomRefactoringUtil.isInStatementList(stmt))
            return "Cannot introduce a variable in a statement without braces. Add braces first."
        loopHeader(expr, stmt)?.let { return it }
        val type = FantomTypeInference.typeOf(expr)
        if (type is FanType.Named && type.name == "Void" && !type.nullable)
            return "Cannot introduce a variable for an expression of type Void."
        return null
    }

    /** A `while` condition or a `for` header runs more than once: taking it out changes the code. */
    private fun loopHeader(expr: PsiElement, stmt: PsiElement): String? {
        val range = expr.textRange
        when (stmt) {
            is FantomWhileStm -> if (stmt.expr?.textRange?.contains(range) == true)
                return "Cannot introduce a variable from a while condition: it is evaluated on each pass."
            is FantomForStm -> {
                val body = stmt.block
                if (body == null || !body.textRange.contains(range))
                    return "Cannot introduce a variable from a for header: it is evaluated on each pass."
            }
        }
        return null
    }

    /**
     * Equal expressions in the body that holds [expr] (method, ctor or closure), in text
     * order. Nested closures are skipped: their code runs at another time.
     */
    private fun occurrences(expr: PsiElement): List<PsiElement> {
        val callable = FantomRefactoringUtil.enclosingCallable(expr)
        val container: PsiElement = when (callable) {
            is FantomMethodDef -> callable.statementBlock
            is FantomCtorDef -> callable.statementBlock
            is FantomClosure -> callable.statementBlock
            else -> null
        } ?: return listOf(expr)
        val key = normalized(expr)
        val found = ArrayList<PsiElement>()
        fun visit(e: PsiElement) {
            if (e.textLength >= expr.textLength && normalized(e) == key) {
                val o = FantomRefactoringUtil.outermost(e)
                if (o.textRange == e.textRange && FantomRefactoringUtil.isExtractableExpression(o) &&
                    FantomRefactoringUtil.core(o).javaClass == FantomRefactoringUtil.core(expr).javaClass) {
                    val s = FantomRefactoringUtil.enclosingStatement(o)
                    if (s != null && loopHeader(o, s) == null && found.none { it.textRange == o.textRange }) {
                        found += o
                        return
                    }
                }
            }
            if (e is FantomClosure && e != callable) return
            var c = e.firstChild
            while (c != null) { visit(c); c = c.nextSibling }
        }
        visit(container)
        if (found.none { it.textRange == expr.textRange }) found += expr
        return found.sortedBy { it.textRange.startOffset }
    }

    private fun perform(project: Project, editor: Editor, file: PsiFile, expr: PsiElement, occurrences: List<PsiElement>) {
        val document = editor.document
        val first = occurrences.minBy { it.textRange.startOffset }
        val container = statementListHolding(occurrences)
        val anchor = topStatementIn(container, first)
        val names = suggestNames(expr, anchor)
        val name = names.first()
        val exprText = expr.text
        val anchorStart = anchor.textRange.startOffset
        val wholeStatement = occurrences.size == 1 && isWholeExpressionStatement(expr, anchor)
        val replaceRanges = occurrences.map { it.textRange }.sortedByDescending { it.startOffset }
        var declOffset = anchorStart

        WriteCommandAction.writeCommandAction(project, file).withName("Introduce Variable").run<RuntimeException> {
            if (wholeStatement) {
                document.replaceString(anchorStart, anchor.textRange.endOffset, "$name := $exprText")
            } else {
                for (r in replaceRanges) document.replaceString(r.startOffset, r.endOffset, name)
                val separator = if (FantomRefactoringUtil.startsLine(document, anchorStart))
                    "\n" + FantomRefactoringUtil.lineIndent(document, anchorStart) else "; "
                document.insertString(anchorStart, "$name := $exprText$separator")
            }
            declOffset = anchorStart
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }

        val local = PsiTreeUtil.findElementOfClassAtOffset(file, declOffset, FantomLocalDef::class.java, false)
        editor.selectionModel.removeSelection()
        editor.caretModel.moveToOffset(declOffset)
        if (local != null) {
            VariableInplaceRenamer(local as PsiNamedElement, editor, project).performInplaceRename(LinkedHashSet(names))
        }
    }

    /** The innermost statement list that holds all [occurrences]. */
    private fun statementListHolding(occurrences: List<PsiElement>): PsiElement {
        var p: PsiElement? = if (occurrences.size == 1) occurrences[0]
            else PsiTreeUtil.findCommonParent(occurrences)
        while (p != null && !(p is FantomStmts || p is FantomCaseBlock || p is FantomDefaultBlock)) p = p.parent
        return p ?: FantomRefactoringUtil.enclosingStatement(occurrences[0])!!.parent
    }

    /** The statement directly in [container] that holds [e]. */
    private fun topStatementIn(container: PsiElement, e: PsiElement): PsiElement {
        var p = e
        while (p.parent != container) p = p.parent
        return p
    }

    /** `foo.bar()` alone as a statement: the statement becomes `name := foo.bar()`. */
    private fun isWholeExpressionStatement(expr: PsiElement, stmt: PsiElement): Boolean {
        if (stmt !is FantomStmt) return false
        val exprStmt = stmt.exprStmt ?: return false
        if (exprStmt.localDef != null || exprStmt.itAdd != null) return false
        return exprStmt.expr?.textRange == expr.textRange
    }

    companion object {
        private fun error(project: Project, editor: Editor, message: String) =
            CommonRefactoringUtil.showErrorHint(project, editor, message, "Introduce Variable", null)

        private fun oneLine(s: String): String {
            val t = s.replace(Regex("\\s+"), " ")
            return if (t.length > 60) t.take(57) + "..." else t
        }

        private fun normalized(e: PsiElement): String = e.text.filterNot { it.isWhitespace() }

        /** Names for [expr], best first, unique among the names visible at [anchor] and in the method. */
        fun suggestNames(expr: PsiElement, anchor: PsiElement): List<String> {
            val taken = HashSet<String>()
            FantomScope.localsVisibleAt(anchor).mapNotNullTo(taken) { it.name }
            FantomRefactoringUtil.enclosingCallable(anchor)?.let { callable ->
                PsiTreeUtil.findChildrenOfType(callable, FantomNamedElement::class.java)
                    .filter { FantomRefactoringUtil.isLocalLike(it) }
                    .mapNotNullTo(taken) { it.name }
            }
            val bases = LinkedHashSet<String>()
            callName(expr)?.let { bases += it }
            bases += typeName(FantomTypeInference.typeOf(expr))
            bases += "value"
            return bases.map { FantomRefactoringUtil.uniqueName(it, taken) }.distinct()
        }

        /** The name of the last call in a chain: `order.total()` gives `total`, `x.toStr` gives `str`. */
        fun callName(expr: PsiElement): String? {
            val term = FantomRefactoringUtil.core(expr).let { c ->
                c as? FantomTermExpr ?: PsiTreeUtil.getParentOfType(c, FantomTermExpr::class.java, false)
                    ?.takeIf { it.textRange == expr.textRange }
            } ?: return null
            var name: String? = null
            val base = term.termBase
            if (base?.idExpr != null) {
                val next = PsiTreeUtil.skipWhitespacesAndCommentsForward(base)
                if (next is FantomTermChain && next.callOp != null) name = base.idExpr?.text
            }
            for (chain in term.termChainList) {
                val slot = chain.dotCall?.slotExpr ?: continue
                name = slot.call?.id?.text ?: slot.field?.id?.text ?: name
            }
            return name?.let { stripPrefix(it) }
        }

        private fun stripPrefix(name: String): String {
            for (prefix in listOf("to", "get", "as")) {
                if (name.length > prefix.length && name.startsWith(prefix) && name[prefix.length].isUpperCase())
                    return name.substring(prefix.length).replaceFirstChar { it.lowercaseChar() }
            }
            return name.replaceFirstChar { it.lowercaseChar() }
        }

        /** `str`, `list`, `map`, `func`, or the type name in lower camel case. */
        fun typeName(type: FanType?): String = when (type) {
            is FanType.ListOf -> "list"
            is FanType.MapOf -> "map"
            is FanType.Func -> "func"
            is FanType.Named -> if (type.name == "Obj") "value" else type.name.replaceFirstChar { it.lowercaseChar() }
            null -> "value"
        }
    }
}
