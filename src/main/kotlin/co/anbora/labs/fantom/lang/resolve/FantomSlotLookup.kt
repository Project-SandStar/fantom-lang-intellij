package co.anbora.labs.fantom.lang.resolve

import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.types.FantomTypeInference
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope

/**
 * Enumerates the slots a receiver of some type has. Used by completion; the
 * resolver keeps its own name-filtered walk in [FantomReference].
 */
object FantomSlotLookup {

    /**
     * The type chain of [start]: itself, its written supertypes breadth first, then
     * the implicit roots (`Enum` or `Facet`, then `Obj`). Each type name once.
     */
    fun typeChain(start: FantomNamedElement, project: Project, scope: GlobalSearchScope): List<FantomNamedElement> {
        val out = ArrayList<FantomNamedElement>()
        val seen = HashSet<String>()
        val queue = ArrayDeque<FantomNamedElement>().apply { add(start) }
        var implicitDone = false
        while (true) {
            if (queue.isEmpty()) {
                if (implicitDone) return out
                implicitDone = true
                for (root in FantomScope.implicitSuperTypeNames(start)) {
                    if (root !in seen) queue += FantomTypeInference.typeDefinitions(root, project)
                }
                if (queue.isEmpty()) return out
            }
            val type = queue.removeFirst()
            val name = type.name ?: continue
            if (!seen.add(name)) continue
            out += type
            for (superName in FantomScope.superTypeNames(type)) {
                queue += FantomTypeInference.typeDefinitions(superName, project)
            }
        }
    }

    /**
     * Every slot visible on [start]'s chain, nearest declaration first. A slot name
     * declared again further up (an override's base) is dropped.
     */
    fun allSlots(start: FantomNamedElement, project: Project, scope: GlobalSearchScope): List<FantomNamedElement> {
        val out = ArrayList<FantomNamedElement>()
        val seen = HashSet<String>()
        for (type in typeChain(start, project, scope)) {
            for (slot in FantomScope.ownSlots(type)) {
                val n = slot.name ?: continue
                if (seen.add(n)) out += slot
            }
        }
        return out
    }

    /** Slots for a receiver whose type is known only by short name (`Str`, `List`, `Map`). */
    fun allSlotsOfTypeName(typeName: String, project: Project, scope: GlobalSearchScope): List<FantomNamedElement> =
        FantomTypeInference.typeDefinitions(typeName, project).firstOrNull()
            ?.let { allSlots(it, project, scope) }
            ?: emptyList()
}
