package co.anbora.labs.fantom.ide.refactoring

import co.anbora.labs.fantom.lang.core.psi.*
import co.anbora.labs.fantom.lang.resolve.FantomScope
import com.intellij.application.options.CodeStyle
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.PsiComment
import com.intellij.psi.util.PsiTreeUtil

/**
 * Shared PSI and text helpers for Introduce Variable, Extract Method, Surround With and
 * Unwrap. The Fantom formatter only fixes indent on Enter, so every refactoring builds
 * its new text with the right indent and edits the document, not the PSI.
 */
object FantomRefactoringUtil {

    // ---------------------------------------------------------------- statements

    /**
     * A real statement: a [FantomStmt] that sits in a statement list or as the brace-less
     * body of a `block`. `catch`/`finally` clauses and the `?.x` chain nodes also extend
     * [FantomStmt] in the generated PSI, so the parent decides.
     */
    fun isStatement(e: PsiElement): Boolean =
        e is FantomStmt && (e.parent is FantomStmts || e.parent is FantomBlock ||
            e.parent is FantomCaseBlock || e.parent is FantomDefaultBlock)

    /** True when [stmt] is in a statement list (so a new statement can go before it). */
    fun isInStatementList(stmt: PsiElement): Boolean =
        stmt.parent is FantomStmts || stmt.parent is FantomCaseBlock || stmt.parent is FantomDefaultBlock

    /** The innermost real statement that holds [e], or null. */
    fun enclosingStatement(e: PsiElement): PsiElement? {
        var p: PsiElement? = e
        while (p != null && p !is PsiFile) {
            if (isStatement(p)) return p
            p = p.parent
        }
        return null
    }

    /** The statements of a `block`: its braced list, or the one brace-less statement. */
    fun blockStatements(block: FantomBlock?): List<PsiElement> {
        if (block == null) return emptyList()
        block.statementBlock?.let { return statementsOf(it) }
        return listOfNotNull(block.stmt)
    }

    fun statementsOf(block: FantomStatementBlock?): List<PsiElement> =
        block?.stmts?.children?.filter { isStatement(it) } ?: emptyList()

    /**
     * The whole statements that [start, end) covers, after trimming white space. They must
     * be siblings in one statement list. Empty when the range does not match statements.
     */
    fun statementsInRange(file: PsiFile, start: Int, end: Int): List<PsiElement> {
        val text = file.text
        var s = start
        var e = end
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (s >= e) return emptyList()
        val first = statementStartingAt(file.findElementAt(s), s) ?: return emptyList()
        val last = statementEndingAt(file.findElementAt(e - 1), e) ?: return emptyList()
        if (first.parent != last.parent) return emptyList()
        val out = ArrayList<PsiElement>()
        var c: PsiElement? = first
        while (c != null) {
            if (isStatement(c)) out += c
            if (c == last) return out
            c = c.nextSibling
        }
        return emptyList()
    }

    private fun statementStartingAt(leaf: PsiElement?, offset: Int): PsiElement? {
        var p = leaf
        var best: PsiElement? = null
        while (p != null && p !is PsiFile && p.textRange.startOffset == offset) {
            if (isStatement(p)) best = p
            p = p.parent
        }
        return best
    }

    private fun statementEndingAt(leaf: PsiElement?, offset: Int): PsiElement? {
        var p = leaf
        var best: PsiElement? = null
        while (p != null && p !is PsiFile && p.textRange.endOffset == offset) {
            if (isStatement(p)) best = p
            p = p.parent
        }
        // A trailing `;` or comment may be outside the statement: accept the statement before it.
        if (best == null && leaf != null && (leaf.text == ";" || leaf is PsiComment)) {
            var q = PsiTreeUtil.prevLeaf(leaf)
            while (q is PsiWhiteSpace) q = PsiTreeUtil.prevLeaf(q)
            if (q != null) return statementEndingAt(q, q.textRange.endOffset)
        }
        return best
    }

    // ---------------------------------------------------------------- expressions

