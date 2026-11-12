package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.generate.FantomImplementMethodsHandler
import co.anbora.labs.fantom.ide.generate.FantomOverrideBaseHandler
import co.anbora.labs.fantom.ide.generate.FantomOverrideCandidates
import co.anbora.labs.fantom.ide.generate.FantomOverrideMethodsHandler
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomSettings
import co.anbora.labs.fantom.lang.resolve.FantomScope
import com.intellij.icons.AllIcons
import com.intellij.lang.LanguageCodeInsightActionHandler
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex

/**
 * Alt+Insert Override/Implement Members and the override gutter markers.
 * The library test needs a Fantom home on this machine and skips without one.
 */
class FantomOverrideTest : BasePlatformTestCase() {

    private val mixin = "mixin M { abstract Str name(); virtual Void go() {} }\n"

    override fun tearDown() {
        try {
            FantomOverrideBaseHandler.testSelection = null
            FantomSettings.getInstance(project).homePath = null
        } finally {
            super.tearDown()
        }
    }

    private fun invoke(handler: LanguageCodeInsightActionHandler) {
        WriteCommandAction.runWriteCommandAction(project) {
            handler.invoke(project, myFixture.editor, myFixture.file)
        }
    }

    /**
     * On a machine with a Fantom install, `Obj` resolves in every test and its virtual
     * slots join the list. Keep the expected text stable: take only slots of [owner].
     */
    private fun onlyFrom(owner: String) {
        FantomOverrideBaseHandler.testSelection = { all -> all.filter { it.owner.name == owner } }
    }

    fun testImplementGeneratesAbstractOnly() {
        myFixture.configureByText("A.fan", mixin + "class A : M { <caret> }")
        invoke(FantomImplementMethodsHandler())
        myFixture.checkResult(
            mixin + """
            class A : M {
              override Str name()
              {
                throw UnsupportedErr()
              }
            }
            """.trimIndent()
        )
    }

    fun testOverrideGeneratesAbstractAndVirtual() {
        myFixture.configureByText("A.fan", mixin + "class A : M { <caret> }")
        onlyFrom("M")
        invoke(FantomOverrideMethodsHandler())
        myFixture.checkResult(
            mixin + """
            class A : M {
              override Str name()
              {
                throw UnsupportedErr()
              }

              override Void go()
              {
                super.go()
              }
            }
            """.trimIndent()
        )
    }

    fun testSecondInvokeAddsNothing() {
        myFixture.configureByText("A.fan", mixin + "class A : M { <caret> }")
        invoke(FantomOverrideMethodsHandler())
        val after = myFixture.file.text
        invoke(FantomOverrideMethodsHandler())
        assertEquals(after, myFixture.file.text)
        invoke(FantomImplementMethodsHandler())
        assertEquals(after, myFixture.file.text)
    }

    fun testIndentFollowsPreviousSlotAndParamsAreCopied() {
        val base = "class B { virtual Int add(Int a, Str? b := null) { 1 } }\n"
        myFixture.configureByText("A.fan", base + "class A : B\n{\n    Int x := 1<caret>\n}\n")
        onlyFrom("B")
        invoke(FantomOverrideMethodsHandler())
        myFixture.checkResult(
            base + """
            class A : B
            {
                Int x := 1

                override Int add(Int a, Str? b)
                {
                  return super.add(a, b)
                }
            }

            """.trimIndent()
        )
    }

    fun testAbstractFieldAndSkippedSlots() {
        val base = "mixin M { abstract Bool on; static Void s() {} private Void p() {} Void plain() {} }\n"
        myFixture.configureByText("A.fan", base + "class A : M { <caret> }")
        val type = enclosingType()
        val names = FantomOverrideCandidates.candidates(type, implementOnly = false).filter { it.owner.name == "M" }.map { it.name }
        assertEquals(listOf("on"), names)
        invoke(FantomImplementMethodsHandler())
        myFixture.checkResult(base + "class A : M {\n  override Bool on\n}")
    }

    fun testSelectionHookPicksSubset() {
        myFixture.configureByText("A.fan", mixin + "class A : M { <caret> }")
        FantomOverrideBaseHandler.testSelection = { all -> all.filter { it.name == "go" } }
        invoke(FantomOverrideMethodsHandler())
        assertTrue(myFixture.file.text.contains("override Void go()"))
        assertFalse(myFixture.file.text.contains("override Str name()"))
    }

    fun testObjSlotsOfferedWithLibrary() {
        val home = FantomHome.detect() ?: run { println("skipped: no Fantom home"); return }
        FantomSettings.getInstance(project).homePath = home.path
        WriteAction.runAndWait<RuntimeException> {
            ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(EmptyRunnable.getInstance(), false, true)
        }
        FileBasedIndex.getInstance().ensureUpToDate(FantomDeclarationIndex.NAME, project, null)
        try {
            myFixture.configureByText("A.fan", "class A { <caret> }")
            val names = FantomOverrideCandidates.candidates(enclosingType(), implementOnly = false).map { it.name }
            assertTrue(names.toString(), names.containsAll(listOf("toStr", "hash", "equals", "compare")))
            assertFalse(names.toString(), names.contains("echo"))
            assertTrue(FantomOverrideCandidates.candidates(enclosingType(), implementOnly = true).isEmpty())
        } finally {
            FantomSettings.getInstance(project).homePath = null
            WriteAction.runAndWait<RuntimeException> {
                ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(EmptyRunnable.getInstance(), false, true)
            }
        }
    }

    fun testOverridingGutterOnOverrideSlot() {
        myFixture.configureByText("A.fan", mixin + "class A : M { override Str name() { \"a\" } }")
        myFixture.doHighlighting()
        val gutters = myFixture.findAllGutters()
        assertTrue(gutters.map { it.tooltipText }.toString(), gutters.any { it.icon == AllIcons.Gutter.OverridingMethod })
        val overriding = gutters.first { it.icon == AllIcons.Gutter.OverridingMethod }
        assertEquals("Overrides M.name", overriding.tooltipText)
    }

    fun testOverriddenGutterOnBaseSlot() {
        myFixture.configureByText("A.fan", mixin + "class A : M { override Str name() { \"a\" } }")
        myFixture.doHighlighting()
        val gutters = myFixture.findAllGutters()
        val overridden = gutters.filter { it.icon == AllIcons.Gutter.OverridenMethod }
        assertEquals(gutters.map { it.tooltipText }.toString(), 1, overridden.size)
        assertEquals("Overridden in A", overridden.single().tooltipText)
    }

    fun testNoGutterWithoutOverride() {
        myFixture.configureByText("A.fan", mixin + "class A : M { Str other() { \"a\" } }")
        myFixture.doHighlighting()
        val gutters = myFixture.findAllGutters()
        assertTrue(gutters.none { it.icon == AllIcons.Gutter.OverridingMethod || it.icon == AllIcons.Gutter.OverridenMethod })
    }

    private fun enclosingType(): FantomNamedElement {
        val at = myFixture.file.findElementAt(myFixture.caretOffset) ?: myFixture.file.findElementAt(myFixture.caretOffset - 1)!!
        return FantomScope.enclosingType(at)!!
    }
}
