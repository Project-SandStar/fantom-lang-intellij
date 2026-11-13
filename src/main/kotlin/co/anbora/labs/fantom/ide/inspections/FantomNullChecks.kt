package co.anbora.labs.fantom.ide.inspections

import co.anbora.labs.fantom.lang.core.psi.FantomBlock
import co.anbora.labs.fantom.lang.core.psi.FantomBreakStm
import co.anbora.labs.fantom.lang.core.psi.FantomClosure
import co.anbora.labs.fantom.lang.core.psi.FantomCondAndExpr
import co.anbora.labs.fantom.lang.core.psi.FantomCondOrExpr
import co.anbora.labs.fantom.lang.core.psi.FantomContinueStm
import co.anbora.labs.fantom.lang.core.psi.FantomCtorDef
import co.anbora.labs.fantom.lang.core.psi.FantomEqualityExpr
import co.anbora.labs.fantom.lang.core.psi.FantomFieldDef
import co.anbora.labs.fantom.lang.core.psi.FantomIfExpr
import co.anbora.labs.fantom.lang.core.psi.FantomIfStm
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomReturnStm
import co.anbora.labs.fantom.lang.core.psi.FantomStmt
import co.anbora.labs.fantom.lang.core.psi.FantomStmts
import co.anbora.labs.fantom.lang.core.psi.FantomThrowStm
import co.anbora.labs.fantom.lang.core.psi.FantomTypes
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Structural null checks for [FantomNullableReceiverInspection]: is the name `x` known
 * to be non-null where it is used? No data flow: only these shapes count, read from the
 * tree between the use and the enclosing method, closure or field.
 *
 * 1. Inside the then branch of `if (x != null) { ... }` (also `if (x != null && ...)`),
 *    and in the else branch of `if (x == null) ... else { ... }`.
 * 2. In a later statement of the same block after a guard `if (x == null) return`
 *    (the then branch ends in `return`, `throw`, `break` or `continue`; no else;
 *    `if (x == null || y == null) return` guards both).
 * 3. In a later operand of `x != null && x.foo`.
 * 4. In the true branch of `x != null ? x.foo : d` and the false branch of `x == null ? d : x.foo`.
 * 5. In a later statement of the same block after `x ?: throw Err()` (or `x ?: return`).
 *
 * The check stops at a closure: a closure may run later, when `x` has changed. An
 * assignment `x = null` between the check and the use is not seen.
 */
object FantomNullChecks {

    fun isCheckedNonNull(use: PsiElement, name: String): Boolean {
        var child: PsiElement = use
        var parent: PsiElement? = use.parent
        while (parent != null) {
            when (parent) {
                is FantomClosure, is FantomMethodDef, is FantomCtorDef, is FantomFieldDef -> return false
                is FantomCondAndExpr -> {
                    val parts = parent.equalityExprList
                    val at = parts.indexOf(child)
                    if (at > 0 && parts.subList(0, at).any { isTest(it, name, notNull = true) }) return true
                }
                is FantomIfExpr -> if (parent.node.findChildByType(FantomTypes.QUEST) != null) {
                    val bodies = parent.ifExprBodyList
                    val cond = parent.condOrExpr
                    if (child == bodies.getOrNull(0) && nonNullWhenTrue(cond, name)) return true
                    if (child == bodies.getOrNull(1) && nonNullWhenFalse(cond, name)) return true
                }
                is FantomIfStm -> {
                    val blocks = parent.blockList
                    val cond = parent.expr?.assignExpr?.takeIf { it.assignOp == null }?.ifExpr
                        ?.takeIf { it.ifExprBodyList.isEmpty() }?.condOrExpr
                    if (child == blocks.getOrNull(0) && nonNullWhenTrue(cond, name)) return true
                    if (child == blocks.getOrNull(1) && nonNullWhenFalse(cond, name)) return true
                }
                is FantomStmts -> {
                    val stmts = parent.stmtList
                    val at = stmts.indexOf(child)
                    if (at > 0 && stmts.subList(0, at).any { isGuard(it, name) }) return true
                }
            }
            child = parent
            parent = parent.parent
        }
        return false
    }

    // `x != null` holds when [cond] is true: a `&&` conjunct says so (no `||`).
    private fun nonNullWhenTrue(cond: FantomCondOrExpr?, name: String): Boolean {
        val and = cond?.condAndExprList?.singleOrNull() ?: return false
        return and.equalityExprList.any { isTest(it, name, notNull = true) }
    }

    // `x != null` holds when [cond] is false: a `||` disjunct is `x == null`.
    private fun nonNullWhenFalse(cond: FantomCondOrExpr?, name: String): Boolean =
        cond?.condAndExprList?.any { and ->
            and.equalityExprList.singleOrNull()?.let { isTest(it, name, notNull = false) } == true
        } == true

    /** `x != null` / `x !== null` ([notNull]), or `x == null` / `x === null`, either side. */
    private fun isTest(eq: FantomEqualityExpr, name: String, notNull: Boolean): Boolean {
        val sides = eq.relationalExprList
        if (sides.size != 2) return false
        val op = eq.node.getChildren(null).firstOrNull { it.text in OPS }?.text ?: return false
        if ((op == "!=" || op == "!==") != notNull) return false
        val a = sides[0].text.trim()
        val b = sides[1].text.trim()
        return (a == name && b == "null") || (a == "null" && b == name)
    }

    /** `if (x == null) return` with no else, or a statement holding `x ?: throw ...`. */
    private fun isGuard(stmt: FantomStmt, name: String): Boolean {
        if (stmt is FantomIfStm) {
            val blocks = stmt.blockList
            if (blocks.size != 1) return false
            val cond = stmt.expr?.assignExpr?.takeIf { it.assignOp == null }?.ifExpr
                ?.takeIf { it.ifExprBodyList.isEmpty() }?.condOrExpr
            return nonNullWhenFalse(cond, name) && exits(blocks[0])
        }
        return PsiTreeUtil.findChildrenOfType(stmt, FantomIfExpr::class.java).any { e ->
            e.node.findChildByType(FantomTypes.QUEST_COLON) != null &&
                e.condOrExpr?.text?.trim() == name &&
                PsiTreeUtil.getParentOfType(e, FantomClosure::class.java, true, FantomStmt::class.java) == null &&
                e.ifExprBodyList.firstOrNull()?.let { it.ifExprThrow != null || it.text.trimStart().startsWith("return") } == true
        }
    }

    private fun exits(block: FantomBlock): Boolean {
        val last = block.stmt ?: block.statementBlock?.stmts?.stmtList?.lastOrNull() ?: return false
        return last is FantomReturnStm || last is FantomThrowStm || last is FantomBreakStm || last is FantomContinueStm
    }

    private val OPS = setOf("==", "!=", "===", "!==")
}
