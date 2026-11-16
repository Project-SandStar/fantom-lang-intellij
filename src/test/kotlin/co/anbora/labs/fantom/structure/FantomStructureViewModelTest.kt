package co.anbora.labs.fantom.structure

import co.anbora.labs.fantom.ide.structure.FantomStructureViewElement
import co.anbora.labs.fantom.ide.structure.FantomStructureViewFactory
import co.anbora.labs.fantom.ide.structure.FantomStructureViewModel
import co.anbora.labs.fantom.lang.FantomLanguage
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * [FantomStructureViewFactory], [FantomStructureViewModel] and [FantomStructureViewElement].
 * Cases not in the root FantomStructureViewTest.
 */
class FantomStructureViewModelTest : BasePlatformTestCase() {

    private fun modelOf(text: String): FantomStructureViewModel {
        val file = myFixture.configureByText("A.fan", text)
        val builder = FantomStructureViewFactory().getStructureViewBuilder(file) as TreeBasedStructureViewBuilder
        val model = builder.createStructureViewModel(myFixture.editor) as FantomStructureViewModel
        Disposer.register(testRootDisposable, model)
        return model
    }

    /** The tree as indented lines of presentable text. */
    private fun dump(element: StructureViewTreeElement, depth: Int = 0): String {
        val line = "  ".repeat(depth) + element.presentation.presentableText + "\n"
        return line + element.children.joinToString("") { dump(it as StructureViewTreeElement, depth + 1) }
    }

    fun testFactoryIsRegistered() {
        assertTrue(LanguageStructureViewBuilder.INSTANCE.forLanguage(FantomLanguage) is FantomStructureViewFactory)
    }

    fun testMixinAndFacetSlots() {
        val model = modelOf(
            """
            mixin M
            {
              abstract Str name()
              Int size := 0
            }
            facet class F
            {
              const Str value := ""
            }
            """.trimIndent()
        )
        assertEquals(
            "A.fan\n  M\n    name(): Str\n    size: Int\n  F\n    value: Str\n",
            dump(model.root),
        )
    }

    fun testStaticInitIsSkipped() {
        val model = modelOf(
            """
            class C
            {
              static { echo("init") }
              static const Int max := 3
            }
            """.trimIndent()
        )
        assertEquals("A.fan\n  C\n    max: Int\n", dump(model.root))
    }

    fun testLeafAndPlusFlags() {
        val model = modelOf("enum class E { a\n  Int n := 0\n  new make() {}\n  Void f() {} }")
        val root = model.root
        val e = root.children.single() as StructureViewTreeElement
        assertTrue(model.isAlwaysShowsPlus(root))
        assertFalse(model.isAlwaysShowsPlus(e))
        assertFalse(model.isAlwaysLeaf(root))
        assertFalse(model.isAlwaysLeaf(e))
        val slots = e.children.map { it as StructureViewTreeElement }
        assertEquals(listOf("a", "n: Int", "new make()", "f(): Void"), slots.map { it.presentation.presentableText })
        slots.forEach { assertTrue(it.presentation.presentableText!!, model.isAlwaysLeaf(it)) }
    }

    fun testAlphaSorterAndSortKeys() {
        val model = modelOf("class B { Void z() {}\n  Void a() {} }")
        assertTrue(model.sorters.contains(Sorter.ALPHA_SORTER))
        val root = model.root as FantomStructureViewElement
        assertEquals("", root.alphaSortKey)
        val b = root.children.single() as FantomStructureViewElement
        assertEquals("B", b.alphaSortKey)
        assertEquals(listOf("z", "a"), b.children.map { (it as FantomStructureViewElement).alphaSortKey })
    }

    fun testElementsNavigate() {
        val model = modelOf("class C { Void f() {} }")
        val c = model.root.children.single() as StructureViewTreeElement
        val f = c.children.single() as StructureViewTreeElement
        assertTrue(c.canNavigate())
        assertTrue(f.canNavigateToSource())
        val text = myFixture.editor.document.text
        f.navigate(true)
        assertEquals(text.indexOf("f()"), myFixture.caretOffset)
    }

    fun testCurrentEditorElementFollowsCaret() {
        val model = modelOf("class C\n{\n  Int a := 1\n  Void f() { echo(<caret>a) }\n}")
        val current = model.currentEditorElement
        assertNotNull(current)
        assertEquals("f(): Void", FantomStructureViewElement(current as com.intellij.psi.PsiElement).presentation.presentableText)
    }

    fun testEmptyFileHasNoChildren() {
        assertEmpty(modelOf("").root.children)
    }
}
