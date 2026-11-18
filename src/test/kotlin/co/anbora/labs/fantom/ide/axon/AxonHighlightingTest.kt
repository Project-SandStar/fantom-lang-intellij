package co.anbora.labs.fantom.ide.axon

import co.anbora.labs.fantom.ide.color.FantomColors
import co.anbora.labs.fantom.lang.axon.AxonLanguage
import co.anbora.labs.fantom.lang.axon.AxonLexer
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes
import com.intellij.lang.LanguageBraceMatching
import com.intellij.lang.LanguageCommenters
import com.intellij.lang.annotation.HighlightSeverity
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
        val expected = mapOf<IElementType, AxonColors>(
            AxonTokenTypes.TYPENAME to AxonColors.TYPENAME,
            AxonTokenTypes.STRING to AxonColors.STRING,
            AxonTokenTypes.URI to AxonColors.URI,
            AxonTokenTypes.NUMBER to AxonColors.NUMBER,
            AxonTokenTypes.REF to AxonColors.REF,
            AxonTokenTypes.SYMBOL to AxonColors.SYMBOL,
            AxonTokenTypes.LINE_COMMENT to AxonColors.LINE_COMMENT,
            AxonTokenTypes.BLOCK_COMMENT to AxonColors.LINE_COMMENT,
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
        ) + AxonTokenTypes.KEYWORDS.types.associateWith { AxonColors.KEYWORD } +
            AxonTokenTypes.OPERATORS.types.associateWith { AxonColors.OPERATOR } +
            // The three value words are constants, not control words.
            listOf(AxonTokenTypes.TRUE, AxonTokenTypes.FALSE, AxonTokenTypes.NULL).associateWith { AxonColors.CONSTANT }
        assertTrue(AxonTokenTypes.KEYWORDS.types.isNotEmpty() && AxonTokenTypes.OPERATORS.types.isNotEmpty())
        for ((type, color) in expected) {
            assertEquals(type.toString(), color, AxonSyntaxHighlighter.colorOf(type))
            assertEquals(type.toString(), listOf(color.textAttributesKey), keysOf(type))
        }
        // Every colour key is reached from some token, or from the annotator.
        assertEquals(AxonColors.entries.toSet() - SEMANTIC, expected.values.toSet())
    }

    /** Plain names and white space keep the default text colour. */
    fun testUncolouredTokens() {
        assertNull(AxonSyntaxHighlighter.colorOf(AxonTokenTypes.IDENTIFIER))
        assertNull(AxonSyntaxHighlighter.colorOf(TokenType.WHITE_SPACE))
        assertNull(AxonSyntaxHighlighter.colorOf(null))
        assertEmpty(keysOf(AxonTokenTypes.IDENTIFIER))
    }

    /** The control words keep the keyword colour. */
    fun testControlWordsStayKeywords() {
        for (type in listOf(
            AxonTokenTypes.DO, AxonTokenTypes.END, AxonTokenTypes.IF, AxonTokenTypes.ELSE, AxonTokenTypes.RETURN,
            AxonTokenTypes.TRY, AxonTokenTypes.CATCH, AxonTokenTypes.THROW, AxonTokenTypes.AND, AxonTokenTypes.OR,
            AxonTokenTypes.NOT, AxonTokenTypes.DEFCOMP,
        )) assertEquals(type.toString(), AxonColors.KEYWORD, AxonSyntaxHighlighter.colorOf(type))
    }

    /** The first keys keep their place: new keys go at the end, so a saved scheme stays valid. */
    fun testOldKeysKeepTheirOrder() {
        assertEquals(
            listOf(
                "KEYWORD", "TYPENAME", "STRING", "URI", "NUMBER", "REF", "SYMBOL", "LINE_COMMENT", "OPERATOR", "FN_ARROW",
                "PARENTHESES", "BRACKETS", "BRACES", "COMMA", "SEMICOLON", "DOT", "BAD_CHAR",
            ),
            AxonColors.entries.take(17).map { it.name },
        )
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
        val fallbacks = mapOf(
            AxonColors.FUNCTION_DECLARATION to FantomColors.METHOD_DECLARATION.textAttributesKey,
            AxonColors.PARAMETER to FantomColors.PARAMETER.textAttributesKey,
            AxonColors.LOCAL_VARIABLE to FantomColors.LOCAL_VARIABLE.textAttributesKey,
            AxonColors.FUNCTION_CALL to FantomColors.FUNCTION_CALL.textAttributesKey,
            // A dot name follows the Axon call colour, and through it the Fantom one.
            AxonColors.DOT_NAME to AxonColors.FUNCTION_CALL.textAttributesKey,
            AxonColors.TAG_NAME to FantomColors.INSTANCE_FIELD.textAttributesKey,
            AxonColors.DEFCOMP_CELL to FantomColors.INSTANCE_FIELD.textAttributesKey,
            AxonColors.CONSTANT to FantomColors.CONSTANT.textAttributesKey,
        )
        assertEquals(SEMANTIC + AxonColors.CONSTANT, fallbacks.keys)
        for ((color, fallback) in fallbacks) assertEquals(color.name, fallback, color.textAttributesKey.fallbackAttributeKey)
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
        assertEquals(keys.size, page.attributeDescriptors.map { it.displayName }.toSet().size)
        assertEmpty(page.colorDescriptors)
        val lexer = AxonLexer()
        lexer.start(demoCode(page))
        val seen = HashSet<AxonColors>()
        while (lexer.tokenType != null) {
            assertFalse("bad char at ${lexer.tokenStart}", lexer.tokenType == TokenType.BAD_CHARACTER)
            AxonSyntaxHighlighter.colorOf(lexer.tokenType)?.let { seen += it }
            lexer.advance()
        }
        // The demo shows every lexer colour but the bad character one.
        assertEquals(AxonColors.entries.toSet() - SEMANTIC - AxonColors.BAD_CHAR, seen)
    }

    /** The preview has no PSI: a tag in the demo text gives each semantic key, and every tag is used. */
    fun testColorSettingsPageTags() {
        val page = AxonColorSettingsPage()
        val tags = page.additionalHighlightingTagToDescriptorMap
        assertEquals(SEMANTIC.map { it.textAttributesKey }.toSet(), tags.values.toSet())
        assertEquals(tags.size, tags.values.toSet().size)
        for (tag in tags.keys) {
            val open = Regex("<$tag>").findAll(page.demoText).count()
            assertTrue("<$tag> is not in the demo", open > 0)
            assertEquals("<$tag> is not closed", open, Regex("</$tag>").findAll(page.demoText).count())
        }
        // No other tag is in the demo.
        assertEquals(tags.keys, Regex("</?([a-zA-Z]+)>").findAll(page.demoText).map { it.groupValues[1] }.toSet())
    }

    /** The demo is correct Axon, and the annotator gives each tagged name the key of its tag. */
    fun testColorSettingsPageDemoMatchesTheEditor() {
        val page = AxonColorSettingsPage()
        val tags = page.additionalHighlightingTagToDescriptorMap
        val code = StringBuilder()
        val expected = ArrayList<Triple<Int, Int, TextAttributesKey>>()
        var last = 0
        for (m in Regex("<([a-zA-Z]+)>(.*?)</\\1>").findAll(page.demoText)) {
            code.append(page.demoText, last, m.range.first)
            expected += Triple(code.length, code.length + m.groupValues[2].length, tags.getValue(m.groupValues[1]))
            code.append(m.groupValues[2])
            last = m.range.last + 1
        }
        code.append(page.demoText, last, page.demoText.length)
        assertEquals(demoCode(page), code.toString())

        myFixture.configureByText("demo.axon", code.toString())
        val infos = myFixture.doHighlighting()
        val errors = infos.filter { it.severity >= HighlightSeverity.WARNING }
        assertTrue("demo has problems: " + errors.joinToString { "${it.startOffset} ${it.description}" }, errors.isEmpty())
        val painted = infos.filter { it.forcedTextAttributesKey in tags.values }
            .map { Triple(it.startOffset, it.endOffset, it.forcedTextAttributesKey!!) }.toSet()
        assertEquals(
            expected.joinToString("\n") { "${code.substring(it.first, it.second)} ${it.third.externalName}" },
            painted.sortedBy { it.first }.joinToString("\n") { "${code.substring(it.first, it.second)} ${it.third.externalName}" },
        )
    }

    private fun demoCode(page: AxonColorSettingsPage): String = page.demoText.replace(Regex("</?[a-zA-Z]+>"), "")

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

    private companion object {
        /** The keys that [AxonHighlightingAnnotator] gives; the lexer gives the rest. */
        val SEMANTIC = setOf(
            AxonColors.FUNCTION_DECLARATION, AxonColors.PARAMETER, AxonColors.LOCAL_VARIABLE, AxonColors.FUNCTION_CALL,
            AxonColors.DOT_NAME, AxonColors.TAG_NAME, AxonColors.DEFCOMP_CELL,
        )
    }
}
