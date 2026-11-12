package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.color.FantomColorSettingPage
import co.anbora.labs.fantom.ide.color.FantomColors
import co.anbora.labs.fantom.ide.highlight.FantomSyntaxHighlighter
import co.anbora.labs.fantom.lang.FantomLanguage
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Colour keys at exact offsets: the annotator part through [HighlightInfo]s, the
 * lexer part through [FantomSyntaxHighlighter]. Also checks the colour page
 * covers every [FantomColors] entry and that its demo text parses.
 */
class FantomColorsTest : BasePlatformTestCase() {

    private fun highlight(text: String): List<HighlightInfo> {
        myFixture.configureByText("A.fan", text.trimIndent())
        return myFixture.doHighlighting()
    }

    private fun offsetOf(word: String, nth: Int = 0): Int {
        val text = myFixture.file.text
        var start = -1
        repeat(nth + 1) { start = text.indexOf(word, start + 1) }
        assertTrue("'$word' #$nth not in\n$text", start >= 0)
        return start
    }

    /** Key names of every info that covers exactly [word] at its [nth] occurrence. */
    private fun keysAt(infos: List<HighlightInfo>, word: String, nth: Int = 0): Set<String> {
        val start = offsetOf(word, nth)
        val end = start + word.length
        return infos.filter { it.startOffset == start && it.endOffset == end }
            .mapNotNull { it.forcedTextAttributesKey?.externalName ?: it.type.attributesKey?.externalName }
            .toSet()
    }

    private fun key(c: FantomColors) = c.textAttributesKey.externalName
    private fun only(c: FantomColors) = setOf(key(c))

    /** Lexer colour keys of the token that starts at [offset] in [text]. */
    private fun lexerKeysAt(text: String, offset: Int): Set<String> {
        val highlighter = FantomSyntaxHighlighter()
        val lexer = highlighter.highlightingLexer
        lexer.start(text)
        while (lexer.tokenType != null) {
            if (lexer.tokenStart == offset) {
                return highlighter.getTokenHighlights(lexer.tokenType!!).map { it.externalName }.toSet()
            }
            lexer.advance()
        }
        fail("no token starts at $offset in\n$text")
        return emptySet()
    }

    private val sample = """
        @Serializable
        const class Box {
          const Int max := 10
          static Int count := 0
          Int size := 0
          static Void bump() { count++ }
          Void run(Int amount) {
            Box.bump
            total := amount + max
            echo("size ${'$'}size of ${'$'}{total}\n")
            [1, 2].each |Int x| { size = x + total }
          }
        }
        enum class Dir { north, south }
    """

    fun testDeclarationNames() {
        val infos = highlight(sample)
        assertEquals(only(FantomColors.CLASS_DECLARATION), keysAt(infos, "Box", 0))
        assertEquals(only(FantomColors.CLASS_DECLARATION), keysAt(infos, "Dir", 0))
        assertEquals(only(FantomColors.METHOD_DECLARATION), keysAt(infos, "run", 0))
        assertEquals(only(FantomColors.FIELD_DECLARATION), keysAt(infos, "size", 0))
        // Static and const slots take their colour at the declaration too.
        assertEquals(only(FantomColors.STATIC_METHOD), keysAt(infos, "bump", 0))
        assertEquals(only(FantomColors.STATIC_FIELD), keysAt(infos, "count", 0))
        assertEquals(only(FantomColors.CONSTANT), keysAt(infos, "max", 0))
        assertEquals(only(FantomColors.CONSTANT), keysAt(infos, "north", 0))
    }

    fun testUses() {
        val infos = highlight(sample)
        assertEquals(only(FantomColors.STATIC_METHOD), keysAt(infos, "bump", 1))
        assertEquals(only(FantomColors.STATIC_FIELD), keysAt(infos, "count", 1))
        assertEquals(only(FantomColors.CONSTANT), keysAt(infos, "max", 1))
        assertEquals(only(FantomColors.TYPE_REFERENCE), keysAt(infos, "Box", 1))
        assertEquals(only(FantomColors.INSTANCE_FIELD), keysAt(infos, "size", 3))
        assertEquals(only(FantomColors.PARAMETER), keysAt(infos, "amount", 1))
        assertEquals(only(FantomColors.LOCAL_VARIABLE), keysAt(infos, "total", 2))
    }

