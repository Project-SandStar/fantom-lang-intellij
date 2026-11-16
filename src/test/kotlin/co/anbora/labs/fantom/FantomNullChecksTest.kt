package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.inspections.FantomNullChecks
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * [FantomNullChecks.isCheckedNonNull] called directly on the `s` of `s.size`
 * (the first `s.size` in the method unless an occurrence is given). No install needed.
 */
class FantomNullChecksTest : BasePlatformTestCase() {

    private fun checked(body: String, occurrence: Int = 1, name: String = "s"): Boolean {
        val text = "class A { Obj? f(Str? s, Str? t) {\n$body\n} }"
        myFixture.configureByText("A.fan", text)
        var at = -1
        repeat(occurrence) { at = text.indexOf("$name.size", at + 1) }
        assertTrue("no $name.size #$occurrence in: $body", at >= 0)
        val use = myFixture.file.findElementAt(at)!!
        assertEquals(name, use.text)
        return FantomNullChecks.isCheckedNonNull(use, name)
    }

    fun testPlainUseIsNotChecked() = assertFalse(checked("return s.size"))

    fun testThenBranchOfNotNull() = assertTrue(checked("if (s != null) { return s.size }\nreturn null"))

    fun testNullOnLeftSide() = assertTrue(checked("if (null != s) return s.size\nreturn null"))

    fun testThenBranchOfAndCondition() = assertTrue(checked("if (s != null && t != null) return s.size\nreturn null"))

    fun testThenBranchOfOrConditionIsNotChecked() = assertFalse(checked("if (s != null || t != null) return s.size\nreturn null"))

    fun testElseBranchOfEqualsNull() = assertTrue(checked("if (s == null) echo(0) else { return s.size }\nreturn null"))

    fun testThenBranchOfEqualsNullIsNotChecked() = assertFalse(checked("if (s == null) { return s.size }\nreturn null"))

    fun testAfterReturnGuard() = assertTrue(checked("if (s == null) return null\necho(1)\nreturn s.size"))

    fun testAfterOrGuardForBothNames() {
        val body = "if (s == null || t == null) return null\nreturn s.size + t.size"
        assertTrue(checked(body))
        assertTrue(checked(body, name = "t"))
    }

    fun testGuardWithoutExitIsNotChecked() = assertFalse(checked("if (s == null) echo(1)\nreturn s.size"))

    fun testUseBeforeGuardIsNotChecked() = assertFalse(checked("x := s.size\nif (s == null) return null\nreturn s.size"))

    fun testRightOperandOfAnd() = assertTrue(checked("return s != null && s.size > 0"))

    fun testTernaryBranches() {
        val body = "x := s != null ? s.size : 0\ny := s == null ? 0 : s.size\nz := s == null ? s.size : 0\nreturn x"
        assertTrue(checked(body, 1))
        assertTrue(checked(body, 2))
        assertFalse(checked(body, 3))
    }

    fun testAfterElvisThrow() = assertTrue(checked("v := s ?: throw Err()\nreturn s.size"))

    fun testClosureStopsTheCheck() = assertFalse(checked("if (s != null) { g := |->Int| { s.size } }\nreturn null"))

    fun testOtherNameIsNotChecked() = assertFalse(checked("if (t != null) return s.size\nreturn null"))
}
