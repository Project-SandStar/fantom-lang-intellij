package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.core.psi.FantomLocalDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.resolve.FantomReference
import co.anbora.labs.fantom.lang.types.FanType
import co.anbora.labs.fantom.lang.types.FantomTypeInference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FantomTypeInferenceTest : BasePlatformTestCase() {

    /** Type of the local named [name] in the configured file. */
    private fun localType(name: String): FanType? {
        val def = PsiTreeUtil.findChildrenOfType(myFixture.file, FantomLocalDef::class.java).first { it.name == name }
        return FantomTypeInference.typeOfDeclaration(def)
    }

    private fun configure(body: String, extraTypes: String = "") {
        myFixture.configureByText("A.fan", "$extraTypes\nclass A { Int count := 0; Str title() { \"t\" } Void f() {\n$body\n} }")
    }

    fun testLiterals() {
        configure("""
            a := 1; b := 1.5f; c := 2.0d; d := "s"; e := 'c'; f := true; g := 5sec; h := `x/y`; i := null; j := 1..3
            k := [1, 2]; l := Str[,]; m := ["a": 1]; n := [Str:Int][:]; o := Str#
        """.trimIndent())
        assertEquals(FanType.INT, localType("a"))
        assertEquals(FanType.FLOAT, localType("b"))
        assertEquals(FanType.DECIMAL, localType("c"))
        assertEquals(FanType.STR, localType("d"))
        assertEquals(FanType.INT, localType("e"))
        assertEquals(FanType.BOOL, localType("f"))
        assertEquals(FanType.DURATION, localType("g"))
        assertEquals(FanType.URI, localType("h"))
        assertEquals(FanType.NULL, localType("i"))
        assertEquals(FanType.RANGE, localType("j"))
        assertEquals(FanType.ListOf(FanType.INT), localType("k"))
        assertEquals(FanType.ListOf(FanType.STR), localType("l"))
        assertEquals(FanType.MapOf(FanType.STR, FanType.INT), localType("m"))
        assertEquals(FanType.MapOf(FanType.STR, FanType.INT), localType("n"))
        assertEquals(FanType.TYPE, localType("o"))
    }

    fun testDeclaredTypesAndSuffixes() {
        configure("Str? a := null; Int[] b := [,]; [Str:Int]? c := null; Str:Obj d := [:]; |Int->Str| e := |Int i->Str| { \"\" }; Str?[] g := [,]")
        assertEquals(FanType.Named("Str", true), localType("a"))
        assertEquals(FanType.ListOf(FanType.INT), localType("b"))
        assertEquals(FanType.MapOf(FanType.STR, FanType.INT, true), localType("c"))
        assertEquals(FanType.MapOf(FanType.STR, FanType.OBJ), localType("d"))
        assertEquals(FanType.Func(listOf(FanType.INT), FanType.STR), localType("e"))
        assertEquals(FanType.ListOf(FanType.Named("Str", true)), localType("g"))
    }

    fun testFromResolvedDeclarations() {
        configure("""
            a := count; b := title; c := title(); d := this; e := (Str)a; f := a as Str; g := a is Str
            h := a > 1 ? "x" : "y"; i := b ?: "z"; j := A(); k := a == 1 && b.isEmpty; l := !f
        """.trimIndent())
        assertEquals(FanType.INT, localType("a"))
        assertEquals(FanType.STR, localType("b"))
        assertEquals(FanType.STR, localType("c"))
        assertEquals(FanType.Named("A"), localType("d"))
        assertEquals(FanType.STR, localType("e"))
        assertEquals(FanType.Named("Str", true), localType("f"))
        assertEquals(FanType.BOOL, localType("g"))
        assertEquals(FanType.STR, localType("h"))
        assertEquals(FanType.STR, localType("i"))
        assertEquals(FanType.Named("A"), localType("j"))
        assertEquals(FanType.BOOL, localType("k"))
        assertEquals(FanType.BOOL, localType("l"))
    }

    fun testChainsThroughOwnTypes() {
        configure(
            "b := B(); c := b.next(); d := b.items[0]; e := b.items; f := b.next().name; g := B.make",
            "class B { Str name := \"\"; Int[] items := [,]; B next() { this } static new make() {} }",
        )
        assertEquals(FanType.Named("B"), localType("b"))
        assertEquals(FanType.Named("B"), localType("c"))
        assertEquals(FanType.INT, localType("d"))
        assertEquals(FanType.ListOf(FanType.INT), localType("e"))
        assertEquals(FanType.STR, localType("f"))
        assertEquals(FanType.Named("B"), localType("g"))
    }

    fun testReceiverTypeNarrowsSlotReference() {
        myFixture.configureByText("A.fan", """
            class P { Void run() {} }
            class Q { Void run() {} }
            class C { Void f() { p := P(); p.r<caret>un } }
        """.trimIndent())
        val ref = myFixture.file.findReferenceAt(myFixture.caretOffset) as FantomReference
        val targets = ref.resolveAll()
        assertEquals(1, targets.size)
        assertEquals("P", FantomDeclarationIndex.enclosingTypeName(targets.single()))
    }

    fun testReceiverNarrowingFollowsSupertypes() {
        myFixture.configureByText("A.fan", """
            class Base { Void go() {} }
            class P : Base {}
            class Q { Void go() {} }
            class C { Void f() { P p := P(); p.g<caret>o } }
        """.trimIndent())
        val ref = myFixture.file.findReferenceAt(myFixture.caretOffset) as FantomReference
        val targets = ref.resolveAll()
        assertEquals(1, targets.size)
        assertEquals("Base", FantomDeclarationIndex.enclosingTypeName(targets.single()))
    }

    fun testUnknownReceiverFallsBackToAllSlots() {
        myFixture.configureByText("A.fan", """
            class P { Void run() {} }
            class Q { Void run() {} }
            class C { Void f(Obj o) { o->foo.r<caret>un } }
        """.trimIndent())
        val ref = myFixture.file.findReferenceAt(myFixture.caretOffset) as FantomReference
        assertEquals(2, ref.resolveAll().size)
    }

    fun testNoTargetDisagreementGivesNull() {
        configure("x := unknownThing; y := x", "")
        assertNull(localType("x"))
        assertNull(localType("y"))
    }
}
