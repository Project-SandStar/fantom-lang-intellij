package co.anbora.labs.fantom.ide.inspections

import co.anbora.labs.fantom.lang.core.psi.FantomDotCall
import co.anbora.labs.fantom.lang.core.psi.FantomDynCallStm
import co.anbora.labs.fantom.lang.core.psi.FantomFacetVal
import co.anbora.labs.fantom.lang.core.psi.FantomId
import co.anbora.labs.fantom.lang.core.psi.FantomSafeDotCallStm
import co.anbora.labs.fantom.lang.core.psi.FantomSafeDynCallStm
import co.anbora.labs.fantom.lang.core.psi.FantomSlotExpr
import co.anbora.labs.fantom.lang.core.psi.FantomStaticCall
import co.anbora.labs.fantom.lang.core.psi.FantomUsingDef
import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomSettings
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
import com.intellij.psi.PsiFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
        val home = FantomSettings.getInstance(holder.project).effectiveHome() ?: return PsiElementVisitor.EMPTY_VISITOR
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is FantomId) return
                val ref = element.reference as? FantomReference ?: return
                if (!isCheckable(element, ref)) return
                if (ref.resolveAll().isNotEmpty()) return
                val fixes: Array<LocalQuickFix> = if (ref.kind == FantomReference.Kind.TYPE) {
                    UsingCandidates.podsFor(home, ref.name).map { AddUsingFix(it, ref.name) }.toTypedArray()
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

    /** Type name to pods whose sources have a `<Name>.fan` file, per Fantom home. */
    internal object UsingCandidates {
        private val cache = ConcurrentHashMap<String, Map<String, List<String>>>()

        fun podsFor(home: File, typeName: String): List<String> =
            cache.getOrPut(home.absolutePath) { scan(home) }[typeName] ?: emptyList()

        fun reset() = cache.clear()

        private fun scan(home: File): Map<String, List<String>> {
            val out = HashMap<String, MutableList<String>>()
            for (root in FantomHome.sourceRoots(home)) {
                val pod = root.parentFile.name
                root.walkTopDown().filter { it.isFile && it.extension == "fan" }.forEach { f ->
                    val pods = out.getOrPut(f.nameWithoutExtension) { ArrayList(1) }
                    if (pod !in pods) pods += pod
                }
            }
            return out
        }
    }

    /** Inserts `using pod::Name` after the last `using` line, or at the top of the file. */
    class AddUsingFix(private val pod: String, private val typeName: String) : LocalQuickFix {
        override fun getFamilyName(): String = "Add using"
        override fun getName(): String = "Add using $pod::$typeName"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val file = descriptor.psiElement?.containingFile ?: return
            val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
            val lastUsing = file.children.filterIsInstance<FantomUsingDef>().lastOrNull()
            val statement = "using $pod::$typeName"
            if (lastUsing != null) {
                document.insertString(lastUsing.textRange.endOffset, "\n$statement")
            } else {
                document.insertString(topInsertOffset(file, document.text), "$statement\n")
            }
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }

        // A `#!` shebang line must stay first.
        private fun topInsertOffset(file: PsiFile, text: String): Int {
            if (!text.startsWith("#!")) return 0
            val nl = text.indexOf('\n')
            return if (nl < 0) text.length else nl + 1
        }
    }

    companion object {
        /** Generic variables of the sys `List`, `Map` and `Func` sources; not real types. */
        val GENERIC_PLACEHOLDERS = setOf("V", "K", "L", "M", "R", "A", "B", "C", "D", "E", "F", "G", "H")
    }
}
