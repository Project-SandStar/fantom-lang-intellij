package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import com.intellij.codeInsight.navigation.actions.GotoTypeDeclarationAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Go to Type Declaration (Cmd+Shift+B) through the declared or inferred type. */
class FantomTypeDeclarationTest : BasePlatformTestCase() {

    private val code = """
        class Bar {}
        class Foo
        {
          Bar? field
          Bar make() { return Bar() }
          Void run(Bar p)
          {
            x := make()
            y := p
            echo(x)
            echo(y)
            echo(field)
          }
        }
    """.trimIndent()

    private fun typesAt(marker: String): List<String> {
        myFixture.configureByText("A.fan", code)
        val offset = code.indexOf(marker)
        assertTrue("no $marker", offset >= 0)
        val targets = GotoTypeDeclarationAction.findSymbolTypes(myFixture.editor, offset).orEmpty()
        return targets.map { (it as FantomNamedElement).name!! }
    }

    fun testInferredLocal() {
        assertEquals(listOf("Bar"), typesAt("x)"))
    }

    fun testLocalFromParam() {
        assertEquals(listOf("Bar"), typesAt("y)"))
    }

    fun testFieldUsage() {
        assertEquals(listOf("Bar"), typesAt("field)"))
    }

    fun testParamDeclaration() {
        assertEquals(listOf("Bar"), typesAt("p)\n"))
    }

    fun testMethodReturnType() {
        assertEquals(listOf("Bar"), typesAt("make()\n"))
    }
}
