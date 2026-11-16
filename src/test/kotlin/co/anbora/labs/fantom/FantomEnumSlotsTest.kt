package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.core.psi.FantomEnumDef
import co.anbora.labs.fantom.lang.core.psi.FantomFieldDef
import co.anbora.labs.fantom.lang.core.psi.FantomLocalDef
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.resolve.FantomReference
import co.anbora.labs.fantom.lang.types.FanType
import co.anbora.labs.fantom.lang.types.FantomTypeInference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The slots the compiler adds to every enum, `static const T[] vals` and
 * `static new fromStr(Str name, Bool checked := true)`, and a private native method
 * called from a ctor above it. No install needed: `sys` types are declared here.
 */
class FantomEnumSlotsTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("sys/fan/Obj.fan", "class Obj { virtual Str toStr() { \"\" } }")
        myFixture.addFileToProject("sys/fan/Enum.fan", "abstract const class Enum { Str name() { \"\" } Int ordinal() { 0 } }")
        myFixture.addFileToProject("sys/fan/Str.fan", "const class Str { Int size() { 0 } }")
        myFixture.addFileToProject("sys/fan/Bool.fan", "const class Bool {}")
        myFixture.addFileToProject("sys/fan/Int.fan", "const class Int {}")
        myFixture.addFileToProject("sys/fan/List.fan", "class List { V? find(|V,Int->Bool| c) { null } Int size() { 0 } }")
        // Another type with a `vals` field and a `fromStr` method: they must not be picked.
        myFixture.addFileToProject("other/fan/Other.fan",
            "class Other { static const Str[] vals := Str[,]; static Other fromStr(Str s) { Other() } }")
        myFixture.addFileToProject("a/fan/Color.fan", "enum class Color { red, green, blue\n  Bool isRed() { this === red } }")
    }

    private fun refAtCaret(): FantomReference {
        val ref = myFixture.file.findReferenceAt(myFixture.caretOffset)
        assertNotNull("no reference at caret", ref)
        return ref as FantomReference
    }

    private fun assertResolvesToEnum(enumName: String) {
        val targets = refAtCaret().resolveAll()
        assertEquals("targets: $targets", 1, targets.size)
        val t = targets[0]
        assertTrue("not the enum: $t", t is FantomEnumDef)
        assertEquals(enumName, t.name)
    }

    private fun localType(name: String): FanType? {
        val def = PsiTreeUtil.findChildrenOfType(myFixture.file, FantomLocalDef::class.java).first { it.name == name }
        return FantomTypeInference.typeOfDeclaration(def)
    }

    fun testBareValsInsideEnum() {
        myFixture.configureByText("Size.fan", """
            enum class Size { small, large
              static Size? first() { v := va<caret>ls.find { true }; return v }
            }
        """.trimIndent())
        assertResolvesToEnum("Size")
    }

    fun testBareFromStrInsideEnum() {
        myFixture.configureByText("Size.fan", """
            enum class Size { small, large
              static Size? decode(Str s) { fromS<caret>tr(s, false) }
            }
        """.trimIndent())
        assertResolvesToEnum("Size")
    }

    fun testValsFromOutside() {
        myFixture.configureByText("A.fan", "class A { Void f() { x := Color.va<caret>ls } }")
        assertResolvesToEnum("Color")
    }

    fun testFromStrFromOutside() {
        myFixture.configureByText("A.fan", "class A { Void f() { x := Color.fromS<caret>tr(\"red\") } }")
        assertResolvesToEnum("Color")
    }

    fun testTypes() {
        myFixture.configureByText("A.fan", """
            class A { Void f() {
              all := Color.vals
              one := Color.fromStr("red")
              lax := Color.fromStr("x", false)
              first := Color.vals.find { true }
              other := Other.vals
            } }
        """.trimIndent())
        assertEquals(FanType.ListOf(FanType.Named("Color")), localType("all"))
        assertEquals(FanType.Named("Color"), localType("one"))
        assertEquals(FanType.Named("Color"), localType("lax"))
        assertEquals(FanType.Named("Color", nullable = true), localType("first"))
        // A real `vals` field keeps its declared type.
        assertEquals(FanType.ListOf(FanType.STR), localType("other"))
    }

    fun testBareTypesInsideEnum() {
        myFixture.configureByText("Size.fan", """
            enum class Size { small, large
              Void f() { all := vals; one := fromStr("small") }
            }
        """.trimIndent())
        assertEquals(FanType.ListOf(FanType.Named("Size")), localType("all"))
        assertEquals(FanType.Named("Size"), localType("one"))
    }

    fun testDeclaredSlotWins() {
        myFixture.configureByText("Size.fan", """
            enum class Size { small, large
              static Str fromStr(Str s) { s }
              Void f() { fromS<caret>tr("x") }
            }
        """.trimIndent())
        val targets = refAtCaret().resolveAll()
        assertEquals(1, targets.size)
        assertTrue(targets[0] is FantomMethodDef)
    }

    fun testNotOnClass() {
        myFixture.configureByText("A.fan", "class B { Void f() { va<caret>ls } }")
        // Not an enum: a bare `vals` must not resolve to any type (only Other.vals is in scope as a slot).
        val targets = refAtCaret().resolveAll()
        assertTrue("targets: $targets", targets.none { it is FantomEnumDef })
    }

    fun testOtherValsStillResolvesToItsField() {
        myFixture.configureByText("A.fan", "class A { Void f() { x := Other.va<caret>ls } }")
        val targets = refAtCaret().resolveAll()
        assertEquals(1, targets.size)
        assertTrue(targets[0] is FantomFieldDef)
    }

    fun testCompletionAfterTypeName() {
        myFixture.configureByText("A.fan", "class A { Void f() { Color.<caret> } }")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("items: $items", "vals" in items)
        assertTrue("items: $items", "fromStr" in items)
        assertTrue("items: $items", "red" in items)
    }

    fun testCompletionBareInsideEnum() {
        myFixture.configureByText("Size.fan", "enum class Size { small, large\n  Void f() { va<caret> }\n}")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings
        // One match is inserted at once; several show a list.
        if (items == null) assertTrue(myFixture.editor.document.text.contains("vals"))
        else assertTrue("items: $items", "vals" in items)
    }

    fun testNoCompletionOnClass() {
        myFixture.configureByText("A.fan", "class B { static Void go() {} }\nclass A { Void f() { B.<caret> } }")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings ?: emptyList()
        assertFalse("items: $items", "vals" in items)
        assertFalse("items: $items", "fromStr" in items)
    }

    /** dom::Elem: `new make(..) { _make(..) }` calls a `private native` method declared below the ctor. */
    fun testPrivateNativeMethodBelowCtor() {
        myFixture.configureByText("Elem.fan", """
            class Elem
            {
              new make(Str tagName := "div", Uri? ns := null) { _ma<caret>ke(tagName, ns) }

              private native Void _make(Str tagName, Uri? ns)
            }
        """.trimIndent())
        val ref = refAtCaret()
        assertEquals(FantomReference.Kind.BARE, ref.kind)
        val targets: List<FantomNamedElement> = ref.resolveAll()
        assertEquals(1, targets.size)
        assertTrue(targets[0] is FantomMethodDef)
        assertEquals("_make", targets[0].name)
    }

    /** A `_Name` with an uppercase letter after the underscores is still a type. */
    fun testUnderscoreTypeStillResolves() {
        myFixture.addFileToProject("a/fan/Hidden.fan", "internal class _Hidden { new make() {} }")
        myFixture.configureByText("A.fan", "class A { Void f() { x := _Hid<caret>den() } }")
        val ref = refAtCaret()
        assertEquals(FantomReference.Kind.TYPE, ref.kind)
        assertEquals("_Hidden", ref.resolveAll().single().name)
    }
}
