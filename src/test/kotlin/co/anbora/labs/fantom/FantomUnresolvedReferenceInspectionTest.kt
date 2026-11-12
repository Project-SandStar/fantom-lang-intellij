package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.inspections.FantomUnresolvedReferenceInspection
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.util.indexing.FileBasedIndex
import java.io.File

/**
 * [FantomUnresolvedReferenceInspection] with the real Fantom install as a library, so
 * `Void`, `Obj` and `echo` resolve and only the made-up names are reported.
 * Skips when no Fantom home is on this machine.
 */
class FantomUnresolvedReferenceInspectionTest : FantomInspectionTestBase() {

    private var home: File? = null

    override fun setUp() {
        super.setUp()
        home = FantomHome.detect()
        val h = home ?: return
        FantomSettings.getInstance(project).homePath = h.path
        WriteAction.runAndWait<RuntimeException> {
            ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(EmptyRunnable.getInstance(), false, true)
        }
        FileBasedIndex.getInstance().ensureUpToDate(FantomDeclarationIndex.NAME, project, null)
        myFixture.enableInspections(FantomUnresolvedReferenceInspection())
    }

    override fun tearDown() {
        try {
            FantomSettings.getInstance(project).homePath = null
        } finally {
            super.tearDown()
        }
    }

    private fun skip(): Boolean {
        if (home == null) println("FantomUnresolvedReferenceInspectionTest skipped: no Fantom home found")
        return home == null
    }

    fun testUnresolvedLocal() {
        if (skip()) return
        checkWarnings("class A { Void f() { x := <warning descr=\"Cannot resolve symbol 'zzqNope'\">zzqNope</warning>; echo(x) } }")
    }

    fun testUnresolvedSlotOnKnownType() {
        if (skip()) return
        checkWarnings("class A { Void f(Obj o) { o.<warning descr=\"Cannot resolve symbol 'zzqNope'\">zzqNope</warning> } }")
    }

    fun testUnresolvedSlotOnStaticCallOfKnownType() {
        if (skip()) return
        checkWarnings("class A { Void f() { Str.<warning descr=\"Cannot resolve symbol 'zzqNope'\">zzqNope</warning> } }")
    }

    fun testGenericPlaceholderInClosureParamNotReported() {
        if (skip()) return
        checkWarnings("class A { Void f(Obj[] list) { list.each |V item| { echo(item) } } }")
    }

    fun testDynamicCallNotReported() {
        if (skip()) return
        checkWarnings("class A { Void f(Obj o) { o->zzqNope } }")
    }

    fun testUnknownReceiverTypeNotReported() {
        if (skip()) return
        // `o->zzqA` has no inferable type, so `.zzqNope` has an unknown receiver.
        checkWarnings("class A { Void f(Obj o) { o->zzqA.zzqNope } }")
    }

    fun testSafeDotOnUnknownReceiverNotReported() {
        if (skip()) return
        checkWarnings("class A { Void f(Obj o) { o->zzqA?.zzqNope } }")
    }

    fun testResolvedCodeHasNoWarnings() {
        if (skip()) return
        checkWarnings("class A { Int count := 0\n Str f(Str s) { count++; return s.upper + count.toStr } }")
    }

    fun testFacetValueNotReported() {
        if (skip()) return
        checkWarnings("class A { @Transient { zzqNope = 1 } Int x := 0 }")
    }
}
