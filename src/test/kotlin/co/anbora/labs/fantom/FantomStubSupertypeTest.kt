package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.resolve.FantomReference
import co.anbora.labs.fantom.lang.resolve.FantomSlotLookup
import co.anbora.labs.fantom.lang.resolve.FantomScope
import co.anbora.labs.fantom.lang.resolve.FantomUsingScope
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * A SkySpark 3.1 install has two pods that each declare a type `Conn`: `connExt::Conn`
 * (an abstract class with `actor` and `proj`) and `hxConn::Conn` (an actor). Both come
 * from pod stubs laid out as `<pod>/fan/<Type>.fan`. A class `: Conn` with `using connExt`
 * must walk into `connExt::Conn`, through implicit `this` and through `this.`.
 */
class FantomStubSupertypeTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "hxConn/fan/Conn.fan",
            "** Generated from hxConn.pod; read-only.\npublic const final class Conn\n{\n  public native Dict rec()\n}\n",
        )
        myFixture.addFileToProject(
            "connExt/fan/Conn.fan",
            """
            ** Generated from connExt.pod 3.1.12; read-only.
            public abstract class Conn
            {
              public new make(ConnActor actor) {}
              public const ConnActor actor
              public native Proj proj()
              public native Dict rec()
            }
            """.trimIndent() + "\n",
        )
        myFixture.addFileToProject("connExt/fan/ConnActor.fan", "public const class ConnActor {}\n")
    }

    private fun resolveAtCaret(): List<FantomNamedElement> {
        val ref = myFixture.file.findReferenceAt(myFixture.caretOffset) as FantomReference
        return ref.resolveAll()
    }

    private fun conn(using: String, body: String) =
        myFixture.configureByText("MyConn.fan", "using $using\nclass MyConn : Conn {\n  new make(ConnActor a) : super(a) {}\n  Void run() { $body }\n}\n")

    fun testImplicitThisFieldOfStubSupertype() {
        conn("connExt", "x := ac<caret>tor")
        val target = resolveAtCaret().single()
        assertEquals("actor", target.name)
        assertEquals("connExt", FantomUsingScope.podOf(target))
    }

    fun testImplicitThisMethodOfStubSupertype() {
        conn("connExt", "p := pr<caret>oj.toStr")
        assertEquals("connExt", FantomUsingScope.podOf(resolveAtCaret().single()))
    }

    fun testThisDotFieldOfStubSupertype() {
        conn("connExt", "x := this.ac<caret>tor")
        val target = resolveAtCaret().single()
        assertEquals("actor", target.name)
        assertEquals("connExt", FantomUsingScope.podOf(target))
    }

    fun testOtherPodsConnWhenItIsTheOneImported() {
        conn("hxConn", "r := re<caret>c")
        assertEquals("hxConn", FantomUsingScope.podOf(resolveAtCaret().single()))
    }

    fun testQualifiedSupertype() {
        myFixture.configureByText("Q.fan", "class Q : connExt::Conn {\n  Void run() { x := ac<caret>tor }\n}\n")
        assertEquals("connExt", FantomUsingScope.podOf(resolveAtCaret().single()))
    }

    fun testCompletionChainHasTheImportedConn() {
        val file = conn("connExt", "")
        val type = FantomScope.enclosingType(file.findElementAt(file.text.indexOf("run"))!!)!!
        val slots = FantomSlotLookup.allSlots(type, project, GlobalSearchScope.allScope(project)).mapNotNull { it.name }
        assertTrue(slots.toString(), slots.containsAll(listOf("actor", "proj")))
    }
}
