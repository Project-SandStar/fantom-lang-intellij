package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.inspections.FantomUnusedUsingInspection

/** [FantomUnusedUsingInspection]: the weak warning and its "Remove unused using" fix. */
class FantomUnusedUsingInspectionTest : FantomInspectionTestBase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(FantomUnusedUsingInspection())
        myFixture.addFileToProject("a/fan/Foo.fan", "class Foo {}")
        myFixture.addFileToProject("a/fan/Bar.fan", "class Bar {}")
        myFixture.addFileToProject("b/fan/Baz.fan", "class Baz {}")
    }

    fun testUnusedTypeImportIsMarked() {
        checkWarnings(
            "using a::Foo\n<weak_warning descr=\"Unused using 'a::Bar'\">using a::Bar</weak_warning>\n\nclass A { Foo? f }",
        )
    }

    fun testUnusedWholePodIsMarked() {
        checkWarnings("using a\n<weak_warning descr=\"Unused using 'b'\">using b</weak_warning>\n\nclass A { Foo? f }")
    }

    fun testCtorCallCountsAsUse() {
        checkWarnings("using a::Foo\n\nclass A { Void f() { x := Foo() } }")
    }

    fun testFfiAndCommentedLinesAreNotMarked() {
        checkWarnings("using [java] java.util::HashMap\nusing b // needed\n\nclass A {}")
    }

    fun testUnresolvedNameKeepsWholePod() {
        checkWarnings("using b\n\nclass A { Zzz? z }")
    }

    fun testRemoveFix() {
        myFixture.configureByText("A.fan", "using a::Foo\nusing a::B<caret>ar\n\nclass A { Foo? f }")
        myFixture.launchAction(myFixture.findSingleIntention("Remove unused using"))
        myFixture.checkResult("using a::Foo\n\nclass A { Foo? f }")
    }

    fun testRemoveLastUsingDropsBlankLine() {
        myFixture.configureByText("A.fan", "using a::B<caret>ar\n\nclass A {}")
        myFixture.launchAction(myFixture.findSingleIntention("Remove unused using"))
        myFixture.checkResult("class A {}")
    }
}