    private val EXPRESSION_KINDS: List<Class<out PsiElement>> = listOf(
        FantomExpr::class.java, FantomAssignExpr::class.java, FantomIfExpr::class.java,
        FantomCondOrExpr::class.java, FantomCondAndExpr::class.java, FantomEqualityExpr::class.java,
        FantomRelationalExpr::class.java, FantomRangeExpr::class.java, FantomAddExpr::class.java,
        FantomMultExpr::class.java, FantomParenExpr::class.java, FantomCastExpr::class.java,
        FantomGroupedExpr::class.java, FantomUnaryExpr::class.java, FantomPrefixExpr::class.java,
        FantomPostfixExpr::class.java, FantomTermExpr::class.java,
    )

    private fun isExpressionKind(e: PsiElement): Boolean =
        EXPRESSION_KINDS.any { it.isInstance(e) } || isTermBaseOperand(e)

    /** `order` in `order.total()` is a value; `echo` in `echo(x)` is only a call name. */
    private fun isTermBaseOperand(e: PsiElement): Boolean {
        if (e !is FantomTermBase) return false
        if (e.idExpr == null && e.literal == null) return false
        val next = PsiTreeUtil.skipWhitespacesAndCommentsForward(e)
        return next !is FantomTermChain || next.callOp == null
    }

    /** The outermost expression with the same text range as [e]. */
    fun outermost(e: PsiElement): PsiElement {
        var r = e
        while (r.parent != null && r.parent !is PsiFile && r.parent.textRange == r.textRange &&
            isExpressionKind(r.parent)) r = r.parent
        return r
    }

    /** The innermost expression with the same text range as [e]; used to compare and name expressions. */
    fun core(e: PsiElement): PsiElement {
        var r = e
        var best = e
        while (true) {
            val kids = r.children.filter { it !is PsiWhiteSpace && it !is PsiComment }
            if (kids.size == 1 && kids[0].textRange == r.textRange) {
                r = kids[0]
                if (isExpressionKind(r)) best = r
            } else return best
        }
    }

    /**
     * True when [e] (an outermost expression) can be taken out into a variable: not the
     * left side of an assignment, not the operand of `++`/`--`, not an assignment itself.
     */
    fun isExtractableExpression(e: PsiElement): Boolean {
        if (!isExpressionKind(core(e)) && !isExpressionKind(e)) return false
        val range = e.textRange
        // An assignment with an operator.
        var p: PsiElement? = e
        while (p != null && p.textRange == range) {
            if (p is FantomAssignExpr && p.assignOp != null) return false
            if (isStatement(p)) break
            p = p.parent
        }
        // Left side of an assignment, or `x++` / `++x` operand.
        p = e.parent
        while (p != null && p !is PsiFile && !isStatement(p)) {
            if (p is FantomAssignExpr && p.assignOp != null && p.ifExpr?.textRange == range) return false
            if (p is FantomPostfixExpr && p.termExpr?.textRange == range) return false
            if (p is FantomPrefixExpr && p.parenExpr?.textRange == range &&
                (p.firstChild.text == "++" || p.firstChild.text == "--")) return false
            if (!p.textRange.contains(range)) break
            p = p.parent
        }
        return enclosingStatement(e) != null
    }

    /**
     * Expressions around the caret, innermost first, one per text range. The walk stops
     * at the enclosing statement.
     */
    fun expressionsAt(file: PsiFile, offset: Int): List<PsiElement> {
        fun walk(leaf: PsiElement?): List<PsiElement> {
            val out = LinkedHashMap<TextRange, PsiElement>()
            var p = leaf
            while (p != null && p !is PsiFile && !isStatement(p) && p !is FantomStatementBlock) {
                if (isExpressionKind(p)) {
                    val o = outermost(p)
                    if (isExtractableExpression(o)) out[o.textRange] = o
                }
                p = p.parent
            }
            return out.values.toList()
        }
        val at = file.findElementAt(offset)
        val list = if (at != null && at !is PsiWhiteSpace) walk(at) else emptyList()
        if (list.isNotEmpty() || offset == 0) return list
        return walk(file.findElementAt(offset - 1))
    }

