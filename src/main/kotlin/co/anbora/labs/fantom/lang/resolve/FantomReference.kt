package co.anbora.labs.fantom.lang.resolve

import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.index.FantomDeclarationKind
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.search.GlobalSearchScope

/**
 * Reference from an identifier use to its declaration.
 *
 * [kind] says where the identifier sits, which picks the resolve order:
 * - [Kind.BARE]: `x` alone or `x(...)`. Locals and params first, then slots of the
 *   enclosing type and its supertypes, then a type by short name.
 * - [Kind.SLOT]: `recv.x` or `Type.x`. Slots named x anywhere in scope (the receiver
 *   type is unknown until layer 5), narrowed to the receiver's type when [ownerName]
 *   is known.
 * - [Kind.TYPE]: `Foo` in a type position or `pod::Foo`. Types only.
 *
 * The element is the identifier leaf's owner (a FantomId); the range is the whole leaf.
 */
class FantomReference(
    element: PsiElement,
    val kind: Kind,
    private val ownerName: String? = null,
) : PsiPolyVariantReferenceBase<PsiElement>(element, TextRange(0, element.textLength)) {

    enum class Kind { BARE, SLOT, TYPE }

    val name: String get() = element.text

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        resolveAll().map { PsiElementResolveResult(it) }.toTypedArray()

    fun resolveAll(): List<FantomNamedElement> {
        val name = name
        val project = element.project
        val scope = GlobalSearchScope.allScope(project)
        return when (kind) {
            Kind.BARE -> {
                FantomScope.localsVisibleAt(element).filter { it.name == name }.take(1).ifEmpty {
                    slotsInEnclosingTypeChain(name, scope).ifEmpty { types(name, project, scope) }
                }
            }
            Kind.SLOT -> {
                val all = FantomDeclarationIndex.findDeclarations(name, project, scope) { !it.isType }
                if (ownerName != null) all.filter { FantomDeclarationIndex.enclosingTypeName(it) == ownerName }.ifEmpty { all }
                else all
            }
            Kind.TYPE -> types(name, project, scope)
        }
    }

    private fun types(name: String, project: com.intellij.openapi.project.Project, scope: GlobalSearchScope) =
        FantomDeclarationIndex.findDeclarations(name, project, scope) { it.isType }

    /** Slots named [name] on the enclosing type, then on each declared supertype, breadth first. */
    private fun slotsInEnclosingTypeChain(name: String, scope: GlobalSearchScope): List<FantomNamedElement> {
        val start = FantomScope.enclosingType(element) ?: return emptyList()
        val project = element.project
        val seen = HashSet<String>()
        val queue = ArrayDeque<FantomNamedElement>().apply { add(start) }
        while (queue.isNotEmpty()) {
            val type = queue.removeFirst()
            val typeName = type.name ?: continue
            if (!seen.add(typeName)) continue
            val hit = FantomScope.ownSlots(type).filter { it.name == name }
            if (hit.isNotEmpty()) return hit
            for (superName in FantomScope.superTypeNames(type)) {
                queue += FantomDeclarationIndex.findDeclarations(superName, project, scope) { it.isType }
            }
        }
        return emptyList()
    }

    override fun handleElementRename(newElementName: String): PsiElement {
        val leaf = element.node.firstChildNode as? LeafElement
            ?: return element
        leaf.replaceWithText(newElementName)
        return element
    }

    /**
     * Called once per word-index hit during find usages and rename, so it gates
     * before it resolves: the target must have the same name and a kind this
     * reference can point at. A BARE name aimed at a local never touches the index.
     */
    override fun isReferenceTo(target: PsiElement): Boolean {
        if (target !is FantomNamedElement || target.name != name) return false
        fun resolvesTo() = resolveAll().any { it.isEquivalentTo(target) }
        return when (kind) {
            Kind.TYPE -> FantomScope.isTypeDef(target) && resolvesTo()
            Kind.SLOT -> FantomScope.isSlot(target) && resolvesTo()
            Kind.BARE -> when {
                FantomScope.isLocalLike(target) ->
                    target.containingFile == element.containingFile &&
                        FantomScope.localsVisibleAt(element).firstOrNull { it.name == name }?.isEquivalentTo(target) == true
                FantomScope.isSlot(target) || FantomScope.isTypeDef(target) -> resolvesTo()
                else -> false
            }
        }
    }

    override fun getVariants(): Array<Any> = emptyArray()
}