    fun testStringInterpolationAndEscape() {
        val infos = highlight(sample)
        assertEquals(only(FantomColors.STRING_INTERPOLATION), keysAt(infos, "\$size"))
        assertEquals(only(FantomColors.STRING_INTERPOLATION), keysAt(infos, "\${total}"))
        assertEquals(only(FantomColors.STRING_ESCAPE), keysAt(infos, "\\n"))
        // Plain string text in between carries no annotation.
        assertEquals(emptySet<String>(), keysAt(infos, " of "))
    }

    fun testFacetAndClosurePipe() {
        val infos = highlight(sample)
        assertEquals(only(FantomColors.FACET), keysAt(infos, "@Serializable"))
        assertEquals(only(FantomColors.CLOSURE_PIPE), keysAt(infos, "|", 0))
        assertEquals(only(FantomColors.CLOSURE_PIPE), keysAt(infos, "|", 1))
    }

    fun testFandocCodeSpan() {
        val infos = highlight("""
            ** Calls `go` once.
            class A { Void go() {} }
        """)
        assertEquals(only(FantomColors.FANDOC_TAG), keysAt(infos, "`go`"))
    }

    fun testNoWarningsInSample() {
        val infos = highlight(sample)
        val bad = infos.filter { it.severity >= HighlightSeverity.WARNING }
        assertTrue(bad.joinToString { "${it.severity} '${it.text}'" }, bad.isEmpty())
    }

    fun testLexerColours() {
        val text = "class A { Void f() { x := 1 + 2; y := `a`; z := 5sec; c := 'c'; this.f } }"
        assertEquals(only(FantomColors.BRACES), lexerKeysAt(text, text.indexOf('{')))
        assertEquals(only(FantomColors.PARENTHESES), lexerKeysAt(text, text.indexOf('(')))
        assertEquals(only(FantomColors.OPERATOR), lexerKeysAt(text, text.indexOf('+')))
        assertEquals(only(FantomColors.OPERATOR), lexerKeysAt(text, text.indexOf(":=")))
        assertEquals(only(FantomColors.SEMICOLON), lexerKeysAt(text, text.indexOf(';')))
        assertEquals(only(FantomColors.DOT), lexerKeysAt(text, text.indexOf("this.") + 4))
        assertEquals(only(FantomColors.URI), lexerKeysAt(text, text.indexOf('`')))
        assertEquals(only(FantomColors.DURATION), lexerKeysAt(text, text.indexOf("5sec")))
        assertEquals(only(FantomColors.CHAR), lexerKeysAt(text, text.indexOf("'c'")))
        assertEquals(only(FantomColors.THIS_SUPER_IT), lexerKeysAt(text, text.indexOf("this")))
        assertEquals(only(FantomColors.KEY_WORD), lexerKeysAt(text, 0))
        assertEquals(only(FantomColors.NUMBERS), lexerKeysAt(text, text.indexOf('1')))
    }

    fun testColorPageCoversEveryKey() {
        val page = FantomColorSettingPage()
        val onPage = page.attributeDescriptors.map { it.key.externalName }.toSet()
        for (c in FantomColors.values()) {
            assertTrue("no descriptor for ${c.name}", key(c) in onPage)
        }
        // Every preview tag points at a key on the page.
        for ((tag, k) in page.additionalHighlightingTagToDescriptorMap) {
            assertTrue("tag <$tag> uses a key that is not on the page", k.externalName in onPage)
        }
    }

    fun testDemoTextParses() {
        val demo = FantomLanguage.LANGUAGE_DEMO_TEXT
        var plain = demo
        for (tag in FantomColorSettingPage.DEMO_TAGS.keys) {
            plain = plain.replace("<$tag>", "").replace("</$tag>", "")
        }
        assertFalse("unknown tag left in demo text", Regex("</?[a-z]+>").containsMatchIn(plain))
        myFixture.configureByText("Demo.fan", plain)
        val errors = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiErrorElement::class.java)
        assertTrue(errors.joinToString("\n") { "${it.textOffset}: ${it.errorDescription}" }, errors.isEmpty())
    }
}
