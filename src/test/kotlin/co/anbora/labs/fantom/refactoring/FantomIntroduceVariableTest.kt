package co.anbora.labs.fantom.refactoring

import co.anbora.labs.fantom.ide.refactoring.FantomIntroduceVariableHandler
import co.anbora.labs.fantom.lang.core.psi.FantomLocalDef
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Introduce Variable: selection, caret, all occurrences, names, refusals. */
class FantomIntroduceVariableTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
    }

    private fun introduce(before: String, after: String) {
        myFixture.configureByText("A.fan", before.trimIndent() + "\n")
        FantomIntroduceVariableHandler().invoke(project, myFixture.editor, myFixture.file, null)
        TemplateManagerImpl.getTemplateState(myFixture.editor)?.gotoEnd(false)
        assertEquals(after.trimIndent() + "\n", myFixture.editor.document.text)
    }

    private fun refused(before: String, message: String) {
        myFixture.configureByText("A.fan", before.trimIndent() + "\n")
        try {
            FantomIntroduceVariableHandler().invoke(project, myFixture.editor, myFixture.file, null)
            fail("expected a refusal")
        } catch (e: CommonRefactoringUtil.RefactoringErrorHintException) {
            assertTrue(e.message, e.message!!.contains(message))
        }
    }

    fun testSelectionIntType() = introduce(
        """
        class A {
          Void f(Int a) {
            echo(<selection>a + 1</selection>)
          }
        }
        """,
        """
        class A {
          Void f(Int a) {
            int := a + 1
            echo(int)
          }
        }
        """,
    )

    fun testReplacedUseResolvesToNewLocal() {
        testSelectionIntType()
        val offset = myFixture.file.text.indexOf("echo(int)") + 5
        val ref = myFixture.file.findReferenceAt(offset)
        assertNotNull("no reference at the replaced use", ref)
        val target = ref!!.resolve()
        assertTrue("resolves to $target", PsiTreeUtil.getParentOfType(target, FantomLocalDef::class.java, false) != null)
    }

    fun testCaretOnCallNamesFromCall() = introduce(
        """
        class A {
          Void f(Order order) {
            echo(order.tot<caret>al())
          }
        }
        """,
        """
        class A {
          Void f(Order order) {
            total := order.total()
            echo(total)
          }
        }
        """,
    )

    fun testToStrGivesStr() = introduce(
        """
        class A {
          Void f(Int a) {
            echo(<selection>a.toStr</selection>)
          }
        }
        """,
        """
        class A {
          Void f(Int a) {
            str := a.toStr
            echo(str)
          }
        }
        """,
    )

    fun testListLiteralNamedList() = introduce(
        """
        class A {
          Void f() {
            echo(<selection>[1, 2]</selection>.size)
          }
        }
        """,
        """
        class A {
          Void f() {
            list := [1, 2]
            echo(list.size)
          }
        }
        """,
    )

    fun testReplaceAllOccurrencesInBlock() = introduce(
        """
        class A {
          Void f(Int a) {
            echo(a * 2)
            if (true) {
              echo(<selection>a * 2</selection>)
            }
          }
        }
        """,
        """
        class A {
          Void f(Int a) {
            int := a * 2
            echo(int)
            if (true) {
              echo(int)
            }
          }
        }
        """,
    )

    fun testNameMadeUnique() = introduce(
        """
        class A {
          Void f(Int a) {
            int := 3
            echo(<selection>a + int</selection>)
          }
        }
        """,
        """
        class A {
          Void f(Int a) {
            int := 3
            int1 := a + int
            echo(int1)
          }
        }
        """,
    )

    fun testWholeStatementBecomesDeclaration() = introduce(
        """
        class A {
          Void f(Int a) {
            <selection>a + 1</selection>
            echo(a)
          }
        }
        """,
        """
        class A {
          Void f(Int a) {
            int := a + 1
            echo(a)
          }
        }
        """,
    )

    fun testInsideClosureBody() = introduce(
        """
        class A {
          Void f(Int[] list) {
            list.each |v| {
              echo(<selection>v * 3</selection>)
            }
          }
        }
        """,
        """
        class A {
          Void f(Int[] list) {
            list.each |v| {
              int := v * 3
              echo(int)
            }
          }
        }
        """,
    )

    fun testRefusesWithoutExpression() = refused(
        """
        class A {
          Void f() {
            <caret>
          }
        }
        """,
        "caret on an expression",
    )

    fun testRefusesBracelessBody() = refused(
        """
        class A {
          Void f(Int a) {
            if (a > 0) echo(<selection>a + 1</selection>)
          }
        }
        """,
        "without braces",
    )

    fun testRefusesWhileCondition() = refused(
        """
        class A {
          Void f(Int a) {
            while (<selection>a < 3</selection>) { a++ }
          }
        }
        """,
        "while condition",
    )

    fun testRefusesFieldInitializer() = refused(
        """
        class A {
          Int x := <selection>1 + 2</selection>
        }
        """,
        "Select an expression",
    )

    fun testRefusesAssignmentTarget() = refused(
        """
        class A {
          Void f(Int a) {
            <selection>a</selection> = 3
          }
        }
        """,
        "Select an expression",
    )
}
