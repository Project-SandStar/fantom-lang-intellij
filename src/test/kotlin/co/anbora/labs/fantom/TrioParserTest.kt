package co.anbora.labs.fantom

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Expected trees for each Trio construct. The tree form is `TrioTestSupport.tree`:
 * one node on a line, a value as its node type and text.
 */
class TrioParserTest : BasePlatformTestCase() {

    private fun check(text: String, expected: String) {
        val file = TrioTestSupport.parse(project, text)
        assertEquals(expected.trimIndent(), TrioTestSupport.tree(file))
    }

    /** Records, `---` lines of three or more dashes, `//` comment lines. */
    fun testRecordsSeparatorsAndComments() = check(
        "// first\ndis: \"Site A\"\nsite\n---\ndis: \"Site B\"\n-----\n\n// last\nname: calcDailyAvg\n",
        """
            comment
            record
              tag dis
                STR_VAL "Site A"
              tag site
            separator
            record
              tag dis
                STR_VAL "Site B"
            separator
            comment
            record
              tag name
                RAW_VAL calcDailyAvg
        """
    )

    /** Quoted and unquoted strings, numbers with units. */
    fun testStringsAndNumbers() = check(
        "a: \"quoted\"\nb: plain text here\nc: 12kW\nd: -3.5\ne: 1e10\nf: 0xff\ng: 75%\nh: NaN\ni: INF\nj: 100_000",
        """
            record
              tag a
                STR_VAL "quoted"
              tag b
                RAW_VAL plain text here
              tag c
                NUMBER_VAL 12kW
              tag d
                NUMBER_VAL -3.5
              tag e
                NUMBER_VAL 1e10
              tag f
                NUMBER_VAL 0xff
              tag g
                NUMBER_VAL 75%
              tag h
                NUMBER_VAL NaN
              tag i
                NUMBER_VAL INF
              tag j
                NUMBER_VAL 100_000
        """
    )

    /** Bools, NA and remove. A word that only starts like a bool is text. */
    fun testBoolsNaAndRemove() = check(
        "a: true\nb: false\nc: T\nd: F\ne: NA\nf: R\ng: trueish\nh: Tx",
        """
            record
              tag a
                BOOL_VAL true
              tag b
                BOOL_VAL false
              tag c
                BOOL_VAL T
              tag d
                BOOL_VAL F
              tag e
                NA_VAL NA
              tag f
                REMOVE_VAL R
              tag g
                RAW_VAL trueish
              tag h
                RAW_VAL Tx
        """
    )

    /** Refs with and without a display name, symbols, URIs. */
    fun testRefsSymbolsAndUris() = check(
        "a: @p:demo:r:1234\nb: @abc-123 \"Main AHU\"\nc: ^lib:io\nd: `http://example.com/a b`",
        """
            record
              tag a
                REF_VAL @p:demo:r:1234
              tag b
                REF_VAL @abc-123 "Main AHU"
              tag c
                SYMBOL_VAL ^lib:io
              tag d
                URI_VAL `http://example.com/a b`
        """
    )

    /** Dates, times, date times with offset and timezone name. */
    fun testDatesAndTimes() = check(
        "a: 2025-01-02\nb: 12:30\nc: 12:30:15.250\nd: 2025-01-02T12:30:00-05:00 New_York\ne: 2025-01-02T12:30:00Z\nf: 2025-01-02T12:30:00Z UTC",
        """
            record
              tag a
                DATE_VAL 2025-01-02
              tag b
                TIME_VAL 12:30
              tag c
                TIME_VAL 12:30:15.250
              tag d
                DATE_TIME_VAL 2025-01-02T12:30:00-05:00 New_York
              tag e
                DATE_TIME_VAL 2025-01-02T12:30:00Z
              tag f
                DATE_TIME_VAL 2025-01-02T12:30:00Z UTC
        """
    )

    /** `C(lat,lng)` and `Type("text")`. An XStr takes a string only. */
    fun testCoordAndXStr() = check(
        "a: C(37.5,-77.4)\nb: Bin(\"text/plain\")\nc: Span(\"today\")\nd: Coord(1,2)",
        """
            record
              tag a
                COORD_VAL C(37.5,-77.4)
              tag b
                XSTR_VAL Bin("text/plain")
              tag c
                XSTR_VAL Span("today")
              tag d
                XSTR_VAL
                  ERROR string expected, got '1'
        """
    )

