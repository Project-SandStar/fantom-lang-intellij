package co.anbora.labs.fantom.ide.imports

import co.anbora.labs.fantom.lang.core.psi.FantomId
import co.anbora.labs.fantom.lang.core.psi.FantomSimpleType
import co.anbora.labs.fantom.lang.core.psi.FantomUsingDef
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.resolve.FantomReference
import co.anbora.labs.fantom.lang.resolve.FantomUsingScope
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil

/**
 * Which `using` lines of a file are unused. One analysis for the import optimizer
 * and the unused-using inspection, so they cannot disagree.
 *
 * A name counts as referenced when a TYPE reference uses it, or a BARE reference
 * whose name starts with an upper-case letter (a type used as a value). A
 * `pod::Name` use also counts for `pod`.
 * - `using pod::Name [as Alias]` is used when the visible name is referenced.
 * - `using pod` is used when a referenced name has a type declaration in `pod`.
 *   When a referenced name has no declaration at all (no index, no Fantom home),
 *   nothing can be proved and every whole-pod `using` is kept.
 * - FFI usings and lines with text after the `using` (a comment) are never unused.
 */
object FantomUsingUsage {

    class Usage(val unused: Set<FantomUsingDef>)

    fun unused(file: PsiFile): Set<FantomUsingDef> =
        CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(compute(file), PsiModificationTracker.MODIFICATION_COUNT)
        }.unused

    fun isFfi(using: FantomUsingDef): Boolean = using.usingPod.podSpec?.ffiPodSpec != null

    /** True when something other than white space follows the `using` on its line. */
    fun hasTrailingText(using: FantomUsingDef, fileText: CharSequence = using.containingFile.text): Boolean {
        val end = using.textRange.endOffset
        var lineEnd = fileText.indexOf('\n', end)
        if (lineEnd < 0) lineEnd = fileText.length
        return fileText.subSequence(end, lineEnd).isNotBlank()
    }

    /** Never removed: FFI, trailing comment, or no Fantom pod to judge. */
    fun isPinned(using: FantomUsingDef, fileText: CharSequence): Boolean =
        isFfi(using) || hasTrailingText(using, fileText) || FantomUsingScope.podName(using) == null

    private fun compute(file: PsiFile): Usage {
        val usings = FantomUsingScope.usingDefs(file)
        if (usings.isEmpty()) return Usage(emptySet())
        val text = file.text
        val names = LinkedHashSet<String>()
        val qualifiedPods = HashSet<String>()
        for (id in PsiTreeUtil.findChildrenOfType(file, FantomId::class.java)) {
            if (PsiTreeUtil.getParentOfType(id, FantomUsingDef::class.java) != null) continue
            val ref = id.reference as? FantomReference
            if (ref == null) {
                val type = id.parent as? FantomSimpleType ?: continue
                if (type.idList.size == 2 && type.idList[0] == id) qualifiedPods += id.text
                continue
            }
            when (ref.kind) {
                FantomReference.Kind.TYPE -> names += ref.name
                FantomReference.Kind.BARE -> if (ref.name.firstOrNull()?.isUpperCase() == true) names += ref.name
                FantomReference.Kind.SLOT -> {}
            }
        }

        val project = file.project
        val scope = GlobalSearchScope.allScope(project)
        val usedPods = HashSet<String>(qualifiedPods)
        var unresolved = false
        for (name in names) {
            val decls = FantomDeclarationIndex.findDeclarations(name, project, scope) { it.isType }
            if (decls.isEmpty()) { unresolved = true; continue }
            for (decl in decls) FantomUsingScope.podOf(decl)?.let { usedPods += it }
        }

        val unused = LinkedHashSet<FantomUsingDef>()
        for (using in usings) {
            if (isPinned(using, text)) continue
            val pod = FantomUsingScope.podName(using) ?: continue
            val visible = FantomUsingScope.visibleName(using)
            val used = if (visible != null) visible in names else unresolved || pod in usedPods
            if (!used) unused += using
        }
        return Usage(unused)
    }
}
