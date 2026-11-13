package co.anbora.labs.fantom.ide.refactoring

import co.anbora.labs.fantom.lang.core.psi.*
import co.anbora.labs.fantom.lang.resolve.FantomScope
import co.anbora.labs.fantom.lang.types.FantomTypeInference
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.util.CommonRefactoringUtil

/**
 * Extract Method (Cmd+Alt+M) for whole statements in a method or ctor body.
 *
 * Inputs are locals and params that the selection reads and that are declared before it.
 * The output is at most one local that the selection writes and the code after it reads:
 * a local declared in the selection gives `x := extracted(a)`, an outer local assigned in
 * the selection gives `x = extracted(a)`. The new method is private, goes after the
 * current slot, and is static when the current method is static.
 */
class FantomExtractMethodHandler : RefactoringActionHandler {

    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext?) {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val selection = editor.selectionModel
        if (!selection.hasSelection()) selection.selectLineAtCaret()
        val stmts = FantomRefactoringUtil.statementsInRange(file, selection.selectionStart, selection.selectionEnd)
        if (stmts.isEmpty()) return error(project, editor, "Select one or more whole statements.")
        val plan = try {
            analyze(stmts)
        } catch (e: Refusal) {
            return error(project, editor, e.message!!)
        }
        val name = askName(project, plan) ?: return
        perform(project, editor, file, plan, name)
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        // Only the editor form is supported.
    }

    private class Refusal(message: String) : Exception(message)

    private class Output(val local: FantomNamedElement, val declaredInside: Boolean)

    private class Plan(
        val stmts: List<PsiElement>,
        val slot: PsiElement,
        val isStatic: Boolean,
        val inputs: List<FantomNamedElement>,
        val output: Output?,
        val takenNames: Set<String>,
    )

    // ------------------------------------------------------------------ analysis

    private fun analyze(stmts: List<PsiElement>): Plan {
        val first = stmts.first()
        if (!FantomRefactoringUtil.isInStatementList(first))
            throw Refusal("Cannot extract a statement without braces. Add braces first.")
        val callable = FantomRefactoringUtil.enclosingCallable(first)
        val body: FantomStatementBlock = when (callable) {
            is FantomMethodDef -> callable.statementBlock
            is FantomCtorDef -> callable.statementBlock
            is FantomClosure -> throw Refusal("Cannot extract from a closure body: `it` and captured locals would change meaning.")
            else -> null
        } ?: throw Refusal("Select statements inside a method or constructor body.")
        val slot = PsiTreeUtil.getParentOfType(callable, FantomSlotDef::class.java) ?: callable!!
        val isStatic = callable is FantomMethodDef && FantomRefactoringUtil.isStatic(callable)
        val range = TextRange(first.textRange.startOffset, stmts.last().textRange.endOffset)

        checkJumps(stmts, range)

        // Locals read or written in the selection.
        val inputs = LinkedHashSet<FantomNamedElement>()
        val written = LinkedHashSet<FantomNamedElement>()
        val declaredInside = LinkedHashSet<FantomNamedElement>()
        for (stmt in stmts) {
            PsiTreeUtil.findChildrenOfType(stmt, FantomNamedElement::class.java)
                .filter { FantomRefactoringUtil.isLocalLike(it) && it is FantomLocalDef }
                .forEach { declaredInside += it }
            if (stmt is FantomNamedElement && stmt is FantomLocalDef) declaredInside += stmt
            for (id in PsiTreeUtil.findChildrenOfType(stmt, FantomId::class.java)) {
                val target = localTarget(id) ?: continue
                if (range.contains(target.textRange)) continue
                val write = isWrite(id)
                if (write) written += target
                // A plain `x = ...` does not read x; `x += 1` and `x++` do.
                if (!write || !isPlainAssign(id)) inputs += target
            }
        }

        // Outputs: locals the selection sets that are read afterwards.
        val outputs = ArrayList<Output>()
        for (local in declaredInside) if (readAfter(local, range, body, first)) outputs += Output(local, true)
        for (local in written) if (readAfter(local, range, body, first)) outputs += Output(local, false)
        if (outputs.size > 1)
            throw Refusal("Cannot extract: the selection sets more than one local used later (" +
                outputs.joinToString { it.local.name ?: "?" } + ").")

        val taken = HashSet<String>()
        FantomScope.enclosingType(first)?.let { type -> FantomScope.ownSlots(type).mapNotNullTo(taken) { it.name } }
        return Plan(stmts, slot, isStatic, inputs.sortedBy { it.textRange.startOffset }, outputs.firstOrNull(), taken)
    }

    /** `return` leaves the method, `break`/`continue` a loop outside the selection: refuse both. */
    private fun checkJumps(stmts: List<PsiElement>, range: TextRange) {
        for (stmt in stmts) {
            for (jump in PsiTreeUtil.findChildrenOfAnyType(stmt, false, FantomReturnStm::class.java,
                FantomBreakStm::class.java, FantomContinueStm::class.java)) {
                val closure = PsiTreeUtil.getParentOfType(jump, FantomClosure::class.java)
                if (closure != null && range.contains(closure.textRange)) continue
                if (jump is FantomReturnStm)
                    throw Refusal("Cannot extract statements that contain `return`.")
                val loop = PsiTreeUtil.getParentOfType(jump, FantomWhileStm::class.java, FantomForStm::class.java,
                    FantomSwitchStm::class.java, FantomClosure::class.java)
                if (loop == null || !range.contains(loop.textRange))
                    throw Refusal("Cannot extract `${jump.firstChild.text}`: its loop is outside the selection.")
            }
        }
    }

    /** The local, param or catch variable that [id] names, or null. */
    private fun localTarget(id: FantomId): FantomNamedElement? {
        val resolved = id.reference?.resolve() ?: return null
        val named = PsiTreeUtil.getParentOfType(resolved, FantomNamedElement::class.java, false) ?: return null
        return named.takeIf { FantomRefactoringUtil.isLocalLike(it) }
    }

    /** True when [id] is the target of `=`, `+=`, ..., `++` or `--`. */
    private fun isWrite(id: FantomId): Boolean {
        val term = PsiTreeUtil.getParentOfType(id, FantomTermExpr::class.java) ?: return false
        if (term.termChainList.isNotEmpty()) return false
        val o = FantomRefactoringUtil.outermost(term)
        return when (val p = o.parent) {
            is FantomAssignExpr -> p.assignOp != null && p.ifExpr == o
            is FantomPostfixExpr -> true
            is FantomPrefixExpr -> p.firstChild.text == "++" || p.firstChild.text == "--"
            else -> false
        }
    }

    private fun isPlainAssign(id: FantomId): Boolean {
        val term = PsiTreeUtil.getParentOfType(id, FantomTermExpr::class.java) ?: return false
        val p = FantomRefactoringUtil.outermost(term).parent
        return p is FantomAssignExpr && p.assignOp?.text == "="
    }

    /**
     * True when [local] is read after [range] in [body]. In a loop that is outside the
     * selection and holds the declaration of [local] outside it, a read anywhere in the
     * loop counts: it runs on the next pass.
     */
    private fun readAfter(local: FantomNamedElement, range: TextRange, body: FantomStatementBlock, first: PsiElement): Boolean {
        var loopRange: TextRange? = null
        var p: PsiElement? = first.parent
        while (p != null && p != body) {
            if (p is FantomClosure) break
            if ((p is FantomWhileStm || p is FantomForStm) && !p.textRange.contains(local.textRange)) loopRange = p.textRange
            p = p.parent
        }
        for (id in PsiTreeUtil.findChildrenOfType(body, FantomId::class.java)) {
            val start = id.textRange.startOffset
            val inScope = start >= range.endOffset || (loopRange != null && loopRange.contains(id.textRange) && !range.contains(id.textRange))
            if (!inScope) continue
            if (localTarget(id) == local) return true
        }
        return false
    }

    // ------------------------------------------------------------------ name

    private fun askName(project: Project, plan: Plan): String? {
        val default = FantomRefactoringUtil.uniqueName("extracted", plan.takenNames)
        if (ApplicationManager.getApplication().isUnitTestMode) return default
        val validator = FantomNamesValidator()
        return Messages.showInputDialog(project, "Method name:", "Extract Method", null, default,
            object : InputValidator {
                override fun checkInput(s: String?): Boolean =
                    s != null && validator.isIdentifier(s, project) && !validator.isKeyword(s, project) && s !in plan.takenNames
                override fun canClose(s: String?): Boolean = checkInput(s)
            })
    }

    // ------------------------------------------------------------------ edit

    private fun perform(project: Project, editor: Editor, file: PsiFile, plan: Plan, name: String) {
        val document = editor.document
        val first = plan.stmts.first()
        val last = plan.stmts.last()
        val stmtIndent = FantomRefactoringUtil.lineIndent(document, first.textRange.startOffset)
        val slotIndent = FantomRefactoringUtil.lineIndent(document, plan.slot.textRange.startOffset)
        val unit = FantomRefactoringUtil.indentUnit(file)
        val bodyIndent = slotIndent + unit

        val output = plan.output
        val returnType = output?.let { typeText(it.local) } ?: "Void"
        val params = plan.inputs.joinToString(", ") { "${typeText(it)} ${it.name}" }
        val args = plan.inputs.joinToString(", ") { it.name ?: "" }

        val bodyText = FantomRefactoringUtil.reindent(FantomRefactoringUtil.rangeText(document, first, last), stmtIndent, bodyIndent)
        val method = buildString {
            append("private ")
            if (plan.isStatic) append("static ")
            append("$returnType $name($params) {\n")
            append(bodyIndent).append(bodyText).append('\n')
            if (output != null) append(bodyIndent).append("return ${output.local.name}\n")
            append(slotIndent).append('}')
        }
        val call = when {
            output == null -> "$name($args)"
            output.declaredInside -> "${output.local.name} := $name($args)"
            else -> "${output.local.name} = $name($args)"
        }

        val slotEnd = plan.slot.textRange.endOffset
        val stmtRange = TextRange(first.textRange.startOffset, last.textRange.endOffset)
        WriteCommandAction.writeCommandAction(project, file).withName("Extract Method").run<RuntimeException> {
            document.insertString(slotEnd, "\n\n$slotIndent$method")
            document.replaceString(stmtRange.startOffset, stmtRange.endOffset, call)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        editor.selectionModel.removeSelection()
        editor.caretModel.moveToOffset(stmtRange.startOffset)
    }

    /** The declared or inferred type of [local]; `Obj?` when it is not known. */
    private fun typeText(local: FantomNamedElement): String =
        FantomTypeInference.typeOfDeclaration(local)?.presentableText ?: "Obj?"

    companion object {
        private fun error(project: Project, editor: Editor, message: String) =
            CommonRefactoringUtil.showErrorHint(project, editor, message, "Extract Method", null)
    }
}
