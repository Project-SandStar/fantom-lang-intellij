package co.anbora.labs.fantom.lang.axon.psi

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException

/**
 * Supplies Axon functions that are not in the file at hand: project functions, pod functions.
 * Registered at the `co.anbora.labs.fantom.axonFunctionSource` extension point.
 * [AxonResolver] asks the sources only after the lexical lookup in the file found nothing.
 */
interface AxonFunctionSource {
    /** The declarations of the function [name] that [context] can call. Empty when this source has none. */
    fun findFunctions(name: String, context: PsiElement): Collection<PsiElement>

    /** The names of the functions that [context] can call, for completion. */
    fun functionNames(context: PsiElement): Collection<String> = emptyList()

    companion object {
        val EP_NAME: ExtensionPointName<AxonFunctionSource> =
            ExtensionPointName.create("co.anbora.labs.fantom.axonFunctionSource")
    }
}

/**
 * The reference of a name use. It is soft: a name that does not resolve is not an error,
 * because most functions (`readAll`, `hisRead`, project functions) are not in the file.
 */
class AxonReference(element: AxonNameUse, range: TextRange) : PsiReferenceBase<AxonNameUse>(element, range, true) {

    override fun resolve(): PsiElement? = AxonResolver.resolve(element)

    override fun getVariants(): Array<Any> = AxonResolver.visible(element).toTypedArray()

    @Throws(IncorrectOperationException::class)
    override fun handleElementRename(newElementName: String): PsiElement {
        val leaf = element.referenceNameElement?.node as? LeafElement
            ?: throw IncorrectOperationException("No name to rename")
        leaf.replaceWithText(newElementName)
        return element
    }
}

/**
 * Lexical name lookup in one Axon file.
 *
 * Axon has one scope per function: a `do` block opens no scope. A function is a lambda,
 * a `defcomp`, or the file. From the use outwards, each function gives its locals
 * (`name: expr`) defined before the use, then its parameters or cells. A `catch (err)`
 * variable is visible in the catch expression. Top-level definitions are visible in
 * the whole file, so a function can call one that is defined further down.
 */
object AxonResolver {

    fun resolve(use: AxonNameUse): PsiElement? {
        val name = use.referenceName ?: return null
        lexical(use, name)?.let { return it }
        for (source in AxonFunctionSource.EP_NAME.extensionList) {
            source.findFunctions(name, use).firstOrNull()?.let { return it }
        }
        return null
    }

    /**
     * The declaration of [use] in its own file, or null. It reads only the PSI of that file:
     * no [AxonFunctionSource], no index. The colour annotator uses it on every name.
     */
    fun resolveInFile(use: AxonNameUse): AxonNamedElement? {
        val name = use.referenceName ?: return null
        return lexical(use, name)
    }

    /** Every declaration visible at [use]; the nearest one comes first for each name. */
    fun visible(use: PsiElement): List<AxonNamedElement> {
        val out = LinkedHashMap<String, AxonNamedElement>()
        walk(use) { decl -> decl.name?.let { out.putIfAbsent(it, decl) }; false }
        return out.values.toList()
    }

    private fun lexical(use: PsiElement, name: String): AxonNamedElement? {
        var found: AxonNamedElement? = null
        walk(use) { decl -> if (decl.name == name) { found = decl; true } else false }
        return found
    }

    /** Offers the declarations visible at [use], nearest first, until [stop] returns true. */
    private fun walk(use: PsiElement, stop: (AxonNamedElement) -> Boolean) {
        val offset = use.textRange.startOffset
        var child: PsiElement = use
        while (true) {
            val parent = child.parent ?: return
            when (parent) {
                is AxonTryExpr -> {
                    val v = parent.catchVar
                    if (v != null && v.textRange.endOffset <= child.textRange.startOffset && stop(v)) return
                }
                is AxonLambdaExpr -> {
                    if (locals(parent, use, offset).any(stop)) return
                    if (parent.paramList.asReversed().any { it !== child && stop(it) }) return
                }
                is AxonDefcompExpr -> {
                    if (locals(parent, use, offset).any(stop)) return
                    if (parent.cellDefList.any(stop)) return
                }
                is PsiFile -> {
                    if (locals(parent, use, offset).any(stop)) return
                    // A top-level definition after the use: functions of one file see each other.
                    parent.children.any { it is AxonDefExpr && it.textRange.startOffset >= offset && stop(it) }
                    return
                }
            }
            child = parent
        }
    }

    /** The definitions of [function] made before [offset], the last one first. */
    private fun locals(function: PsiElement, use: PsiElement, offset: Int): List<AxonDefExpr> {
        val out = ArrayList<AxonDefExpr>()
        collectDefs(function, out)
        return out.filter { def ->
            def.textOffset < offset &&
                // `x: x + 1` reads the outer x; `f: (n) => f(n - 1)` calls itself.
                (def.expr is AxonLambdaExpr || !PsiTreeUtil.isAncestor(def, use, true))
        }.asReversed()
    }

    /** The definitions that belong to [function]: the ones in nested lambdas and defcomps do not. */
    fun collectDefs(function: PsiElement, out: MutableList<AxonDefExpr>) {
        var c = function.firstChild
        while (c != null) {
            if (c is AxonDefExpr) out.add(c)
            if (c is AxonElement && c !is AxonLambdaExpr && c !is AxonDefcompExpr) collectDefs(c, out)
            c = c.nextSibling
        }
    }

    /** The function that holds [element]: a lambda, a `defcomp`, or null for the file. */
    fun functionOf(element: PsiElement): PsiElement? =
        PsiTreeUtil.getParentOfType(element, AxonLambdaExpr::class.java, AxonDefcompExpr::class.java)

    /** True for `name: (a) => ...` and `name: defcomp ... end`. */
    fun isFunction(def: AxonDefExpr): Boolean = def.expr is AxonLambdaExpr || def.expr is AxonDefcompExpr
}
