package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomLibraryRootsProvider
import co.anbora.labs.fantom.lang.library.FantomSettings
import co.anbora.labs.fantom.lang.resolve.FantomReference
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex
import java.io.File

/**
 * Home = haxall-4.0.7 (no `sys`), Fantom sources = fantom-1.0.84. Haxall's nested pods and
 * Fantom's `sys` are both indexed: `xeto::Dict`, `haystack::Etc` and `sys::Str` resolve and
 * complete. Skipped when either install is missing. All Haxall pods are indexed.
 */
class FantomHaxallLibraryTest : BasePlatformTestCase() {

    private val haxall = File(System.getProperty("user.home"), "haxall/haxall-4.0.7")
    private val fantom = File(System.getProperty("user.home"), "fantom/fantom-1.0.84")
    private val ready get() = haxall.isDirectory && fantom.isDirectory

    override fun setUp() {
        super.setUp()
        if (!ready) return
        val settings = FantomSettings.getInstance(project)
        settings.homePath = haxall.path
        settings.sourcesPath = fantom.path
        val t0 = System.nanoTime()
        rootsChanged()
        FileBasedIndex.getInstance().ensureUpToDate(FantomDeclarationIndex.NAME, project, null)
        println("FantomHaxallLibraryTest: roots + index ${(System.nanoTime() - t0) / 1_000_000}ms")
    }

    override fun tearDown() {
        try {
            if (ready) {
                FantomSettings.getInstance(project).homePath = null
                FantomSettings.getInstance(project).sourcesPath = null
                rootsChanged()
            }
        } finally {
            super.tearDown()
        }
    }

    private fun rootsChanged() {
        FantomSettings.clearPodCache()
        WriteAction.runAndWait<RuntimeException> {
            ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(EmptyRunnable.getInstance(), com.intellij.openapi.project.RootsChangeRescanningInfo.TOTAL_RESCAN)
        }
    }

    private fun skip(): Boolean {
        if (!ready) println("FantomHaxallLibraryTest skipped: ${haxall.path} or ${fantom.path} missing")
        return !ready
    }

    private fun resolveAt(text: String): PsiElement {
        myFixture.configureByText("A.fan", text)
        val ref = myFixture.file.findReferenceAt(myFixture.caretOffset) as FantomReference
        return ref.resolveAll().single()
    }

    private fun pathOf(e: PsiElement) = e.containingFile.virtualFile.path

    fun testSettingsAndLibraries() {
        if (skip()) return
        val settings = FantomSettings.getInstance(project)
        assertEquals(fantom, settings.effectiveHome()) // Haxall has no bin/fan
        val pods = settings.podSources()
        val libs = FantomLibraryRootsProvider().getAdditionalProjectLibraries(project)
            .map { it as FantomLibraryRootsProvider.FantomLibrary }
        val fromHaxall = pods.count { it.podDir.startsWith(haxall) }
        println("FantomHaxallLibraryTest: ${libs.size} libraries, ${pods.sumOf { it.roots.size }} roots " +
            "($fromHaxall Haxall pods, ${pods.size - fromHaxall} Fantom pods)")
        assertEquals(pods.size, libs.size)
        assertEquals(pods.size, pods.map { it.pod }.toSet().size) // de-duplicated
        assertTrue(libs.any { it.presentableText == "haystack" && it.podDir == File(haxall, "src/core/haystack") })
        assertTrue(libs.any { it.presentableText == "sys" && it.podDir == File(fantom, "src/sys") })
        // A pod in both installs is taken from the home.
        val bothNames = FantomHome.podSources(haxall).map { it.pod }.toSet() intersect
            FantomHome.podSources(fantom).map { it.pod }.toSet()
        for (name in bothNames) assertTrue(name, pods.single { it.pod == name }.podDir.startsWith(haxall))
    }

    fun testHaxallAndSysTypesResolve() {
        if (skip()) return
        val dict = resolveAt("using xeto\nusing haystack\nclass A { Di<caret>ct? d }")
        assertEquals(File(haxall, "src/core/xeto/fan/Dict.fan").path, pathOf(dict))
        assertTrue(ProjectFileIndex.getInstance(project).isInLibrarySource(dict.containingFile.virtualFile))
        val etc = resolveAt("using xeto\nusing haystack\nclass A { Void f() { x := Et<caret>c.emptyDict } }")
        assertEquals(File(haxall, "src/core/haystack/fan/Etc.fan").path, pathOf(etc))
        val str = resolveAt("using xeto\nusing haystack\nclass A { St<caret>r name := \"x\" }")
        assertEquals(File(fantom, "src/sys/fan/Str.fan").path, pathOf(str))
    }

    fun testDictMembersComplete() {
        if (skip()) return
        myFixture.configureByText(
            "A.fan",
            "using xeto\nusing haystack\nclass A { Void f() { Dict d := Etc.emptyDict; d.<caret> } }"
        )
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings ?: emptyList()
        assertTrue(items.take(30).toString(), "get" in items)
        assertTrue(items.take(30).toString(), "has" in items)
    }

    fun testStrMembersComplete() {
        if (skip()) return
        myFixture.configureByText("A.fan", "using haystack\nclass A { Void f() { s := \"x\"; s.<caret> } }")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings ?: emptyList()
        assertTrue(items.take(30).toString(), "size" in items)
    }
}
