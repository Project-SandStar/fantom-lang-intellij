package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.core.psi.FantomCatchDef
import co.anbora.labs.fantom.lang.core.psi.FantomClassDef
import co.anbora.labs.fantom.lang.core.psi.FantomClosureParam
import co.anbora.labs.fantom.lang.core.psi.FantomCtorDef
import co.anbora.labs.fantom.lang.core.psi.FantomEnumDef
import co.anbora.labs.fantom.lang.core.psi.FantomEnumValDef
import co.anbora.labs.fantom.lang.core.psi.FantomFacetDef
import co.anbora.labs.fantom.lang.core.psi.FantomFieldDef
import co.anbora.labs.fantom.lang.core.psi.FantomFile
import co.anbora.labs.fantom.lang.core.psi.FantomLocalDef
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomMixinDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomParam
import co.anbora.labs.fantom.lang.core.psi.FantomSimpleType
import co.anbora.labs.fantom.lang.core.psi.FantomTypes
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.DebugUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Proves that every declaration rule that mixes in `FantomNamedElementImpl`
 * reports the right name: the IDENTIFIER of its own `id` child, never the
 * identifier of a type.
 */
class FantomNamedElementTest : BasePlatformTestCase() {

    // ---- helpers -------------------------------------------------------

    private fun parse(text: String): PsiFile {
        val file = myFixture.configureByText("A.fan", text)
        assertTrue("fixture must create a FantomFile, got ${file.javaClass.name}", file is FantomFile)
        assertFalse("parse errors in:\n$text\n${tree(file)}", PsiTreeUtil.hasErrorElements(file))
        return file
    }

    private fun tree(e: PsiElement): String = DebugUtil.psiToString(e, false)

    private inline fun <reified T : FantomNamedElement> namesOf(file: PsiFile): List<String?> =
        PsiTreeUtil.findChildrenOfType(file, T::class.java).map { it.name }

    private inline fun <reified T : FantomNamedElement> only(file: PsiFile): T {
        val all = PsiTreeUtil.findChildrenOfType(file, T::class.java)
        assertEquals("expected exactly one ${T::class.simpleName} in\n${tree(file)}", 1, all.size)
        return all.first()
    }

    private fun assertIdentifier(e: FantomNamedElement) {
        val id = e.nameIdentifier
        assertNotNull("nameIdentifier of ${e.javaClass.simpleName} in\n${tree(e)}", id)
        assertEquals("nameIdentifier must be an IDENTIFIER leaf", FantomTypes.IDENTIFIER, id!!.node.elementType)
        assertEquals(id.text, e.name)
    }

    // ---- types ---------------------------------------------------------

    fun testClassName() {
        val file = parse("class Foo {}\n")
        val cls = only<FantomClassDef>(file)
        assertEquals("Foo", cls.name)
        assertIdentifier(cls)
    }

    fun testClassNameWithFacetsFlagsAndInheritance() {
        val file = parse("@Js\nabstract const class Bar : Base, Mixin {}\n")
        val cls = only<FantomClassDef>(file)
        assertEquals("Bar", cls.name)
        assertEquals(listOf("Bar"), namesOf<FantomClassDef>(file))
    }

    fun testMixinName() {
        val file = parse("mixin Walkable {}\n")
        val m = only<FantomMixinDef>(file)
        assertEquals("Walkable", m.name)
        assertIdentifier(m)
        assertEmpty(PsiTreeUtil.findChildrenOfType(file, FantomClassDef::class.java))
    }

    fun testFacetClassName() {
        val file = parse("facet class Serializable {}\n")
        val f = only<FantomFacetDef>(file)
        assertEquals("Serializable", f.name)
        assertIdentifier(f)
        assertEmpty(PsiTreeUtil.findChildrenOfType(file, FantomClassDef::class.java))
    }

    fun testEnumClassNameAndValues() {
        val file = parse("enum class Color { a, b, c(1)\n  private new make(Int v := 0) {}\n}\n")
        val e = only<FantomEnumDef>(file)
        assertEquals("Color", e.name)
        assertIdentifier(e)
        assertEmpty(PsiTreeUtil.findChildrenOfType(file, FantomClassDef::class.java))
        val values = PsiTreeUtil.findChildrenOfType(file, FantomEnumValDef::class.java)
        assertEquals(listOf("a", "b", "c"), values.map { it.name })
        values.forEach { assertIdentifier(it) }
    }

    // ---- fields --------------------------------------------------------

    fun testFieldNameSimpleType() {
        val file = parse("class A {\n  Str name := \"x\"\n}\n")
        val field = only<FantomFieldDef>(file)
        assertEquals("name", field.name)
        assertIdentifier(field)
    }

    fun testFieldNameMapType() {
        val file = parse("class A {\n  [Str:Int] m\n}\n")
        val field = only<FantomFieldDef>(file)
        assertEquals("m", field.name)
        assertIdentifier(field)
    }

    fun testFieldNameFuncType() {
        val file = parse("class A {\n  |Int->Str| f\n}\n")
        val field = only<FantomFieldDef>(file)
        assertEquals("f", field.name)
        assertIdentifier(field)
    }

    fun testFieldNameNullableListAndFlags() {
        val file = parse("class A {\n  @Transient private static const Str?[] items := [,]\n}\n")
        val field = only<FantomFieldDef>(file)
        assertEquals("items", field.name)
    }

