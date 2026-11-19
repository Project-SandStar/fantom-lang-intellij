package co.anbora.labs.fantom.lang.axon.psi

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
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

    /** The completion items for the functions that [context] can call. By default one plain item per name. */
    fun lookups(context: PsiElement): Collection<LookupElement> =
        functionNames(context).map { LookupElementBuilder.create(it) }

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

    /**
     * The names visible in the file, then the functions of the sources (the Axon project of the
     * file). A source function with the name of a visible declaration is left out: the
     * declaration in the file wins, as it does in [AxonResolver.resolve].
     */
    override fun getVariants(): Array<Any> {
        val visible = AxonResolver.visible(element)
        val seen = visible.mapNotNullTo(HashSet()) { it.name }
        val out = ArrayList<Any>(visible)
        for (source in AxonFunctionSource.EP_NAME.extensionList) {
            for (lookup in source.lookups(element)) if (seen.add(lookup.lookupString)) out.add(lookup)
        }
        return out.toTypedArray()
    }

    @Throws(IncorrectOperationException::class)
    override fun handleElementRename(newElementName: String): PsiElement {
        val leaf = element.referenceNameElement?.node as? LeafElement
            ?: throw IncorrectOperationException("No name to rename")
        // a project function is its file: a file rename passes "name.axon", and an Axon name has no dot
        leaf.replaceWithText(newElementName.removeSuffix(".axon"))
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
        walk(use, null) { decl -> decl.name?.let { out.putIfAbsent(it, decl) }; false }
        return out.values.toList()
    }

    private fun lexical(use: PsiElement, name: String): AxonNamedElement? {
        var found: AxonNamedElement? = null
        walk(use, name) { decl -> if (decl.name == name) { found = decl; true } else false }
        return found
    }

    /**
     * Offers the declarations visible at [use], nearest first, until [stop] returns true.
     * With a [name], it offers only the definitions of that name: the lookup of one name
     * does not read the other definitions of the function.
     */
    private fun walk(use: PsiElement, name: String?, stop: (AxonNamedElement) -> Boolean) {
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
                    if (locals(parent, use, offset, name).any(stop)) return
                    if (parent.paramList.asReversed().any { it !== child && stop(it) }) return
                }
                is AxonDefcompExpr -> {
                    if (locals(parent, use, offset, name).any(stop)) return
                    if (parent.cellDefList.any(stop)) return
                }
                is PsiFile -> {
                    if (locals(parent, use, offset, name).any(stop)) return
                    // A top-level definition after the use: functions of one file see each other.
                    defsOf(parent).of(name).any { it.parent === parent && it.textRange.startOffset >= offset && stop(it) }
                    return
                }
            }
            child = parent
        }
    }

    /** The definitions of [function] made before [offset], the last one first. With a [name], only the ones of that name. */
    private fun locals(function: PsiElement, use: PsiElement, offset: Int, name: String?): Sequence<AxonDefExpr> {
        val defs = defsOf(function).of(name)
        // The definitions are in text order: a binary search finds the last one before the use.
        var low = 0
        var high = defs.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (defs[mid].textOffset < offset) low = mid + 1 else high = mid
        }
        return (low - 1 downTo 0).asSequence().map { defs[it] }.filter { def ->
            // `x: x + 1` reads the outer x; `f: (n) => f(n - 1)` calls itself.
            def.expr is AxonLambdaExpr || !PsiTreeUtil.isAncestor(def, use, true)
        }
    }

    /** The definitions of one function, in text order, and the same ones by name. */
    private class Defs(val all: List<AxonDefExpr>) {
        private val byName = HashMap<String, MutableList<AxonDefExpr>>()

        init {
            for (def in all) def.name?.let { byName.getOrPut(it) { ArrayList() }.add(def) }
        }

        fun of(name: String?): List<AxonDefExpr> = if (name == null) all else byName[name] ?: emptyList()
    }

    /**
     * The definitions of [function], kept until the PSI changes. The colour annotator resolves every
     * name of a file: without this, each name reads the whole function again.
     */
    private fun defsOf(function: PsiElement): Defs = CachedValuesManager.getCachedValue(function) {
        val out = ArrayList<AxonDefExpr>()
        collectDefs(function, out)
        CachedValueProvider.Result.create(Defs(out), PsiModificationTracker.MODIFICATION_COUNT)
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
