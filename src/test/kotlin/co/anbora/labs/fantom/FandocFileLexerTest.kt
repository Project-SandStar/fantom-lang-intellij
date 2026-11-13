package co.anbora.labs.fantom

import co.anbora.labs.fantom.LexerTestSupport.checkInvariants
import co.anbora.labs.fantom.LexerTestSupport.visible
import co.anbora.labs.fantom.lang.fandoc.FandocLexer
import junit.framework.TestCase

class FandocFileLexerTest : TestCase() {

    /** Start of Fantom 1.0.82 src/web/pod.fandoc, plus a pre block. */
    private val doc = """
        **************************************************************************
        ** title:      Web
        ** author:     Brian Frank
        **************************************************************************

        Overview [#overview]
        ********************
        The [web]`web::index` pod defines the standard APIs used to handle
        both client and server side HTTP requests.

          - [WebClient]`web::WebClient`: manages client side of the HTTP protocol
          1. configure request fields such as 'reqUri', 'reqMethod', and 'reqHeaders'

        Sub ###
        =======
        pre>
        x := 'a'
        <pre
        It's done.
    """.trimIndent()

    fun testInvariants() {
        checkInvariants(::FandocLexer, doc)
    }

    fun testTokens() {
        val toks = visible(FandocLexer(), doc)
        assertTrue(toks.contains("Fandoc:META '** title:      Web'"))
        assertTrue(toks.contains("Fandoc:HEADING 'Overview [#overview]'"))
        assertTrue(toks.contains("Fandoc:HEADING_MARK '********************'"))
        assertTrue(toks.contains("Fandoc:HEADING 'Sub ###'"))
        assertTrue(toks.contains("Fandoc:HEADING_MARK '======='"))
        assertTrue(toks.contains("Fandoc:LINK '`web::index`'"))
        assertTrue(toks.contains("Fandoc:LIST_MARKER '-'"))
        assertTrue(toks.contains("Fandoc:LIST_MARKER '1.'"))
        assertTrue(toks.contains("Fandoc:CODE ''reqUri''"))
        assertTrue(toks.contains("Fandoc:PRE 'pre>\nx := 'a'\n<pre'"))
        // An apostrophe inside a word is not code.
        assertTrue(toks.toString(), toks.contains("Fandoc:TEXT 'It's done.'"))
    }
}
