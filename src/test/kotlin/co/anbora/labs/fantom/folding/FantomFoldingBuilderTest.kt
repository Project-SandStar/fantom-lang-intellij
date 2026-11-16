package co.anbora.labs.fantom.folding

import co.anbora.labs.fantom.ide.folding.FantomFoldingBuilder
import co.anbora.labs.fantom.lang.FantomLanguage
import com.intellij.lang.folding.LanguageFolding
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** [FantomFoldingBuilder]: one fold per `{ }` block of a type body, enum, field accessor, statement block or switch. */
class FantomFoldingBuilderTest : BasePlatformTestCase() {

    private val builder = FantomFoldingBuilder()

    /** "PsiClass: text" for each region, with newlines shown as `\n`. */
    private fun regions(text: String): List<String> {
        val file = myFixture.configureByText("A.fan", text)
        val document = myFixture.editor.document
        return builder.buildFoldRegions(file, document, false).map {
            val psi = it.element.psi
            assertEquals("{...}", builder.getPlaceholderText(it.element))
            assertFalse(builder.isCollapsedByDefault(it.element))
            assertEquals(psi.textRange, it.range)
            psi.javaClass.simpleName.removeSuffix("Impl") + ": " + document.getText(it.range).replace("\n", "\\n")
        }
    }

    fun testIsRegisteredForFantom() {
        assertTrue(LanguageFolding.INSTANCE.allForLanguage(FantomLanguage).any { it is FantomFoldingBuilder })
    }

    fun testClassAndMethodBodies() {
        assertEquals(
            listOf(
                "FantomHighOrderBlock: {\\n  Void f() {\\n    x := 1\\n  }\\n}",
                "FantomStatementBlock: {\\n    x := 1\\n  }",
            ),
            regions("class C {\n  Void f() {\n    x := 1\n  }\n}"),
        )
    }

    fun testEnumBody() {
        assertEquals(listOf("FantomEnumBlock: { red, green }"), regions("enum class Color { red, green }"))
    }

    fun testFieldAccessorBlock() {
        assertEquals(
            listOf(
                "FantomHighOrderBlock: {\\n  Int x { get { return 1 } }\\n}",
                "FantomFieldDefBlock: { get { return 1 } }",
                "FantomStatementBlock: { return 1 }",
            ),
            regions("class C {\n  Int x { get { return 1 } }\n}"),
        )
    }

    fun testSwitchBlock() {
        assertEquals(
            listOf(
                "FantomHighOrderBlock: { Void f(Int a) { switch (a) { case 1: return } } }",
                "FantomStatementBlock: { switch (a) { case 1: return } }",
                "FantomSwitchStmBlock: { case 1: return }",
            ),
            regions("class C { Void f(Int a) { switch (a) { case 1: return } } }"),
        )
    }

    fun testClosureBodyFolds() {
        assertEquals(
            listOf(
                "FantomHighOrderBlock: { Void f() { list.each |x| { echo(x) } } }",
                "FantomStatementBlock: { list.each |x| { echo(x) } }",
                "FantomStatementBlock: { echo(x) }",
            ),
            regions("class C { Void f() { list.each |x| { echo(x) } } }"),
        )
    }

    fun testMixinAndFacetBodies() {
        assertEquals(
            listOf("FantomHighOrderBlock: {}", "FantomHighOrderBlock: {}"),
            regions("mixin M {}\nfacet class F {}"),
        )
    }

    fun testCommentsDoNotFold() {
        assertEmpty(regions("/* a\n b */\n** doc\n// line\n"))
    }
}
