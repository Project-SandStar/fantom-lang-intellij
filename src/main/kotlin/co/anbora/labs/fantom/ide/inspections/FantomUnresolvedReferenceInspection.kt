package co.anbora.labs.fantom.ide.inspections

import co.anbora.labs.fantom.ide.compiler.FantomCompilerService
import co.anbora.labs.fantom.ide.imports.FantomUsingCandidates
import co.anbora.labs.fantom.ide.imports.FantomUsingInserter
import co.anbora.labs.fantom.lang.core.psi.FantomDotCall
import co.anbora.labs.fantom.lang.core.psi.FantomDynCallStm
import co.anbora.labs.fantom.lang.core.psi.FantomFacetVal
import co.anbora.labs.fantom.lang.core.psi.FantomId
import co.anbora.labs.fantom.lang.core.psi.FantomSafeDotCallStm
import co.anbora.labs.fantom.lang.core.psi.FantomSafeDynCallStm
import co.anbora.labs.fantom.lang.core.psi.FantomSlotExpr
import co.anbora.labs.fantom.lang.core.psi.FantomStaticCall
import co.anbora.labs.fantom.lang.library.FantomSettings
import co.anbora.labs.fantom.lang.resolve.FantomReference
import co.anbora.labs.fantom.lang.types.FantomTypeInference
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor

/**
 * Warns on an identifier use that resolves to nothing.
 *
 * The resolver has known blind spots; those are not reported:
 * - no Fantom home: without the `sys` library even `Str` has no target;
 * - generic placeholders (`V`, `K`, `L`, `M`, `R`, `A`..`H`) in a type position;
 * - dynamic calls `->x` and `?->x`;
 * - `recv.x` when the receiver type cannot be inferred, and `#slot` literals;
 * - `Type.x` when `Type` itself does not resolve;
 * - facet values inside `@Facet { x = 1 }` (they have no reference at all).
 *
 * An unresolved type name gets an "Add using pod::Name" fix for every pod of the
 * install that has a `Name.fan` source file.
 */
class FantomUnresolvedReferenceInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        // The indexed pods (home plus Fantom sources); none means no library, so stay quiet.
        val pods = FantomSettings.getInstance(holder.project).podSources()
        if (pods.isEmpty()) return PsiElementVisitor.EMPTY_VISITOR
        // A fresh compiler result for this pod says exactly what does not resolve; do not repeat it.
        if (FantomCompilerService.getInstance(holder.project).isFresh(holder.file)) return PsiElementVisitor.EMPTY_VISITOR
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is FantomId) return
                val ref = element.reference as? FantomReference ?: return
                if (!isCheckable(element, ref)) return
                if (ref.resolveAll().isNotEmpty()) return
                val fixes: Array<LocalQuickFix> = if (ref.kind == FantomReference.Kind.TYPE) {
                    FantomUsingCandidates.podsFor(pods, ref.name).map { AddUsingFix(it, ref.name) }.toTypedArray()
                } else emptyArray()
                holder.registerProblem(element, "Cannot resolve symbol '${ref.name}'", *fixes)
            }
        }
    }

    private fun isCheckable(id: FantomId, ref: FantomReference): Boolean {
        if (id.parent is FantomFacetVal) return false
        return when (ref.kind) {
            FantomReference.Kind.BARE -> true
            FantomReference.Kind.TYPE -> ref.name !in GENERIC_PLACEHOLDERS
            FantomReference.Kind.SLOT -> hasKnownReceiver(id)
        }
    }

    // `recv.x` / `recv?.x` with an inferred receiver type, or `Type.x` with a resolving type.
    private fun hasKnownReceiver(id: FantomId): Boolean {
        val slotExpr = id.parent?.parent as? FantomSlotExpr ?: return false
        return when (val chainOp = slotExpr.parent) {
            is FantomDynCallStm, is FantomSafeDynCallStm -> false
            is FantomDotCall, is FantomSafeDotCallStm -> FantomTypeInference.receiverType(chainOp) != null
            is FantomStaticCall -> {
                val typeName = chainOp.type.simpleType?.idList?.lastOrNull()?.text ?: return false
                FantomTypeInference.typeDefinitions(typeName, id.project).isNotEmpty()
            }
            else -> false
        }
    }

    /** Inserts `using pod::Name` through [FantomUsingInserter]. */
    class AddUsingFix(private val pod: String, private val typeName: String) : LocalQuickFix {
        override fun getFamilyName(): String = "Add using"
        override fun getName(): String = "Add using $pod::$typeName"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val file = descriptor.psiElement?.containingFile ?: return
            FantomUsingInserter.insert(file, pod, typeName)
        }
    }

    companion object {
        /** Generic variables of the sys `List`, `Map` and `Func` sources; not real types. */
        val GENERIC_PLACEHOLDERS = setOf("V", "K", "L", "M", "R", "A", "B", "C", "D", "E", "F", "G", "H")
    }
}
