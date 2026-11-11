package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.parameters.FantomParameterInfoHandler
import co.anbora.labs.fantom.lang.core.psi.FantomArgs
import co.anbora.labs.fantom.lang.core.psi.FantomCtorDef
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext
import com.intellij.testFramework.utils.parameterInfo.MockParameterInfoUIContext
import com.intellij.testFramework.utils.parameterInfo.MockUpdateParameterInfoContext

/** Parameter Info (Cmd+P) for Fantom calls: items, rendered text, highlighted parameter. */
class FantomParameterInfoTest : BasePlatformTestCase() {

    private class Shown(val items: List<FantomNamedElement>, val current: Int, val text: String, val highlighted: String?)

    private fun show(text: String): Shown {
        myFixture.configureByText("A.fan", text.trimIndent())
        val handler = FantomParameterInfoHandler()
        val create = MockCreateParameterInfoContext(myFixture.editor, myFixture.file)
        val args = handler.findElementForParameterInfo(create)
        assertNotNull("no args at caret", args)
        val shown = create.itemsToShow ?: emptyArray()
        val items = shown.map { it as FantomNamedElement }

        val update = MockUpdateParameterInfoContext(myFixture.editor, myFixture.file, shown)
        val updateArgs = handler.findElementForUpdatingParameterInfo(update)
        assertNotNull("no args for update", updateArgs)
        update.parameterOwner = updateArgs
        handler.updateParameterInfo(updateArgs!!, update)

        val ui = MockParameterInfoUIContext<FantomArgs>(args)
        ui.currentParameterIndex = update.currentParameter
        handler.updateUI(items.first(), ui)
        val hl = if (ui.highlightStart >= 0) ui.text.substring(ui.highlightStart, ui.highlightEnd) else null
        return Shown(items, update.currentParameter, ui.text, hl)
    }

    fun testSecondArgumentIsHighlighted() {
        val s = show("""
            class A {
              Void f(Int a, Int b) {}
              Void g() { f(1, <caret>2) }
            }
        """)
        assertEquals(1, s.items.size)
        assertTrue(s.items.single() is FantomMethodDef)
        assertEquals(1, s.current)
        assertEquals("Int a, Int b", s.text)
        assertEquals("Int b", s.highlighted)
    }

    fun testFirstArgumentAndDefaultValue() {
        val s = show("""
            class A {
              Void f(Type a, Str? b := "x") {}
              Void g() { f(<caret>) }
            }
        """)
        assertEquals(0, s.current)
        assertEquals("Type a, Str? b := \"x\"", s.text)
        assertEquals("Type a", s.highlighted)
    }

    fun testCtorCallOnType() {
        myFixture.addFileToProject("B.fan", "class B { new make(Int size) {} new makeEmpty() {} }")
        val s = show("""
            class A { Void g() { b := B(<caret>1) } }
        """)
        assertEquals(2, s.items.size)
        assertTrue(s.items.all { it is FantomCtorDef })
        assertEquals("Int size", s.text)
        assertEquals("Int size", s.highlighted)
    }

    fun testDotCallResolvesSlot() {
        myFixture.addFileToProject("B.fan", "class B { Void putPair(Str k, Obj v) {} }")
        val s = show("""
            class A { Void g(B b) { b.putPair("k", <caret>1) } }
        """)
        assertEquals(1, s.items.size)
        assertEquals("Str k, Obj v", s.text)
        assertEquals("Obj v", s.highlighted)
    }

    fun testNoHintOutsideParens() {
        myFixture.configureByText("A.fan", "class A { Void f(Int a) {} Void g() { f<caret>(1) } }")
        val create = MockCreateParameterInfoContext(myFixture.editor, myFixture.file)
        assertNull(FantomParameterInfoHandler().findElementForParameterInfo(create))
    }
}
