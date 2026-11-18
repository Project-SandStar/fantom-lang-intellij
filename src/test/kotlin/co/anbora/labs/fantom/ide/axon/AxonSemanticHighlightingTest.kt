package co.anbora.labs.fantom.ide.axon

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Colours by meaning from [AxonHighlightingAnnotator], in `.axon` files and in injected Axon.
 * The tests read the raw [HighlightInfo]s: a name with no semantic key must have no info at all.
 */
class AxonSemanticHighlightingTest : BasePlatformTestCase() {

    private val semantic = setOf(
        AxonColors.FUNCTION_DECLARATION, AxonColors.PARAMETER, AxonColors.LOCAL_VARIABLE, AxonColors.FUNCTION_CALL,
        AxonColors.DOT_NAME, AxonColors.TAG_NAME, AxonColors.DEFCOMP_CELL,
    ).associateBy { it.textAttributesKey }

    private lateinit var infos: List<HighlightInfo>

    private fun highlight(name: String, text: String) {
        myFixture.configureByText(name, text)
        infos = myFixture.doHighlighting()
    }

    private fun axon(text: String) = highlight("a.axon", text.trimIndent() + "\n")

    /** The semantic colours on exactly [word], at its [nth] occurrence as a whole word. */
    private fun at(word: String, nth: Int = 0): Set<AxonColors> {
        val text = myFixture.file.text
        val start = Regex("(?<![A-Za-z0-9_])" + Regex.escape(word) + "(?![A-Za-z0-9_])").findAll(text).map { it.range.first }
            .elementAtOrNull(nth) ?: error("'$word' #$nth not in\n$text")
        return infos.filter { it.startOffset == start && it.endOffset == start + word.length }
            .mapNotNull { semantic[it.forcedTextAttributesKey] }.toSet()
    }

    private fun assertColor(expected: AxonColors, word: String, vararg nth: Int) {
        for (n in if (nth.isEmpty()) intArrayOf(0) else nth) assertEquals("$word #$n", setOf(expected), at(word, n))
    }

    private fun assertPlain(word: String, nth: Int = 0) = assertEquals("$word #$nth", emptySet<AxonColors>(), at(word, nth))

    private fun assertNoProblem() {
        val bad = infos.filter { it.severity >= HighlightSeverity.WEAK_WARNING }
        assertTrue("unexpected: " + bad.joinToString { "${it.severity} '${it.text}' ${it.description}" }, bad.isEmpty())
    }

    private val sample = """
        // Daily average for a point
        calcDailyAvg: (point, dates) => do
          his: point.hisRead(dates)
          if (his.isEmpty) return null
          total: his.foldCol("v0", sum)
          total / his.size
        end

        scaleAll: (list, factor: 2) => list.map(x => x * factor)

        safeRead: (id) => do
          try
            readById(id)
          catch (err)
            logErr(err)
        end
    """

    fun testFunctionDeclarations() {
        axon(sample)
        assertColor(AxonColors.FUNCTION_DECLARATION, "calcDailyAvg")
        assertColor(AxonColors.FUNCTION_DECLARATION, "scaleAll")
        assertColor(AxonColors.FUNCTION_DECLARATION, "safeRead")
        assertNoProblem()
    }

    fun testParametersAtDeclarationAndUse() {
        axon(sample)
        // "point" #0 is a word of the comment on the first line.
        assertPlain("point", 0)
        assertColor(AxonColors.PARAMETER, "point", 1, 2)
        assertColor(AxonColors.PARAMETER, "dates", 0, 1)
        assertColor(AxonColors.PARAMETER, "list", 0, 1)
        // A parameter with a default value, and the bare parameter of `x => ...`.
        assertColor(AxonColors.PARAMETER, "factor", 0, 1)
        assertColor(AxonColors.PARAMETER, "x", 0, 1)
        assertColor(AxonColors.PARAMETER, "id", 0, 1)
    }

    fun testLocalsAtDeclarationAndUse() {
        axon(sample)
        assertColor(AxonColors.LOCAL_VARIABLE, "his", 0, 1, 2, 3)
        assertColor(AxonColors.LOCAL_VARIABLE, "total", 0, 1)
        // The catch variable is a local.
        assertColor(AxonColors.LOCAL_VARIABLE, "err", 0, 1)
    }

