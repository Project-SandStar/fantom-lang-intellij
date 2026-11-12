package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.templates.FantomTemplateContextType
import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.impl.TemplateSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Live templates from `liveTemplates/Fantom.xml` and the Fantom template context. */
class FantomLiveTemplateTest : BasePlatformTestCase() {

    private fun template(key: String) = TemplateSettings.getInstance().getTemplate(key, "Fantom")

    fun testMainTemplateIsRegistered() {
        val t = template("main")
        assertNotNull(t)
        assertTrue(t!!.string, t.string.contains("static Void main"))
    }

    fun testEachTemplateIsRegistered() {
        val t = template("each")
        assertNotNull(t)
        assertTrue(t!!.string, t.string.contains(".each |"))
    }

    fun testClassTemplateIsRegistered() {
        val t = template("class")
        assertNotNull(t)
        assertTrue(t!!.string, t.string.contains("class "))
    }

    fun testAllTemplatesAreRegistered() {
        val keys = listOf("main", "if", "ife", "for", "while", "each", "try", "tryf", "sw", "echo", "doc", "class", "fn")
        val missing = keys.filter { template(it) == null }
        assertTrue("missing templates: $missing", missing.isEmpty())
    }

    fun testContextIsTrueInFantomFile() {
        val file = myFixture.configureByText("A.fan", "class A { }")
        assertTrue(FantomTemplateContextType().isInContext(TemplateActionContext.expanding(file, 0)))
    }

    fun testContextIsFalseInTextFile() {
        val file = myFixture.configureByText("A.txt", "class A { }")
        assertFalse(FantomTemplateContextType().isInContext(TemplateActionContext.expanding(file, 0)))
    }

    fun testMainTemplateExpandsInEditor() {
        myFixture.configureByText("A.fan", "class A {\n  main<caret>\n}")
        myFixture.type('\t')
        myFixture.checkResult("class A {\n  static Void main(Str[] args) {\n    <caret>\n  }\n}")
    }
}
