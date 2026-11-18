package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.axon.AxonParserDefinition
import co.anbora.labs.fantom.lang.axon.parser.AxonParserUtil
import co.anbora.labs.fantom.lang.axon.psi.AxonDefExpr
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase

/**
 * Errors that the Haxall parser reports and that a token type alone does not show:
 * a string or URI with no closing quote, and a `name: value` pair in a list.
 */
class AxonParserErrorTest : ParsingTestCase("", "axon", AxonParserDefinition()) {

    override fun getTestDataPath(): String = "src/test/testData"

    private fun parse(text: String): PsiFile = createPsiFile("a", text).also { ensureParsed(it) }

    private fun errors(text: String): List<String> =
        PsiTreeUtil.collectElementsOfType(parse(text), PsiErrorElement::class.java).map { it.errorDescription }

    fun testOpenStringIsAnError() {
        assertEquals(listOf("Unterminated string"), errors("x: \"abc"))
        assertEquals(listOf("Unterminated string"), errors("x: \"abc\\\""))
        assertEquals(listOf("Unterminated string"), errors("x: r\"abc"))
        assertEquals(listOf("Unterminated string"), errors("x: \"\"\"abc\ny: 1"))
        assertEquals(listOf("Unterminated string"), errors("x: \"\"\"abc\"\""))
        assertEquals(listOf("Unterminated string"), errors("x: {\"a b: 1}").take(1))
    }

    fun testOpenUriIsAnError() {
        assertEquals(listOf("Unterminated URI"), errors("x: `http://demo/"))
    }

    /** A string ends at the line end, so the next line is parsed as code. */
    fun testLineAfterOpenStringIsParsed() {
        val text = "x: \"abc\ny: 1\nz: `demo\nw: 2\n"
        assertEquals(listOf("Unterminated string", "Unterminated URI"), errors(text))
        val names = PsiTreeUtil.collectElementsOfType(parse(text), AxonDefExpr::class.java).map { it.name }
        assertEquals(listOf("x", "y", "z", "w"), names)
    }

    fun testClosedLiteralsHaveNoError() {
        val sources = listOf(
            "x: \"\"", "x: \"a\"", "x: \"a\\\"b\"", "x: \"a\\\\\"", "x: r\"a\\b\"", "x: r\"\"",
            "x: \"\"\"\"\"\"", "x: \"\"\"a \"b\" c\"\"\"", "x: \"\"\"a\nb\"\"\"",
            "x: `a`", "x: ``", "x: `a\\`b`", "x: {\"a b\": 1, \"c\"}",
        )
        for (s in sources) assertEquals(s, emptyList<String>(), errors(s))
    }

    /** `Tokenizer.skipCommentML` stops at the end of the text with no error. */
    fun testOpenBlockCommentIsNoError() {
        assertEquals(emptyList<String>(), errors("x: 1\n/* open /* nested */\ny: 2"))
    }

    fun testIsClosedLiteral() {
        assertTrue(AxonParserUtil.isClosedLiteral("\"a\""))
        assertTrue(AxonParserUtil.isClosedLiteral("\"\"\"a\"\"\""))
        assertFalse(AxonParserUtil.isClosedLiteral("\""))
        assertFalse(AxonParserUtil.isClosedLiteral("r\""))
        assertFalse(AxonParserUtil.isClosedLiteral("\"\"\"a\""))
        assertFalse(AxonParserUtil.isClosedLiteral("`a\\`"))
    }

    fun testPairInListIsAnError() {
        val message = "A list cannot hold 'name: value' pairs (use a dict {name: value})"
        assertEquals(listOf(message), errors("x: [a: 1]"))
        assertEquals(listOf(message, message), errors("x: [a: 1, b: 2]\ny: 3"))
        assertEquals(listOf(message), errors("x: [1, b: 2,]"))
    }

    fun testListItemsThatLookLikePairsHaveNoError() {
        val sources = listOf("x: [a, b]", "x: [{a: 1}]", "x: [(a: 1) => a]", "x: [a => a, f(b: 2)]", "x: [a.b, c::d]", "x: []", "x: [1,]")
        for (s in sources) assertEquals(s, emptyList<String>(), errors(s))
    }
}