    fun testCallsAndDotNames() {
        axon(sample)
        // The name after a dot, with arguments.
        assertColor(AxonColors.FUNCTION_CALL, "hisRead")
        assertColor(AxonColors.FUNCTION_CALL, "foldCol")
        assertColor(AxonColors.FUNCTION_CALL, "map")
        // A bare name before `(`: it is not in the file, and it is not an error.
        assertColor(AxonColors.FUNCTION_CALL, "readById")
        assertColor(AxonColors.FUNCTION_CALL, "logErr")
        // The name after a dot, with no arguments.
        assertColor(AxonColors.DOT_NAME, "isEmpty")
        assertColor(AxonColors.DOT_NAME, "size")
        assertNoProblem()
    }

    /** `sum` is not defined in the file and is not called: no colour, no error. */
    fun testUnresolvedBareNameStaysPlain() {
        axon(sample)
        assertPlain("sum")
        axon("readAll(point and siteRef == campus)")
        assertColor(AxonColors.FUNCTION_CALL, "readAll")
        assertPlain("point")
        assertPlain("siteRef")
        assertPlain("campus")
        assertNoProblem()
    }

    fun testTrailingLambdaIsACall() {
        axon("""
            (rows) => do
              rows.each row => echo(row)
              rows.findAll() r => r.has("x")
              doLater(5) () => rows
            end
        """)
        assertColor(AxonColors.FUNCTION_CALL, "each")
        assertColor(AxonColors.FUNCTION_CALL, "findAll")
        assertColor(AxonColors.FUNCTION_CALL, "doLater")
        assertColor(AxonColors.FUNCTION_CALL, "has")
        assertColor(AxonColors.PARAMETER, "rows", 0, 1, 2, 3)
        assertColor(AxonColors.PARAMETER, "row", 0, 1)
        assertColor(AxonColors.PARAMETER, "r", 0, 1)
    }

    /** A use of a function of the file is a call, with or without `(`; a later definition counts too. */
    fun testUseOfAFunctionOfTheFile() {
        axon("""
            main: (list) => list.map(double).map(v => triple(v))
            double: (n) => n * 2
            triple: (n) => n * 3
        """)
        assertColor(AxonColors.FUNCTION_DECLARATION, "main")
        assertColor(AxonColors.FUNCTION_DECLARATION, "double", 1)
        assertColor(AxonColors.FUNCTION_CALL, "double", 0)
        assertColor(AxonColors.FUNCTION_CALL, "triple", 0)
        assertColor(AxonColors.FUNCTION_DECLARATION, "triple", 1)
    }

    /** A local with a lambda value is a function; a parameter that is called stays a parameter. */
    fun testLocalFunctionAndCalledParameter() {
        axon("""
            (fn, a) => do
              twice: (v) => fn(fn(v))
              limit: 10
              twice(a) + limit
            end
        """)
        assertColor(AxonColors.PARAMETER, "fn", 0, 1, 2)
        assertColor(AxonColors.FUNCTION_DECLARATION, "twice", 0)
        assertColor(AxonColors.FUNCTION_CALL, "twice", 1)
        assertColor(AxonColors.LOCAL_VARIABLE, "limit", 0, 1)
        assertColor(AxonColors.PARAMETER, "v", 0, 1)
    }

    /** A value at the top of a file is a local of the file. */
    fun testTopLevelValueIsALocal() {
        axon("""
            maxRows: 100
            firstRows: (grid) => grid[0..maxRows]
        """)
        assertColor(AxonColors.LOCAL_VARIABLE, "maxRows", 0, 1)
        assertColor(AxonColors.FUNCTION_DECLARATION, "firstRows")
    }

    /** A local is in scope only after its definition; before it the name is unknown. */
    fun testNameBeforeItsDefinitionStaysPlain() {
        axon("""
            () => do
              echo(count)
              count: 1
              count
            end
        """)
        assertPlain("count", 0)
        assertColor(AxonColors.LOCAL_VARIABLE, "count", 1, 2)
    }

    fun testTagNames() {
        axon("""
            (rec) => do
              area: rec->area
              {dis: "Main", site, -old, "quoted key": 1, geoCity: rec->geoCity, area: area}
            end
        """)
        // After `->`.
        assertColor(AxonColors.TAG_NAME, "area", 1)
        assertColor(AxonColors.TAG_NAME, "geoCity", 1)
        // Dict keys: with a value, a marker, a removed tag.
        assertColor(AxonColors.TAG_NAME, "dis")
        assertColor(AxonColors.TAG_NAME, "site")
        assertColor(AxonColors.TAG_NAME, "old")
        assertColor(AxonColors.TAG_NAME, "geoCity", 0)
        assertColor(AxonColors.TAG_NAME, "area", 2)
        // The local of the same name keeps the local colour, as a definition and as a dict value.
        assertColor(AxonColors.LOCAL_VARIABLE, "area", 0, 3)
        // A quoted key is a string.
        assertTrue(infos.none { it.forcedTextAttributesKey in semantic && it.text.contains("quoted") })
        assertNoProblem()
    }

