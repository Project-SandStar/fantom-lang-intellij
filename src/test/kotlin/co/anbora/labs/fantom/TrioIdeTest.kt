package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.axon.AxonLanguage
import co.anbora.labs.fantom.lang.trio.TrioFile
import co.anbora.labs.fantom.lang.trio.TrioFoldingBuilder
import co.anbora.labs.fantom.lang.trio.TrioLexer
import co.anbora.labs.fantom.lang.trio.TrioSrcValue
import co.anbora.labs.fantom.lang.trio.TrioStructureViewElement
import co.anbora.labs.fantom.lang.trio.TrioSyntaxHighlighter
import co.anbora.labs.fantom.lang.trio.psi.TrioTag
import co.anbora.labs.fantom.lang.trio.psi.TrioTypes
import co.anbora.labs.fantom.lang.trio.records
import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.IncorrectOperationException

/** Editor features of `.trio` files: injection, errors, folding, structure view, commenter, braces, colours. */
class TrioIdeTest : BasePlatformTestCase() {

    private val funcRecord =
        "name: calcDailyAvg\nfunc\ndoc: \"readAll here is text\"\nsrc:\n  (pt) => do\n    readAll(site)\n  end\n"

    private fun injectedAt(word: String, nth: Int = 0): String? {
        val text = myFixture.file.text
        var at = -1
        repeat(nth + 1) { at = text.indexOf(word, at + 1) }
        assertTrue("'$word' not in text", at >= 0)
        val element = InjectedLanguageManager.getInstance(project).findInjectedElementAt(myFixture.file, at + 1)
        return element?.containingFile?.language?.id
    }

