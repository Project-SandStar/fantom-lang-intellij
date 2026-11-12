package co.anbora.labs.fantom.ide.inspections

import co.anbora.labs.fantom.lang.core.psi.FantomCatchDef
import co.anbora.labs.fantom.lang.core.psi.FantomClosureParam
import co.anbora.labs.fantom.lang.core.psi.FantomDotCall
import co.anbora.labs.fantom.lang.core.psi.FantomFieldDef
import co.anbora.labs.fantom.lang.core.psi.FantomId
import co.anbora.labs.fantom.lang.core.psi.FantomLocalDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomParam
import co.anbora.labs.fantom.lang.core.psi.FantomTermChain
import co.anbora.labs.fantom.lang.core.psi.FantomTermExpr
import co.anbora.labs.fantom.lang.core.psi.FantomType
import co.anbora.labs.fantom.lang.core.psi.FantomTypes
import co.anbora.labs.fantom.lang.resolve.FantomReference
import co.anbora.labs.fantom.lang.types.FantomTypeInference
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.PsiTreeUtil

/**
 * Warns on `x.slot` when `x` is a local, parameter or field whose written type is
 * nullable (`Str?`), and offers to make it `x?.slot`.
 *
 * On purpose this is narrow: only the declared type counts (an inferred type or an
 * initializer does not), only the first `.` after the name is checked, and the slots
 * every `Obj?` accepts (`equals`, `toStr`, `isImmutable`, `typeof`, `hash`, `compare`)
 * are exempt. There is no flow analysis, so `if (x != null) x.foo` is still reported.
 */
class FantomNullableReceiverInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is FantomDotCall) return
                val slotName = element.slotExpr.let { it.field?.id ?: it.call?.id }?.text ?: return
                if (slotName in NULL_SAFE_SLOTS) return
                val step = element.parent as? FantomTermChain ?: return
                val term = step.parent as? FantomTermExpr ?: return
                if (term.termChainList.firstOrNull() != step) return
                val idExpr = term.termBase?.idExpr ?: return
                val receiverId: FantomId = idExpr.local?.id ?: idExpr.slotExpr?.let { it.field?.id ?: it.call?.id } ?: return
                val ref = receiverId.reference as? FantomReference ?: return
                val target = ref.resolveAll().singleOrNull() ?: return
                if (declaredType(target)?.nullable != true) return
                holder.registerProblem(receiverId, "Receiver may be null", ReplaceWithSafeDotFix())
            }
        }

    private fun declaredType(decl: FantomNamedElement): co.anbora.labs.fantom.lang.types.FanType? {
        val written: FantomType = when (decl) {
            is FantomLocalDef -> decl.type
            is FantomParam -> decl.type
            is FantomFieldDef -> decl.type
            is FantomClosureParam -> decl.type
            is FantomCatchDef -> decl.type
            else -> null
        } ?: return null
        return FantomTypeInference.fromPsiType(written)
    }

    /** Replaces the `.` right after the receiver with `?.`. */
    class ReplaceWithSafeDotFix : LocalQuickFix {
        override fun getFamilyName(): String = "Replace '.' with '?.'"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val receiver = descriptor.psiElement ?: return
            val term = PsiTreeUtil.getParentOfType(receiver, FantomTermExpr::class.java) ?: return
            val dot = term.termChainList.firstOrNull()?.dotCall?.node?.findChildByType(FantomTypes.DOT) ?: return
            val file = receiver.containingFile
            val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
            document.replaceString(dot.startOffset, dot.startOffset + dot.textLength, "?.")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }

    companion object {
        /** Slots that `Obj?` accepts on a null receiver. */
        val NULL_SAFE_SLOTS = setOf("equals", "toStr", "isImmutable", "typeof", "hash", "compare")
    }
}
