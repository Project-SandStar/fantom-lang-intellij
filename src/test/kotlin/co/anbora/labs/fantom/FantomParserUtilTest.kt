package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.FantomParserDefinition
import co.anbora.labs.fantom.lang.core.FantomParserUtil
import co.anbora.labs.fantom.lang.lexer.FantomLexer
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiBuilderFactory
import com.intellij.testFramework.ParsingTestCase

/**
 * Checks the newline and whitespace sensitive externals in [FantomParserUtil]
 * directly on a [PsiBuilder], without going through the grammar.
 */
class FantomParserUtilTest : ParsingTestCase("", "fan", FantomParserDefinition()) {

    override fun getTestDataPath(): String = "src/test/testData"

    private fun builder(text: String): PsiBuilder =
        PsiBuilderFactory.getInstance().createBuilder(FantomParserDefinition(), FantomLexer(), text)

    /** Advances to the first token whose text is [tokenText]. */
    private fun PsiBuilder.seek(tokenText: String): PsiBuilder {
        while (!eof() && tokenText != tokenText()) advanceLexer()
        assertFalse("token '$tokenText' not found", eof())
        return this
    }

    private fun PsiBuilder.tokenText(): String? = this.tokenText

    fun testEosOnNewline() {
        val b = builder("a := 1\nb := 2").seek("b")
        assertTrue(FantomParserUtil.eos(b, 0))
        assertEquals("b", b.tokenText)
    }

    fun testEosOnNewlineAfterLineComment() {
        val b = builder("a := 1 // c\nb := 2").seek("b")
        assertTrue(FantomParserUtil.eos(b, 0))
    }

    fun testEosOnNewlineInsideBlockComment() {
        val b = builder("a := 1 /* x\n y */ b := 2").seek("b")
        assertTrue(FantomParserUtil.eos(b, 0))
    }

    fun testEosFalseOnSameLine() {
        val b = builder("a := 1 b := 2").seek("b")
        assertFalse(FantomParserUtil.eos(b, 0))
        val c = builder("a := 1 /* x */ b := 2").seek("b")
        assertFalse(FantomParserUtil.eos(c, 0))
    }

    fun testEosConsumesSemicolon() {
        val b = builder("a := 1; b := 2").seek(";")
        assertTrue(FantomParserUtil.eos(b, 0))
        assertEquals("b", b.tokenText)
    }

    fun testEosOnRbraceAndEof() {
        val b = builder("{ a := 1 }").seek("}")
        assertTrue(FantomParserUtil.eos(b, 0))
        assertEquals("}", b.tokenText)
        val c = builder("a := 1").seek("1")
        c.advanceLexer()
        assertTrue(c.eof())
        assertTrue(FantomParserUtil.eos(c, 0))
    }

    fun testEosAtFileStart() {
        assertTrue(FantomParserUtil.eos(builder("a"), 0))
        assertTrue(FantomParserUtil.eos(builder("  a"), 0))
    }

    fun testSameLine() {
        assertTrue(FantomParserUtil.sameLine(builder("foo (1)").seek("("), 0))
        assertTrue(FantomParserUtil.sameLine(builder("foo(1)").seek("("), 0))
        assertFalse(FantomParserUtil.sameLine(builder("foo\n(1)").seek("("), 0))
        assertFalse(FantomParserUtil.sameLine(builder("foo\r\n[1]").seek("["), 0))
    }

    fun testNoSpaceBefore() {
        assertTrue(FantomParserUtil.noSpaceBefore(builder("Str? a").seek("?"), 0))
        assertFalse(FantomParserUtil.noSpaceBefore(builder("Str ? a").seek("?"), 0))
        assertFalse(FantomParserUtil.noSpaceBefore(builder("Str/**/? a").seek("?"), 0))
        assertTrue(FantomParserUtil.noSpaceBefore(builder("?"), 0))
    }

    fun testTypeName() {
        assertTrue(FantomParserUtil.typeName(builder("Str x"), 0))
        assertTrue(FantomParserUtil.typeName(builder("_Foo x"), 0))
        assertTrue(FantomParserUtil.typeName(builder("sys::Str x"), 0))
        assertFalse(FantomParserUtil.typeName(builder("foo x"), 0))
        assertFalse(FantomParserUtil.typeName(builder("foo.Bar"), 0))
        assertFalse(FantomParserUtil.typeName(builder("123"), 0))
        assertFalse(FantomParserUtil.typeName(builder("it"), 0))
        // consumes nothing
        val b = builder("Str x")
        FantomParserUtil.typeName(b, 0)
        assertEquals("Str", b.tokenText)
    }

    fun testLexerTokens() {
        fun kinds(text: String): List<String> {
            val b = builder(text)
            val out = ArrayList<String>()
            while (!b.eof()) { out += b.tokenType.toString(); b.advanceLexer() }
            return out
        }
        assertEquals(listOf("IDENTIFIER", "->", "IDENTIFIER"), kinds("a->b"))
        assertEquals(listOf("IDENTIFIER", "?->", "IDENTIFIER"), kinds("a?->b"))
        assertEquals(listOf("|", "IDENTIFIER", "->", "IDENTIFIER", "|"), kinds("|Int->Str|"))
        assertEquals(listOf("|", "->", "|"), kinds("|->|"))
        assertEquals(listOf("INT_LITERAL", "INT_LITERAL", "INT_LITERAL"), kinds("0b101 0b1_0 0xFF"))
        assertEquals(listOf("FLOAT_LITERAL", "FLOAT_LITERAL", "FLOAT_LITERAL"), kinds(".5f 1e3F 1_0.5f"))
        assertEquals(listOf("DECIMAL_LITERAL", "DECIMAL_LITERAL", "DECIMAL_LITERAL"), kinds("1.5 1e3 2d"))
        assertEquals(listOf("DURATION_LITERAL", "DURATION_LITERAL", "DURATION_LITERAL"), kinds("1ns 1.5sec 2day"))
        assertEquals(listOf("IDENTIFIER", "IDENTIFIER", "it", "void", "assert", "do", "foreach", "volatile"),
            kinds("enum facet it void assert do foreach volatile"))
        assertEquals(listOf("IDENTIFIER", "IDENTIFIER"), kinds("a /* x /* y */ z */ b"))
        assertEquals(listOf("IDENTIFIER"), kinds("a /* unterminated /* b"))
    }
}
