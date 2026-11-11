package co.anbora.labs.fantom

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Builds the structure view model for a small `.fan` file and checks the tree:
 * file -> types -> enum values, fields, ctors, methods, with their presentable texts.
 */
class FantomStructureViewTest : BasePlatformTestCase() {

    private fun rootOf(text: String): StructureViewTreeElement {
        val file = myFixture.configureByText("A.fan", text)
        assertEmpty("parse errors", PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java))
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(file)
        assertNotNull("no structure view builder registered for fantom_lang", builder)
        val model = (builder as TreeBasedStructureViewBuilder).createStructureViewModel(myFixture.editor)
        Disposer.register(testRootDisposable, model)
        return model.root
    }

    private fun texts(element: StructureViewTreeElement): List<String> =
        element.children.map { it.presentation.presentableText!! }

    fun testTypesUnderFile() {
        val root = rootOf(
            """
            class Foo {}
            mixin Bar {}
            enum class Color { red, green }
            facet class Tag {}
            """.trimIndent()
        )
        assertEquals("A.fan", root.presentation.presentableText)
        assertEquals(listOf("Foo", "Bar", "Color", "Tag"), texts(root))
    }

    fun testSlotsUnderClass() {
        val root = rootOf(
            """
            class Foo
            {
              new make(Str name, Int flags := 0) { this.name = name }
              Str name
              Int count := 0
              Void run() {}
              static Str greet(Str who, Int times) { return who }
            }
            """.trimIndent()
        )
        val foo = root.children.single() as StructureViewTreeElement
        assertEquals("Foo", foo.presentation.presentableText)
        assertEquals(
            listOf(
                "new make(Str, Int)",
                "name: Str",
                "count: Int",
                "run(): Void",
                "greet(Str, Int): Str",
            ),
            texts(foo)
        )
        assertEmpty(foo.children.flatMap { it.children.toList() })
    }

    fun testEnumValuesThenSlots() {
        val root = rootOf(
            """
            enum class Color
            {
              red, green, blue
              Str label() { return name }
            }
            """.trimIndent()
        )
        val color = root.children.single() as StructureViewTreeElement
        assertEquals(listOf("red", "green", "blue", "label(): Str"), texts(color))
    }
}
