package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.refactoring.FantomRenameSlotProcessor
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** [FantomRenameSlotProcessor]: which slots have an override family, the family itself, and the rename. No install needed. */
class FantomRenameSlotProcessorTest : BasePlatformTestCase() {

    private val code = """
        mixin M { abstract Str title(); Void mixinPlain() {} }
        class A : M { override Str title() { "a" } virtual Int size() { 0 } Int plain() { 0 } private Void hidden() {} static Void util() {} }
        class B : A { override Int size() { 1 } }
        class C : B { override Int size() { 2 } }
        class D { Int size() { 3 } }
    """.trimIndent()

    private fun slot(owner: String, name: String): FantomNamedElement =
        PsiTreeUtil.findChildrenOfType(myFixture.file, FantomNamedElement::class.java)
            .first { it.name == name && FantomDeclarationIndex.enclosingTypeName(it) == owner }

    private fun owners(list: List<FantomNamedElement>) = list.map { FantomDeclarationIndex.enclosingTypeName(it) }

    override fun setUp() {
        super.setUp()
        myFixture.configureByText("H.fan", code)
    }

    fun testIsFamilySlot() {
        assertTrue(FantomRenameSlotProcessor.isFamilySlot(slot("A", "size")))      // virtual
        assertTrue(FantomRenameSlotProcessor.isFamilySlot(slot("B", "size")))      // override
        assertTrue(FantomRenameSlotProcessor.isFamilySlot(slot("M", "title")))     // abstract
        assertTrue(FantomRenameSlotProcessor.isFamilySlot(slot("M", "mixinPlain"))) // any mixin slot
        assertFalse(FantomRenameSlotProcessor.isFamilySlot(slot("A", "plain")))
        assertFalse(FantomRenameSlotProcessor.isFamilySlot(slot("A", "hidden")))
        assertFalse(FantomRenameSlotProcessor.isFamilySlot(slot("A", "util")))
        assertFalse(FantomRenameSlotProcessor.isFamilySlot(slot("D", "size")))
    }

    fun testFamilyFromBaseReachesAllOverrides() {
        val family = FantomRenameSlotProcessor.family(slot("A", "size"))
        assertEquals("A", owners(family).first())
        assertSameElements(owners(family), "A", "B", "C")
    }

    fun testFamilyFromLeafGoesUpAndDown() {
        assertSameElements(owners(FantomRenameSlotProcessor.family(slot("C", "size"))), "A", "B", "C")
        assertSameElements(owners(FantomRenameSlotProcessor.family(slot("A", "title"))), "M", "A")
    }

    fun testNonFamilySlotIsAlone() {
        assertEquals(listOf("D"), owners(FantomRenameSlotProcessor.family(slot("D", "size"))))
    }

    fun testProjectSlotsAreNotReadOnly() {
        assertFalse(FantomRenameSlotProcessor.isReadOnly(slot("A", "size")))
    }

    fun testRenameVirtualRenamesOverridesOnly() {
        myFixture.configureByText("H.fan", code.replaceFirst("virtual Int size()", "virtual Int si<caret>ze()"))
        myFixture.renameElementAtCaret("count")
        myFixture.checkResult(
            code.replace("Int size()", "Int count()").replace("class D { Int count() { 3 } }", "class D { Int size() { 3 } }"),
        )
    }
}
