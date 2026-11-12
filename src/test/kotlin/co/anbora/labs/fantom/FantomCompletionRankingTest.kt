package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Order, visibility, insert text and keyword placement of the basic completion list. */
class FantomCompletionRankingTest : BasePlatformTestCase() {

    private val types = """
        class P { Str name := "p"; Int count := 0; Void run() {} Void runWith(Int x, Str y) {} static new make() {} }
        class Other { private Int hidden := 0; protected Int guarded := 0; Int shown := 0; Void show() {} }
        class Base { Void inherited() {} }
        class Sub : Base { Void own() {} }
    """.trimIndent()

    private val objSlots = listOf("echo", "toStr", "equals", "hash", "typeof", "with", "trap", "compare", "isImmutable", "toImmutable")

    private fun complete(body: String, extra: String = types): List<String> {
        myFixture.configureByText("A.fan", "$extra\n$body")
        myFixture.complete(CompletionType.BASIC)
        return myFixture.lookupElementStrings ?: emptyList()
    }

    private fun withSysLibrary(block: () -> Unit): Boolean {
        val home = FantomHome.detect() ?: run { println("skipped: no Fantom home"); return false }
        FantomSettings.getInstance(project).homePath = home.path
        try {
            WriteAction.runAndWait<RuntimeException> {
                ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(EmptyRunnable.getInstance(), false, true)
            }
            block()
        } finally {
            FantomSettings.getInstance(project).homePath = null
        }
        return true
    }

    fun testOwnSlotsInSourceOrderFirst() {
        val items = complete("class C { Void f() { p := P(); p.<caret> } }")
        assertEquals(listOf("name", "count", "run", "runWith"), items.take(4))
        assertFalse("ctor hidden after an instance receiver: $items", items.contains("make"))
    }

    fun testOwnSlotsBeforeObjSlotsWithLibrary() {
        withSysLibrary {
            val items = complete("class C { Void f() { p := P(); p.<caret> } }")
            assertEquals(listOf("name", "count", "run", "runWith"), items.take(4))
            val present = objSlots.filter { it in items }
            assertTrue("Obj slots expected: $items", present.size >= 5)
            val lastOwn = items.indexOf("runWith")
            for (o in present) assertTrue("$o must come after own slots: $items", items.indexOf(o) > lastOwn)
        }
    }

    fun testInheritedAfterOwnBeforeObj() {
        val items = complete("class C { Void f() { s := Sub(); s.<caret> } }")
        assertTrue(items.toString(), items.indexOf("own") < items.indexOf("inherited"))
        withSysLibrary {
            val withLib = complete("class C { Void f() { s := Sub(); s.<caret> } }")
            val echo = withLib.indexOf("echo")
            assertTrue(withLib.toString(), echo > withLib.indexOf("inherited"))
        }
    }

    fun testPrivateSlotsOfAnotherTypeAreAbsent() {
        val items = complete("class C { Void f() { other := Other(); other.<caret> } }")
        assertFalse(items.toString(), items.contains("hidden"))
        assertTrue(items.containsAll(listOf("shown", "show", "guarded")))
        assertTrue("protected sorts below public: $items", items.indexOf("guarded") > items.indexOf("show"))
    }

    fun testPrivateSlotsVisibleOnThis() {
        val items = complete("class C { private Int secret := 0; Void f() { this.<caret> } }")
        assertTrue(items.toString(), items.contains("secret"))
    }

    fun testTypeDotListsCtorBeforeInstanceSlots() {
        val items = complete("class C { Void f() { P.<caret> } }")
        assertTrue(items.toString(), items.indexOf("make") < items.indexOf("run"))
    }

    fun testLocalsBeforeSlotsBeforeTypesBeforeKeywords() {
        val items = complete("class C { Int field := 1; Void f(Str param) { local := 2; <caret> } }")
        val local = items.indexOf("local")
        val param = items.indexOf("param")
        val field = items.indexOf("field")
        val type = items.indexOf("P")
        val keyword = items.indexOf("return")
        assertTrue(items.toString(), local in 0 until param && param < field && field < type && type < keyword)
    }

    fun testNoParamMethodInsertsNameOnly() {
        myFixture.configureByText("A.fan", "$types\nclass C { Void f() { p := P(); p.ru<caret> } }")
        myFixture.complete(CompletionType.BASIC)
        myFixture.lookup?.let { myFixture.finishLookup('\n') }
        myFixture.checkResult("$types\nclass C { Void f() { p := P(); p.run<caret> } }")
    }

    fun testParamMethodInsertsParens() {
        myFixture.configureByText("A.fan", "$types\nclass C { Void f() { p := P(); p.runW<caret> } }")
        myFixture.complete(CompletionType.BASIC)
        myFixture.checkResult("$types\nclass C { Void f() { p := P(); p.runWith(<caret>) } }")
    }

    fun testKeywordsAbsentAfterDot() {
        val items = complete("class C { Void f() { p := P(); p.<caret> } }")
        for (kw in listOf("return", "if", "class", "static", "this", "null")) {
            assertFalse("$kw after dot: $items", items.contains(kw))
        }
    }

    fun testFileLevelOffersTypeKeywords() {
        val items = complete("<caret>")
        assertTrue(items.toString(), items.containsAll(listOf("class", "mixin", "enum", "facet", "using")))
        assertFalse(items.toString(), items.contains("return"))
    }

    fun testTypeBodyOffersModifiers() {
        val items = complete("class C {\n  <caret>\n  Void f() {}\n}")
        assertTrue(items.toString(), items.containsAll(listOf("static", "override", "private")))
        assertFalse(items.toString(), items.contains("return"))
    }
}
