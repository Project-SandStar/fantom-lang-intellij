package co.anbora.labs.fantom.ide.navigation

import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.resolve.FantomScope
import com.intellij.codeInsight.navigation.actions.GotoTypeDeclarationAction
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FindSymbolParameters

/**
 * Go to Class / Symbol rows, [FantomNavigationItem], [FantomImplementationSearch] and
 * [FantomTypeDeclarationProvider], over a small pod with its own `sys` types.
 */
class FantomNavigationTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("sys/fan/Obj.fan", "class Obj {}")
        myFixture.addFileToProject("sys/fan/Str.fan", "const final class Str {}")
        myFixture.addFileToProject("sys/fan/Int.fan", "const final class Int {}")
        myFixture.addFileToProject(
            "geo/fan/Shapes.fan",
            """
            mixin Shape
            {
              abstract Int area()
              virtual Str label() { "shape" }
            }
            class Square : Shape
            {
              override Int area() { 4 }
              const Int side := 2
            }
            class Cube : Square
            {
              override Int area() { 24 }
              override Str label() { "cube" }
            }
            class Plain { Int area() { 0 } }
            """.trimIndent(),
        )
    }

    private fun names(c: ChooseByNameContributorEx): Set<String> {
        val out = HashSet<String>()
        c.processNames({ out.add(it); true }, GlobalSearchScope.projectScope(project), null)
        return out
    }

    private fun rows(c: ChooseByNameContributorEx, name: String): List<NavigationItem> {
        val out = ArrayList<NavigationItem>()
        c.processElementsWithName(name, { out.add(it); true }, FindSymbolParameters.wrap(name, GlobalSearchScope.projectScope(project)))
        return out
    }

    private fun decl(owner: String?, name: String): FantomNamedElement {
        val file = psiManager.findFile(myFixture.findFileInTempDir("geo/fan/Shapes.fan"))!!
        return PsiTreeUtil.findChildrenOfType(file, FantomNamedElement::class.java).first {
            it.name == name && (if (owner == null) FantomScope.isTypeDef(it) else FantomScope.enclosingType(it)?.name == owner)
        }
    }

    private fun label(e: Any?): String {
        e as FantomNamedElement
        return if (FantomScope.isTypeDef(e)) e.name!! else "${FantomScope.enclosingType(e)?.name}.${e.name}"
    }

    fun testGotoClassFindsTypeNotSlot() {
        val names = names(FantomGotoClassContributor())
        assertTrue(names.toString(), names.containsAll(listOf("Shape", "Square", "Cube", "Obj")))
        assertFalse(names.contains("side"))
        val row = rows(FantomGotoClassContributor(), "Square").single() as FantomNavigationItem
        assertEquals("Square", label(row.element))
        assertEmpty(rows(FantomGotoClassContributor(), "side"))
    }

    fun testGotoSymbolFindsSlot() {
        assertTrue(names(FantomGotoSymbolContributor()).contains("side"))
        val row = rows(FantomGotoSymbolContributor(), "side").single()
        assertEquals("side", row.name)
        assertEquals("Square (Shapes.fan)", row.presentation!!.locationString)
        // Each `area` is its own row: Shape, Square, Cube and Plain.
        assertEquals(
            setOf("Shape.area", "Square.area", "Cube.area", "Plain.area"),
            rows(FantomGotoSymbolContributor(), "area").map { label((it as FantomNavigationItem).element) }.toSet(),
        )
    }

    fun testNavigationItem() {
        val square = decl(null, "Square")
        val item = FantomNavigationItem(square)
        assertEquals("Square", item.name)
        assertSame(square, item.targetElement)
        assertEquals("Square", item.presentation.presentableText)
        assertEquals("Shapes.fan", item.presentation.locationString)
        assertTrue(item.canNavigate())
        assertTrue(item.canNavigateToSource())
        assertEquals(FantomNavigationItem(square), item)
        assertEquals(item.hashCode(), FantomNavigationItem(square).hashCode())
        assertFalse(item == FantomNavigationItem(decl(null, "Cube")))
        assertEquals("FantomNavigationItem(Square)", item.toString())

        val area = FantomNavigationItem.presentationOf(decl("Square", "area"))
        assertEquals("area(): Int", area.presentableText)
        assertEquals("Square (Shapes.fan)", area.locationString)
        assertEquals("Square (Shapes.fan)", FantomItemPresentationProvider().getPresentation(decl("Square", "area") as co.anbora.labs.fantom.lang.core.psi.impl.FantomNamedElementImpl).locationString)
    }

    fun testImplementationsOfMixinMethod() {
        val found = DefinitionsScopedSearch.search(decl("Shape", "area")).findAll().map { label(it) }.toSet()
        assertEquals(setOf("Square.area", "Cube.area"), found)
        val label = DefinitionsScopedSearch.search(decl("Shape", "label")).findAll().map { label(it) }.toSet()
        assertEquals(setOf("Cube.label"), label)
    }

    fun testImplementationsOfMixinAndSubTypes() {
        assertEquals(setOf("Square", "Cube"), FantomImplementationSearch.subTypes(decl(null, "Shape")).map { label(it) }.toSet())
        assertEquals(setOf("Cube"), FantomImplementationSearch.subTypes(decl(null, "Square")).map { label(it) }.toSet())
        assertEmpty(FantomImplementationSearch.subTypes(decl(null, "Cube")))
        // A method nobody overrides gives nothing.
        assertEmpty(DefinitionsScopedSearch.search(decl("Plain", "area")).findAll())
    }

    private fun typeAt(text: String, marker: String): List<String> {
        myFixture.configureByText("Use.fan", text)
        val offset = text.indexOf(marker)
        assertTrue(marker, offset >= 0)
        return GotoTypeDeclarationAction.findSymbolTypes(myFixture.editor, offset).orEmpty().map { label(it) }
    }

    fun testTypeOfLocalGoesToClass() {
        val text = "class Use\n{\n  Void run()\n  {\n    sq := Square()\n    n := 3\n    echo(sq)\n    echo(n)\n  }\n}\n"
        assertEquals(listOf("Square"), typeAt(text, "sq)"))
        assertEquals(listOf("Int"), typeAt(text, "n)"))
    }

    fun testTypeOfSlotDeclaration() {
        val provider = FantomTypeDeclarationProvider()
        assertEquals(listOf("Int"), provider.getSymbolTypeDeclarations(decl("Square", "side"))!!.map { label(it) })
        // A type declaration has no type to go to.
        assertNull(provider.getSymbolTypeDeclarations(decl(null, "Square")))
    }
}
