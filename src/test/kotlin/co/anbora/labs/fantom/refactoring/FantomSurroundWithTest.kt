package co.anbora.labs.fantom.refactoring

import co.anbora.labs.fantom.lang.FantomLanguage
import com.intellij.codeInsight.generation.surroundWith.SurroundWithHandler
import com.intellij.lang.LanguageSurrounders
import com.intellij.lang.surroundWith.Surrounder
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Surround With: statements (if, if/else, try/catch, while, closure) and expressions. */
class FantomSurroundWithTest : BasePlatformTestCase() {

    private fun surrounder(description: String): Surrounder =
        LanguageSurrounders.INSTANCE.allForLanguage(FantomLanguage)
            .flatMap { it.surrounders.toList() }
            .first { it.templateDescription == description }

    private fun surround(description: String, before: String, after: String) {
        myFixture.configureByText("A.fan", before.trimIndent() + "\n")
        SurroundWithHandler.invoke(project, myFixture.editor, myFixture.file, surrounder(description))
        myFixture.checkResult(after.trimIndent() + "\n")
    }

    private fun available(before: String): List<String> {
        myFixture.configureByText("A.fan", before.trimIndent() + "\n")
        val sel = myFixture.editor.selectionModel
        return LanguageSurrounders.INSTANCE.allForLanguage(FantomLanguage).flatMap { d ->
            val elements = d.getElementsToSurround(myFixture.file, sel.selectionStart, sel.selectionEnd)
            if (elements.isEmpty()) emptyList()
            else d.surrounders.filter { it.isApplicable(elements) }.map { it.templateDescription }
        }
    }

    fun testIf() = surround(
        "if",
        """
        class A {
          Void f(Int a) {
            <selection>echo(a)
            echo(a + 1)</selection>
          }
        }
        """,
        """
        class A {
          Void f(Int a) {
            if (<selection>true</selection>) {
              echo(a)
              echo(a + 1)
            }
          }
        }
        """,
    )

    fun testIfElse() = surround(
        "if / else",
        """
        class A {
          Void f(Int a) {
            <selection>echo(a)</selection>
          }
        }
        """,
        """
        class A {
          Void f(Int a) {
            if (<selection>true</selection>) {
              echo(a)
            } else {

            }
          }
        }
        """,
    )

    fun testTryCatchNestedBodyKeepsRelativeIndent() = surround(
        "try / catch",
        """
        class A {
          Void f(Int a) {
            <selection>if (a > 0) {
              echo(a)
            }</selection>
          }
        }
        """,
        """
        class A {
          Void f(Int a) {
            try {
              if (a > 0) {
                echo(a)
              }
            } catch (Err e) {
              <caret>
            }
          }
        }
        """,
    )

    fun testWhile() = surround(
        "while",
        """
        class A {
          Void f(Int a) {
            <selection>a++</selection>
          }
        }
        """,
        """
        class A {
          Void f(Int a) {
            while (<selection>true</selection>) {
              a++
            }
          }
        }
        """,
    )

    fun testClosure() = surround(
        "closure |->| { }",
        """
        class A {
          Void f(Int a) {
            <selection>echo(a)</selection>
          }
        }
        """,
        """
        class A {
          Void f(Int a) {
            |->|<caret> {
              echo(a)
            }
          }
        }
        """,
    )

    fun testParens() = surround(
        "(expr)",
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
            echo((a + 1)<caret>)
          }
        }
        """,
    )

    fun testCastUsesInferredType() = surround(
        "(expr as Type)",
        """
        class A {
          Void f(Str s) {
            echo(<selection>s</selection>)
          }
        }
        """,
        """
        class A {
          Void f(Str s) {
            echo((s as <selection>Str</selection>))
          }
        }
        """,
    )

    fun testNotOfferedWhenLocalUsedAfter() {
        val offered = available(
            """
            class A {
              Void f() {
                <selection>x := 1</selection>
                echo(x)
              }
            }
            """,
        )
        assertFalse(offered.toString(), "if" in offered)
    }

    fun testNotOfferedForPartialStatement() {
        val offered = available(
            """
            class A {
              Void f(Int a) {
                ec<selection>ho(a)
                echo</selection>(a)
              }
            }
            """,
        )
        assertTrue(offered.toString(), offered.isEmpty())
    }
}
