package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.core.psi.FantomClassDef
import co.anbora.labs.fantom.lang.core.psi.FantomFieldDef
import co.anbora.labs.fantom.lang.core.psi.FantomLocalDef
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomParam
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.DebugUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Go to declaration (Ctrl+click, Cmd+B) through [co.anbora.labs.fantom.lang.resolve.FantomReference].
 * Each test checks two paths: the reference at the caret resolves, and
 * `elementAtCaret` (what the goto action uses) lands on the same declaration.
 */
class FantomGotoDeclarationTest : BasePlatformTestCase() {

    private inline fun <reified T : FantomNamedElement> assertGoto(name: String, fileName: String? = null) {
        val ref = myFixture.getReferenceAtCaretPositionWithAssertion()
        val resolved = ref.resolve()
        assertNotNull("reference did not resolve\n" + tree(), resolved)
        assertTrue("resolved to ${resolved!!.javaClass.simpleName}\n" + tree(), resolved is T)
        assertEquals(name, (resolved as FantomNamedElement).name)

        val target: PsiElement = myFixture.elementAtCaret
        assertTrue("elementAtCaret is ${target.javaClass.simpleName}", target is T)
        assertEquals(name, (target as FantomNamedElement).name)
        assertSame(resolved, target)
        if (fileName != null) assertEquals(fileName, target.containingFile.name)
    }

    private fun tree() = DebugUtil.psiToString(myFixture.file, false)

    fun testLocal() {
        myFixture.configureByText("A.fan", """
            class A { Void f() { total := 1; echo(tot<caret>al) } }
        """.trimIndent())
        assertGoto<FantomLocalDef>("total")
    }

    fun testParam() {
        myFixture.configureByText("A.fan", """
            class A { Void f(Int count) { echo(cou<caret>nt) } }
        """.trimIndent())
        assertGoto<FantomParam>("count")
    }

    fun testFieldOnThis() {
        myFixture.configureByText("A.fan", """
            class A { Int total := 0; Void f() { this.tot<caret>al = 1 } }
        """.trimIndent())
        assertGoto<FantomFieldDef>("total")
    }

    fun testMethodCall() {
        myFixture.configureByText("A.fan", """
            class A { Void foo() {} Void f() { fo<caret>o() } }
        """.trimIndent())
        assertGoto<FantomMethodDef>("foo")
    }

    fun testStaticCall() {
        myFixture.addFileToProject("B.fan", "class B { static Void go() {} }")
        myFixture.configureByText("A.fan", """
            class A { Void f() { B.g<caret>o } }
        """.trimIndent())
        assertGoto<FantomMethodDef>("go", "B.fan")
    }

    fun testTypeInFieldDeclaration() {
        myFixture.configureByText("A.fan", """
            class Other {}
            class A { Oth<caret>er o }
        """.trimIndent())
        assertGoto<FantomClassDef>("Other")
    }

    fun testTypeInIsCheck() {
        myFixture.configureByText("A.fan", """
            class Other {}
            class A { Bool f(Obj x) { return x is Oth<caret>er } }
        """.trimIndent())
        assertGoto<FantomClassDef>("Other")
    }

    fun testTypeInCast() {
        myFixture.configureByText("A.fan", """
            class Other {}
            class A { Void f(Obj x) { o := (Oth<caret>er)x } }
        """.trimIndent())
        assertGoto<FantomClassDef>("Other")
    }

    fun testCrossFileClass() {
        myFixture.addFileToProject("Other.fan", "class Other {}")
        myFixture.configureByText("A.fan", """
            class A { Void f() { o := Oth<caret>er() } }
        """.trimIndent())
        assertGoto<FantomClassDef>("Other", "Other.fan")
    }
}
