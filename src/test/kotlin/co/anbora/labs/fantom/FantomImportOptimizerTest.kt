package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.imports.FantomImportOptimizer
import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * [FantomImportOptimizer]: removes unused `using` lines, keeps used, FFI and
 * commented ones, and sorts the block. Pods `a`, `b`, `c` and `sys` are project
 * folders, so no Fantom install is needed.
 */
class FantomImportOptimizerTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("sys/fan/Str.fan", "class Str {}")
        myFixture.addFileToProject("a/fan/Foo.fan", "class Foo {}")
        myFixture.addFileToProject("a/fan/Bar.fan", "class Bar {}")
        myFixture.addFileToProject("b/fan/Baz.fan", "class Baz {}")
        myFixture.addFileToProject("c/fan/Qux.fan", "class Qux {}")
    }

    private fun optimize(before: String, after: String) {
        myFixture.configureByText("A.fan", before)
        OptimizeImportsProcessor(project, myFixture.file).run()
        myFixture.checkResult(after)
    }

    fun testRemovesUnusedKeepsUsedAndSorts() {
        optimize(
            "using c\nusing b::Baz\nusing a\nusing sys\nusing a::Bar\n\nclass A { Foo? f; Baz? b; Str? s }",
            "using sys\nusing a\nusing b::Baz\n\nclass A { Foo? f; Baz? b; Str? s }",
        )
    }

    fun testWholePodBeforeTypeImportOfSamePod() {
        optimize(
            "using a::Bar\nusing a\n\nclass A { Foo? f; Bar? b }",
            "using a\nusing a::Bar\n\nclass A { Foo? f; Bar? b }",
        )
    }

    fun testKeepsFfiAndCommentedLines() {
        optimize(
            "using c::Qux // keep me\nusing [java] java.util::HashMap\nusing a::Bar\n\nclass A { Foo? f }",
            "using c::Qux // keep me\nusing [java] java.util::HashMap\n\nclass A { Foo? f }",
        )
    }

    fun testCtorCallKeepsUsing() {
        optimize(
            "using a::Foo\nusing b::Baz\n\nclass A { Void f() { x := Foo() } }",
            "using a::Foo\n\nclass A { Void f() { x := Foo() } }",
        )
    }

    fun testStaticCallKeepsWholePod() {
        optimize(
            "using b\nusing a\n\nclass A { Void f() { Foo.bar } }",
            "using a\n\nclass A { Void f() { Foo.bar } }",
        )
    }

    fun testAliasCountsAsUsed() {
        optimize(
            "using a::Foo as F\nusing a::Bar as B\n\nclass A { F? f }",
            "using a::Foo as F\n\nclass A { F? f }",
        )
    }

    fun testUnresolvedNameKeepsWholePodImports() {
        optimize(
            "using c\nusing a::Bar\n\nclass A { Zzz? z }",
            "using c\n\nclass A { Zzz? z }",
        )
    }

    fun testRemovingAllUsingsLeavesNoBlankLines() {
        optimize("using c\nusing a::Bar\n\nclass A {}", "class A {}")
    }

    fun testSecondRunChangesNothing() {
        val text = "using sys\nusing a\nusing b::Baz\n\nclass A { Foo? f; Baz? b; Str? s }"
        optimize(text, text)
        OptimizeImportsProcessor(project, myFixture.file).run()
        myFixture.checkResult(text)
    }

    fun testFullLineCommentStaysAtTop() {
        optimize(
            "// imports\nusing b\nusing a\n\nclass A { Foo? f; Baz? b }",
            "// imports\nusing a\nusing b\n\nclass A { Foo? f; Baz? b }",
        )
    }
}
