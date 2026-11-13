package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.resolve.FantomScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.CodeInsightTestUtil

/** Go to Implementation (Cmd+Alt+B) on mixins, classes and virtual or abstract slots. */
class FantomImplementationTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "Impl.fan",
            """
            class Square : Shape { override Float area() { 1f } }
            class Big : Square { override Float area() { 2f } override Str name() { "b" } }
            class Other { Float area() { 0f } }
            """.trimIndent()
        )
    }

    private val shape = "mixin Shape { abstract Float area(); virtual Str name() { \"s\" } }"

    /** "Owner.name" of each implementation found for the declaration at the caret. */
    private fun implementationsAt(marker: String): Set<String> {
        myFixture.configureByText("Shape.fan", shape)
        myFixture.editor.caretModel.moveToOffset(shape.indexOf(marker))
        val data = CodeInsightTestUtil.gotoImplementation(myFixture.editor, myFixture.file)
        return data.targets.map { label(it as FantomNamedElement) }.toSet()
    }

    private fun label(e: FantomNamedElement): String =
        if (FantomScope.isTypeDef(e)) e.name!! else "${FantomScope.enclosingType(e)?.name}.${e.name}"

    fun testMixinGivesAllSubtypes() {
        assertEquals(setOf("Square", "Big"), implementationsAt("Shape"))
    }

    fun testAbstractMethodGivesOverrides() {
        assertEquals(setOf("Square.area", "Big.area"), implementationsAt("area"))
    }

    fun testVirtualMethodGivesOverrides() {
        assertEquals(setOf("Big.name"), implementationsAt("name"))
    }

    fun testClassGivesSubclasses() {
        myFixture.configureByText("Shape.fan", shape)
        val square = PsiTreeUtil.findChildrenOfType(myFixture.findFileInTempDir("Impl.fan").let { psiManager.findFile(it)!! }, FantomNamedElement::class.java)
            .first { it.name == "Square" && FantomScope.isTypeDef(it) }
        val found = DefinitionsScopedSearch.search(square).findAll().map { label(it as FantomNamedElement) }.toSet()
        assertEquals(setOf("Big"), found)
    }
}
