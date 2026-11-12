package co.anbora.labs.fantom.ide.generate

import co.anbora.labs.fantom.ide.presentation.FantomPresentation
import co.anbora.labs.fantom.lang.core.psi.FantomFieldDef
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomMixinDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.resolve.FantomScope
import co.anbora.labs.fantom.lang.resolve.FantomSlotLookup
import co.anbora.labs.fantom.lang.types.FantomTypeInference
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope

/**
 * Which inherited slots a type can override or must implement, and the text of
 * the override. Shared by the Alt+Insert handlers and the gutter markers.
 *
 * Fantom rules: a method is overridable when it is `virtual` or `abstract`; a mixin
 * method without a body is abstract; an `override` that is not `final` stays virtual.
 * An abstract field is implemented as `override <Type> name`. `private`, `static`,
 * `native` slots, ctors and enum values are never offered.
 */
object FantomOverrideCandidates {

    /** A slot the enclosing type may override, with the type that declares it. */
    data class Candidate(val slot: FantomNamedElement, val owner: FantomNamedElement, val isAbstract: Boolean) {
        val name: String get() = slot.name ?: "?"
    }

    /**
     * Slots of [type]'s supertypes that it does not declare yet, nearest declaration
     * per name first. [implementOnly] keeps the abstract ones.
     */
    fun candidates(type: FantomNamedElement, implementOnly: Boolean): List<Candidate> {
        val project = type.project
        val chain = FantomSlotLookup.typeChain(type, project, GlobalSearchScope.allScope(project)).drop(1)
        val seen = FantomScope.ownSlots(type).mapNotNullTo(HashSet()) { it.name }
        val out = ArrayList<Candidate>()
        for (owner in chain) {
            for (slot in FantomScope.ownSlots(owner)) {
                val name = slot.name ?: continue
                if (!seen.add(name)) continue
                val kind = overridability(slot, owner) ?: continue
                if (implementOnly && !kind) continue
                out += Candidate(slot, owner, kind)
            }
        }
        return out
    }

    /**
     * True when [slot] is abstract, false when it is virtual, null when it cannot be
     * overridden. [owner] is the type that declares it.
     */
    fun overridability(slot: FantomNamedElement, owner: FantomNamedElement = FantomScope.enclosingType(slot) ?: slot): Boolean? {
        if (slot !is FantomMethodDef && slot !is FantomFieldDef) return null
        val mods = FantomPresentation.modifiers(slot)
        if ("private" in mods || "static" in mods || "native" in mods || "final" in mods) return null
        if ("abstract" in mods) return true
        if (slot is FantomMethodDef && owner is FantomMixinDef && slot.statementBlock == null) return true
        if (slot is FantomMethodDef && ("virtual" in mods || "override" in mods)) return false
        return null
    }

    /** The nearest slot with the same name on the supertypes of [slot]'s type, or null. */
    fun baseSlot(slot: FantomNamedElement): FantomNamedElement? {
        val name = slot.name ?: return null
        val type = FantomScope.enclosingType(slot) ?: return null
        val project = slot.project
        for (owner in FantomSlotLookup.typeChain(type, project, GlobalSearchScope.allScope(project)).drop(1)) {
            FantomScope.ownSlots(owner).firstOrNull { it.name == name }?.let { return it }
        }
        return null
    }

    /**
     * Project slots named like [slot] whose type has [slot]'s type on its chain. Reads
     * the declaration index for the name, then re-parses only the owning types.
     */
    fun overridingSlots(slot: FantomNamedElement): List<FantomNamedElement> {
        val name = slot.name ?: return emptyList()
        val type = FantomScope.enclosingType(slot) ?: return emptyList()
        val typeName = type.name ?: return emptyList()
        val project: Project = slot.project
        val scope = GlobalSearchScope.projectScope(project)
        val owners = FantomDeclarationIndex.entries(name, project, scope)
            .mapNotNullTo(LinkedHashSet()) { it.ownerType }
        owners.remove(typeName)
        val out = ArrayList<FantomNamedElement>()
        for (ownerName in owners) {
            for (owner in FantomTypeInference.typeDefinitions(ownerName, project)) {
                val chain = FantomSlotLookup.typeChain(owner, project, scope).drop(1)
                if (chain.none { it.name == typeName }) continue
                for (own in FantomScope.ownSlots(owner)) {
                    if (own.name == name && (own is FantomMethodDef || own is FantomFieldDef)) out += own
                }
            }
        }
        return out
    }

    /**
     * The override text for [candidate], each line prefixed with [indent]. Methods:
     * `override Ret name(Type p)` then a block; the body throws for abstract slots,
     * calls `super` for virtual ones. Abstract fields: `override Type name`.
     */
    fun generate(candidate: Candidate, indent: String): String {
        val slot = candidate.slot
        val name = candidate.name
        return when (slot) {
            is FantomFieldDef -> "${indent}override ${typeText(slot.type.text)} $name"
            is FantomMethodDef -> {
                val params = slot.itemParameterList.paramsList.mapNotNull { it.param }
                val ret = typeText(slot.type.text)
                val sig = params.joinToString(", ") { "${typeText(it.type.text)} ${it.name ?: "?"}" }
                val args = params.joinToString(", ") { it.name ?: "?" }
                val body = when {
                    candidate.isAbstract -> "throw UnsupportedErr()"
                    ret == "Void" -> "super.$name($args)"
                    else -> "return super.$name($args)"
                }
                buildString {
                    append(indent).append("override ").append(ret).append(' ').append(name).append('(').append(sig).append(")\n")
                    append(indent).append("{\n")
                    append(indent).append("  ").append(body).append('\n')
                    append(indent).append('}')
                }
            }
            else -> ""
        }
    }

    private fun typeText(text: String?): String = text?.trim()?.replace(Regex("\\s+"), " ") ?: "Obj?"
}
