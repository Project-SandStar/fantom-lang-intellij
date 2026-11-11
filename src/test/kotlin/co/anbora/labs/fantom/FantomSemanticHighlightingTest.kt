package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.color.FantomColors
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Semantic colours from [co.anbora.labs.fantom.ide.annotator.HighlightingAnnotator].
 * The tests read the raw [HighlightInfo]s instead of `checkHighlighting`, so an
 * identifier that does not resolve (no `sys` model yet) must simply have no colour.
 */
class FantomSemanticHighlightingTest : BasePlatformTestCase() {

    private fun highlight(text: String): List<HighlightInfo> {
        myFixture.configureByText("A.fan", text.trimIndent())
        return myFixture.doHighlighting()
    }

    /** External names of the text attribute keys of every info that covers exactly [word] at its [nth] occurrence. */
    private fun keysAt(infos: List<HighlightInfo>, word: String, nth: Int = 0): Set<String> {
        val text = myFixture.file.text
        var start = -1
        repeat(nth + 1) { start = text.indexOf(word, start + 1) }
        assertTrue("'$word' #$nth not in\n$text", start >= 0)
        val end = start + word.length
        return infos.filter { it.startOffset == start && it.endOffset == end }
            .mapNotNull { it.forcedTextAttributesKey?.externalName ?: it.type.attributesKey?.externalName }
            .toSet()
    }

    private fun key(c: FantomColors) = c.textAttributesKey.externalName

    fun testLocalUse() {
        val infos = highlight("class A { Void f() { total := 1; echo(total) } }")
        assertEquals(setOf(key(FantomColors.LOCAL_VARIABLE)), keysAt(infos, "total", 1))
        // The declaration name is not a use: no colour.
        assertEquals(emptySet<String>(), keysAt(infos, "total", 0))
    }

    fun testParamUse() {
        val infos = highlight("class A { Void f(Int count) { echo(count) } }")
        assertEquals(setOf(key(FantomColors.PARAMETER)), keysAt(infos, "count", 1))
    }

    fun testFieldUseBareAndOnThis() {
        val infos = highlight("class A { Int total := 0; Void f() { total = 1; this.total = 2 } }")
        assertEquals(setOf(key(FantomColors.INSTANCE_FIELD)), keysAt(infos, "total", 1))
        assertEquals(setOf(key(FantomColors.INSTANCE_FIELD)), keysAt(infos, "total", 2))
    }

    fun testMethodCall() {
        val infos = highlight("class A { Void foo() {} Void f() { foo() } }")
        assertEquals(setOf(key(FantomColors.FUNCTION_CALL)), keysAt(infos, "foo", 1))
    }

    fun testTypeUseInStaticCallAndCtorCall() {
        myFixture.addFileToProject("B.fan", "class B { static Void go() {} }")
        // `B` in `B.go` and in `B()` sits under FantomSimple / static call, which the
        // old type branch leaves bare: only the resolver gives it a colour.
        val infos = highlight("class A { Void f() { B.go; b := B() } }")
        assertEquals(setOf(key(FantomColors.TYPES)), keysAt(infos, "B", 0))
        assertEquals(setOf(key(FantomColors.TYPES)), keysAt(infos, "B", 1))
        assertEquals(setOf(key(FantomColors.FUNCTION_CALL)), keysAt(infos, "go", 0))
    }

    fun testLibraryNamesNeverWarnAndColourOnlyWhenResolved() {
        val infos = highlight("""
            class A {
              Str name := "x"
              Void f() { echo(name.toStr); s := Str.defVal }
            }
        """)
        val bad = infos.filter { it.severity >= HighlightSeverity.WARNING }
        assertTrue("unexpected: " + bad.joinToString { "${it.severity} '${it.text}' ${it.description}" }, bad.isEmpty())
        // With a Fantom install on the machine the library resolves sys names; without one they stay neutral.
        val hasSys = co.anbora.labs.fantom.lang.library.FantomSettings.getInstance(project).effectiveHome() != null
        if (hasSys) {
            assertEquals(setOf(key(FantomColors.FUNCTION_CALL)), keysAt(infos, "echo"))
            // `Str` #1 is the tail of `toStr`; the type in `Str.defVal` is #2.
            assertEquals(setOf(key(FantomColors.TYPES)), keysAt(infos, "Str", 2))
        } else {
            assertEquals(emptySet<String>(), keysAt(infos, "echo"))
            assertEquals(emptySet<String>(), keysAt(infos, "Str", 2))
            // Unresolved `recv.name` keeps the old method call colour.
            assertEquals(setOf(key(FantomColors.METHOD_CALL)), keysAt(infos, "toStr"))
            assertEquals(setOf(key(FantomColors.METHOD_CALL)), keysAt(infos, "defVal"))
        }
    }
}
