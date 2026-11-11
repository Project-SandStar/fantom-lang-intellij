package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.index.FantomDeclarationKind
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex

class FantomDeclarationIndexTest : BasePlatformTestCase() {

    private val source = """
        class Foo
        {
          Str name := "x"
          Void run(Int a) {}
          static new make() {}
        }
        mixin Walk { abstract Void go() }
        enum class Color { red, green }
    """.trimIndent()

    fun testIndexesTypesAndSlotsWithOwner() {
        myFixture.configureByText("A.fan", source)
        val scope = GlobalSearchScope.projectScope(project)

        val foo = FantomDeclarationIndex.entries("Foo", project, scope)
        assertEquals(listOf(FantomDeclarationIndex.Entry(FantomDeclarationKind.CLASS, null)), foo)

        val run = FantomDeclarationIndex.entries("run", project, scope)
        assertEquals(listOf(FantomDeclarationIndex.Entry(FantomDeclarationKind.METHOD, "Foo")), run)

        val make = FantomDeclarationIndex.entries("make", project, scope)
        assertEquals(FantomDeclarationKind.CTOR, make.single().kind)

        val red = FantomDeclarationIndex.entries("red", project, scope)
        assertEquals(FantomDeclarationIndex.Entry(FantomDeclarationKind.ENUM_VALUE, "Color"), red.single())

        assertEquals(FantomDeclarationKind.MIXIN, FantomDeclarationIndex.entries("Walk", project, scope).single().kind)
        assertEquals(FantomDeclarationKind.ENUM, FantomDeclarationIndex.entries("Color", project, scope).single().kind)
    }

    fun testParamsAndLocalsAreNotIndexed() {
        myFixture.configureByText("A.fan", source)
        val scope = GlobalSearchScope.projectScope(project)
        assertEmpty(FantomDeclarationIndex.entries("a", project, scope))
        val names = FantomDeclarationIndex.allNames(project, scope)
        assertTrue(names.containsAll(listOf("Foo", "name", "run", "make", "Walk", "go", "Color", "red", "green")))
        // Key lists are global to the index (a Fantom library on the machine may declare an `a`),
        // so prove the project itself holds no `a` through the containing-files lookup.
        assertTrue(FileBasedIndex.getInstance().getContainingFiles(FantomDeclarationIndex.NAME, "a", scope).isEmpty())
    }

    fun testFindDeclarationsReturnsPsi() {
        myFixture.configureByText("A.fan", source)
        val scope = GlobalSearchScope.projectScope(project)
        val types = FantomDeclarationIndex.findDeclarations("Foo", project, scope) { it.isType }
        assertEquals("Foo", types.single().name)
        val none = FantomDeclarationIndex.findDeclarations("run", project, scope) { it.isType }
        assertEmpty(none)
    }
}
