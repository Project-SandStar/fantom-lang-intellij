package co.anbora.labs.fantom.ide.refactoring

import co.anbora.labs.fantom.ide.generate.FantomOverrideCandidates
import co.anbora.labs.fantom.ide.navigation.FantomImplementationSearch
import co.anbora.labs.fantom.ide.presentation.FantomPresentation
import co.anbora.labs.fantom.lang.core.psi.FantomFieldDef
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomMixinDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.resolve.FantomScope
import co.anbora.labs.fantom.lang.resolve.FantomSlotLookup
import co.anbora.labs.fantom.lang.resolve.FantomUsingScope
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.util.containers.MultiMap

/**
 * Renames a Fantom slot together with its override family.
 *
 * The family of a `virtual`, `abstract`, `override` or mixin slot is every slot
 * linked to it by overriding: all same-named overridable slots on the supertypes,
 * and every project slot that overrides one of them, closed transitively.
 *
 * Library sources and pod stubs are read-only. When a family reaches one, only the
 * project part (members reachable through project members) can be renamed, and only
 * after the user agrees that it will no longer override.
 */
class FantomRenameSlotProcessor : RenamePsiElementProcessor() {

    private enum class Mode { FAMILY, ONLY_THIS, PROJECT_PART }

    override fun canProcessElement(element: PsiElement): Boolean = isFamilySlot(element)

    override fun substituteElementToRename(element: PsiElement, editor: Editor?): PsiElement? {
        val slot = element as? FantomNamedElement ?: return element
        slot.putUserData(MODE, null)
        if (isReadOnly(slot)) return element
        val family = family(slot)
        if (family.size <= 1) return element
        val project = slot.project
        val unitTest = ApplicationManager.getApplication().isUnitTestMode
        val library = family.firstOrNull { isReadOnly(it) }
        if (library != null) {
            val refusal = "'${slot.name}' overrides ${describe(library)} in a library and cannot be renamed."
            if (unitTest) {
                CommonRefactoringUtil.showErrorHint(project, editor, refusal, TITLE, null)
                return null
            }
            val answer = Messages.showOkCancelDialog(
                project,
                "$refusal\n\nRename only the project declarations? They will no longer override ${describe(library)}.",
                TITLE, "Rename Project Part", Messages.getCancelButton(), Messages.getWarningIcon(),
            )
            if (answer != Messages.OK) return null
            slot.putUserData(MODE, Mode.PROJECT_PART)
            return element
        }
        val base = FantomOverrideCandidates.baseSlot(slot)
        if (base == null || base !in family) {
            slot.putUserData(MODE, Mode.FAMILY)
            return element
        }
        if (unitTest) {
            slot.putUserData(MODE, Mode.FAMILY)
            return element
        }
        val answer = Messages.showYesNoCancelDialog(
            project,
            "'${slot.name}' overrides ${describe(base)}.\nRename base method too?",
            TITLE, Messages.getYesButton(), Messages.getNoButton(), Messages.getCancelButton(),
            Messages.getQuestionIcon(),
        )
        when (answer) {
            Messages.YES -> slot.putUserData(MODE, Mode.FAMILY)
            Messages.NO -> slot.putUserData(MODE, Mode.ONLY_THIS)
            else -> return null
        }
        return element
    }

    override fun prepareRenaming(element: PsiElement, newName: String, allRenames: MutableMap<PsiElement, String>) {
        val slot = element as? FantomNamedElement ?: return
        val mode = slot.getUserData(MODE)
        slot.putUserData(MODE, null)
        if (mode == Mode.ONLY_THIS || isReadOnly(slot)) return
        val family = family(slot)
        val members = when {
            mode == Mode.PROJECT_PART || family.any { isReadOnly(it) } -> family(slot, stopAtReadOnly = true)
            else -> family
        }
        for (m in members) {
            if (m != slot && !isReadOnly(m)) allRenames[m] = newName
        }
    }

