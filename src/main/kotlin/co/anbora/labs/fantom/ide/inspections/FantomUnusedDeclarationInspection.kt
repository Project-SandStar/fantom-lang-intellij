package co.anbora.labs.fantom.ide.inspections

import co.anbora.labs.fantom.lang.core.psi.FantomCatchDef
import co.anbora.labs.fantom.lang.core.psi.FantomClosureParam
import co.anbora.labs.fantom.lang.core.psi.FantomCtorDef
import co.anbora.labs.fantom.lang.core.psi.FantomFieldDef
import co.anbora.labs.fantom.lang.core.psi.FantomLocalDef
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomParam
import co.anbora.labs.fantom.lang.core.psi.FantomTypes
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil

/**
 * Greys out a declaration nobody refers to: a local, a method or ctor parameter, a
 * closure parameter, a catch parameter, or a private field or method.
 *
 * Not reported: names that start with `_`, `it`, ctor parameters, parameters of a
 * method without a body (abstract, native) or with `override` (the signature is
 * fixed by the supertype), private ctors (`Foo()` refers to the type, not to `make`)
 * and every non-private slot.
 */
class FantomUnusedDeclarationInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is FantomNamedElement) return
                val kind = kindOf(element) ?: return
                val name = element.name ?: return
                if (name == "it" || name.startsWith("_")) return
                val nameId = element.nameIdentifier ?: return
                if (ReferencesSearch.search(element, element.useScope).findFirst() != null) return
                holder.registerProblem(nameId, "Unused $kind '$name'", ProblemHighlightType.LIKE_UNUSED_SYMBOL)
            }
        }

    // The word for the message, or null when the declaration is out of scope for this inspection.
    private fun kindOf(e: FantomNamedElement): String? = when (e) {
        is FantomLocalDef -> "local"
        is FantomCatchDef -> "catch parameter"
        is FantomClosureParam -> "closure parameter"
        is FantomParam -> {
            val method = PsiTreeUtil.getParentOfType(e, FantomMethodDef::class.java, true, FantomCtorDef::class.java)
            if (method != null && method.statementBlock != null && !hasFlag(method.methodFlags, FantomTypes.OVERRIDE)) "parameter" else null
        }
        is FantomFieldDef -> if (hasFlag(e.fieldFlags, FantomTypes.PRIVATE)) "private field" else null
        is FantomMethodDef -> if (hasFlag(e.methodFlags, FantomTypes.PRIVATE)) "private method" else null
        else -> null
    }

    // Flags are `fieldFlag*` / `methodFlag*` nodes; the keyword leaf sits one or two levels down.
    private fun hasFlag(flags: PsiElement?, token: IElementType): Boolean =
        flags != null && SyntaxTraverser.psiTraverser(flags).any { it.node.elementType == token }
}
