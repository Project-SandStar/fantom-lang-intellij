package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.core.psi.FantomClosureParam
import co.anbora.labs.fantom.lang.core.psi.FantomLiteral
import co.anbora.labs.fantom.lang.core.psi.FantomLocalDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomTypes
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomSettings
import co.anbora.labs.fantom.lang.resolve.FantomReference
import co.anbora.labs.fantom.lang.types.FanType
import co.anbora.labs.fantom.lang.types.FantomTypeInference
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex
import java.io.File

/**
 * Types inside closures and it-blocks: `it`, untyped closure params, generic
 * placeholders bound from the receiver, and closure return types.
 */
class FantomClosureTypeTest : BasePlatformTestCase() {

    private var home: File? = null

    override fun tearDown() {
        try {
            if (home != null) FantomSettings.getInstance(project).homePath = null
        } finally {
            super.tearDown()
        }
    }

    /** Enables the Fantom home as a library; false (test skipped) when none is installed. */
    private fun enableLibrary(): Boolean {
        val h = FantomHome.detect() ?: run {
            println("FantomClosureTypeTest: skipped, no Fantom home found")
            return false
        }
        home = h
        FantomSettings.getInstance(project).homePath = h.path
        WriteAction.runAndWait<RuntimeException> {
            ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(EmptyRunnable.getInstance(), false, true)
        }
        FileBasedIndex.getInstance().ensureUpToDate(FantomDeclarationIndex.NAME, project, null)
        return true
    }

    private fun configure(body: String, extraTypes: String = "") {
        myFixture.configureByText("A.fan", "$extraTypes\nclass A { Void f() {\n$body\n} }")
    }

    private fun localType(name: String): FanType? {
        val def = PsiTreeUtil.findChildrenOfType(myFixture.file, FantomLocalDef::class.java).first { it.name == name }
        return FantomTypeInference.typeOfDeclaration(def)
    }

    private fun closureParamType(name: String): FanType? {
        val p = PsiTreeUtil.findChildrenOfType(myFixture.file, FantomClosureParam::class.java).first { it.name == name }
        return FantomTypeInference.typeOfDeclaration(p)
    }

    /** Types of every `it` literal in the file, in source order. */
    private fun itTypes(): List<FanType?> =
        PsiTreeUtil.findChildrenOfType(myFixture.file, FantomLiteral::class.java)
            .filter { it.node.firstChildNode?.elementType == FantomTypes.IT }
            .map { FantomTypeInference.typeOf(it) }

    /** Names of the enclosing types of what the identifier at [offset] resolves to. */
    private fun ownersAt(offset: Int): List<String?> {
        val leaf = myFixture.file.findElementAt(offset)!!
        val ref = leaf.parent.reference as FantomReference
        return ref.resolveAll().map { FantomDeclarationIndex.enclosingTypeName(it) }
    }

    private fun offsetOf(text: String, occurrence: Int = 1): Int {
        var at = -1
        repeat(occurrence) { at = myFixture.file.text.indexOf(text, at + 1) }
        assertTrue("not found: $text", at >= 0)
        return at
    }

    private val classes = "class B { Str name := \"\" }\nclass C { Str name := \"\" }"

    fun testItInCtorBlock() {
        configure("b := B { it.name = \"x\" }", classes)
        assertEquals(listOf<FanType?>(FanType.Named("B")), itTypes())
        assertEquals(FanType.Named("B"), localType("b"))
        assertEquals(listOf("B"), ownersAt(offsetOf("name = ")))
    }

    fun testItInWithBlock() {
        configure("b := B().with { it.name = \"y\" }", classes)
        assertEquals(listOf<FanType?>(FanType.Named("B")), itTypes())
        assertEquals(listOf("B"), ownersAt(offsetOf("name = ")))
    }

    fun testItAfterCallWithArgs() {
        configure("x := D(1) { it.name = \"z\" }", "$classes\nclass D { new make(Int i) {} Str name := \"\" }")
        assertEquals(listOf<FanType?>(FanType.Named("D")), itTypes())
        assertEquals(listOf("D"), ownersAt(offsetOf("name = ")))
    }

    fun testLibraryClosures() {
        if (!enableLibrary()) return
        configure(
            """
            Str[] l := [,]
            l.each |x| { x.size }
            l.each |y, i| { i }
            l.each { it.size }
            m := l.map |s| { s.size }
            f := l.find { it.isEmpty }
            a := l.last
            d := l.dup
            [Str:Int][:].each |v, k| { }
            ks := [Str:Int][:].keys
            g := [Str:Int][:].get("a")
            n := l.map |t| { return t.isEmpty }
            """.trimIndent(),
        )
        assertEquals(FanType.STR, closureParamType("x"))
        assertEquals(listOf("Str"), ownersAt(offsetOf("x.size") + 2))
        assertEquals(FanType.INT, closureParamType("i"))
        assertEquals(listOf<FanType?>(FanType.STR, FanType.STR), itTypes())
        assertEquals(FanType.ListOf(FanType.INT), localType("m"))
        assertEquals(FanType.STR.withNullable(true), localType("f"))
        assertEquals(FanType.STR.withNullable(true), localType("a"))
        assertEquals(FanType.ListOf(FanType.STR), localType("d"))
        assertEquals(FanType.INT, closureParamType("v"))
        assertEquals(FanType.STR, closureParamType("k"))
        assertEquals(FanType.ListOf(FanType.STR), localType("ks"))
        assertEquals(FanType.INT.withNullable(true), localType("g"))
        assertEquals(FanType.ListOf(FanType.BOOL), localType("n"))
    }

    fun testUnknownReceiverIsNull() {
        configure("o->x.each |y| { }; p := o->x.each { it }", classes)
        assertNull(closureParamType("y"))
        assertEquals(listOf<FanType?>(null), itTypes())
        assertNull(localType("p"))
    }
}
