package co.anbora.labs.fantom

import co.anbora.labs.fantom.LexerTestSupport.checkInvariants
import co.anbora.labs.fantom.LexerTestSupport.tokens
import co.anbora.labs.fantom.LexerTestSupport.visible
import co.anbora.labs.fantom.lang.trio.TrioLexer
import junit.framework.TestCase

class TrioLexerTest : TestCase() {

    /** Haxall 4.0.7 src/ext/hxIO/lib/lib.trio, lines 1 to 14. */
    private val libTrio = """
        //
        // Copyright (c) 2021, SkyFoundry LLC
        // Licensed under the Academic Free License version 3.0
        //
        // History:
        //   11 Aug 2021  Brian Frank  Creation
        //

        --------------------------------------------------------------------------
        def: ^lib:io
        depends: [^lib:ph, ^lib:axon, ^lib:hx]
        typeName:"hxIO::IOExt"
        doc: "I/O function extension"
        --------------------------------------------------------------------------
    """.trimIndent() + "\n"

    fun testLibTrio() {
        checkInvariants(::TrioLexer, libTrio)
        val toks = visible(TrioLexer(), libTrio)
        assertEquals("comment '//'", toks.first())
        assertTrue(toks.contains("separator '--------------------------------------------------------------------------'"))
        val defAt = toks.indexOf("tag name 'def'")
        assertEquals(listOf("tag name 'def'", ": ':'", "symbol '^lib:io'"), toks.subList(defAt, defAt + 3))
        val depAt = toks.indexOf("tag name 'depends'")
        assertEquals(
            listOf(": ':'", "[ '['", "symbol '^lib:ph'", ", ','", "symbol '^lib:axon'", ", ','", "symbol '^lib:hx'", "] ']'"),
            toks.subList(depAt + 1, depAt + 9)
        )
        val typeAt = toks.indexOf("tag name 'typeName'")
        assertEquals(listOf("tag name 'typeName'", ": ':'", "string '\"hxIO::IOExt\"'"), toks.subList(typeAt, typeAt + 3))
    }

    private val record = """
        name: "calcDailyAvg"
        hidden
        src:
          (day) => do
            data: read(temp).hisRead(day)

            data.hisRollup(avg, 1hr)
          end
        doc:
          Multi-line
          text
        ---
        name: "other"
        src: (x) => x + 1
    """.trimIndent()

    fun testMultiLineValuesAndSrc() {
        checkInvariants(::TrioLexer, record)
        val toks = visible(TrioLexer(), record)
        assertTrue(toks.contains("tag name 'hidden'"))
        val src = "(day) => do\n    data: read(temp).hisRead(day)\n\n    data.hisRollup(avg, 1hr)\n  end"
        assertTrue(toks.toString(), toks.contains("Axon source '$src'"))
        assertTrue(toks.contains("indented text 'Multi-line\n  text'"))
        assertTrue(toks.contains("separator '---'"))
        assertTrue(toks.contains("Axon source '(x) => x + 1'"))
    }

    private fun values(text: String): List<String> = visible(TrioLexer(), text).filter { !it.startsWith("tag name") && it != ": ':'" }

    fun testScalars() {
        val text = listOf(
            "a: 12kW", "b: -3.5", "c: 1e10", "d: 0xff", "e: 75%", "f: NaN", "g: 2025-01-02", "h: 12:30",
            "i: 2025-01-02T12:30:00-05:00 New_York", "j: T", "k: false", "l: NA", "m: plain text here",
            "n: trueish", "o: 12 apples", "p: 2025-01-02 was a day", "q: `http://example.com/a`", "r: @a-b \"Dis\"",
        ).joinToString("\n")
        checkInvariants(::TrioLexer, text)
        assertEquals(
            listOf(
                "number '12kW'", "number '-3.5'", "number '1e10'", "number '0xff'", "number '75%'",
                "number 'NaN'", "date '2025-01-02'", "time '12:30'",
                "date time '2025-01-02T12:30:00-05:00 New_York'", "bool 'T'", "bool 'false'", "NA 'NA'",
                "text 'plain text here'", "text 'trueish'", "text '12 apples'", "text '2025-01-02 was a day'",
                "uri '`http://example.com/a`'", "ref '@a-b'", "string '\"Dis\"'",
            ),
            values(text)
        )
    }

    fun testBlocksRestart() {
        val text = "grid: Zinc:\n  ver:\"3.0\"\n  a,b\n\n  1,2\nitems: [\n  1,\n  // note\n  2\n]\nrec: Trio:\n  x: 1\nparams:{\n  a: \"b\"\n}\nlast\n"
        checkInvariants(::TrioLexer, text)
        val toks = visible(TrioLexer(), text)
        assertTrue(toks.toString(), toks.contains("indented text 'ver:\"3.0\"\n  a,b\n\n  1,2'"))
        assertTrue(toks.toString(), toks.contains("indented text 'x: 1'"))
        assertTrue(toks.toString(), toks.contains("comment '// note'"))
        assertEquals("tag name 'last'", toks.last())
    }

    fun testBadLinesDoNotStopTheLexer() {
        val text = "a: 1\n9 bad\n: bad\nb x\nc: 2\r\nd:\r\n  text\r\ne"
        checkInvariants(::TrioLexer, text, allowBad = true)
        val toks = visible(TrioLexer(), text)
        assertTrue(toks.toString(), toks.contains("number '2'"))
        assertTrue(toks.toString(), toks.contains("indented text 'text'"))
        assertEquals("tag name 'e'", toks.last())
    }

    /** A string with no closing quote ends at its line, and never goes over a `---` line. */
    fun testOpenStrings() {
        val text = "a: \"open\nb: 1\n---\nc: \"closed\"\nd: \"two\nlines\"\ne: \"not - a\n- record end\"\n"
        checkInvariants(::TrioLexer, text)
        val toks = visible(TrioLexer(), text)
        assertTrue(toks.toString(), toks.contains("open string '\"open'"))
        assertTrue(toks.toString(), toks.contains("string '\"closed\"'"))
        assertTrue(toks.toString(), toks.contains("string '\"two\nlines\"'"))
        assertTrue(toks.toString(), toks.contains("string '\"not - a\n- record end\"'"))
        // All tokens after the open string, up to the record end, have a state that is not a restart point.
        val all = tokens(TrioLexer(), text)
        val open = all.indexOfFirst { it.type == "open string" }
        val end = all.indexOfFirst { it.type == "separator" }
        assertTrue(all.subList(open + 1, end + 1).all { it.state != 0 })
        assertEquals(0, all[end + 1].state and (1 shl 16))
        assertTrue(all.drop(end + 1).any { it.state == 0 })
    }
}
