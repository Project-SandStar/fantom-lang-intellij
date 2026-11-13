package co.anbora.labs.fantom.refactoring

import co.anbora.labs.fantom.ide.refactoring.FantomExtractMethodHandler
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Extract Method: inputs, one output, static, placement, refusals. */
class FantomExtractMethodTest : BasePlatformTestCase() {

    private fun extract(before: String, after: String) {
        myFixture.configureByText("A.fan", before.trimIndent() + "\n")
        FantomExtractMethodHandler().invoke(project, myFixture.editor, myFixture.file, null)
        assertEquals(after.trimIndent() + "\n", myFixture.editor.document.text)
    }

    private fun refused(before: String, message: String) {
        myFixture.configureByText("A.fan", before.trimIndent() + "\n")
        try {
            FantomExtractMethodHandler().invoke(project, myFixture.editor, myFixture.file, null)
            fail("expected a refusal")
        } catch (e: CommonRefactoringUtil.RefactoringErrorHintException) {
            assertTrue(e.message, e.message!!.contains(message))
        }
    }

    fun testNoInputsNoOutput() = extract(
        """
        class A {
          Void f() {
            <selection>echo(1)
            echo(2)</selection>
          }
        }
        """,
        """
        class A {
          Void f() {
            extracted()
          }

          private Void extracted() {
            echo(1)
            echo(2)
          }
        }
        """,
    )

    fun testParamsAndLocalsAsInputs() = extract(
        """
        class A {
          Void f(Int a, Str s) {
            b := a * 2
            <selection>echo(s + b)</selection>
          }
        }
        """,
        """
        class A {
          Void f(Int a, Str s) {
            b := a * 2
            extracted(s, b)
          }

          private Void extracted(Str s, Int b) {
            echo(s + b)
          }
        }
        """,
    )

    fun testDeclaredOutput() = extract(
        """
        class A {
          Int f(Int a) {
            <selection>x := a + 1
            echo(x)</selection>
            return x
          }
        }
        """,
        """
        class A {
          Int f(Int a) {
            x := extracted(a)
            return x
          }

          private Int extracted(Int a) {
            x := a + 1
            echo(x)
            return x
          }
        }
        """,
    )

    fun testAssignedOuterOutput() = extract(
        """
        class A {
          Void f(Int a) {
            n := 0
            <selection>n = a + 3</selection>
            echo(n)
          }
        }
        """,
        """
        class A {
          Void f(Int a) {
            n := 0
            n = extracted(a)
            echo(n)
          }

          private Int extracted(Int a) {
            n = a + 3
            return n
          }
        }
        """,
    )

    fun testStaticMethodGivesStatic() = extract(
        """
        class A {
          static Void main() {
            <selection>echo("hi")</selection>
          }
        }
        """,
        """
        class A {
          static Void main() {
            extracted()
          }

          private static Void extracted() {
            echo("hi")
          }
        }
        """,
    )

    fun testNestedBodyReindentedAndThisKept() = extract(
        """
        class A {
          Int count := 0
          Void f(Int[] list) {
            <selection>list.each |v| {
              if (v > 0) {
                this.count++
              }
            }</selection>
          }
        }
        """,
        """
        class A {
          Int count := 0
          Void f(Int[] list) {
            extracted(list)
          }

          private Void extracted(Int[] list) {
            list.each |v| {
              if (v > 0) {
                this.count++
              }
            }
          }
        }
        """,
    )

    fun testNameAvoidsExistingSlot() = extract(
        """
        class A {
          Void extracted() {}
          Void f() {
            <selection>echo(1)</selection>
          }
        }
        """,
        """
        class A {
          Void extracted() {}
          Void f() {
            extracted1()
          }

          private Void extracted1() {
            echo(1)
          }
        }
        """,
    )

    fun testRefusesTwoOutputs() = refused(
        """
        class A {
          Void f() {
            <selection>x := 1
            y := 2</selection>
            echo(x + y)
          }
        }
        """,
        "more than one local",
    )

    fun testRefusesReturn() = refused(
        """
        class A {
          Int f(Int a) {
            <selection>if (a > 0) { return 1 }</selection>
            return 0
          }
        }
        """,
        "return",
    )

    fun testRefusesBreakOutsideLoop() = refused(
        """
        class A {
          Void f(Int a) {
            while (true) {
              <selection>if (a > 0) { break }</selection>
              a++
            }
          }
        }
        """,
        "loop is outside",
    )

    fun testRefusesPartialStatement() = refused(
        """
        class A {
          Void f() {
            ec<selection>ho(1)</selection>
          }
        }
        """,
        "whole statements",
    )

    fun testRefusesOutsideMethod() = refused(
        """
        class A {
          Void f(Int[] list) {
            list.each |v| {
              <selection>echo(v)</selection>
            }
          }
        }
        """,
        "closure",
    )

    fun testLoopReadOnNextPassIsOutput() = refused(
        """
        class A {
          Void f() {
            x := 0
            y := 0
            while (x < 3) {
              echo(y)
              <selection>x = x + 1
              y = x</selection>
            }
          }
        }
        """,
        "more than one local",
    )
}
