package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.core.psi.FantomClassDef
import co.anbora.labs.fantom.lang.core.psi.FantomFieldDef
import co.anbora.labs.fantom.lang.core.psi.FantomLocalDef
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomParam
import co.anbora.labs.fantom.lang.resolve.FantomReference
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FantomReferenceTest : BasePlatformTestCase() {

    private fun resolveAtCaret(): FantomNamedElement? {
        val ref = myFixture.file.findReferenceAt(myFixture.caretOffset)
        assertNotNull("no reference at caret", ref)
        return ref!!.resolve() as? FantomNamedElement
    }

    fun testLocalResolvesToNearestEarlierDef() {
        myFixture.configureByText("A.fan", """
            class A { Void f() { xx := 1; y := x<caret>x + 1 } }
        """.trimIndent())
        val t = resolveAtCaret()
        assertTrue(t is FantomLocalDef); assertEquals("xx", t!!.name)
    }

    fun testParamResolves() {
        myFixture.configureByText("A.fan", """
            class A { Void f(Int count) { echo(co<caret>unt) } }
        """.trimIndent())
        assertTrue(resolveAtCaret() is FantomParam)
    }

    fun testLocalDeclaredLaterIsNotVisible() {
        myFixture.configureByText("A.fan", """
            class A { Void f() { echo(z<caret>z); zz := 1 } }
        """.trimIndent())
        assertNull(resolveAtCaret())
    }

    fun testBareNameFallsBackToFieldOfEnclosingType() {
        myFixture.configureByText("A.fan", """
            class A { Int total := 0; Void f() { tot<caret>al = 1 } }
        """.trimIndent())
        val t = resolveAtCaret()
        assertTrue(t is FantomFieldDef); assertEquals("total", t!!.name)
    }

    fun testSlotOnSuperTypeThroughIndex() {
        myFixture.addFileToProject("B.fan", "class Base { Void hello() {} }")
        myFixture.configureByText("A.fan", """
            class A : Base { Void f() { hel<caret>lo } }
        """.trimIndent())
        val t = resolveAtCaret()
        assertTrue(t is FantomMethodDef); assertEquals("hello", t!!.name)
    }

    fun testTypeNameResolvesAcrossFiles() {
        myFixture.addFileToProject("Other.fan", "class Other {}")
        myFixture.configureByText("A.fan", """
            class A { Oth<caret>er o }
        """.trimIndent())
        assertTrue(resolveAtCaret() is FantomClassDef)
    }

    fun testStaticCallNarrowsToOwner() {
        myFixture.addFileToProject("B.fan", "class B { static Void go() {} }\nclass C { static Void go() {} }")
        myFixture.configureByText("A.fan", """
            class A { Void f() { B.g<caret>o } }
        """.trimIndent())
        val ref = myFixture.file.findReferenceAt(myFixture.caretOffset) as FantomReference
        val targets = ref.resolveAll()
        assertEquals(1, targets.size)
        assertEquals("B", co.anbora.labs.fantom.lang.index.FantomDeclarationIndex.enclosingTypeName(targets[0]))
    }

    fun testThisDotNarrowsToEnclosingType() {
        myFixture.addFileToProject("B.fan", "class B { Int cur := 0 }")
        myFixture.configureByText("A.fan", """
            class A { Int cur := 1; Void f() { this.c<caret>ur = 2 } }
        """.trimIndent())
        val ref = myFixture.file.findReferenceAt(myFixture.caretOffset) as FantomReference
        val targets = ref.resolveAll()
        assertEquals(1, targets.size)
        assertEquals("A.fan", targets.single().containingFile.name)
    }

    fun testDeclarationNameHasNoReference() {
        myFixture.configureByText("A.fan", "class A { Int fo<caret>o := 1 }")
        assertNull(myFixture.file.findReferenceAt(myFixture.caretOffset))
    }

    fun testRenameFollowsLocalUses() {
        myFixture.configureByText("A.fan", """
            class A { Void f() { x<caret>x := 1; echo(xx); xx = 2 } }
        """.trimIndent())
        myFixture.renameElementAtCaret("count")
        myFixture.checkResult("class A { Void f() { count := 1; echo(count); count = 2 } }")
    }
}
