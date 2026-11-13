package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.core.psi.FantomClosureParam
import co.anbora.labs.fantom.lang.core.psi.FantomLocalDef
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomSettings
import co.anbora.labs.fantom.lang.resolve.FantomReference
import co.anbora.labs.fantom.lang.types.FanType
import co.anbora.labs.fantom.lang.types.FantomTypeInference
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex
import java.io.File

/**
 * Typing gaps closed in r23: it-block field assignment, closures handed to
 * constructors, facet values and generic sys methods.
 */
class FantomTypingGapsTest : BasePlatformTestCase() {

    private var home: File? = null

    override fun tearDown() {
        try {
            if (home != null) FantomSettings.getInstance(project).homePath = null
        } finally {
            super.tearDown()
        }
    }

    private fun enableLibrary(): Boolean {
        val h = FantomHome.detect() ?: run {
            println("FantomTypingGapsTest: skipped, no Fantom home found")
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
        myFixture.configureByText("A.fan", "$extraTypes\nclass A { Str name := \"\"\nVoid f() {\n$body\n} }")
    }

    private fun closureParamType(name: String): FanType? {
        val p = PsiTreeUtil.findChildrenOfType(myFixture.file, FantomClosureParam::class.java).first { it.name == name }
        return FantomTypeInference.typeOfDeclaration(p)
    }

    private fun refAt(offset: Int): FantomReference =
        myFixture.file.findElementAt(offset)!!.parent.reference as FantomReference

    /** Names of the enclosing types of what the identifier at [offset] resolves to. */
    private fun ownersAt(offset: Int): List<String?> =
        refAt(offset).resolveAll().map { FantomDeclarationIndex.enclosingTypeName(it) }

    private fun offsetOf(text: String, occurrence: Int = 1): Int {
        var at = -1
        repeat(occurrence) { at = myFixture.file.text.indexOf(text, at + 1) }
        assertTrue("not found: $text", at >= 0)
        return at
    }

    private val classes = "class B { Str name := \"\"\nInt size }"

    // ---- 1. it-block field assignment ----

    fun testBareAssignInCtorBlockResolvesToItField() {
        configure("b := B { name = \"x\" }", classes)
        assertEquals(listOf("B"), ownersAt(offsetOf("name = ")))
    }

    fun testBareAssignInTrailingItBlock() {
        configure("b := B(); b.with { size = 2 }\nx := name", classes)
        assertEquals(listOf("B"), ownersAt(offsetOf("size = ")))
        // Outside the it-block the enclosing type's field wins.
        assertEquals(listOf("A"), ownersAt(offsetOf("x := name") + 5))
    }

    fun testBareReadInItBlockKeepsEnclosingType() {
        configure("b := B { echo(name) }", classes)
        assertEquals(listOf("A"), ownersAt(offsetOf("name)")))
    }

    fun testLocalWinsOverItField() {
        configure("Str name := \"\"\nb := B { name = \"x\" }", classes)
        assertTrue(refAt(offsetOf("name = ")).resolveAll().single() is FantomLocalDef)
    }

    fun testMapItBlockOrdered() {
        if (!enableLibrary()) return
        configure("m := Str:Obj?[:] { ordered = true }")
        assertEquals(listOf("Map"), ownersAt(offsetOf("ordered")))
    }

    // ---- 2. closures handed to constructors ----

    private val ctorClasses = """
        class D { new make(Int i, |Str s, Int n| f) {} }
        class E { new make(|This| f) {} Int size }
        class G { new fromList(|Int k| f) {} }
    """.trimIndent()

    fun testTrailingClosureOnCtorCall() {
        configure("D(1) |x, y| { x.size }", ctorClasses)
        assertEquals(FanType.STR, closureParamType("x"))
        assertEquals(FanType.INT, closureParamType("y"))
    }

    fun testClosureArgOnCtorCall() {
        configure("D(1, |p| { })", ctorClasses)
        assertEquals(FanType.STR, closureParamType("p"))
    }

    fun testCtorThisParamBindsType() {
        configure("E(|e| { e.size })", ctorClasses)
        assertEquals(FanType.Named("E"), closureParamType("e"))
        assertEquals(listOf("E"), ownersAt(offsetOf("size })")))
    }

    // ---- 3. facet values ----

    private val facet = "facet class Fac { const Int count := 0\nconst Str label := \"\"\nstatic const Int max := 9 }\n"

    fun testFacetValueResolvesToFacetField() {
        myFixture.configureByText("A.fan", "$facet@Fac { count = 1; label = \"a\" }\nclass A {}")
        assertEquals(listOf("Fac"), ownersAt(offsetOf("count = ")))
        assertEquals(listOf("Fac"), ownersAt(offsetOf("label = ")))
    }

    fun testFacetValueRenamesWithField() {
        myFixture.configureByText("A.fan", "$facet@Fac { count = 1 }\nclass A {}")
        myFixture.editor.caretModel.moveToOffset(offsetOf("count :="))
        myFixture.renameElementAtCaret("total")
        assertTrue(myFixture.file.text, myFixture.file.text.contains("@Fac { total = 1 }"))
    }

    fun testFacetValueCompletion() {
        myFixture.configureByText("A.fan", "$facet@Fac { co<caret> }\nclass A {}")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings
        // A single match is inserted at once.
        if (items == null) assertTrue(myFixture.file.text.contains("@Fac { count"))
        else assertTrue("$items", "count" in items)
        myFixture.configureByText("B.fan", "$facet@Fac { <caret> }\nclass B {}")
        myFixture.completeBasic()
        val all = myFixture.lookupElementStrings.orEmpty()
        assertTrue("$all", "count" in all && "label" in all && "max" !in all)
    }

    // ---- 6. unknown receiver hint ----

    fun testUnknownReceiverText() {
        configure("x := zzqNope()\nx.ab")
        val at = myFixture.file.findElementAt(offsetOf("x.ab") + 2)!!
        assertEquals(
            "Type of 'x' unknown: declare it (Str x := ...) to see its slots",
            co.anbora.labs.fantom.ide.completion.FantomCompletionContributor.unknownReceiverText(at),
        )
    }

    fun testKnownReceiverHasNoText() {
        configure("Str s := \"\"\ns.ab\nthis.ab")
        val file = myFixture.file
        assertNull(co.anbora.labs.fantom.ide.completion.FantomCompletionContributor.unknownReceiverText(file.findElementAt(offsetOf("s.ab") + 2)!!))
        assertNull(co.anbora.labs.fantom.ide.completion.FantomCompletionContributor.unknownReceiverText(file.findElementAt(offsetOf("this.ab") + 5)!!))
    }

    fun testOnlyCtorWhenNoMake() {
        configure("G(|k| { })", ctorClasses)
        assertEquals(FanType.INT, closureParamType("k"))
    }
}
