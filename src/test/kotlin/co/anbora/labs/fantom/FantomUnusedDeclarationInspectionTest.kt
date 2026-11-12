package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.inspections.FantomUnusedDeclarationInspection

/** [FantomUnusedDeclarationInspection]: needs no Fantom install, every name is in the file. */
class FantomUnusedDeclarationInspectionTest : FantomInspectionTestBase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(FantomUnusedDeclarationInspection())
    }

    fun testUnusedLocal() {
        checkWarnings("class A { Void f() { <weak_warning descr=\"Unused local 'x'\">x</weak_warning> := 1 } }")
    }

    fun testUsedLocalNotFlagged() {
        checkWarnings("class A { Void f() { x := 1; echo(x) } }")
    }

    fun testUnusedPrivateMethod() {
        checkWarnings("class A { private Void <weak_warning descr=\"Unused private method 'helper'\">helper</weak_warning>() {} }")
    }

    fun testUsedPrivateMethodNotFlagged() {
        checkWarnings("class A { private Void helper() {}\n Void f() { helper } }")
    }

    fun testPublicSlotNotFlagged() {
        checkWarnings("class A { Void helper() {}\n Int count := 0 }")
    }

    fun testUnusedParam() {
        checkWarnings("class A { Void f(Int <weak_warning descr=\"Unused parameter 'n'\">n</weak_warning>) {} }")
    }

    fun testOverrideAbstractAndCtorParamsNotFlagged() {
        checkWarnings("class A { override Void f(Int n) {}\n abstract Void g(Int m)\n native Void h(Int k)\n new make(Int c) {} }")
    }

    fun testUnderscoreNamesNotFlagged() {
        checkWarnings("class A { Void f(Int _unused) { x := [1]; x.each |_e| {} } }")
    }

    fun testUnusedClosureParam() {
        checkWarnings("class A { Void f() { x := [1]; x.each |<weak_warning descr=\"Unused closure parameter 'e'\">e</weak_warning>| {} } }")
    }

    fun testUnusedPrivateField() {
        checkWarnings("class A { private Int <weak_warning descr=\"Unused private field 'hidden'\">hidden</weak_warning> := 0 }")
    }
}
