package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.inspections.FantomNullableReceiverInspection

/** [FantomNullableReceiverInspection]: the receiver declarations are all in the file, so no install is needed. */
class FantomNullableReceiverInspectionTest : FantomInspectionTestBase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(FantomNullableReceiverInspection())
    }

    fun testNullableParamReceiver() {
        checkWarnings("class A { Int f(Str? s) { <warning descr=\"Receiver may be null\">s</warning>.size } }")
    }

    fun testNullableLocalAndField() {
        checkWarnings("class A { Str? name\n Void f() { Str? s := null; <warning descr=\"Receiver may be null\">s</warning>.size; <warning descr=\"Receiver may be null\">name</warning>.size } }")
    }

    fun testSafeDotNotFlagged() {
        checkWarnings("class A { Void f(Str? s) { s?.size } }")
    }

    fun testNonNullableNotFlagged() {
        checkWarnings("class A { Void f(Str s) { s.size; t := null; t.size } }")
    }

    fun testNullSafeSlotsNotFlagged() {
        checkWarnings("class A { Void f(Str? s) { s.toStr; s.equals(s); s.hash; s.typeof; s.isImmutable; s.compare(s) } }")
    }

    fun testReplaceDotFix() {
        myFixture.configureByText("A.fan", "class A { Int f(Str? s) { <caret>s.size } }")
        myFixture.launchAction(myFixture.findSingleIntention("Replace '.' with '?.'"))
        myFixture.checkResult("class A { Int f(Str? s) { s?.size } }")
    }
}
