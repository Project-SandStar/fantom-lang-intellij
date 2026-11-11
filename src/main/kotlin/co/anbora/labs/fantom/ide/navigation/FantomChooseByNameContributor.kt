package co.anbora.labs.fantom.ide.navigation

import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.index.FantomDeclarationKind
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter

/**
 * Shared shape of Go to Class and Go to Symbol for Fantom. Names come from
 * [FantomDeclarationIndex] through `processAllKeys`, so the scope and the
 * [IdFilter] the popup passes in are honored. Elements come from
 * [FantomDeclarationIndex.findDeclarations], which has no IdFilter parameter;
 * the scope alone bounds it.
 */
abstract class FantomChooseByNameContributor(
    /** Which declaration kinds this popup lists. Null means every kind, with no per-name value check. */
    private val kindFilter: ((FantomDeclarationKind) -> Boolean)?,
) : ChooseByNameContributorEx {

    override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) {
        val index = FileBasedIndex.getInstance()
        if (kindFilter == null) {
            // Every kind: pass the keys straight through. Stale names drop out in processElementsWithName.
            index.processAllKeys(FantomDeclarationIndex.NAME, processor, scope, filter)
            return
        }
        // Collect first, filter after: no index reads inside the key-processing lambda.
        val keys = ArrayList<String>()
        index.processAllKeys(FantomDeclarationIndex.NAME, { keys.add(it); true }, scope, filter)
        for (name in keys) {
            if (!matchesKind(name, scope, kindFilter)) continue
            if (!processor.process(name)) return
        }
    }

    override fun processElementsWithName(
        name: String,
        processor: Processor<in NavigationItem>,
        parameters: FindSymbolParameters,
    ) {
        val found = FantomDeclarationIndex.findDeclarations(
            name, parameters.project, parameters.searchScope, kindFilter ?: { true },
        )
        for (element in found) {
            if (!processor.process(FantomNavigationItem(element))) return
        }
    }

    /** True when at least one entry under [name] in [scope] passes [filter]. Stale keys fall out here. */
    private fun matchesKind(name: String, scope: GlobalSearchScope, filter: (FantomDeclarationKind) -> Boolean): Boolean =
        FileBasedIndex.getInstance().getValues(FantomDeclarationIndex.NAME, name, scope)
            .any { entries -> entries.any { filter(it.kind) } }
}

/** Go to Class (Cmd+O): classes, mixins, enums and facets. */
class FantomGotoClassContributor : FantomChooseByNameContributor({ it.isType })

/** Go to Symbol (Cmd+Alt+O): every indexed declaration, slots included. */
class FantomGotoSymbolContributor : FantomChooseByNameContributor(null)
