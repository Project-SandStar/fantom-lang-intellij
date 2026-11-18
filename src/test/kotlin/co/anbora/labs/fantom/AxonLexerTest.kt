package co.anbora.labs.fantom

import co.anbora.labs.fantom.LexerTestSupport.checkInvariants
import co.anbora.labs.fantom.LexerTestSupport.visible
import co.anbora.labs.fantom.lang.axon.AxonLexer
import co.anbora.labs.fantom.lang.axon.AxonTokenTypes
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

    /**
     * A token class prints its name (`NUMBER '1hr'`). A keyword or a sign has one token
     * type each, named after its text, so it prints as `'do' 'do'`.
     */
    private fun fixed(vararg texts: String): List<String> = texts.map { "'$it' '$it'" }

    fun testHaxallSampleTokens() {
        val toks = visible(AxonLexer(), haxallSample)
        assertTrue(toks.containsAll(fixed("=>", "do", "and", "end", "->", "==")))
        assertTrue(toks.contains("LINE_COMMENT '// find periods in cooling mode for this AHU'"))
        assertTrue(toks.contains("NUMBER '1hr'"))
        assertTrue(toks.contains("NUMBER '2010-07'"))
        assertTrue(toks.contains("NUMBER '2023-03-14'"))
        assertTrue(toks.contains("NUMBER '0:00'"))
        assertTrue(toks.contains("STRING '\"New_York\"'"))
    }

    fun testDateRange() {
        assertEquals(
            listOf("NUMBER '2023-01-01'", "'..' '..'", "NUMBER '2023-02-28'"),
            visible(AxonLexer(), "2023-01-01..2023-02-28")
        )
    }

    fun testLiterals() {
        assertEquals(
            listOf(
                "REF '@p:demo:r:2f797ee0-541a6010'",
                "SYMBOL '^lib:ph'",
                "URI '`io/points.csv`'",
                "NUMBER '5kW'",
                "NUMBER '10min'",
                "NUMBER '75°F'",
                "NUMBER '2.5'",
                "NUMBER '0x1F'",
                "NUMBER '12:30:00'",
                "STRING 'r\"\\raw\"'",
                "TYPENAME 'Number'",
            ) + fixed("null", "true", "false"),
            visible(AxonLexer(), "@p:demo:r:2f797ee0-541a6010 ^lib:ph `io/points.csv` 5kW 10min 75°F 2.5 0x1F 12:30:00 r\"\\raw\" Number null true false")
        )
    }

    fun testKeywords() {
        val words = "do end if else return throw try catch defcomp deflinks and or not true false null"
        assertEquals(fixed(*words.split(' ').toTypedArray()), visible(AxonLexer(), words))
        // Every keyword has its own token type, and all of them are in the KEYWORDS set.
        val types = LexerTestSupport.tokens(AxonLexer(), words).map { it.type }.filter { it != "WHITE_SPACE" }.toSet()
        assertEquals(16, types.size)
        assertEquals(AxonTokenTypes.KEYWORDS.types.map { it.toString() }.toSet(), types)
        assertEquals(listOf("IDENTIFIER 'done'", "IDENTIFIER 'endTime'"), visible(AxonLexer(), "done endTime"))
    }

    fun testStringsAndComments() {
        assertEquals(
            listOf("STRING '\"a \\\"b\\\" \\${'$'}c\"'", "BLOCK_COMMENT '/* x /* nested */ y */'", "IDENTIFIER 'z'"),
            visible(AxonLexer(), "\"a \\\"b\\\" \\${'$'}c\" /* x /* nested */ y */ z")
        )
        val triple = "\"\"\"line 1\n   \"quoted\" line 2\"\"\""
        assertEquals(listOf("STRING '$triple'"), visible(AxonLexer(), triple))
    }

    fun testOperators() {
        // `?`, `&` and `|` are signs the tokenizer knows but the grammar does not use.
        assertEquals(
            fixed("<=>", "<=", ">=", "<", ">", "==", "!=", "=", "::", "->", "..", "+", "-", "*", "/") +
                listOf("OPERATOR '?'", "OPERATOR '&'", "OPERATOR '|'"),
            visible(AxonLexer(), "<=> <= >= < > == != = :: -> .. + - * / ? & |")
        )
        assertEquals(fixed("(", ")", "[", "]", "{", "}", ",", ";", ":", "."), visible(AxonLexer(), "()[]{},;:."))
        // `_` is the partial-application placeholder only when it stands alone.
        assertEquals(fixed("_") + "IDENTIFIER 'a_b'", visible(AxonLexer(), "_ a_b"))
    }

    fun testUnclosedConstructsStayOneToken() {
        checkInvariants(::AxonLexer, "x: \"open\ny: /* open comment", allowBad = true)
    }
}
