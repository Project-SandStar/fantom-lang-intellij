package co.anbora.labs.fantom.ide.navigation

import co.anbora.labs.fantom.ide.generate.FantomOverrideCandidates
import co.anbora.labs.fantom.lang.FantomFileType
import co.anbora.labs.fantom.lang.core.psi.FantomFieldDef
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.resolve.FantomScope
import co.anbora.labs.fantom.lang.resolve.FantomSlotLookup
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor

/**
 * Go to Implementation (Cmd+Alt+B) for Fantom, in project scope:
 * a method or field gives the slots that override it in subtypes;
 * a class or mixin gives the types that extend or implement it, directly or not.
 */
class FantomImplementationSearch :
    QueryExecutorBase<PsiElement, DefinitionsScopedSearch.SearchParameters>(true) {

    override fun processQuery(
        params: DefinitionsScopedSearch.SearchParameters,
        consumer: Processor<in PsiElement>,
    ) {
        val element = params.element as? FantomNamedElement ?: return
        val found = when {
            element is FantomMethodDef || element is FantomFieldDef -> FantomOverrideCandidates.overridingSlots(element)
            FantomScope.isTypeDef(element) -> subTypes(element)
            else -> return
        }
        for (e in found) if (!consumer.process(e)) return
    }

    companion object {
        /** Project types whose type chain holds [type] (by name); [type] itself is left out. */
        fun subTypes(type: FantomNamedElement): List<FantomNamedElement> {
            val name = type.name ?: return emptyList()
            val project = type.project
            val scope = GlobalSearchScope.projectScope(project)
            val manager = PsiManager.getInstance(project)
            val out = ArrayList<FantomNamedElement>()
            for (vf in FileTypeIndex.getFiles(FantomFileType, scope)) {
                ProgressManager.checkCanceled()
                val file = manager.findFile(vf) ?: continue
                // Cheap text filter before any type chain walk.
                if (!file.text.contains(name)) continue
                for (candidate in PsiTreeUtil.findChildrenOfType(file, FantomNamedElement::class.java)) {
                    if (candidate == type || !FantomScope.isTypeDef(candidate)) continue
                    val chain = FantomSlotLookup.typeChain(candidate, project, scope).drop(1)
                    if (chain.any { it == type || it.name == name }) out += candidate
                }
            }
            return out
        }
    }
}
