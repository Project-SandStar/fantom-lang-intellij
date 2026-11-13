package co.anbora.labs.fantom.refactoring

import co.anbora.labs.fantom.ide.refactoring.FantomUnwrapDescriptor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Unwrap/Remove: if, else, try, catch, while, for and a closure block. */
class FantomUnwrapTest : BasePlatformTestCase() {

    private fun options(before: String): List<String> {
        myFixture.configureByText("A.fan", before.trimIndent() + "\n")
        return FantomUnwrapDescriptor().collectUnwrappers(project, myFixture.editor, myFixture.file)
            .map { it.second.getDescription(it.first) }
    }

    private fun unwrap(option: String, before: String, after: String) {
        myFixture.configureByText("A.fan", before.trimIndent() + "\n")
        val pair = FantomUnwrapDescriptor().collectUnwrappers(project, myFixture.editor, myFixture.file)
            .firstOrNull { it.second.getDescription(it.first) == option }
        assertNotNull("no option $option", pair)
        WriteCommandAction.runWriteCommandAction(project) { pair!!.second.unwrap(myFixture.editor, pair.first) }
        assertEquals(after.trimIndent() + "\n", myFixture.editor.document.text)
    }

    fun testUnwrapIf() = unwrap(
        "Unwrap 'if...'",
        """
        class A {
          Void f(Int a) {
            if (a > 0) {
              echo(<caret>a)
              echo(a + 1)
            }
            echo(3)
          }
        }
        """,
        """
        class A {
          Void f(Int a) {
            echo(a)
            echo(a + 1)
            echo(3)
          }
        }
        """,
    )

    fun testUnwrapIfBracelessBody() = unwrap(
        "Unwrap 'if...'",
        """
        class A {
          Void f(Int a) {
            if (a > 0) echo(<caret>a)
          }
        }
        """,
        """
        class A {
          Void f(Int a) {
            echo(a)
          }
        }
        """,
    )

    fun testUnwrapElse() = unwrap(
        "Unwrap 'else...'",
        """
        class A {
          Void f(Int a) {
            if (a > 0) {
              echo(a)
            } else {
              echo(<caret>0)
            }
          }
        }
        """,
        """
        class A {
          Void f(Int a) {
            echo(0)
          }
        }
        """,
    )

    fun testUnwrapElseIfKeepsInnerIf() = unwrap(
        "Unwrap 'else...'",
        """
        class A {
          Void f(Int a) {
            if (a > 0) {
              echo(a)
            } else if (a < 0) {
              echo(<caret>0)
            }
          }
        }
        """,
        """
        class A {
          Void f(Int a) {
            if (a < 0) {
              echo(0)
            }
          }
        }
        """,
    )

    fun testRemoveElse() = unwrap(
        "Remove 'else...'",
        """
        class A {
          Void f(Int a) {
            if (a > 0) {
              echo(a)
            } else {
              echo(<caret>0)
            }
          }
        }
        """,
        """
        class A {
          Void f(Int a) {
            if (a > 0) {
              echo(a)
            }
          }
        }
        """,
    )

    fun testUnwrapTryKeepsFinally() = unwrap(
        "Unwrap 'try...'",
        """
        class A {
          Void f(Int a) {
            try {
              echo(<caret>a)
            } catch (Err e) {
              echo(e)
            } finally {
              echo(2)
            }
          }
        }
        """,
        """
        class A {
          Void f(Int a) {
            echo(a)
            echo(2)
          }
        }
        """,
    )

    fun testRemoveCatch() = unwrap(
        "Remove 'catch...'",
        """
        class A {
          Void f(Int a) {
            try {
              echo(a)
            } catch (ArgErr e) {
              echo(<caret>e)
            } catch (Err e) {
              echo(e)
            }
          }
        }
        """,
        """
        class A {
          Void f(Int a) {
            try {
              echo(a)
            } catch (Err e) {
              echo(e)
            }
          }
        }
        """,
    )

    fun testOnlyCatchIsNotRemoved() {
        val offered = options(
            """
            class A {
              Void f(Int a) {
                try {
                  echo(a)
                } catch (Err e) {
                  echo(<caret>e)
                }
              }
            }
            """,
        )
        assertFalse(offered.toString(), "Remove 'catch...'" in offered)
        assertTrue(offered.toString(), "Unwrap 'try...'" in offered)
    }

    fun testUnwrapWhileNested() = unwrap(
        "Unwrap 'while...'",
        """
        class A {
          Void f(Int a) {
            while (a < 3) {
              if (a > 1) {
                echo(<caret>a)
              }
              a++
            }
          }
        }
        """,
        """
        class A {
          Void f(Int a) {
            if (a > 1) {
              echo(a)
            }
            a++
          }
        }
        """,
    )

    fun testUnwrapForKeepsInit() = unwrap(
        "Unwrap 'for...'",
        """
        class A {
          Void f() {
            for (i := 0; i < 3; ++i) {
              echo(<caret>i)
            }
          }
        }
        """,
        """
        class A {
          Void f() {
            i := 0
            echo(i)
          }
        }
        """,
    )

    fun testUnwrapClosureBlock() = unwrap(
        "Unwrap closure block",
        """
        class A {
          Void f() {
            |->| {
              echo(<caret>1)
            }
          }
        }
        """,
        """
        class A {
          Void f() {
            echo(1)
          }
        }
        """,
    )

    fun testEmptyIfBodyRemovesStatement() = unwrap(
        "Unwrap 'if...'",
        """
        class A {
          Void f(Int a) {
            if (<caret>a > 0) {}
            echo(a)
          }
        }
        """,
        """
        class A {
          Void f(Int a) {
            echo(a)
          }
        }
        """,
    )
}
