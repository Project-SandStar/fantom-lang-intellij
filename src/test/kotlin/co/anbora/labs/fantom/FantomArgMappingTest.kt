package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.parameters.FantomArgMapping
import co.anbora.labs.fantom.lang.core.psi.FantomArgs
import co.anbora.labs.fantom.lang.core.psi.FantomParam
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** [FantomArgMapping]: arguments to parameters by position, and the ctor choice. Types are in the file; no install needed. */
class FantomArgMappingTest : BasePlatformTestCase() {

    private val types = """
        class P {
          new make(Int x) {}
          new fromStr(Str s) {}
          Void run(Int a, Str b := "", Bool c := true) {}
        }
        class Q {
          new two(Int x, Int y) {}
          new one(Int x) {}
        }
    """.trimIndent()

    /** The args of the first call in [body] whose text starts with [callText]. */
    private fun argsOf(body: String, callText: String): FantomArgs {
        myFixture.configureByText("A.fan", "$types\nclass A { Void f(P p) {\n$body\n} }")
        val at = myFixture.file.text.indexOf(callText)
        assertTrue("not found: $callText", at >= 0)
        return PsiTreeUtil.findChildrenOfType(myFixture.file, FantomArgs::class.java)
            .first { it.textRange.startOffset >= at }
    }

    private fun name(p: FantomParam) = p.text.substringBefore(":=").trim().substringAfterLast(' ')

    private fun mapped(args: FantomArgs): List<Pair<String, String>> =
        FantomArgMapping.map(args).map { it.arg.text to name(it.param) }

    fun testMethodArgsByPosition() {
        val args = argsOf("p.run(1, \"x\")", "p.run")
        assertEquals("run", FantomArgMapping.callee(args)?.name)
        assertEquals(listOf("1" to "a", "\"x\"" to "b"), mapped(args))
    }

    fun testParamsListsAllInOrder() {
        val callee = FantomArgMapping.callee(argsOf("p.run(1)", "p.run"))!!
        assertEquals(listOf("a", "b", "c"), FantomArgMapping.params(callee).map(::name))
    }

    fun testExtraArgsStopAtShorterList() {
        val args = argsOf("p.run(1, \"x\", false, 4)", "p.run")
        assertEquals(listOf("a", "b", "c"), mapped(args).map { it.second })
    }

    fun testTypeCallPicksMakeWhenSeveralCtorsFit() {
        val args = argsOf("x := P(1)", "P(1)")
        assertEquals("make", FantomArgMapping.callee(args)?.name)
        assertEquals(listOf("1" to "x"), mapped(args))
    }

    fun testTypeCallPicksTheOnlyCtorThatFitsByCount() {
        assertEquals("two", FantomArgMapping.callee(argsOf("x := Q(1, 2)", "Q(1, 2)"))?.name)
        assertEquals("one", FantomArgMapping.callee(argsOf("x := Q(1)", "Q(1)"))?.name)
    }

    fun testUnresolvedCallMapsNothing() {
        val args = argsOf("p.nothing(1)", "p.nothing")
        assertNull(FantomArgMapping.callee(args))
        assertTrue(FantomArgMapping.map(args).isEmpty())
    }
}