    override fun findExistingNameConflicts(
        element: PsiElement,
        newName: String,
        conflicts: MultiMap<PsiElement, String>,
        allRenames: MutableMap<PsiElement, String>,
    ) {
        val slot = element as? FantomNamedElement ?: return
        val renamed = allRenames.keys
        val project = slot.project
        val scope = GlobalSearchScope.allScope(project)
        val type = FantomScope.enclosingType(slot) ?: return
        val types = LinkedHashSet<FantomNamedElement>()
        types += FantomSlotLookup.typeChain(type, project, scope)
        types += FantomImplementationSearch.subTypes(type)
        for (t in types) {
            for (other in FantomScope.ownSlots(t)) {
                if (other.name != newName || other in renamed || other == slot) continue
                val message = "${describe(other)} already exists in the hierarchy of '${slot.name}'"
                if (message !in conflicts.get(other)) conflicts.putValue(other, message)
            }
        }
    }

    companion object {
        private const val TITLE = "Rename"
        private val MODE = Key.create<Mode>("fantom.rename.slot.mode")

        /** True for methods and fields that can take part in overriding. */
        fun isFamilySlot(element: PsiElement): Boolean {
            if (element !is FantomMethodDef && element !is FantomFieldDef) return false
            val slot = element as FantomNamedElement
            val mods = FantomPresentation.modifiers(slot)
            if ("private" in mods || "static" in mods) return false
            if ("virtual" in mods || "abstract" in mods || "override" in mods) return true
            return FantomScope.enclosingType(slot) is FantomMixinDef
        }

        /**
         * The override family of [slot], [slot] first. Read-only members are kept
         * but not expanded; with [stopAtReadOnly] they are left out, so only the part linked
         * through project code remains.
         */
        fun family(slot: FantomNamedElement, stopAtReadOnly: Boolean = false): List<FantomNamedElement> {
            if (!isFamilySlot(slot)) return listOf(slot)
            val out = LinkedHashSet<FantomNamedElement>()
            val queue = ArrayDeque<FantomNamedElement>()
            out += slot
            queue += slot
            while (queue.isNotEmpty()) {
                val m = queue.removeFirst()
                val next = ArrayList<FantomNamedElement>()
                next += ancestors(m)
                if (isFamilySlot(m)) next += FantomOverrideCandidates.overridingSlots(m)
                for (n in next) {
                    val readOnly = isReadOnly(n)
                    if (stopAtReadOnly && readOnly) continue
                    // A library slot is kept for the message but never expanded: its
                    // overriders would be every same-named slot in the project.
                    if (out.add(n) && !readOnly) queue += n
                }
            }
            return out.toList()
        }

        /** Overridable slots named like [slot] on all supertypes of its type. */
        private fun ancestors(slot: FantomNamedElement): List<FantomNamedElement> {
            val name = slot.name ?: return emptyList()
            if ("override" !in FantomPresentation.modifiers(slot)) return emptyList()
            val type = FantomScope.enclosingType(slot) ?: return emptyList()
            val project = slot.project
            return FantomSlotLookup.typeChain(type, project, GlobalSearchScope.allScope(project)).drop(1)
                .flatMap { owner -> FantomScope.ownSlots(owner).filter { it.name == name && isFamilySlot(it) } }
        }

        /** True when [element] lives outside the project content: a library source or a pod stub. */
        fun isReadOnly(element: PsiElement): Boolean {
            val vf = element.containingFile?.virtualFile ?: return true
            if (!vf.isWritable) return true
            return !ProjectFileIndex.getInstance(element.project).isInContent(vf)
        }

        /** `pod::Type.slot`, or `Type.slot` when the pod is unknown. */
        private fun describe(slot: FantomNamedElement): String {
            val type = FantomScope.enclosingType(slot)?.name
            val pod = FantomUsingScope.podOf(slot)
            val owner = when {
                type == null -> ""
                pod != null -> "$pod::$type."
                else -> "$type."
            }
            return owner + (slot.name ?: "?")
        }
    }
}
