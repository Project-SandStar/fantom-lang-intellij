package co.anbora.labs.fantom

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Breadcrumbs show type > slot > closure with the structure view texts. */
class FantomBreadcrumbsTest : BasePlatformTestCase() {

    private fun crumbs(text: String): List<String> {
        myFixture.configureByText("A.fan", text)
        return myFixture.breadcrumbsAtCaret.map { it.text }
    }

    fun testTypeAndMethod() {
        assertEquals(
            listOf("Foo", "run(Int): Void"),
            crumbs("class Foo\n{\n  Void run(Int n) { echo(<caret>n) }\n}\n"),
        )
    }

    fun testField() {
        assertEquals(listOf("Foo", "size: Int"), crumbs("class Foo\n{\n  Int size := <caret>3\n}\n"))
    }

    fun testClosure() {
        assertEquals(
            listOf("Foo", "run(Int[]): Void", "|Int x|"),
            crumbs("class Foo\n{\n  Void run(Int[] xs) { xs.each |Int x| { echo(<caret>x) } }\n}\n"),
        )
    }

    fun testMixinAndCtor() {
        assertEquals(listOf("Bar", "new make(Str)"), crumbs("class Bar\n{\n  new make(Str s) { echo(<caret>s) }\n}\n"))
    }
}
