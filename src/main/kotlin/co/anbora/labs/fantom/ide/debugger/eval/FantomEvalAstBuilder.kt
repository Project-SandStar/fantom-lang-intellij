package co.anbora.labs.fantom.ide.debugger.eval

import co.anbora.labs.fantom.lang.core.psi.FantomTypes.*
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * Turns the PSI of a [FantomExpressionCodeFragment] into an [FExpr]. Runs inside a read action.
 * Only the node types listed here are accepted; any other construct gives
 * "Not supported in the Fantom evaluator: <construct>".
 */
object FantomEvalAstBuilder {

    fun build(fragment: ASTNode): FExpr {
        firstError(fragment)?.let { throw FantomEvalException("Syntax error: ${it.errorDescription}") }
        val exprs = kids(fragment)
        if (exprs.isEmpty()) throw FantomEvalException("Empty expression")
        if (exprs.size > 1 || exprs[0].elementType != EXPR) notSupported("statement")
        return expr(exprs[0])
    }

    private fun firstError(node: ASTNode): PsiErrorElement? {
        val psi = node.psi
        if (psi is PsiErrorElement) return psi
        var c = node.firstChildNode
        while (c != null) {
            firstError(c)?.let { return it }
            c = c.treeNext
        }
        return null
    }

    /** Children without whitespace and comments. */
    private fun kids(node: ASTNode): List<ASTNode> = node.getChildren(null).filter {
        it.elementType != TokenType.WHITE_SPACE && it.psi !is PsiWhiteSpace && it.psi !is PsiComment
    }

    private fun one(node: ASTNode): ASTNode = kids(node).singleOrNull() ?: notSupported(describe(node))

    private fun describe(node: ASTNode): String = node.text.trim().let { if (it.length > 40) it.take(40) + "..." else it }

    private fun expr(node: ASTNode): FExpr = when (node.elementType) {
        EXPR, PAREN_EXPR -> expr(one(node))
        ASSIGN_EXPR -> {
            val k = kids(node)
            if (k.size != 1) notSupported("assignment")
            expr(k[0])
        }
        IF_EXPR -> ifExpr(node)
        IF_EXPR_BODY -> {
            val k = one(node)
            if (k.elementType == IF_EXPR_THROW) notSupported("throw")
            expr(k)
        }
        COND_OR_EXPR, COND_AND_EXPR, EQUALITY_EXPR, ADD_EXPR, MULT_EXPR -> binaryChain(node)
        RELATIONAL_EXPR -> relational(node)
        RANGE_EXPR -> {
            val k = kids(node)
            if (k.size != 1) notSupported("range")
            expr(k[0])
        }
        UNARY_EXPR -> expr(one(node))
        PREFIX_EXPR -> prefix(node)
        POSTFIX_EXPR -> notSupported("++ and --")
        CAST_EXPR -> notSupported("cast")
        GROUPED_EXPR -> {
            val k = kids(node)
            var e = expr(k.first { it.elementType == EXPR })
            for (c in k) if (c.elementType == TERM_CHAIN) e = chain(e, c)
            e
        }
        TERM_EXPR -> {
            val k = kids(node)
            var e = termBase(k[0])
            for (c in k.drop(1)) e = chain(e, c)
            e
        }
        else -> notSupported(describe(node))
    }

    private fun ifExpr(node: ASTNode): FExpr {
        val k = kids(node)
        val cond = expr(k[0])
        if (k.size == 1) return cond
        return when (k[1].elementType) {
            QUEST_COLON -> FElvis(cond, expr(k[2]))
            QUEST -> FTernary(cond, expr(k[2]), expr(k[4]))
            else -> notSupported(describe(node))
        }
    }

    /** `a op b op c`, left to right. */
    private fun binaryChain(node: ASTNode): FExpr {
        val k = kids(node)
        var e = expr(k[0])
        var i = 1
        while (i + 1 < k.size) {
            val op = k[i].text
            if (op == "===" || op == "!==") notSupported("$op (same instance)")
            e = FBinary(op, e, expr(k[i + 1]))
            i += 2
        }
        return e
    }

    private fun relational(node: ASTNode): FExpr {
        val k = kids(node)
        if (k.size == 1) return expr(k[0])
        val op = k[1].text
        return when (k[1].elementType) {
            IS, ISNOT, AS -> FTypeCheck(op, expr(k[0]), k[2].text.filterNot { it.isWhitespace() })
            else -> FBinary(op, expr(k[0]), expr(k[2]))
        }
    }

    private fun prefix(node: ASTNode): FExpr {
        val k = kids(node)
        val operand = expr(k[1])
        return when (k[0].text) {
            "!" -> FUnary("!", operand)
            "-" -> when {
                operand is FLit && operand.value is Long -> FLit(-(operand.value))
                operand is FLit && operand.value is Double -> FLit(-(operand.value))
                else -> FUnary("-", operand)
            }
            "+" -> operand
            else -> notSupported("++ and --")
        }
    }

