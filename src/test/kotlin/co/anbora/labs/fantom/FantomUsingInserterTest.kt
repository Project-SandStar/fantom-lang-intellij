package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.imports.FantomUsingInserter
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** [FantomUsingInserter]: where the `using` line goes. No install needed. */
class FantomUsingInserterTest : BasePlatformTestCase() {

    private fun insert(text: String, pod: String, type: String?, ifNeeded: Boolean = false): String {
        myFixture.configureByText("A.fan", text)
        WriteCommandAction.runWriteCommandAction(project) {
            if (ifNeeded) FantomUsingInserter.insertIfNeeded(myFixture.file, pod, type!!)
            else FantomUsingInserter.insert(myFixture.file, pod, type)
        }
        return myFixture.editor.document.text
    }

    fun testStatement() {
        assertEquals("using concurrent", FantomUsingInserter.statement("concurrent", null))
        assertEquals("using concurrent::Actor", FantomUsingInserter.statement("concurrent", "Actor"))
    }

    fun testTopOfFileWithoutUsings() {
        assertEquals("using util::Json\nclass A {}", insert("class A {}", "util", "Json"))
    }

    fun testAfterLastUsing() {
        assertEquals(
            "using inet\nusing util::Json\nusing concurrent::Actor\n\nclass A {}",
            insert("using inet\nusing util::Json\n\nclass A {}", "concurrent", "Actor"),
        )
    }

    fun testWholePodUsing() {
        assertEquals("using concurrent\nclass A {}", insert("class A {}", "concurrent", null))
    }

    fun testAfterShebang() {
        assertEquals("#! /usr/bin/env fan\nusing util::Json\nclass A {}", insert("#! /usr/bin/env fan\nclass A {}", "util", "Json"))
    }

    /** Current behaviour: with no newline after the shebang, the line is added at the end with no line break before it. */
    fun testShebangOnlyWithoutNewline() {
        assertEquals("#! /usr/bin/env fanusing util::Json\n", insert("#! /usr/bin/env fan", "util", "Json"))
    }

    fun testIfNeededSkipsVisibleType() {
        val text = "using util::Json\nclass A {}"
        assertEquals(text, insert(text, "util", "Json", ifNeeded = true))
        val whole = "using util\nclass A {}"
        assertEquals(whole, insert(whole, "util", "Json", ifNeeded = true))
        assertEquals("class A {}", insert("class A {}", "sys", "Str", ifNeeded = true))
    }

    fun testIfNeededAddsMissingType() {
        assertEquals("using util::Json\nclass A {}", insert("class A {}", "util", "Json", ifNeeded = true))
    }
}
