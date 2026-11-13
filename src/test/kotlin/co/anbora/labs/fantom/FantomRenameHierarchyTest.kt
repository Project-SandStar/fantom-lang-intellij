package co.anbora.labs.fantom

import com.intellij.refactoring.rename.inplace.MemberInplaceRenameHandler
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.testFramework.fixtures.CodeInsightTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Renaming a slot renames its whole override family. */
class FantomRenameHierarchyTest : BasePlatformTestCase() {

    private val callers = """
        class U {
          Void f(Named n, A a, B b, C c) {
            echo(n.name)
            echo(a.name())
            echo(b.name)
            echo(c.name)
          }
        }
    """.trimIndent()

    private fun hierarchy(caretIn: String): String {
        fun m(owner: String, text: String) = if (owner == caretIn) text.replaceFirst("name", "na<caret>me") else text
        return listOf(
            m("Named", "mixin Named { abstract Str name() }"),
            m("A", "class A : Named { override Str name() { \"a\" } }"),
            m("B", "class B : Named { override Str name() { \"b\" } }"),
            m("C", "class C : A { override Str name() { \"c\" } }"),
        ).joinToString("\n")
    }

    private val renamed = """
        mixin Named { abstract Str title() }
        class A : Named { override Str title() { "a" } }
        class B : Named { override Str title() { "b" } }
        class C : A { override Str title() { "c" } }
    """.trimIndent()

    private fun checkAllRenamed() {
        myFixture.checkResult("H.fan", renamed, true)
        myFixture.checkResult("U.fan", callers.replace(".name", ".title"), true)
    }

    fun testRenameAtMixinRenamesFamily() {
        myFixture.addFileToProject("U.fan", callers)
        myFixture.configureByText("H.fan", hierarchy("Named"))
        myFixture.renameElementAtCaret("title")
        checkAllRenamed()
    }

    fun testRenameAtLeafOverrideWithYesRenamesFamily() {
        myFixture.addFileToProject("U.fan", callers)
        myFixture.configureByText("H.fan", hierarchy("C"))
        myFixture.renameElementAtCaret("title")
        checkAllRenamed()
    }

    fun testInplaceRenameAtMixinRenamesFamily() {
        myFixture.addFileToProject("U.fan", callers)
        myFixture.configureByText("H.fan", hierarchy("Named"))
        CodeInsightTestUtil.doInlineRename(MemberInplaceRenameHandler(), "title", myFixture)
        checkAllRenamed()
    }

    fun testOverrideOfLibrarySlotIsRefused() {
        FantomTestLibrary.with(project) {
            myFixture.configureByText("P.fan", "class P { override Str to<caret>Str() { \"p\" } }")
            try {
                myFixture.renameElementAtCaret("show")
                fail("rename should be refused")
            } catch (e: CommonRefactoringUtil.RefactoringErrorHintException) {
                assertTrue(e.message, e.message!!.contains("'toStr' overrides sys::Obj.toStr in a library and cannot be renamed"))
            }
            myFixture.checkResult("class P { override Str toStr() { \"p\" } }")
        }
    }

    fun testConflictWhenSubclassHasNewName() {
        myFixture.configureByText("H.fan", hierarchy("Named") + "\nclass D : C { Str title() { \"d\" } }")
        try {
            myFixture.renameElementAtCaret("title")
            fail("conflict expected")
        } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
            assertTrue(e.messages.joinToString(), e.messages.any { it.contains("title") })
        }
    }

    fun testPrivateMethodRenamesAlone() {
        myFixture.addFileToProject("B.fan", "class B : A { Void help() {} }")
        myFixture.configureByText("A.fan", "class A { private Void he<caret>lp() {} Void f() { help } }")
        myFixture.renameElementAtCaret("assist")
        myFixture.checkResult("A.fan", "class A { private Void assist() {} Void f() { assist } }", true)
        myFixture.checkResult("B.fan", "class B : A { Void help() {} }", true)
    }
}