    private fun termBase(node: ASTNode): FExpr {
        val n = if (node.elementType == TERM_BASE) one(node) else node
        return when (n.elementType) {
            LITERAL -> literal(n)
            ID_EXPR -> idExpr(one(n))
            TYPE_BASE -> {
                val t = one(n)
                if (t.elementType != STATIC_CALL) notSupported(describe(t))
                val k = kids(t)
                val type = k.first { it.elementType == TYPE }.text.filterNot { it.isWhitespace() }
                val (name, args) = slot(k.first { it.elementType == SLOT_EXPR })
                FStatic(type, name, args)
            }
            else -> notSupported(describe(n))
        }
    }

    private fun idExpr(n: ASTNode): FExpr = when (n.elementType) {
        LOCAL -> FName(n.text.trim())
        SLOT_EXPR -> {
            val (name, args) = slot(n)
            if (args == null) FName(name) else FMember(null, name, args)
        }
        else -> notSupported(describe(n))
    }

    /** `slotExpr ::= field | call`: the name and the call arguments, null for a field. */
    private fun slot(node: ASTNode): Pair<String, List<FExpr>?> {
        val s = one(node)
        val k = kids(s)
        return when (s.elementType) {
            FIELD -> {
                if (k.any { it.elementType == AND }) notSupported("&field (storage)")
                k.first { it.elementType == ID }.text to null
            }
            CALL -> {
                if (k.any { it.elementType == CLOSURE }) notSupported("closure")
                val args = k.firstOrNull { it.elementType == ARGS }
                k.first { it.elementType == ID }.text to (args?.let { args(it) } ?: if (k.any { it.elementType == LPAR }) emptyList() else null)
            }
            else -> notSupported(describe(s))
        }
    }

    private fun args(node: ASTNode): List<FExpr> = kids(node).filter { it.elementType == EXPR }.map { expr(it) }

    private fun chain(target: FExpr, node: ASTNode): FExpr {
        val c = if (node.elementType == TERM_CHAIN) one(node) else node
        val k = kids(c)
        return when (c.elementType) {
            DOT_CALL, SAFE_DOT_CALL_STM -> {
                val (name, args) = slot(k.first { it.elementType == SLOT_EXPR })
                FMember(target, name, args, safe = c.elementType == SAFE_DOT_CALL_STM)
            }
            INDEX_EXPR -> FIndex(target, expr(k.first { it.elementType == EXPR }))
            CALL_OP -> {
                if (k.any { it.elementType == CLOSURE }) notSupported("closure")
                val args = k.firstOrNull { it.elementType == ARGS }?.let { args(it) } ?: emptyList()
                when {
                    target is FName -> FMember(null, target.name, args)
                    target is FMember && target.args == null -> target.copy(args = args)
                    target is FStatic && target.args == null -> target.copy(args = args)
                    else -> notSupported("call of a function value")
                }
            }
            DYN_CALL_STM, SAFE_DYN_CALL_STM -> notSupported("-> (dynamic call)")
            IT_BLOCK -> notSupported("it-block")
            else -> notSupported(describe(c))
        }
    }

    private fun literal(node: ASTNode): FExpr {
        val c = one(node)
        return when (c.elementType) {
            NULL -> FLit(null)
            THIS -> FThis
            IT -> FName("it")
            SUPER -> notSupported("super")
            BOOL -> FLit(c.text.trim() == "true")
            INT_LITERAL -> FLit(parseInt(c.text))
            FLOAT_LITERAL -> FLit(parseFloat(c.text))
            STR -> str(one(c))
            LIST -> FList(kids(c).firstOrNull { it.elementType == LIST_ITEMS }
                ?.let { items -> kids(items).filter { it.elementType == EXPR }.map { expr(it) } } ?: emptyList())
            MAP -> notSupported("map literal")
            else -> notSupported(kindName(c.elementType))
        }
    }

    private fun kindName(t: IElementType): String = when (t) {
        DECIMAL_LITERAL -> "Decimal literal"
        DURATION_LITERAL -> "Duration literal"
        URI_LITERAL -> "Uri literal"
        else -> t.toString()
    }

    internal fun parseInt(text: String): Long {
        val t = text.replace("_", "")
        return when {
            t.startsWith("0x") || t.startsWith("0X") -> java.lang.Long.parseUnsignedLong(t.substring(2), 16)
            else -> t.toLongOrNull() ?: notSupported("Int literal $text")
        }
    }

    internal fun parseFloat(text: String): Double {
        val t = text.replace("_", "").removeSuffix("f").removeSuffix("F")
        return t.toDoubleOrNull() ?: notSupported("Float literal $text")
    }

    private fun str(token: ASTNode): FExpr {
        val text = token.text
        return when (token.elementType) {
            CHAR_LITERAL -> {
                val s = unescape(text.substring(1, text.length - 1))
                FLit(s.codePointAt(0).toLong())
            }
            STRING_LITERAL -> FLit(unescape(text.substring(1, text.length - 1)))
            else -> notSupported("triple-quoted Str")
        }
    }

    /** Fantom escapes; an unescaped `$` is interpolation, which the evaluator does not do. */
    internal fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '$') notSupported("Str interpolation")
            if (ch != '\\' || i + 1 >= s.length) { sb.append(ch); i++; continue }
            val e = s[i + 1]
            i += 2
            when (e) {
                'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                else -> sb.append(e)
            }
        }
        return sb.toString()
    }
}
