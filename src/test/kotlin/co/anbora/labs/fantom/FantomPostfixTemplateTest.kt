package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.templates.FantomPostfixTemplateProvider
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplate
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Postfix templates: `expr.if`, `.not`, `.each`, `.echo`, `.var`, `.return`, `.null`, `.nn`. */
class FantomPostfixTemplateTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
    }

    private fun expand(before: String, after: String) {
        myFixture.configureByText("A.fan", before)
        myFixture.type('\t')
        myFixture.checkResult(after)
    }

    fun testIfOnBool() = expand(
        "class A { Void f(Bool b) { b.if<caret> } }",
        "class A { Void f(Bool b) { if (b) {\n  <caret>\n} } }",
    )

    fun testNotOnBool() = expand(
        "class A { Void f(Bool b) { b.not<caret> } }",
        "class A { Void f(Bool b) { !b<caret> } }",
    )

    fun testNotOnCompoundBoolAddsParens() = expand(
        "class A { Void f(Bool b, Bool c) { b && c.not<caret> } }",
        "class A { Void f(Bool b, Bool c) { !(b && c)<caret> } }",
    )

    fun testEchoOnAnyExpression() = expand(
        "class A { Void f(Int i) { i.echo<caret> } }",
        "class A { Void f(Int i) { echo(i)<caret> } }",
    )

    fun testEachOnList() = expand(
        "class A { Void f(Str[] l) { l.each<caret> } }",
        "class A { Void f(Str[] l) { l.each |v| {\n  <caret>\n} } }",
    )

    fun testEachOnMap() = expand(
        "class A { Void f([Str:Int] m) { m.each<caret> } }",
        "class A { Void f([Str:Int] m) { m.each |v| {\n  <caret>\n} } }",
    )

    fun testReturnOnAnyExpression() = expand(
        "class A { Int f(Int i) { i.return<caret> } }",
        "class A { Int f(Int i) { return i<caret> } }",
    )

    fun testNullCheck() = expand(
        "class A { Void f(Str? s) { s.null<caret> } }",
        "class A { Void f(Str? s) { if (s == null) {\n  <caret>\n} } }",
    )

    fun testNotNullCheck() = expand(
        "class A { Void f(Str? s) { s.nn<caret> } }",
        "class A { Void f(Str? s) { if (s != null) {\n  <caret>\n} } }",
    )

    fun testVarNameFromType() = expand(
        "class A { Str g() { \"x\" } Void f() { g().var<caret> } }",
        "class A { Str g() { \"x\" } Void f() { <selection>str<caret></selection> := g() } }",
    )

    fun testVarNameForList() = expand(
        "class A { Void f(Str[] l) { l.var<caret> } }",
        "class A { Void f(Str[] l) { <selection>list<caret></selection> := l } }",
    )

    fun testBodyIndentFollowsLine() = expand(
        "class A {\n  Void f(Bool b) {\n    b.if<caret>\n  }\n}",
        "class A {\n  Void f(Bool b) {\n    if (b) {\n      <caret>\n    }\n  }\n}",
    )

    fun testEachOnIntIsNotApplicable() {
        myFixture.configureByText("A.fan", "class A { Void f(Int i) { i.each<caret> } }")
        val each = FantomPostfixTemplateProvider().templates.first { it.key == ".each" }
        val context = myFixture.file.findElementAt(myFixture.caretOffset - ".each".length - 1)!!
        assertFalse(each.isApplicable(context, myFixture.editor.document, myFixture.caretOffset))
        myFixture.type('\t')
        assertFalse(myFixture.editor.document.text.contains("|v|"))
    }

    fun testIfOnIntIsNotApplicable() {
        myFixture.configureByText("A.fan", "class A { Void f(Int i) { i.if<caret> } }")
        val ifT: PostfixTemplate = FantomPostfixTemplateProvider().templates.first { it.key == ".if" }
        val context = myFixture.file.findElementAt(myFixture.caretOffset - ".if".length - 1)!!
        assertFalse(ifT.isApplicable(context, myFixture.editor.document, myFixture.caretOffset))
    }
}
