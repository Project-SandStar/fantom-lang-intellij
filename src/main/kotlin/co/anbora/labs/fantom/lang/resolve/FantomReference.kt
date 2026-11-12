package co.anbora.labs.fantom.lang.resolve

import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.index.FantomDeclarationKind
import co.anbora.labs.fantom.lang.types.FantomTypeInference
import com.intellij.openapi.roots.ProjectFileIndex
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
    /** True for `this.x` and `super.x`: the receiver is the enclosing type, so search its chain. */
    private val thisReceiver: Boolean = false,
) : PsiPolyVariantReferenceBase<PsiElement>(element, TextRange(0, element.textLength)) {

    enum class Kind { BARE, SLOT, TYPE }

    val name: String get() = element.text

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        resolveAll().map { PsiElementResolveResult(it) }.toTypedArray()

    fun resolveAll(): List<FantomNamedElement> = preferProject(resolveRaw())

    /**
     * When a name is declared both in the project and in the Fantom library (for
     * example the install itself opened as a project, or a pod copied into a project),
     * the project copy wins. Library-only results are returned as they are.
     */
    private fun preferProject(targets: List<FantomNamedElement>): List<FantomNamedElement> {
        if (targets.size < 2) return targets
        val index = ProjectFileIndex.getInstance(element.project)
        val inProject = targets.filter { t ->
            val vf = t.containingFile?.virtualFile ?: return@filter false
            index.isInContent(vf) && !index.isInLibrary(vf)
        }
        return inProject.ifEmpty { targets }
    }

    private fun resolveRaw(): List<FantomNamedElement> {
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
                if (thisReceiver) return slotsInEnclosingTypeChain(name, scope)
                if (ownerName != null) {
                    // `Type.x`: slots on that type and its supertypes.
                    val narrowed = FantomTypeInference.typeDefinitions(ownerName, project)
                        .flatMap { slotsInTypeChain(it, name, scope) }
                    if (narrowed.isNotEmpty()) return narrowed
                }
                // `recv.x`: narrow by the inferred type of the receiver when it is known.
                receiverTypeName()?.let { typeName ->
                    val narrowed = FantomTypeInference.typeDefinitions(typeName, project)
                        .flatMap { slotsInTypeChain(it, name, scope) }
                    if (narrowed.isNotEmpty()) return narrowed
                }
                val all = FantomDeclarationIndex.findDeclarations(name, project, scope) { !it.isType }
                if (ownerName != null) all.filter { FantomDeclarationIndex.enclosingTypeName(it) == ownerName }.ifEmpty { all }
                else all
            }
            Kind.TYPE -> types(name, project, scope)
        }
    }

    private fun types(name: String, project: com.intellij.openapi.project.Project, scope: GlobalSearchScope) =
        FantomDeclarationIndex.findDeclarations(name, project, scope) { it.isType }

    // The short type name of the receiver of `recv.x`, from the chain operator that holds this id.
    private fun receiverTypeName(): String? {
        val slotExpr = element.parent?.parent as? co.anbora.labs.fantom.lang.core.psi.FantomSlotExpr ?: return null
        val chainOp = slotExpr.parent ?: return null
        return FantomTypeInference.receiverType(chainOp)?.slotOwnerName
    }

    private fun slotsInEnclosingTypeChain(name: String, scope: GlobalSearchScope): List<FantomNamedElement> {
        val start = FantomScope.enclosingType(element) ?: return emptyList()
        return slotsInTypeChain(start, name, scope)
    }

    /**
     * Slots named [name] on [start], then on each declared supertype, breadth first,
     * and last on the implicit roots every Fantom type has: `Enum` for enums, `Facet`
     * for facets, and `Obj` for everything (where `echo`, `toStr`, `equals` live).
     */
    private fun slotsInTypeChain(start: FantomNamedElement, name: String, scope: GlobalSearchScope): List<FantomNamedElement> {
        val project = element.project
        val seen = HashSet<String>()
        val queue = ArrayDeque<FantomNamedElement>().apply { add(start) }
        var implicitDone = false
        while (true) {
            if (queue.isEmpty()) {
                if (implicitDone) return emptyList()
                implicitDone = true
                for (root in FantomScope.implicitSuperTypeNames(start)) {
                    if (root !in seen) queue += FantomDeclarationIndex.findDeclarations(root, project, scope) { it.isType }
                }
                if (queue.isEmpty()) return emptyList()
            }
            val type = queue.removeFirst()
            val typeName = type.name ?: continue
            if (!seen.add(typeName)) continue
            val hit = FantomScope.ownSlots(type).filter { it.name == name }
            if (hit.isNotEmpty()) return hit
            for (superName in FantomScope.superTypeNames(type)) {
                queue += FantomDeclarationIndex.findDeclarations(superName, project, scope) { it.isType }
            }
        }
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
