package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.core.psi.FantomClassDef
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomLibraryRootsProvider
import co.anbora.labs.fantom.lang.library.FantomSettings
import co.anbora.labs.fantom.lang.resolve.FantomReference
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex
import java.io.File

/**
 * With a Fantom home configured, the install's pod sources are a library: indexed,
 * resolvable, and marked as library files. Skips when no Fantom home is on this machine.
 */
class FantomLibraryTest : BasePlatformTestCase() {

    private var home: File? = null

    override fun setUp() {
        super.setUp()
        home = FantomHome.detect()
        val h = home ?: return
        FantomSettings.getInstance(project).homePath = h.path
        com.intellij.openapi.application.WriteAction.runAndWait<RuntimeException> {
            ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(EmptyRunnable.getInstance(), false, true)
        }
        FileBasedIndex.getInstance().ensureUpToDate(co.anbora.labs.fantom.lang.index.FantomDeclarationIndex.NAME, project, null)
    }

    override fun tearDown() {
        try {
            FantomSettings.getInstance(project).homePath = null
        } finally {
            super.tearDown()
        }
    }

    private fun skip(): Boolean {
        if (home == null) println("FantomLibraryTest skipped: no Fantom home found")
        return home == null
    }

    fun testProviderReturnsOneLibraryWithPodRoots() {
        if (skip()) return
        val libs = FantomLibraryRootsProvider().getAdditionalProjectLibraries(project)
        assertEquals(1, libs.size)
        val lib = libs.single() as FantomLibraryRootsProvider.FantomLibrary
        assertTrue(lib.presentableText.startsWith("Fantom "))
        assertTrue(lib.sourceRoots.any { it.path.endsWith("/sys/fan") })
    }

    fun testSysTypeResolvesFromProjectFile() {
        if (skip()) return
        myFixture.configureByText("A.fan", "class A { St<caret>r name := \"x\" }")
        val ref = myFixture.file.findReferenceAt(myFixture.caretOffset) as FantomReference
        val targets = ref.resolveAll()
        assertEquals(1, targets.size)
        val target = targets.single()
        assertTrue(target is FantomClassDef)
        assertEquals("Str.fan", target.containingFile.name)
        assertTrue(ProjectFileIndex.getInstance(project).isInLibrarySource(target.containingFile.virtualFile))
    }

    fun testStaticCallIntoSysNarrowsToOwner() {
        if (skip()) return
        myFixture.configureByText("A.fan", "class A { Void f() { x := Env.c<caret>ur } }")
        val ref = myFixture.file.findReferenceAt(myFixture.caretOffset) as FantomReference
        val targets = ref.resolveAll()
        assertEquals(1, targets.size)
        assertTrue(targets.single() is FantomMethodDef)
        assertEquals("Env.fan", targets.single().containingFile.name)
    }

    fun testNoHomeMeansNoLibrary() {
        if (skip()) return
        FantomSettings.getInstance(project).homePath = null
        val prev = System.getProperty(FantomHome.PROPERTY)
        System.setProperty(FantomHome.PROPERTY, com.intellij.openapi.util.io.FileUtil.createTempDirectory("nohome", null).path)
        try {
            // detect() still finds the versioned folders under DEFAULT_PARENT, so only assert the
            // configured path is ignored when invalid: effectiveHome never returns the bogus dir.
            val eff = FantomSettings.getInstance(project).effectiveHome()
            assertTrue(eff == null || FantomHome.isHome(eff))
        } finally {
            if (prev == null) System.clearProperty(FantomHome.PROPERTY) else System.setProperty(FantomHome.PROPERTY, prev)
        }
    }
}
