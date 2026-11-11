package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.core.psi.FantomClassDef
import co.anbora.labs.fantom.lang.core.psi.FantomClosure
import co.anbora.labs.fantom.lang.core.psi.FantomClosureParam
import co.anbora.labs.fantom.lang.core.psi.FantomFieldDef
import co.anbora.labs.fantom.lang.core.psi.FantomLocalDef
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomParam
import co.anbora.labs.fantom.lang.resolve.FantomReference
import com.intellij.psi.PsiFile
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.usageView.UsageInfo

/**
 * Find usages follows [FantomReference] across files. Every usage must be a
 * use site that resolves back to the declaration, never the declaration itself.
 */
class FantomUsagesTest : BasePlatformTestCase() {

    private inline fun <reified T : FantomNamedElement> decl(file: PsiFile, name: String): T =
        PsiTreeUtil.findChildrenOfType(file, T::class.java).first { it.name == name }

    private fun assertParses(vararg files: PsiFile) {
        for (f in files) assertFalse("parse errors in ${f.name}:\n${f.text}", PsiTreeUtil.hasErrorElements(f))
    }

    /** Usages of [target], checked to be real use sites that resolve to it. */
    private fun usagesOf(target: FantomNamedElement): List<UsageInfo> {
        val usages = myFixture.findUsages(target).toList()
        val declId = target.nameIdentifier!!
        for (u in usages) {
            val ref = u.reference
            assertNotNull("usage without reference: ${u.element?.text}", ref)
            assertTrue("usage is not a FantomReference", ref is FantomReference)
            val resolved = (ref as FantomReference).resolveAll()
            assertTrue("usage '${ref.element.text}' does not resolve to the target", resolved.any { it.isEquivalentTo(target) })
            val same = u.file == declId.containingFile && u.element!!.textRange.startOffset == declId.textRange.startOffset
            assertFalse("declaration counted as usage", same)
        }
        return usages
    }

    private fun describe(usages: List<UsageInfo>) =
        usages.joinToString { "${it.file?.name}@${it.element?.textRange?.startOffset}:${it.element?.text}" }

    private fun countIn(usages: List<UsageInfo>, fileName: String) = usages.count { it.file?.name == fileName }

    fun testClassUsedAsFieldTypeCastIsCheckAndStaticCall() {
        val a = myFixture.addFileToProject("A.fan", "class Foo { static Void go() {} }")
        val b = myFixture.addFileToProject("B.fan", """
            class Bar {
              Foo f
              Void m(Obj o) {
                x := (Foo)o
                if (o is Foo) echo(o)
                Foo.go()
              }
            }
        """.trimIndent())
        assertParses(a, b)
        val usages = usagesOf(decl<FantomClassDef>(a, "Foo"))
        assertEquals(describe(usages), 4, countIn(usages, "B.fan"))
        assertEquals(describe(usages), 0, countIn(usages, "A.fan"))
        assertTrue(usages.all { (it.reference as FantomReference).kind == FantomReference.Kind.TYPE })
    }

    fun testMethodUsedAsDotCallInOtherFileAndBareInOwnFile() {
        val a = myFixture.addFileToProject("A.fan", "class A { Void run() {} Void f() { run } }")
        val b = myFixture.addFileToProject("B.fan", "class B { Void g(A obj) { obj.run() } }")
        assertParses(a, b)
        val usages = usagesOf(decl<FantomMethodDef>(a, "run"))
        assertEquals(describe(usages), 1, countIn(usages, "A.fan"))
        assertEquals(describe(usages), 1, countIn(usages, "B.fan"))
        assertEquals(2, usages.size)
    }

    fun testLocalUsedThreeTimes() {
        val a = myFixture.addFileToProject("A.fan", "class A { Void f() { xx := 1; echo(xx); xx = xx + 1 } }")
        // Same name in another file must not count.
        myFixture.addFileToProject("B.fan", "class B { Void f() { xx := 2; echo(xx) } }")
        assertParses(a)
        val target = decl<FantomLocalDef>(a, "xx")
        val usages = usagesOf(target)
        assertEquals(describe(usages), 3, usages.size)
        assertTrue(usages.all { it.file?.name == "A.fan" })
        assertTrue("local use scope must be local", target.useScope is LocalSearchScope)
    }

    fun testFieldUsedAsStorageAndBareName() {
        val a = myFixture.addFileToProject("A.fan", "class A { Int total := 0; Void f() { &total = 1; echo(total) } }")
        assertParses(a)
        val usages = usagesOf(decl<FantomFieldDef>(a, "total"))
        assertEquals(describe(usages), 2, usages.size)
        val texts = usages.map { it.element!!.parent.text }.sorted()
        assertEquals(listOf("&total", "total"), texts)
    }

    fun testParamUsedInClosureBody() {
        val a = myFixture.addFileToProject("A.fan", "class A { Void f(Int count) { go |Int v| { echo(v + count) }; echo(count) } }")
        assertParses(a)
        val target = decl<FantomParam>(a, "count")
        val usages = usagesOf(target)
        assertEquals(describe(usages), 2, usages.size)
        assertTrue(target.useScope is LocalSearchScope)
    }

    fun testClosureParamScopeIsItsClosure() {
        val a = myFixture.addFileToProject("A.fan", "class A { Void f(Int count) { go |Int v| { echo(v + count) }; echo(count) } }")
        assertParses(a)
        val target = decl<FantomClosureParam>(a, "v")
        val usages = usagesOf(target)
        assertEquals(describe(usages), 1, usages.size)
        val scope = target.useScope as LocalSearchScope
        assertTrue(scope.scope.single() is FantomClosure)
    }

    fun testLocalShadowingFieldIsNotAFieldUsage() {
        val a = myFixture.addFileToProject("A.fan", "class A { Int total := 0; Void f() { total := 1; echo(total) } }")
        assertParses(a)
        val usages = usagesOf(decl<FantomFieldDef>(a, "total"))
        assertEquals(describe(usages), 0, usages.size)
        assertEquals(1, usagesOf(decl<FantomLocalDef>(a, "total")).size)
    }

    fun testSameNameLocalInOtherMethodIsNotAUsage() {
        val a = myFixture.addFileToProject("A.fan", "class A { Void f() { xx := 1; echo(xx) } Void g() { xx := 2; echo(xx) } }")
        assertParses(a)
        val first = PsiTreeUtil.findChildrenOfType(a, FantomLocalDef::class.java).first { it.name == "xx" }
        val usages = usagesOf(first)
        assertEquals(describe(usages), 1, usages.size)
    }

    fun testMethodCalledThroughSubtype() {
        val a = myFixture.addFileToProject("A.fan", "class A { Void hello() {} }")
        val b = myFixture.addFileToProject("B.fan", "class B : A { Void g() { hello } }")
        assertParses(a, b)
        val usages = usagesOf(decl<FantomMethodDef>(a, "hello"))
        assertEquals(describe(usages), 1, countIn(usages, "B.fan"))
    }
}