    /** Lists and dicts on one line, also empty and nested. Dict items go with or without commas. */
    fun testListsAndDicts() = check(
        "a: [1, 2kW, \"x\", @r, N, M, T]\nb: {dis:\"x\", site, area:10ft²}\nc: []\nd: {}\ne: [{a:1}, [2, 3]]\nf: {a b:1 c}",
        """
            record
              tag a
                LIST_VAL
                  NUMBER_VAL 1
                  NUMBER_VAL 2kW
                  STR_VAL "x"
                  REF_VAL @r
                  NULL_VAL N
                  MARKER_VAL M
                  BOOL_VAL T
              tag b
                DICT_VAL
                  item dis
                    STR_VAL "x"
                  item site
                  item area
                    NUMBER_VAL 10ft²
              tag c
                LIST_VAL []
              tag d
                DICT_VAL {}
              tag e
                LIST_VAL
                  DICT_VAL
                    item a
                      NUMBER_VAL 1
                  LIST_VAL
                    NUMBER_VAL 2
                    NUMBER_VAL 3
              tag f
                DICT_VAL
                  item a
                  item b
                    NUMBER_VAL 1
                  item c
        """
    )

    /** A list or dict that goes on over indented lines. */
    fun testListAndDictOverLines() = check(
        "a: [\n  1,\n  2\n]\nb:{\n  x: \"y\"\n}\nc: 1",
        """
            record
              tag a
                LIST_VAL
                  NUMBER_VAL 1
                  NUMBER_VAL 2
              tag b
                DICT_VAL
                  item x
                    STR_VAL "y"
              tag c
                NUMBER_VAL 1
        """
    )

    /** A tag and then indented lines: one string. An empty line stays in the block. */
    fun testMultiLineString() = check(
        "doc:\n  First line.\n\n  Third line.\nnext: 1",
        """
            record
              tag doc
                TEXT_BLOCK First line.\n\n  Third line.
              tag next
                NUMBER_VAL 1
        """
    )

    /** `Zinc:` and `Trio:` and then an indented block. */
    fun testNestedZincAndTrio() = check(
        "grid: Zinc:\n  ver:\"3.0\"\n  a,b\n  1,2\nnested: Trio:\n  x: 1\n  y\nz",
        """
            record
              tag grid
                ZINC_BLOCK Zinc:\n  ver:"3.0"\n  a,b\n  1,2
              tag nested
                TRIO_BLOCK Trio:\n  x: 1\n  y
              tag z
        """
    )

    /** The `src` value is one SRC node (the Axon host): block form, on the tag line, and both. A quoted `src` is a string. */
    fun testSrcValues() = check(
        "name: calcDailyAvg\nfunc\nsrc:\n  (pt) => do\n    pt.hisRead(today)\n\n  end\ndoc: \"x\"\n---\nsrc: (x) => x + 1\n---\nsrc:\"(a) => a\"\n---\nsrc: (a) =>\n  a + 1\nx",
        """
            record
              tag name
                RAW_VAL calcDailyAvg
              tag func
              tag src
                SRC (pt) => do\n    pt.hisRead(today)\n\n  end
              tag doc
                STR_VAL "x"
            separator
            record
              tag src
                SRC (x) => x + 1
            separator
            record
              tag src
                STR_VAL "(a) => a"
            separator
            record
              tag src
                SRC (a) =>\n  a + 1
              tag x
        """
    )

    /** A bad line or a bad value gives an error; the next tag and the next record parse as usual. */
    fun testErrorsRecoverAtTheNextTag() = check(
        "a: 1\n9bad line\nb: 2\n---\nc: [1, 2\nd: 3\n---\ne: C(1)\nf\n---\n: x\ng: {a:}\nh",
        """
            record
              tag a
                NUMBER_VAL 1
                ERROR '9bad line' unexpected
              tag b
                NUMBER_VAL 2
            separator
            record
              tag c
                LIST_VAL
                  NUMBER_VAL 1
                  NUMBER_VAL 2
                  ERROR ',', C, M, N, NA, R, '[', ']', bool, date, date time, number, ref, string, symbol, time, type name, uri or '{' expected, got 'd'
              tag d
                NUMBER_VAL 3
            separator
            record
              tag e
                COORD_VAL
                  ERROR ',' expected, got ')'
              tag f
            separator
            ERROR ': x' unexpected
            record
              tag g
                DICT_VAL
                  item a
                ERROR C, M, N, NA, R, '[', bool, date, date time, number, ref, string, symbol, time, type name, uri or '{' expected, got '}'
              tag h
        """
    )

    /** Marker tags, `name:` with no value, and separators with no record between them. */
    fun testMarkersEmptyValuesAndEmptyRecords() = check(
        "a\n\n\nb:\nc: \n---\n---\n\n",
        """
            record
              tag a
              tag b
              tag c
            separator
            separator
        """
    )

    /** A closed string can go over lines. An open string ends at its line. */
    fun testStringsOverLines() = check(
        "a: \"two\nlines\"\nb: \"open\nc: 1",
        """
            record
              tag a
                STR_VAL "two\nlines"
              tag b
                STR_VAL "open
              tag c
                NUMBER_VAL 1
        """
    )
}
