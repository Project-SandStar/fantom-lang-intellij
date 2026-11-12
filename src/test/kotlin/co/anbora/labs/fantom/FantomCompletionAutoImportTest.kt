package co.anbora.labs.fantom

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Auto-import on type completion: a type no `using` makes visible shows its pod as
 * tail text and inserting it adds `using <pod>::<Name>`. Skips without a Fantom install.
 */
class FantomCompletionAutoImportTest : BasePlatformTestCase() {

    private fun item(name: String): LookupElement {
        myFixture.complete(CompletionType.BASIC)
        return myFixture.lookupElements?.firstOrNull { it.lookupString == name }
            ?: throw AssertionError("$name not offered: ${myFixture.lookupElementStrings}")
    }

    private fun tail(item: LookupElement): String? =
        LookupElementPresentation().also { item.renderElement(it) }.tailText

    private fun insert(item: LookupElement) {
        myFixture.lookup.currentItem = item
        myFixture.finishLookup('\n')
    }

    fun testHiddenTypeShowsPodAndInsertsUsing() {
        FantomTestLibrary.with(project) {
            myFixture.configureByText("A.fan", "class A { Void f() { Act<caret> } }")
            val actor = item("Actor")
            assertEquals(" (concurrent)", tail(actor))
            insert(actor)
            myFixture.checkResult("using concurrent::Actor\nclass A { Void f() { Actor<caret> } }")
        }
    }

    fun testVisibleTypeHasNoTailAndAddsNoLine() {
        FantomTestLibrary.with(project) {
            myFixture.configureByText("A.fan", "using concurrent\n\nclass A { Void f() { Act<caret> } }")
            val actor = item("Actor")
            assertNull(tail(actor))
            insert(actor)
            myFixture.checkResult("using concurrent\n\nclass A { Void f() { Actor<caret> } }")
        }
    }

    fun testSysTypeHasNoTail() {
        FantomTestLibrary.with(project) {
            myFixture.configureByText("A.fan", "class A { Void f() { St<caret> } }")
            val str = item("Str")
            assertNull(tail(str))
        }
    }

    // Two matching types, so the lookup stays open and the tail text can be read.
    fun testProjectTypeInOtherPodGetsTail() {
        myFixture.addFileToProject("zz/fan/Zzqx.fan", "class Zzqx {}")
        myFixture.addFileToProject("zz/fan/Zzqy.fan", "class Zzqy {}")
        myFixture.configureByText("A.fan", "class A { Void f() { Zzq<caret> } }")
        val zz = item("Zzqx")
        assertEquals(" (zz)", tail(zz))
        insert(zz)
        myFixture.checkResult("using zz::Zzqx\nclass A { Void f() { Zzqx<caret> } }")
    }

    // One match: completion inserts it at once and the insert handler must still run.
    fun testSingleMatchAutoInsertsUsing() {
        myFixture.addFileToProject("zz/fan/Zzqx.fan", "class Zzqx {}")
        myFixture.configureByText("A.fan", "class A { Void f() { Zzq<caret> } }")
        myFixture.complete(CompletionType.BASIC)
        myFixture.checkResult("using zz::Zzqx\nclass A { Void f() { Zzqx<caret> } }")
    }
}
