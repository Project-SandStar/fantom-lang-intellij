package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.axon.AxonLanguage
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AxonInjectionTest : BasePlatformTestCase() {

    private fun injectedAt(word: String, nth: Int = 0): String? {
        val text = myFixture.file.text
        var at = -1
        repeat(nth + 1) { at = text.indexOf(word, at + 1) }
        assertTrue("'$word' not in text", at >= 0)
        val element = InjectedLanguageManager.getInstance(project).findInjectedElementAt(myFixture.file, at + 1)
        return element?.containingFile?.language?.id
    }

    private fun fan(body: String) {
        myFixture.configureByText("A.fan", "class A\n{\n  Void run(Obj cx)\n  {\n$body\n  }\n}\n")
    }

    fun testEvalCallsAreAxon() {
        fan(
            """
            |    eval("readAll(site)")
            |    cx.evalAll("read(ahu and equip)", 5)
            |    x := evalToFunc("(a) => a + 1")
            |    parseAxon("now() + 1hr")
            |    Axon("10kW * 2")
            """.trimMargin()
        )
        assertEquals(AxonLanguage.id, injectedAt("readAll"))
        assertEquals(AxonLanguage.id, injectedAt("ahu"))
        assertEquals(AxonLanguage.id, injectedAt("(a)"))
        assertEquals(AxonLanguage.id, injectedAt("now"))
        assertEquals(AxonLanguage.id, injectedAt("10kW"))
    }

    fun testOtherStringsAreNot() {
        fan(
            """
            |    echo("readAll(site)")
            |    s := "plain text"
            |    evaluate("x + 1")
            |    eval(prefix + "tail")
            |    eval('c')
            """.trimMargin()
        )
        assertNull(injectedAt("readAll"))
        assertNull(injectedAt("plain"))
        assertNull(injectedAt("x + 1"))
        assertNull(injectedAt("tail"))
    }

    fun testLanguageComment() {
        fan(
            """
            |    // language=axon
            |    q := "readAll(point and his)"
            |    echo(q)
            |  }
            |  Void other()
            |  {
            |    r := "readAll(site)"
            """.trimMargin()
        )
        assertEquals(AxonLanguage.id, injectedAt("point"))
        assertNull(injectedAt("site"))
    }

    fun testEscapesAreDecoded() {
        fan("    eval(\"read(dis==\\\"AHU-1\\\")\")")
        val at = myFixture.file.text.indexOf("read(")
        val injected = InjectedLanguageManager.getInstance(project).findInjectedElementAt(myFixture.file, at + 1)!!
        // The Axon lexer sees the decoded text, so `\"AHU-1\"` is one Axon string (leaf text stays in host form).
        val leaves = PsiTreeUtil.collectElements(injected.containingFile) { it.firstChild == null }
            .map { it.node.elementType.toString() }
        assertEquals(
            listOf("Axon:IDENTIFIER", "Axon:LPAREN", "Axon:IDENTIFIER", "Axon:OPERATOR", "Axon:STRING", "Axon:RPAREN"),
            leaves
        )
    }

    fun testTrioSrcIsAxon() {
        myFixture.configureByText(
            "lib.trio",
            "name: \"f\"\nsrc:\n  (x) => do\n    readAll(site)\n  end\ndoc: \"readAll here is text\"\n"
        )
        assertEquals(AxonLanguage.id, injectedAt("readAll", 0))
        assertNull(injectedAt("readAll", 1))
    }
}
