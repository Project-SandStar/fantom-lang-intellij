package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.navigation.FantomGotoClassContributor
import co.anbora.labs.fantom.ide.navigation.FantomGotoSymbolContributor
import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FindSymbolParameters

/**
 * Go to Class and Go to Symbol over two `.fan` files: which names each lists,
 * and what the rows for `Foo` and `run` look like.
 */
class FantomGotoContributorTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.configureByText(
            "A.fan",
            """
            class Foo
            {
              Str name := "x"
              Void run() {}
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "B.fan",
            """
            mixin Bar { abstract Void go() }
            enum class Color { red }
            """.trimIndent()
        )
    }

    private fun names(contributor: ChooseByNameContributorEx): List<String> {
        val out = ArrayList<String>()
        contributor.processNames({ out.add(it); true }, GlobalSearchScope.projectScope(project), null)
        return out
    }

    private fun items(contributor: ChooseByNameContributorEx, name: String): List<NavigationItem> {
        val out = ArrayList<NavigationItem>()
        val params = FindSymbolParameters.wrap(name, GlobalSearchScope.projectScope(project))
        contributor.processElementsWithName(name, { out.add(it); true }, params)
        return out
    }

    fun testGotoClassListsTypesOnly() {
        val names = names(FantomGotoClassContributor())
        assertTrue(names.toString(), names.containsAll(listOf("Foo", "Bar", "Color")))
        assertFalse(names.toString(), names.contains("run"))
        assertFalse(names.toString(), names.contains("red"))
    }

    fun testGotoClassItemForFoo() {
        val item = items(FantomGotoClassContributor(), "Foo").single()
        assertEquals("Foo", item.name)
        assertEquals("Foo", item.presentation!!.presentableText)
        assertEquals("A.fan", item.presentation!!.locationString)
        assertNotNull(item.presentation!!.getIcon(false))
        assertEmpty(items(FantomGotoClassContributor(), "run"))
    }

    fun testGotoSymbolListsSlotsToo() {
        val names = names(FantomGotoSymbolContributor())
        assertTrue(names.toString(), names.containsAll(listOf("Foo", "Bar", "Color", "run", "name", "go", "red")))
    }

    fun testContributorsAreRegistered() {
        assertTrue(ChooseByNameContributor.CLASS_EP_NAME.extensionList.any { it is FantomGotoClassContributor })
        assertTrue(ChooseByNameContributor.SYMBOL_EP_NAME.extensionList.any { it is FantomGotoSymbolContributor })
    }

    fun testGotoSymbolItemForRun() {
        val item = items(FantomGotoSymbolContributor(), "run").single()
        assertEquals("run", item.name)
        assertEquals("run(): Void", item.presentation!!.presentableText)
        val location = item.presentation!!.locationString!!
        assertTrue(location, location.contains("Foo"))
        assertTrue(location, location.contains("A.fan"))
    }
}