    /** The expression whose range is exactly the trimmed [start, end), or null. */
    fun expressionInRange(file: PsiFile, start: Int, end: Int): PsiElement? {
        val text = file.text
        var s = start
        var e = end
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (s >= e) return null
        var p = file.findElementAt(s)
        while (p != null && p !is PsiFile && p.textRange.startOffset == s && p.textRange.endOffset <= e) {
            if (p.textRange.endOffset == e && isExpressionKind(p)) {
                val o = outermost(p)
                return if (isExtractableExpression(o)) o else null
            }
            p = p.parent
        }
        return null
    }

    // ---------------------------------------------------------------- declarations

    /** The method, ctor, closure or field initialiser that holds [e]. */
    fun enclosingCallable(e: PsiElement): PsiElement? =
        PsiTreeUtil.getParentOfType(e, FantomMethodDef::class.java, FantomCtorDef::class.java,
            FantomClosure::class.java, FantomFieldDef::class.java, FantomStaticInit::class.java)

    fun isStatic(method: FantomMethodDef): Boolean =
        method.methodFlags.methodFlagList.any { it.text == "static" }

    /** True for locals, params, closure params and catch params. */
    fun isLocalLike(e: PsiElement?): Boolean = e != null && FantomScope.isLocalLike(e)

    // ---------------------------------------------------------------- names

    /** [base], or [base] with a number, that is a valid identifier and not in [taken]. */
    fun uniqueName(base: String, taken: Set<String>): String {
        val validator = FantomNamesValidator()
        val start = if (validator.isIdentifier(base, null)) base else base + "Value"
        if (start !in taken) return start
        var i = 1
        while ("$start$i" in taken) i++
        return "$start$i"
    }

    // ---------------------------------------------------------------- text

    /** One indent unit from the code style: two spaces by default. */
    fun indentUnit(file: PsiFile): String {
        val o = CodeStyle.getIndentOptions(file)
        return if (o.USE_TAB_CHARACTER) "\t" else " ".repeat(o.INDENT_SIZE.coerceAtLeast(1))
    }

    /** Leading white space of the line that holds [offset]. */
    fun lineIndent(document: Document, offset: Int): String {
        val line = document.getLineNumber(offset)
        val start = document.getLineStartOffset(line)
        val text = document.charsSequence
        var i = start
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return text.subSequence(start, i).toString()
    }

    /** True when only white space is between the line start and [offset]. */
    fun startsLine(document: Document, offset: Int): Boolean {
        val start = document.getLineStartOffset(document.getLineNumber(offset))
        return document.charsSequence.subSequence(start, offset).isBlank()
    }

    /**
     * Re-indents [text] (which starts right after its own indent [from]): every line after
     * the first loses [from] and gets [to]. Blank lines become empty.
     */
    fun reindent(text: String, from: String, to: String): String {
        val lines = text.split('\n')
        return lines.mapIndexed { i, line ->
            when {
                i == 0 -> line
                line.isBlank() -> ""
                line.startsWith(from) -> to + line.substring(from.length)
                else -> to + line.trimStart()
            }
        }.joinToString("\n")
    }

    /** Text of [first]..[last] (siblings) in the document. */
    fun rangeText(document: Document, first: PsiElement, last: PsiElement): String =
        document.charsSequence.subSequence(first.textRange.startOffset, last.textRange.endOffset).toString()

    /**
     * The range to delete to remove whole lines [start, end): from the line start (when
     * only indent is before [start]) to after the line break (when only space follows [end]).
     */
    fun wholeLinesRange(document: Document, start: Int, end: Int): TextRange {
        val text = document.charsSequence
        var s = start
        var e = end
        val lineStart = document.getLineStartOffset(document.getLineNumber(start))
        if (text.subSequence(lineStart, start).isBlank()) s = lineStart
        var i = e
        while (i < text.length && (text[i] == ' ' || text[i] == '\t' || text[i] == ';')) i++
        if (i < text.length && text[i] == '\n' && s == lineStart) e = i + 1
        else if (s == lineStart) s = start
        return TextRange(s, e)
    }
}
