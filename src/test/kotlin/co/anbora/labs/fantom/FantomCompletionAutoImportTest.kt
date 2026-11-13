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

    // ---- build.fan depends ----------------------------------------------------------

    private fun podWithDepends(depends: String) {
        myFixture.addFileToProject(
            "foo/build.fan",
            "using build\nclass Build : BuildPod\n{\n  new make()\n  {\n    podName = \"foo\"\n    depends = [$depends]\n  }\n}\n",
        )
        myFixture.addFileToProject("foo/fan/A.fan", "class A { Void f() { Act<caret> } }")
        myFixture.configureFromTempProjectFile("foo/fan/A.fan")
    }

    // In a pod depending on concurrent, Actor comes before any Act* type of a pod outside depends.
    fun testDependedPodTypeFirstAndOthersMarked() {
        FantomTestLibrary.with(project) {
            myFixture.addFileToProject("zz/fan/ActZzq.fan", "class ActZzq {}")
            podWithDepends("\"sys 1.0\", \"concurrent 1.0\"")
            val actor = item("Actor")
            assertEquals(" (concurrent)", tail(actor))
            val names = myFixture.lookupElementStrings!!
            val outside = myFixture.lookupElements!!.filter { tail(it)?.endsWith("(not in depends)") == true }
            assertTrue("a type outside depends is offered: $names", outside.any { it.lookupString == "ActZzq" })
            assertEquals(" (zz) (not in depends)", tail(item("ActZzq")))
            val actorIndex = names.indexOf("Actor")
            for (o in outside) assertTrue("Actor before ${o.lookupString}: $names", actorIndex < names.indexOf(o.lookupString))
        }
    }

    // Without a library: of two project pods, the one in depends ranks first.
    fun testDependedProjectPodFirst() {
        myFixture.addFileToProject("dep/fan/ActZzb.fan", "class ActZzb {}")
        myFixture.addFileToProject("nodep/fan/ActZza.fan", "class ActZza {}")
        podWithDepends("\"sys 1.0\", \"dep 1.0\"")
        myFixture.complete(CompletionType.BASIC)
        val names = myFixture.lookupElementStrings!!
        assertTrue(names.toString(), names.indexOf("ActZzb") in 0 until names.indexOf("ActZza"))
        assertEquals(" (dep)", tail(item("ActZzb")))
        assertEquals(" (nodep) (not in depends)", tail(item("ActZza")))
    }

    // Without a build.fan nothing is marked.
    fun testNoBuildFileNoMark() {
        myFixture.addFileToProject("nodep/fan/ActZza.fan", "class ActZza {}")
        myFixture.addFileToProject("nodep/fan/ActZzb.fan", "class ActZzb {}")
        myFixture.configureByText("A.fan", "class A { Void f() { ActZz<caret> } }")
        assertEquals(" (nodep)", tail(item("ActZza")))
    }
}
