package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.core.psi.FantomLiteral
import co.anbora.labs.fantom.lang.core.psi.FantomTypes
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.resolve.FantomReference
import co.anbora.labs.fantom.lang.types.FanType
import co.anbora.labs.fantom.lang.types.FantomItScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** [FantomItScope.typeOfIt]: `it` in an it-block and in a zero-param closure. Types are in the file; no install needed. */
class FantomItScopeTest : BasePlatformTestCase() {

    private val types = """
        class B { Str name := ""; Int count := 0 }
        class Box { Void each(|B item| f) {} Void with(|B b, Int i| f) {} }
    """.trimIndent()

    private fun configure(body: String) {
        myFixture.configureByText("A.fan", "$types\nclass A { Void f(Box box) {\n$body\n} }")
    }

    private fun itTypes(): List<FanType?> =
        PsiTreeUtil.findChildrenOfType(myFixture.file, FantomLiteral::class.java)
            .filter { it.node.firstChildNode?.elementType == FantomTypes.IT }
            .map { FantomItScope.typeOfIt(it) }

    /** Owner type names of what the name at the first [text] (after `it.`) resolves to. */
    private fun ownersOf(text: String): List<String?> {
        val at = myFixture.file.text.indexOf(text)
        assertTrue("not found: $text", at >= 0)
        val ref = myFixture.file.findElementAt(at)!!.parent.reference as FantomReference
        return ref.resolveAll().map { FantomDeclarationIndex.enclosingTypeName(it) }
    }

    fun testItInCtorItBlockIsTheType() {
        configure("b := B { it.name = \"x\" }")
        assertEquals(listOf<FanType?>(FanType.Named("B")), itTypes())
    }

    fun testItInZeroParamClosureIsTheFuncParam() {
        configure("box.each |->| { echo(it.count) }")
        assertEquals(listOf<FanType?>(FanType.Named("B")), itTypes())
        assertEquals(listOf<String?>("B"), ownersOf("count)"))
    }

    fun testItInBareBlockArgIsTheFuncParam() {
        configure("box.each { echo(it.name) }")
        assertEquals(listOf<FanType?>(FanType.Named("B")), itTypes())
        assertEquals(listOf<String?>("B"), ownersOf("name)"))
    }

    fun testClosureWithParamsDoesNotBindIt() {
        configure("box.with |b, i| { echo(it) }")
        assertEquals(listOf<FanType?>(null), itTypes())
    }

    fun testItOutsideAnyBlockIsNull() {
        configure("echo(it)")
        assertEquals(listOf<FanType?>(null), itTypes())
    }
}
