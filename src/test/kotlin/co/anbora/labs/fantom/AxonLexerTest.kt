package co.anbora.labs.fantom

import co.anbora.labs.fantom.LexerTestSupport.checkInvariants
import co.anbora.labs.fantom.LexerTestSupport.visible
import co.anbora.labs.fantom.lang.axon.AxonLexer
import junit.framework.TestCase

class AxonLexerTest : TestCase() {

    /**
     * 20 lines of Axon from the Haxall 4.0.7 docs (src/xeto/hx.doc.haxall/AxonUsage.md
     * and AxonLang.md), with the `>> result` notes removed.
     */
    private val haxallSample = """
        (ahu, dates) => do

          // find periods in cooling mode for this AHU
          coolPeriods: read(cool and equipRef==ahu->id)
               .hisRead(dates).hisFindPeriods(v => v)

          // find periods in heating mode for this AHU
          heatPeriods: read(heat and equipRef==ahu->id)
                .hisRead(dates).hisFindPeriods(v => v)

          // compute when AHU running in both heating and cooling modes
          hisPeriodIntersection([coolPeriods, heatPeriods])
        end
        eachDay(2010-07, day => echo(day))  // inside parens
        now() + 1hr
        dateTime(2023-03-14, 0:00, "New_York")
        toDateSpan(2023-01-01..2023-02-28)
        eachDay(2024-02) (d) => echo(d)
        x.any v => v < 20
        f: (a, b:2) => a + b
    """.trimIndent()

    fun testHaxallSampleInvariants() {
        assertEquals(20, haxallSample.lines().size)
        checkInvariants(::AxonLexer, haxallSample)
    }

    fun testHaxallSampleTokens() {
        val toks = visible(AxonLexer(), haxallSample)
        assertTrue(toks.contains("Axon:FN_ARROW '=>'"))
        assertTrue(toks.contains("Axon:KEYWORD 'do'"))
        assertTrue(toks.contains("Axon:KEYWORD 'and'"))
        assertTrue(toks.contains("Axon:KEYWORD 'end'"))
        assertTrue(toks.contains("Axon:LINE_COMMENT '// find periods in cooling mode for this AHU'"))
        assertTrue(toks.contains("Axon:NUMBER '1hr'"))
        assertTrue(toks.contains("Axon:NUMBER '2010-07'"))
        assertTrue(toks.contains("Axon:NUMBER '2023-03-14'"))
        assertTrue(toks.contains("Axon:NUMBER '0:00'"))
        assertTrue(toks.contains("Axon:STRING '\"New_York\"'"))
        assertTrue(toks.contains("Axon:OPERATOR '->'"))
        assertTrue(toks.contains("Axon:OPERATOR '=='"))
    }

    fun testDateRange() {
        assertEquals(
            listOf("Axon:NUMBER '2023-01-01'", "Axon:OPERATOR '..'", "Axon:NUMBER '2023-02-28'"),
            visible(AxonLexer(), "2023-01-01..2023-02-28")
        )
    }

    fun testLiterals() {
        assertEquals(
            listOf(
                "Axon:REF '@p:demo:r:2f797ee0-541a6010'",
                "Axon:SYMBOL '^lib:ph'",
                "Axon:URI '`io/points.csv`'",
                "Axon:NUMBER '5kW'",
                "Axon:NUMBER '10min'",
                "Axon:NUMBER '75°F'",
                "Axon:NUMBER '2.5'",
                "Axon:NUMBER '0x1F'",
                "Axon:NUMBER '12:30:00'",
                "Axon:STRING 'r\"\\raw\"'",
                "Axon:TYPENAME 'Number'",
                "Axon:KEYWORD 'null'",
                "Axon:KEYWORD 'true'",
                "Axon:KEYWORD 'false'",
            ),
            visible(AxonLexer(), "@p:demo:r:2f797ee0-541a6010 ^lib:ph `io/points.csv` 5kW 10min 75°F 2.5 0x1F 12:30:00 r\"\\raw\" Number null true false")
        )
    }

    fun testKeywords() {
        val words = "do end if else return throw try catch defcomp deflinks and or not true false null"
        val toks = visible(AxonLexer(), words)
        assertEquals(16, toks.size)
        assertTrue(toks.all { it.startsWith("Axon:KEYWORD") })
        assertEquals(listOf("Axon:IDENTIFIER 'done'", "Axon:IDENTIFIER 'endTime'"), visible(AxonLexer(), "done endTime"))
    }

    fun testStringsAndComments() {
        assertEquals(
            listOf("Axon:STRING '\"a \\\"b\\\" \\${'$'}c\"'", "Axon:BLOCK_COMMENT '/* x /* nested */ y */'", "Axon:IDENTIFIER 'z'"),
            visible(AxonLexer(), "\"a \\\"b\\\" \\${'$'}c\" /* x /* nested */ y */ z")
        )
        val triple = "\"\"\"line 1\n   \"quoted\" line 2\"\"\""
        assertEquals(listOf("Axon:STRING '$triple'"), visible(AxonLexer(), triple))
    }

    fun testOperators() {
        assertEquals(
            listOf("<=>", "<=", ">=", "!=", "::", "+", "-", "*", "/", "?", "&", "|").map { "Axon:OPERATOR '$it'" },
            visible(AxonLexer(), "<=> <= >= != :: + - * / ? & |")
        )
        assertEquals(
            listOf("Axon:LPAREN '('", "Axon:RPAREN ')'", "Axon:LBRACKET '['", "Axon:RBRACKET ']'",
                "Axon:LBRACE '{'", "Axon:RBRACE '}'", "Axon:COMMA ','", "Axon:SEMICOLON ';'", "Axon:COLON ':'", "Axon:DOT '.'"),
            visible(AxonLexer(), "()[]{},;:.")
        )
    }

    fun testUnclosedConstructsStayOneToken() {
        checkInvariants(::AxonLexer, "x: \"open\ny: /* open comment", allowBad = true)
    }
}