    // ---- methods, params, ctors ---------------------------------------

    fun testMethodNameAndParams() {
        val file = parse("class A {\n  Void run(Int a, Str? b := \"x\") {}\n}\n")
        val m = only<FantomMethodDef>(file)
        assertEquals("run", m.name)
        assertIdentifier(m)
        val params = PsiTreeUtil.findChildrenOfType(file, FantomParam::class.java)
        assertEquals(listOf("a", "b"), params.map { it.name })
        params.forEach { assertIdentifier(it) }
    }

    fun testMethodNameWithFuncTypeReturnAndParam() {
        val file = parse("class A {\n  |Int->Str| mapper(|Str->Int| g, [Str:Int] h) { g }\n}\n")
        val m = only<FantomMethodDef>(file)
        assertEquals("mapper", m.name)
        assertEquals(listOf("g", "h"), namesOf<FantomParam>(file))
    }

    fun testStaticCtorName() {
        val file = parse("class A {\n  static new make(Int x) {}\n}\n")
        val ctor = only<FantomCtorDef>(file)
        assertEquals("make", ctor.name)
        assertIdentifier(ctor)
        assertEquals(listOf("x"), namesOf<FantomParam>(file))
        assertEmpty(PsiTreeUtil.findChildrenOfType(file, FantomMethodDef::class.java))
    }

    fun testCtorNameWithChain() {
        val file = parse("class A : B {\n  new makeIt(Str s) : super(s) {}\n}\n")
        val ctor = only<FantomCtorDef>(file)
        assertEquals("makeIt", ctor.name)
    }

    // ---- locals, closures, catch --------------------------------------

    fun testLocalNames() {
        val file = parse("class A {\n  Void f() {\n    x := 1\n    Int y := 2\n    Str z\n    echo(z)\n  }\n}\n")
        val locals = PsiTreeUtil.findChildrenOfType(file, FantomLocalDef::class.java)
        assertEquals(listOf("x", "y", "z"), locals.map { it.name })
        locals.forEach { assertIdentifier(it) }
    }

    fun testClosureParamNames() {
        val file = parse("class A {\n  Void f(Int[] list) {\n    list.each |Int v, i| {}\n  }\n}\n")
        val params = PsiTreeUtil.findChildrenOfType(file, FantomClosureParam::class.java)
        assertEquals(listOf("v", "i"), params.map { it.name })
        params.forEach { assertIdentifier(it) }
    }

    fun testCatchName() {
        val file = parse("class A {\n  Void f() {\n    try {\n      echo(1)\n    } catch (Err e) {\n      echo(e)\n    }\n  }\n}\n")
        val c = only<FantomCatchDef>(file)
        assertEquals("e", c.name)
        assertIdentifier(c)
    }

    // ---- offsets, rename, negative ------------------------------------

    fun testMethodTextOffsetPointsAtName() {
        val text = "class A {\n  @Operator static Void run(Int a) {}\n}\n"
        val file = parse(text)
        val m = only<FantomMethodDef>(file)
        assertEquals(text.indexOf("run"), m.textOffset)
        assertTrue("textOffset must not be the start of the facets", m.textOffset != m.textRange.startOffset)
        assertEquals(text.indexOf("@Operator"), m.textRange.startOffset)
    }

    fun testFieldTextOffsetPointsAtName() {
        val text = "class A {\n  private const Str name := \"x\"\n}\n"
        val file = parse(text)
        val f = only<FantomFieldDef>(file)
        assertEquals(text.indexOf("name"), f.textOffset)
    }

    fun testSetNameOnField() {
        val file = parse("class A {\n  Str name := \"x\"\n}\n")
        val field = only<FantomFieldDef>(file)
        WriteCommandAction.runWriteCommandAction(project) { field.setName("title") }
        val after = myFixture.file
        assertFalse("tree has errors after rename:\n${tree(after)}", PsiTreeUtil.hasErrorElements(after))
        assertEquals("class A {\n  Str title := \"x\"\n}\n", after.text)
        val renamed = only<FantomFieldDef>(after)
        assertEquals("title", renamed.name)
        assertEquals("title", renamed.nameIdentifier?.text)
    }

    fun testTypeReferenceIsNotNamedElement() {
        val file = parse("class A {\n  Str name := \"x\"\n}\n")
        val types = PsiTreeUtil.findChildrenOfType(file, FantomSimpleType::class.java)
        assertFalse("expected a FantomSimpleType for Str", types.isEmpty())
        assertEquals("Str", types.first().text)
        types.forEach { assertFalse("${it.text} must not be a FantomNamedElement", it is FantomNamedElement) }
        assertFalse(FantomNamedElement::class.java.isAssignableFrom(FantomSimpleType::class.java))
    }

    fun testAllTwelveClassesAreNamedElements() {
        val classes = listOf(
            FantomClassDef::class.java, FantomMixinDef::class.java, FantomFacetDef::class.java,
            FantomEnumDef::class.java, FantomEnumValDef::class.java, FantomFieldDef::class.java,
            FantomMethodDef::class.java, FantomCtorDef::class.java, FantomParam::class.java,
            FantomLocalDef::class.java, FantomClosureParam::class.java, FantomCatchDef::class.java,
        )
        for (c in classes) {
            assertTrue("${c.simpleName} must extend FantomNamedElement", FantomNamedElement::class.java.isAssignableFrom(c))
        }
    }
}