    /** A keyword is a name after a dot and as a dict key. */
    fun testKeywordAsName() {
        axon("""
            (a) => {end: a.not, do: a.and(true)}
        """)
        assertColor(AxonColors.TAG_NAME, "end")
        assertColor(AxonColors.TAG_NAME, "do")
        assertColor(AxonColors.DOT_NAME, "not")
        assertColor(AxonColors.FUNCTION_CALL, "and")
        assertNoProblem()
    }

    fun testDefcomp() {
        axon("""
            addOne: defcomp
              inVal: {is: ^number}
              outVal: {is: ^number}
              do
                step: 1
                outVal = inVal + step
              end
            end
        """)
        assertColor(AxonColors.FUNCTION_DECLARATION, "addOne")
        assertColor(AxonColors.DEFCOMP_CELL, "inVal", 0, 1)
        assertColor(AxonColors.DEFCOMP_CELL, "outVal", 0, 1)
        assertColor(AxonColors.TAG_NAME, "is", 0, 1)
        assertColor(AxonColors.LOCAL_VARIABLE, "step", 0, 1)
        assertNoProblem()
    }

    /** Broken code keeps the colours of the names that the parser found. */
    fun testColoursSurviveASyntaxError() {
        axon("""
            calc: (a, b) => do
              total: (a + b
              total
            end
        """)
        assertColor(AxonColors.FUNCTION_DECLARATION, "calc")
        assertColor(AxonColors.PARAMETER, "a", 0, 1)
        assertColor(AxonColors.LOCAL_VARIABLE, "total", 0)
    }

    fun testInjectedInFantomString() {
        highlight(
            "A.fan",
            "class A\n{\n  Void run(Obj cx)\n  {\n" +
                "    cx.eval(\"(point, dates) => do his: point.hisRead(dates); his.size + offset end\")\n" +
                "  }\n}\n",
        )
        assertColor(AxonColors.PARAMETER, "point", 0, 1)
        assertColor(AxonColors.PARAMETER, "dates", 0, 1)
        assertColor(AxonColors.LOCAL_VARIABLE, "his", 0, 1)
        assertColor(AxonColors.FUNCTION_CALL, "hisRead")
        assertColor(AxonColors.DOT_NAME, "size")
        assertPlain("offset")
        // Fantom names outside the string get no Axon colour.
        assertPlain("cx", 1)
        assertPlain("eval")
        assertNoProblem()
    }

    /**
     * A string with `$` + name is only a part of the Axon text. The Axon parser stops at the `$`,
     * so the names before it take colours, the names after it stay plain, and nothing is an error.
     */
    fun testInjectedTemplateString() {
        highlight(
            "A.fan",
            "class A\n{\n  Void run(Obj cx, Str id)\n  {\n" +
                "    cx.eval(\"(span) => span.toHis(readById(\$id).dur)\")\n" +
                "  }\n}\n",
        )
        assertColor(AxonColors.PARAMETER, "span", 0, 1)
        assertColor(AxonColors.FUNCTION_CALL, "toHis")
        assertColor(AxonColors.FUNCTION_CALL, "readById")
        assertPlain("dur")
        val bad = infos.filter { it.severity >= HighlightSeverity.ERROR }
        assertTrue("unexpected: " + bad.joinToString { "'${it.text}' ${it.description}" }, bad.isEmpty())
    }

    fun testInjectedInTrioSrc() {
        highlight(
            "lib.trio",
            "name: calcTotal\nfunc\nsrc:\n  (a, b) => do\n    total: a.toNum + b\n    logIt({val: total, extra})\n    total\n  end\n",
        )
        assertColor(AxonColors.PARAMETER, "a", 0, 1)
        assertColor(AxonColors.PARAMETER, "b", 0, 1)
        assertColor(AxonColors.LOCAL_VARIABLE, "total", 0, 1, 2)
        assertColor(AxonColors.DOT_NAME, "toNum")
        assertColor(AxonColors.FUNCTION_CALL, "logIt")
        assertColor(AxonColors.TAG_NAME, "val")
        assertColor(AxonColors.TAG_NAME, "extra")
        // Trio tag names outside `src:` get no Axon colour.
        assertPlain("calcTotal")
        assertPlain("func")
        assertNoProblem()
    }
}
