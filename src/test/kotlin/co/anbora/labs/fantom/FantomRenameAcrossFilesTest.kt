package co.anbora.labs.fantom

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Rename follows references into other files and into closure bodies. */
class FantomRenameAcrossFilesTest : BasePlatformTestCase() {

    fun testRenameClassAcrossFiles() {
        myFixture.addFileToProject("B.fan", """
            class Bar {
              Foo f
              Void m(Obj o) {
                x := (Foo)o
                if (o is Foo) echo(o)
                Foo.go()
              }
            }
        """.trimIndent())
        myFixture.configureByText("A.fan", "class F<caret>oo { static Void go() {} }")
        myFixture.renameElementAtCaret("Qux")
        myFixture.checkResult("A.fan", "class Qux { static Void go() {} }", true)
        myFixture.checkResult("B.fan", """
            class Bar {
              Qux f
              Void m(Obj o) {
                x := (Qux)o
                if (o is Qux) echo(o)
                Qux.go()
              }
            }
        """.trimIndent(), true)
    }

    fun testRenameMethodAcrossFiles() {
        myFixture.addFileToProject("B.fan", "class B { Void g(A obj) { obj.run() } }")
        myFixture.configureByText("A.fan", "class A { Void r<caret>un() {} Void f() { run } }")
        myFixture.renameElementAtCaret("execute")
        myFixture.checkResult("A.fan", "class A { Void execute() {} Void f() { execute } }", true)
        myFixture.checkResult("B.fan", "class B { Void g(A obj) { obj.execute() } }", true)
    }

    fun testRenameParamUsedInClosureBody() {
        myFixture.configureByText("A.fan", "class A { Void f(Int co<caret>unt) { go |Int v| { echo(v + count) }; echo(count) } }")
        myFixture.renameElementAtCaret("n")
        myFixture.checkResult("A.fan", "class A { Void f(Int n) { go |Int v| { echo(v + n) }; echo(n) } }", true)
    }

    fun testRenameFieldWithStorageForm() {
        myFixture.configureByText("A.fan", "class A { Int to<caret>tal := 0; Void f() { &total = 1; echo(total) } }")
        myFixture.renameElementAtCaret("sum")
        myFixture.checkResult("A.fan", "class A { Int sum := 0; Void f() { &sum = 1; echo(sum) } }", true)
    }

    fun testRenameMethodRenamesCallThroughSubtype() {
        myFixture.addFileToProject("B.fan", "class B : A { Void g() { hello } }")
        myFixture.configureByText("A.fan", "class A { Void he<caret>llo() {} }")
        myFixture.renameElementAtCaret("hi")
        myFixture.checkResult("A.fan", "class A { Void hi() {} }", true)
        myFixture.checkResult("B.fan", "class B : A { Void g() { hi } }", true)
    }
}
