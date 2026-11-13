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

    fun testInsideIfNotNull() {
        checkWarnings("class A { Void f(Str? s, Str? t) { if (s != null) { s.size }\nif (null != s && t != null) s.size\nif (s == null) echo(1) else { s.size } } }")
    }

    fun testAfterNullGuard() {
        checkWarnings("class A { Int f(Str? s, Str? t) { if (s == null || t == null) return 0\necho(1)\nreturn s.size + t.size } }")
    }

    fun testAfterThrowGuardInLoop() {
        checkWarnings("class A { Void f(Str?[] l) { l.each |Str? s| { if (s == null) throw Err()\ns.size } } }")
    }

    fun testRightOfAnd() {
        checkWarnings("class A { Bool f(Str? s) { s != null && s.isEmpty } }")
    }

    fun testTernary() {
        checkWarnings("class A { Int f(Str? s) { x := s != null ? s.size : 0; return s == null ? 0 : s.size } }")
    }

    fun testAfterElvisThrow() {
        checkWarnings("class A { Int f(Str? s) { s ?: throw Err()\nreturn s.size } }")
    }

    fun testUncheckedStillFlagged() {
        checkWarnings(
            "class A { Void f(Str? s) {\n" +
                "if (s != null) echo(1)\n<warning descr=\"Receiver may be null\">s</warning>.size\n" +
                "if (s == null) echo(2)\n<warning descr=\"Receiver may be null\">s</warning>.size\n" +
                "s == null && <warning descr=\"Receiver may be null\">s</warning>.isEmpty\n" +
                "if (s != null || true) <warning descr=\"Receiver may be null\">s</warning>.size\n" +
                "if (s != null) { f := |->Int| { <warning descr=\"Receiver may be null\">s</warning>.size } }\n" +
                "} }",
        )
    }

    fun testReplaceDotFix() {
        myFixture.configureByText("A.fan", "class A { Int f(Str? s) { <caret>s.size } }")
        myFixture.launchAction(myFixture.findSingleIntention("Replace '.' with '?.'"))
        myFixture.checkResult("class A { Int f(Str? s) { s?.size } }")
    }
}
