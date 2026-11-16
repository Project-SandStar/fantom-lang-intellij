package co.anbora.labs.fantom.structure

import co.anbora.labs.fantom.ide.structure.FantomBreadcrumbsProvider
import co.anbora.labs.fantom.lang.FantomLanguage
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** [FantomBreadcrumbsProvider]: type > slot > closure. Cases not in the root FantomBreadcrumbsTest. */
class FantomBreadcrumbsProviderTest : BasePlatformTestCase() {

    private fun crumbs(text: String): List<String> {
        myFixture.configureByText("A.fan", text)
        return myFixture.breadcrumbsAtCaret.map { it.text }
    }

    fun testLanguage() {
        assertEquals(listOf(FantomLanguage), FantomBreadcrumbsProvider().languages.toList())
    }

    fun testOutsideAnyTypeHasNoCrumbs() {
        assertEmpty(crumbs("using sys<caret>\nclass Foo {}\n"))
    }

    fun testOnClassHeaderShowsType() {
        assertEquals(listOf("Foo"), crumbs("class Fo<caret>o\n{\n}\n"))
    }

    fun testClosureSignatureWhitespaceCollapses() {
        assertEquals(
            listOf("Foo", "run(Int[]): Void", "|Int x -> Str|"),
            crumbs("class Foo\n{\n  Void run(Int[] xs) { xs.map |Int   x  ->  Str| { <caret>x.toStr } }\n}\n"),
        )
    }

    fun testNestedClosures() {
        assertEquals(
            listOf("Foo", "run(): Void", "|Int a|", "|Int b|"),
            crumbs("class Foo\n{\n  Void run() { f |Int a| { g |Int b| { echo(<caret>b) } } }\n}\n"),
        )
    }

    fun testEnumMethod() {
        assertEquals(
            listOf("Color", "label(): Str"),
            crumbs("enum class Color\n{\n  red, green\n  Str label() { return <caret>name }\n}\n"),
        )
    }

    fun testFacetField() {
        assertEquals(listOf("Tag", "value: Str"), crumbs("facet class Tag\n{\n  const Str value := <caret>\"\"\n}\n"))
    }
}
