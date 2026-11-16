package co.anbora.labs.fantom.ide.axon

import co.anbora.labs.fantom.lang.axon.AxonLanguage
import co.anbora.labs.fantom.lang.core.psi.impl.FantomStrInjectionHost
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * [AxonInjector] cases the root `AxonInjectionTest` leaves out: argument position,
 * comment spelling and placement, the injected range, and `isAxonString` itself.
 */
class AxonInjectorTest : BasePlatformTestCase() {

    private fun fan(body: String) {
        myFixture.configureByText("A.fan", "class A\n{\n  Void run(Obj cx)\n  {\n$body\n  }\n}\n")
    }

    private fun injectedAt(word: String): String? {
        val at = myFixture.file.text.indexOf(word)
        assertTrue("'$word' not in text", at >= 0)
        return InjectedLanguageManager.getInstance(project).findInjectedElementAt(myFixture.file, at + 1)
            ?.containingFile?.language?.id
    }

    private fun hostWith(word: String): FantomStrInjectionHost =
        PsiTreeUtil.findChildrenOfType(myFixture.file, FantomStrInjectionHost::class.java).first { it.text.contains(word) }

    fun testCallNames() {
        assertEquals(setOf("eval", "evalAll", "evalToFunc", "parseAxon", "Axon"), AxonInjector.CALL_NAMES)
    }

    /** Only the first argument counts; a later string or a string inside a larger expression does not. */
    fun testOnlyWholeFirstArgument() {
        fan(
            """
            |    eval(cx, "secondArg(x)")
            |    eval("firstArg(y)", "otherArg(z)")
            |    eval("joined(a)" + b)
            """.trimMargin()
        )
        assertNull(injectedAt("secondArg"))
        assertEquals(AxonLanguage.id, injectedAt("firstArg"))
        assertNull(injectedAt("otherArg"))
        assertNull(injectedAt("joined"))
        assertTrue(AxonInjector.isAxonString(hostWith("firstArg")))
        assertFalse(AxonInjector.isAxonString(hostWith("otherArg")))
    }

    /**
     * The comment matches in any case and with spaces. The injector reads only the line
     * just above; `axonx` names another language.
     */
    fun testLanguageCommentSpelling() {
        fan(
            """
            |    //  Language = AXON  (query)
            |    a := "upperCase(p)"
            |    // language=axon
            |
            |    b := "gapLine(q)"
            |    // language=axonx
            |    c := "otherLang(r)"
            """.trimMargin()
        )
        assertEquals(AxonLanguage.id, injectedAt("upperCase"))
        // AxonInjector itself wants the comment on the line just above...
        assertFalse(AxonInjector.isAxonString(hostWith("gapLine")))
        assertTrue(AxonInjector.isAxonString(hostWith("upperCase")))
        // ...but the string still gets Axon from another injector (most likely the
        // platform's own `language=` comment support, which skips the blank line).
        assertEquals(AxonLanguage.id, injectedAt("gapLine"))
        assertNull(injectedAt("otherLang"))
    }

    /** A string on the first line of a file has no line above it. */
    fun testStringOnFirstLine() {
        myFixture.configureByText("A.fan", "const Str q := \"readAll(site)\"\n")
        assertNull(injectedAt("readAll"))
    }

    /** The injected text is the string content, without the quotes. */
    fun testInjectedRangeIsContent() {
        fan("    eval(\"readAll(site)\")")
        val at = myFixture.file.text.indexOf("readAll")
        val injected = InjectedLanguageManager.getInstance(project).findInjectedElementAt(myFixture.file, at)!!
        assertEquals("readAll(site)", injected.containingFile.text)
    }

    /** A `.trio` record without `src:` gets no Axon, even when a value reads like Axon. */
    fun testTrioWithoutSrc() {
        myFixture.configureByText("lib.trio", "name: \"f\"\ndoc: \"readAll(site)\"\n")
        assertNull(injectedAt("readAll"))
    }
}
