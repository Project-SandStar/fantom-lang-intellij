package co.anbora.labs.fantom

import co.anbora.labs.fantom.LexerTestSupport.checkInvariants
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
        assertEquals("Trio:COMMENT '//'", toks.first())
        assertTrue(toks.contains("Trio:SEPARATOR '--------------------------------------------------------------------------'"))
        val defAt = toks.indexOf("Trio:TAG_NAME 'def'")
        assertEquals(listOf("Trio:TAG_NAME 'def'", "Trio:COLON ':'", "Trio:VALUE '^lib:io'"), toks.subList(defAt, defAt + 3))
        val typeAt = toks.indexOf("Trio:TAG_NAME 'typeName'")
        assertEquals(listOf("Trio:TAG_NAME 'typeName'", "Trio:COLON ':'", "Trio:VALUE '\"hxIO::IOExt\"'"), toks.subList(typeAt, typeAt + 3))
    }

    private val record = """
        name: "compareTemp"
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
        assertTrue(toks.contains("Trio:TAG_NAME 'hidden'"))
        val src = "(day) => do\n    data: read(temp).hisRead(day)\n\n    data.hisRollup(avg, 1hr)\n  end"
        assertTrue(toks.toString(), toks.contains("Trio:SRC_VALUE '$src'"))
        assertTrue(toks.contains("Trio:VALUE 'Multi-line\n  text'"))
        assertTrue(toks.contains("Trio:SEPARATOR '---'"))
        assertTrue(toks.contains("Trio:SRC_VALUE '(x) => x + 1'"))
    }
}
