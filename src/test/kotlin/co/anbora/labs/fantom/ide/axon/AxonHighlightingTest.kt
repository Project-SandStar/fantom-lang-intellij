package co.anbora.labs.fantom.ide.axon

import co.anbora.labs.fantom.ide.color.FantomColors
import co.anbora.labs.fantom.lang.axon.AxonLanguage
import co.anbora.labs.fantom.lang.axon.AxonLexer
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes
import com.intellij.lang.LanguageBraceMatching
import com.intellij.lang.LanguageCommenters
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Axon colours, colour page, brace matcher and commenter. */
class AxonHighlightingTest : BasePlatformTestCase() {

    private fun keysOf(type: IElementType): List<TextAttributesKey> = AxonSyntaxHighlighter().getTokenHighlights(type).toList()

    fun testTokenColours() {
        val expected = mapOf(
            AxonTokenTypes.KEYWORD to AxonColors.KEYWORD,
            AxonTokenTypes.TYPENAME to AxonColors.TYPENAME,
            AxonTokenTypes.STRING to AxonColors.STRING,
            AxonTokenTypes.URI to AxonColors.URI,
            AxonTokenTypes.NUMBER to AxonColors.NUMBER,
            AxonTokenTypes.REF to AxonColors.REF,
            AxonTokenTypes.SYMBOL to AxonColors.SYMBOL,
            AxonTokenTypes.LINE_COMMENT to AxonColors.LINE_COMMENT,
            AxonTokenTypes.BLOCK_COMMENT to AxonColors.LINE_COMMENT,
            AxonTokenTypes.OPERATOR to AxonColors.OPERATOR,
            AxonTokenTypes.COLON to AxonColors.OPERATOR,
            AxonTokenTypes.FN_ARROW to AxonColors.FN_ARROW,
            AxonTokenTypes.LPAREN to AxonColors.PARENTHESES,
            AxonTokenTypes.RPAREN to AxonColors.PARENTHESES,
            AxonTokenTypes.LBRACKET to AxonColors.BRACKETS,
            AxonTokenTypes.RBRACKET to AxonColors.BRACKETS,
            AxonTokenTypes.LBRACE to AxonColors.BRACES,
            AxonTokenTypes.RBRACE to AxonColors.BRACES,
            AxonTokenTypes.COMMA to AxonColors.COMMA,
            AxonTokenTypes.SEMICOLON to AxonColors.SEMICOLON,
            AxonTokenTypes.DOT to AxonColors.DOT,
            TokenType.BAD_CHARACTER to AxonColors.BAD_CHAR,
        )
        for ((type, color) in expected) {
            assertEquals(type.toString(), color, AxonSyntaxHighlighter.colorOf(type))
            assertEquals(type.toString(), listOf(color.textAttributesKey), keysOf(type))
        }
        // Every colour key is reached from some token.
        assertEquals(AxonColors.entries.toSet(), expected.values.toSet())
    }

    /** Plain names and white space keep the default text colour. */
    fun testUncolouredTokens() {
        assertNull(AxonSyntaxHighlighter.colorOf(AxonTokenTypes.IDENTIFIER))
        assertNull(AxonSyntaxHighlighter.colorOf(TokenType.WHITE_SPACE))
        assertNull(AxonSyntaxHighlighter.colorOf(null))
        assertEmpty(keysOf(AxonTokenTypes.IDENTIFIER))
    }

    /** External names are stable, and each key falls back to its Fantom twin. */
    fun testKeyNamesAndFallbacks() {
        for (color in AxonColors.entries) {
            assertEquals("co.anbora.labs.axon.${color.name}", color.textAttributesKey.externalName)
        }
        assertEquals(FantomColors.KEY_WORD.textAttributesKey, AxonColors.KEYWORD.textAttributesKey.fallbackAttributeKey)
        assertEquals(FantomColors.STRINGS.textAttributesKey, AxonColors.STRING.textAttributesKey.fallbackAttributeKey)
        assertEquals(FantomColors.FACET.textAttributesKey, AxonColors.REF.textAttributesKey.fallbackAttributeKey)
        assertEquals(FantomColors.CLOSURE_PIPE.textAttributesKey, AxonColors.FN_ARROW.textAttributesKey.fallbackAttributeKey)
        assertEquals(HighlighterColors.BAD_CHARACTER, AxonColors.BAD_CHAR.textAttributesKey.fallbackAttributeKey)
    }

    fun testHighlighterFactoryIsRegistered() {
        val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(AxonLanguage, project, null)
        assertInstanceOf(highlighter, AxonSyntaxHighlighter::class.java)
        assertInstanceOf(highlighter.highlightingLexer, AxonLexer::class.java)
    }

    /** The colour page lists every key once, and its demo lexes with no bad character. */
    fun testColorSettingsPage() {
        val page = AxonColorSettingsPage()
        assertEquals("Axon", page.displayName)
        val keys = page.attributeDescriptors.map { it.key }
        assertEquals(AxonColors.entries.map { it.textAttributesKey }, keys)
        assertEmpty(page.colorDescriptors)
        assertNull(page.additionalHighlightingTagToDescriptorMap)

        val lexer = AxonLexer()
        lexer.start(page.demoText)
        val seen = HashSet<AxonColors>()
        while (lexer.tokenType != null) {
            assertFalse("bad char at ${lexer.tokenStart}", lexer.tokenType == TokenType.BAD_CHARACTER)
            AxonSyntaxHighlighter.colorOf(lexer.tokenType)?.let { seen += it }
            lexer.advance()
        }
        // The demo shows every colour but the bad character one.
        assertEquals(AxonColors.entries.toSet() - AxonColors.BAD_CHAR, seen)
    }

    fun testBraceMatcher() {
        val matcher = LanguageBraceMatching.INSTANCE.forLanguage(AxonLanguage)
        assertInstanceOf(matcher, AxonBraceMatcher::class.java)
        val pairs = (matcher as AxonBraceMatcher).pairs.map { Triple(it.leftBraceType, it.rightBraceType, it.isStructural) }
        assertEquals(
            listOf(
                Triple(AxonTokenTypes.LPAREN, AxonTokenTypes.RPAREN, false),
                Triple(AxonTokenTypes.LBRACKET, AxonTokenTypes.RBRACKET, false),
                Triple(AxonTokenTypes.LBRACE, AxonTokenTypes.RBRACE, true),
            ),
            pairs,
        )
        assertTrue(matcher.isPairedBracesAllowedBeforeType(AxonTokenTypes.LPAREN, AxonTokenTypes.IDENTIFIER))
        assertEquals(7, matcher.getCodeConstructStart(null, 7))
    }

    fun testCommenter() {
        val commenter = LanguageCommenters.INSTANCE.forLanguage(AxonLanguage)
        assertInstanceOf(commenter, AxonCommenter::class.java)
        assertEquals("//", commenter.lineCommentPrefix)
        assertEquals("/*", commenter.blockCommentPrefix)
        assertEquals("*/", commenter.blockCommentSuffix)
        assertNull(commenter.commentedBlockCommentPrefix)
    }

    /** Line comment on an `.axon` file uses `//`. */
    fun testCommentLineAction() {
        myFixture.configureByText("a.axon", "read(<caret>site)\n")
        myFixture.performEditorAction("CommentByLineComment")
        myFixture.checkResult("//read(site)\n")
    }
}