    private fun errors(): List<String> =
        myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }.map { it.description ?: "" }

    fun testSrcOfAFuncRecordIsAxon() {
        myFixture.configureByText("funcs.trio", funcRecord + "---\nname: other\nsrc: (x) => x + 1\n")
        assertNull(injectedAt("readAll", 0))
        assertEquals(AxonLanguage.id, injectedAt("readAll", 1))
        assertEquals(AxonLanguage.id, injectedAt("x + 1"))
        assertNull(injectedAt("other"))
    }

    /** The host is the whole `src` value and no more: from its first character to the end of its last line. */
    fun testSrcHostRange() {
        myFixture.configureByText("funcs.trio", funcRecord + "---\nname: other\n")
        val hosts = PsiTreeUtil.findChildrenOfType(myFixture.file, TrioSrcValue::class.java).toList()
        assertEquals(1, hosts.size)
        assertEquals("(pt) => do\n    readAll(site)\n  end", hosts[0].text)
        assertEquals("src", (hosts[0].parent as TrioTag).name)
    }

    fun testGoodFileHasNoErrors() {
        myFixture.configureByText(
            "ok.trio",
            "// sites\nid: @a \"Site A\"\nsite\narea: 100ft²\ntags: [@a, 1, \"x\"]\nmeta: {dis:\"x\" site}\n---\n" + funcRecord
        )
        assertEquals(emptyList<String>(), errors())
    }

    /** An error in one record does not reach the next tag or the next record. */
    fun testErrorsStayInTheirRecord() {
        myFixture.configureByText("bad.trio", "a: [1, 2\n9bad\nb: 2\n---\nc: C(1)\nd: 3\n---\ne: 4\nf\n")
        // the open list (seen at the bad line 2) and the coord with one number; line 2 is also a bad character
        assertEquals(listOf(1, 5, 2), TrioTestSupport.problems(myFixture.file).map { it.substringBefore(':').toInt() })
        assertEquals(2, errors().size)
        val records = (myFixture.file as TrioFile).records()
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d"), listOf("e", "f")), records.map { r -> r.tagList.map { it.name } })
        assertEmpty(PsiTreeUtil.findChildrenOfType(records[2], com.intellij.psi.PsiErrorElement::class.java))
    }

    fun testFolding() {
        val text = "name: one\nsite\n---\n" + funcRecord + "---\nlist: [\n  1,\n  2]\ndoc:\n  line 1\n  line 2\nx: 1\n---\nmarker\n"
        myFixture.configureByText("fold.trio", text)
        val builder = TrioFoldingBuilder()
        val regions = builder.buildFoldRegions(myFixture.file, myFixture.editor.document, false)
            .map { text.substring(it.range.startOffset, it.range.endOffset).lines().first() + " | " + builder.getPlaceholderText(it.element) }
        assertEquals(
            listOf(
                "name: one | one ...",
                "name: calcDailyAvg | calcDailyAvg ...",
                // the src value folds from the end of the `src:` line
                " |  ...",
                "list: [ | ...",
                "[ | [...]",
                " |  ...",
            ),
            regions
        )
    }

    fun testStructureView() {
        myFixture.configureByText(
            "view.trio",
            "name: calcDailyAvg\nfunc\n---\ndis: \"Site A\"\nname: siteA\n---\nid: @abc \"Main AHU\"\nahu\n---\ndis: \"Only dis\"\n---\nmarker\n---\nid: @p:site:r:1f\n"
        )
        val root = TrioStructureViewElement(myFixture.file)
        val records = root.children
        assertEquals(
            listOf("calcDailyAvg", "siteA", "Main AHU", "Only dis", "marker", "@p:site:r:1f"),
            records.map { it.presentation.presentableText }
        )
        assertEquals(
            listOf("name", "func"),
            (records[0] as TrioStructureViewElement).children.map { it.presentation.presentableText }
        )
    }

    fun testCommenter() {
        myFixture.configureByText("c.trio", "a: 1\n<caret>b: 2\n")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        myFixture.checkResult("a: 1\n// b: 2\n")
        assertEquals(listOf("a"), (myFixture.file as TrioFile).records().single().tagList.map { it.name })
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("//"))
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        myFixture.checkResult("a: 1\nb: 2\n")
    }

    fun testBraceMatching() {
        val text = "a: [1, {x: 2}, 3]\nb: C(1,2)\n"
        myFixture.configureByText("b.trio", text)
        for ((open, close) in listOf('[' to ']', '{' to '}', '(' to ')')) {
            myFixture.editor.caretModel.moveToOffset(text.indexOf(open))
            assertEquals(
                "match of $open",
                text.indexOf(close),
                BraceMatchingUtil.getMatchedBraceOffset(myFixture.editor, true, myFixture.file)
            )
        }
    }

    /** The colour keys of the first highlighter are still there, with the same names. */
    fun testColours() {
        val h = TrioSyntaxHighlighter()
        fun name(type: com.intellij.psi.tree.IElementType): String? = h.getTokenHighlights(type).firstOrNull()?.externalName
        assertEquals("co.anbora.labs.trio.COMMENT", name(TrioTypes.COMMENT))
        assertEquals("co.anbora.labs.trio.SEPARATOR", name(TrioTypes.SEPARATOR))
        assertEquals("co.anbora.labs.trio.TAG_NAME", name(TrioTypes.TAG_NAME))
        assertEquals("co.anbora.labs.trio.COLON", name(TrioTypes.COLON))
        assertEquals("co.anbora.labs.trio.VALUE", name(TrioTypes.UNQUOTED))
        assertEquals("co.anbora.labs.trio.VALUE", name(TrioTypes.STRING))
        assertEquals("co.anbora.labs.trio.NUMBER", name(TrioTypes.NUMBER))
        assertEquals("co.anbora.labs.trio.REF", name(TrioTypes.REF))
        assertNull(name(TrioTypes.SRC_VALUE))
    }

    /** The token types that the editor shows after `typed`, against a new lexer run over the new text. */
    private fun staleChars(text: String, typed: String): List<Int> {
        myFixture.configureByText("typed.trio", text)
        myFixture.doHighlighting()
        myFixture.type(typed)
        val now = myFixture.editor.document.text
        val shown = arrayOfNulls<String>(now.length)
        val iterator = (myFixture.editor as EditorEx).highlighter.createIterator(0)
        while (!iterator.atEnd()) {
            for (i in iterator.start until iterator.end) shown[i] = iterator.tokenType.toString()
            iterator.advance()
        }
        val fresh = arrayOfNulls<String>(now.length)
        val lexer = TrioLexer()
        lexer.start(now)
        while (lexer.tokenType != null) {
            for (i in lexer.tokenStart until lexer.tokenEnd) fresh[i] = lexer.tokenType.toString()
            lexer.advance()
        }
        return now.indices.filter { shown[it] != fresh[it] }
    }

    /**
     * The editor restarts the lexer near the edit. Text typed after a block or after an open
     * string can change tokens before it, so the colours must still be the colours of a full run.
     */
    fun testColoursAreRightAfterTyping() {
        // an indent makes the next line a part of the block
        assertEquals(emptyList<Int>(), staleChars("doc:\n  a\n<caret>next: 1\n", "  "))
        assertEquals(emptyList<Int>(), staleChars("doc:\n  a\n\n\n<caret>next: 1\n", "  "))
        assertEquals(emptyList<Int>(), staleChars("src:\n  (a) => a\n\n\n<caret>next: 1\n", "  "))
        // a quote closes the string that is open two lines before
        assertEquals(emptyList<Int>(), staleChars("a: \"open\nb: 1\nc: 2<caret>\nd: 3\n", "\""))
        assertEquals(emptyList<Int>(), staleChars("a: @r \"open\nb: 1\nc: 2<caret>\nd: 3\n", "\""))
        assertEquals(emptyList<Int>(), staleChars("a: [\n  1,\n  2\n<caret>\nb: 3\n", "]"))
    }

    fun testRenameOfATagName() {
        myFixture.configureByText("r.trio", "na<caret>me: calcDailyAvg\nsite\n")
        myFixture.renameElementAtCaret("dis")
        assertEquals("dis: calcDailyAvg\nsite\n", myFixture.file.text)
    }

    /** A new name that is not a tag name stops the rename with an error; the file does not change. */
    fun testRenameToABadNameIsAnError() {
        myFixture.configureByText("r.trio", "na<caret>me: calcDailyAvg\nsite\n")
        var error: Throwable? = null
        try {
            myFixture.renameElementAtCaret("9 x")
        } catch (e: Throwable) {
            error = e
        }
        assertNotNull("the rename must fail", error)
        assertTrue(error.toString(), generateSequence(error) { it.cause }.any { it is IncorrectOperationException })
        assertEquals("name: calcDailyAvg\nsite\n", myFixture.file.text)
    }
}
