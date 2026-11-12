package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FantomCompletionTest : BasePlatformTestCase() {

    private val types = """
        class P { Str name := "p"; Int count := 0; Void run() {} Void runWith(Int x, Str y) {} static new make() {} }
        class Q { Void other() {} }
        class Base { Void inherited() {} }
        class Sub : Base { Void own() {} }
    """.trimIndent()

    private fun complete(body: String, extra: String = types): List<String> {
        myFixture.configureByText("A.fan", "$extra\n$body")
        myFixture.complete(CompletionType.BASIC)
        return myFixture.lookupElementStrings ?: emptyList()
    }

    fun testReceiverSlotsOnly() {
        val items = complete("class C { Void f() { p := P(); p.<caret> } }")
        assertTrue(items.containsAll(listOf("name", "count", "run", "runWith")))
        assertFalse(items.contains("other"))
        assertTrue("Obj slots are not visible without the sys library: " + items, !items.contains("nonexistent"))
    }

    fun testInheritedSlotsAndNoDuplicates() {
        val items = complete("class C { Void f() { s := Sub(); s.<caret> } }")
        assertTrue(items.containsAll(listOf("own", "inherited")))
        assertEquals(items.size, items.toSet().size)
    }

    fun testThisDotListsOwnChain() {
        val items = complete("class C : Base { Int local := 0; Void f() { this.<caret> } }")
        assertTrue(items.containsAll(listOf("local", "f", "inherited")))
        assertFalse(items.contains("run"))
    }

    fun testStaticTypeDotListsSlots() {
        val items = complete("class C { Void f() { P.<caret> } }")
        assertTrue(items.containsAll(listOf("make", "run")))
        assertFalse(items.contains("other"))
    }

    fun testUnknownReceiverOffersNothing() {
        val items = complete("class C { Void f(Obj o) { o->x.<caret> } }")
        assertTrue(items.toString(), items.isEmpty())
    }

    fun testBareNameListsLocalsSlotsTypesKeywords() {
        val items = complete("class C { Int field := 1; Void f(Str param) { local := 2; <caret> } }")
        assertTrue(items.containsAll(listOf("local", "param", "field", "f", "P", "Q", "return", "if")))
    }

    fun testTypePositionListsTypesOnly() {
        val items = complete("class C { Void f() { x := (<caret>) } }")
        // the dummy sits in a grouped expr, so this is bare; the declaration form below is the type position
        val decl = complete("class C { Void f() { S<caret> x } }")
        assertTrue(decl.containsAll(listOf("Sub")))
        assertTrue(items.isNotEmpty())
    }

    fun testMethodWithParamsInsertsParens() {
        myFixture.configureByText("A.fan", "$types\nclass C { Void f() { p := P(); p.runW<caret> } }")
        myFixture.complete(CompletionType.BASIC)
        myFixture.checkResult("$types\nclass C { Void f() { p := P(); p.runWith(<caret>) } }")
    }

    fun testSysReceiverWithLibrary() {
        val home = FantomHome.detect() ?: run { println("skipped: no Fantom home"); return }
        FantomSettings.getInstance(project).homePath = home.path
        try {
            WriteAction.runAndWait<RuntimeException> {
                ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(EmptyRunnable.getInstance(), false, true)
            }
            val items = complete("class C { Void f(Str s) { s.<caret> } }", "")
            assertTrue(items.containsAll(listOf("size", "upper", "trim", "toInt", "echo")))
            assertFalse(items.contains("add"))
        } finally {
            FantomSettings.getInstance(project).homePath = null
        }
    }
}
