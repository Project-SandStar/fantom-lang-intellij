package co.anbora.labs.fantom.lang.resolve

import co.anbora.labs.fantom.lang.core.psi.FantomCatchDef
import co.anbora.labs.fantom.lang.core.psi.FantomCatchStm
import co.anbora.labs.fantom.lang.core.psi.FantomClassDef
import co.anbora.labs.fantom.lang.core.psi.FantomClosure
import co.anbora.labs.fantom.lang.core.psi.FantomClosureParam
import co.anbora.labs.fantom.lang.core.psi.FantomCtorDef
import co.anbora.labs.fantom.lang.core.psi.FantomEnumDef
import co.anbora.labs.fantom.lang.core.psi.FantomEnumValDef
import co.anbora.labs.fantom.lang.core.psi.FantomFacetDef
import co.anbora.labs.fantom.lang.core.psi.FantomFieldDef
import co.anbora.labs.fantom.lang.core.psi.FantomForStm
import co.anbora.labs.fantom.lang.core.psi.FantomLocalDef
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomMixinDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomParam
import co.anbora.labs.fantom.lang.core.psi.FantomStatementBlock
import co.anbora.labs.fantom.lang.core.psi.FantomStmt
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

/**
 * Lexical scope walk for Fantom, in the order the compiler resolves a bare name:
 * locals declared earlier in the enclosing blocks, closure params, method or ctor
 * params, catch params; then slots of the enclosing type; then types.
 *
 * Only the first two groups live here. Slots on supertypes and types by name are
 * resolved through the declaration index by [FantomReference].
 */
object FantomScope {

    /** Locals, params, closure params and catch params visible at [from], innermost first. */
    fun localsVisibleAt(from: PsiElement): List<FantomNamedElement> {
        val out = ArrayList<FantomNamedElement>()
        var child: PsiElement = from
        var p: PsiElement? = from.parent
        while (p != null && p !is PsiFile) {
            when (p) {
                is FantomStatementBlock -> collectLocalsBefore(p, from, out)
                is FantomForStm -> p.forInit?.let { collectLocalDefs(it, out) }
                is FantomCatchStm -> p.catchDef?.let { out += it }
                is FantomClosure -> p.closureSig?.closureParams?.closureParamList?.forEach { out += it }
                is FantomMethodDef -> { p.itemParameterList?.let { collectParams(it, out) }; return out }
                is FantomCtorDef -> { p.itemParameterList?.let { collectParams(it, out) }; return out }
                is FantomFieldDef -> return out // field initializer: no locals
            }
            child = p
            p = p.parent
        }
        return out
    }

    /** The type declaration that encloses [element], or null at file level. */
    fun enclosingType(element: PsiElement): FantomNamedElement? {
        var p: PsiElement? = element.parent
        while (p != null) {
            if (isTypeDef(p)) return p as FantomNamedElement
            p = p.parent
        }
        return null
    }

    /** Direct slots (fields, methods, ctors, enum values) declared in [type]. */
    fun ownSlots(type: FantomNamedElement): List<FantomNamedElement> =
        PsiTreeUtil.findChildrenOfType(type, FantomNamedElement::class.java)
            .filter { isSlot(it) && enclosingType(it) == type }

    /** Short names of the supertypes written in the `: A, B` inheritance clause. */
    fun superTypeNames(type: FantomNamedElement): List<String> {
        val inheritance = when (type) {
            is FantomClassDef -> type.inheritance
            is FantomMixinDef -> type.inheritance
            is FantomEnumDef -> type.inheritance
            is FantomFacetDef -> type.inheritance
            else -> null
        } ?: return emptyList()
        return inheritance.typeList.typeList.mapNotNull { t ->
            t.simpleType?.idList?.lastOrNull()?.text
        }
    }

    fun isTypeDef(e: PsiElement): Boolean =
        e is FantomClassDef || e is FantomMixinDef || e is FantomEnumDef || e is FantomFacetDef

    fun isSlot(e: PsiElement): Boolean =
        e is FantomFieldDef || e is FantomMethodDef || e is FantomCtorDef || e is FantomEnumValDef

    fun isLocalLike(e: PsiElement): Boolean =
        e is FantomLocalDef || e is FantomParam || e is FantomClosureParam || e is FantomCatchDef

    // Locals declared in statements of [block] that start before [from]. The walk arrives
    // here with [from] somewhere inside one of the block's statements (or inside its
    // `stmts` wrapper), so compare by offset, not by identity.
    private fun collectLocalsBefore(block: FantomStatementBlock, from: PsiElement, out: MutableList<FantomNamedElement>) {
        val stmts = block.stmts ?: return
        val limit = from.textRange.startOffset
        for (stmt in stmts.children) {
            if (stmt !is FantomStmt) continue
            if (stmt.textRange.startOffset > limit) break
            // The statement that contains [from] may itself be `name := expr`; the name is
            // not visible inside its own initializer.
            if (stmt.textRange.contains(limit)) {
                for (def in PsiTreeUtil.findChildrenOfType(stmt, FantomLocalDef::class.java)) {
                    if (def.textRange.endOffset <= limit && !PsiTreeUtil.isAncestor(def, from, false)) out += def
                }
                break
            }
            collectLocalDefs(stmt, out)
        }
    }

    // Local defs directly in [root], not inside a nested block or closure.
    private fun collectLocalDefs(root: PsiElement, out: MutableList<FantomNamedElement>) {
        for (def in PsiTreeUtil.findChildrenOfType(root, FantomLocalDef::class.java)) {
            val nested = PsiTreeUtil.getParentOfType(def, FantomStatementBlock::class.java, true, FantomStmt::class.java)
            if (nested == null || !PsiTreeUtil.isAncestor(root, nested, false)) out += def
        }
    }

    private fun collectParams(list: PsiElement, out: MutableList<FantomNamedElement>) {
        out += PsiTreeUtil.findChildrenOfType(list, FantomParam::class.java)
    }
}
