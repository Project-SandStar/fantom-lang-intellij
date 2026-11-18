package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.axon.AxonParserDefinition
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase
import java.io.File

/**
 * Parses real Axon, read in place, and fails when a source has a parse error.
 *
 * - the Axon of `~/haxall/haxall-4.0.7`: every `axon` code block of its `.md` files and
 *   every Axon `src:` value of its `.trio` files. In 4.0.7 all 20 `src:` values belong to
 *   view records, which hold view settings and no Axon, so the trio part is empty there;
 * - every `.axon` file under `~/Code/axon-mcp-server/proj`, when that folder is there.
 *   These are private working files and a few have real errors, so this test only
 *   fails above [PROJECT_MAX_ERROR_RATIO];
 * - every source snippet of the tree-sitter-axon test corpus in `~/Code/mcpfantom`
 *   (the oracle: that grammar accepts them, so this parser must too).
 *
 * Nothing is copied into the repository. A missing folder skips the test.
 */
class AxonCorpusParsingTest : ParsingTestCase("", "axon", AxonParserDefinition()) {

    private val user = File(System.getProperty("user.home"))

    override fun getTestDataPath(): String = "src/test/testData"

    /** One Axon source: where it is from and its text. */
    private class Source(val name: String, val text: String)

    /** `name: message at line N` for the first error of [source], null when it parses clean. */
    private fun firstError(source: Source): String? {
        val psi = createPsiFile("a", source.text)
        ensureParsed(psi)
        val error = PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java) ?: return null
        val line = source.text.substring(0, error.textOffset).count { it == '\n' } + 1
        return "${source.name}: line $line: ${error.errorDescription}"
    }

    /**
     * Fails when more than [maxRatio] of [sources] have a parse error. With [private] the
     * message gives the error texts and their counts only, never a file name, a line or
     * a token of the source.
     */
    private fun check(
        what: String,
        sources: List<Source>,
        allowed: Set<String> = emptySet(),
        maxRatio: Double = 0.0,
        private: Boolean = false,
    ) {
        assertTrue("$what: no Axon source found", sources.isNotEmpty())
        val failed = sources.mapNotNull { s -> firstError(s)?.takeIf { s.name !in allowed } }
        println("$what: ${sources.size} sources, ${failed.size} with parse errors")
        if (failed.size > sources.size * maxRatio) {
            val lines = if (!private) failed.take(40) else
                // `, got 'text'` holds text of the private file: cut it.
                failed.groupingBy { it.substringAfter(": line ").substringAfter(": ").substringBefore(", got '") }.eachCount()
                    .entries.sortedByDescending { it.value }.map { "${it.value} x ${it.key}" }
            fail("$what: ${failed.size} of ${sources.size} sources have parse errors:\n" + lines.joinToString("\n"))
        }
    }

    fun testHaxallAxon() {
        val root = File(user, "haxall/haxall-4.0.7")
        if (!TestInstalls.requireInstall(root, "Haxall 4.0.7 sources")) return
        val files = root.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
        val trio = files.filter { it.extension == "trio" }
            .flatMap { f -> trioSrcValues(f.readText()).mapIndexed { i, text -> Source("${f.relativeTo(root).path}#$i", text) } }
        val docs = files.filter { it.extension == "md" }
            .flatMap { f -> markdownAxonBlocks(f.readText()).mapIndexed { i, text -> Source("${f.relativeTo(root).path}#$i", text) } }
        println("Haxall: ${trio.size} trio src values, ${docs.size} markdown blocks")
        check("Haxall Axon", trio + docs, HAXALL_ALLOWED)
    }

    /**
     * The Axon strings of the Haxall Fantom tests: the first argument of `eval(...)`,
     * `verifyEval(...)` and the like must parse clean, and the first argument of
     * `verifySyntaxErr(...)` and `verifyExprErr(...)` in the Axon tests must have an error.
     */
    fun testHaxallTestSuite() {
        val root = File(user, "haxall/haxall-4.0.7")
        if (!TestInstalls.requireInstall(root, "Haxall 4.0.7 sources")) return
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension == "fan" && "test" in it.relativeTo(root).path.split(File.separatorChar) }
            .sortedBy { it.path }.toList()
        val valid = ArrayList<Source>()
        val invalid = ArrayList<Source>()
        for (f in files) {
            val path = f.relativeTo(root).path
            val text = f.readText()
            val names = if (path.endsWith("testAxon/fan/ParseTest.fan")) VALID_CALLS + PARSE_TEST_CALLS else VALID_CALLS
            fantomCallStrings(text, names).mapTo(valid) { (line, src) -> Source("$path:$line", src) }
            if (path.startsWith("src/test/testAxon/")) {
                fantomCallStrings(text, INVALID_CALLS).mapTo(invalid) { (line, src) -> Source("$path:$line", src) }
            }
        }
        check("Haxall test suite", valid, HAXALL_TESTS_ALLOWED)

        assertTrue("Haxall test suite: no must-fail source found", invalid.isNotEmpty())
        val accepted = invalid.filter { it.name !in HAXALL_TESTS_ACCEPTED && firstError(it) == null }
        println("Haxall test suite: ${invalid.size} must-fail sources, ${accepted.size} parse with no error")
        if (accepted.isNotEmpty()) {
            fail("Haxall test suite: these sources must have a parse error:\n" +
                accepted.take(40).joinToString("\n") { "${it.name}: ${it.text.replace("\n", "\\n").take(80)}" })
        }
    }

    fun testProjectAxonFiles() {
        val root = File(user, "Code/axon-mcp-server/proj")
        // Not an install: this folder is on one machine only, so its absence never fails the test.
        if (!root.isDirectory) {
            println("AxonCorpusParsingTest.testProjectAxonFiles skipped: missing ${root.path}")
            return
        }
        val sources = root.walkTopDown().filter { it.isFile && it.extension == "axon" }.sortedBy { it.path }
            .map { Source(it.relativeTo(root).path, it.readText()) }.toList()
        if (sources.isEmpty()) {
            println("AxonCorpusParsingTest.testProjectAxonFiles skipped: no .axon file under ${root.path}")
            return
        }
        check("Project axon files", sources, maxRatio = PROJECT_MAX_ERROR_RATIO, private = true)
    }

    fun testTreeSitterCorpus() {
        val root = File(user, "Code/mcpfantom/tree-sitter-axon/test/corpus")
        if (!root.isDirectory) {
            println("AxonCorpusParsingTest.testTreeSitterCorpus skipped: missing ${root.path}")
            return
        }
        val sources = root.listFiles { f -> f.extension == "txt" }.orEmpty().sortedBy { it.name }
            .flatMap { f -> treeSitterCases(f.readText()).map { (name, text) -> Source("${f.name} / $name", text) } }
        check("tree-sitter-axon corpus", sources)
    }

    companion object {
        /** Haxall sources that are known not to parse, as `path#index`, each with the construct that fails. */
        val HAXALL_ALLOWED: Set<String> = setOf(
            // `(cells, num) => ...`: the three dots stand for a body the document leaves out. Not Axon.
            "src/xeto/hx.io/skills/know-io.md#2",
        )

        /** Test methods of the Haxall tests whose first argument is Axon that must parse. */
        val VALID_CALLS: Set<String> = setOf("eval", "verifyEval", "verifyBlock", "verifyToStr")

        /** The same, for the Axon `ParseTest` only: other tests use these names for filters and XML. */
        val PARSE_TEST_CALLS: Set<String> = setOf("verifyParse", "verifyExpr")

        /** Test methods of the Haxall Axon tests whose first argument must not parse. */
        val INVALID_CALLS: Set<String> = setOf("verifySyntaxErr", "verifyExprErr")

        /** Haxall test strings that are known not to parse, as `path:line`. */
        val HAXALL_TESTS_ALLOWED: Set<String> = setOf(
            // A plain "..." string that runs over several lines. The Haxall tokenizer rejects it too
            // ("Unexpected end of str"); the test needs a database, so it does not run by default.
            "src/conn/hxSql/test/SqlConnTest.fan:67",
            "src/conn/hxSql/test/SqlConnTest.fan:139",
        )

        /** Haxall must-fail strings that this parser accepts, as `path:line`. */
        val HAXALL_TESTS_ACCEPTED: Set<String> = setOf(
            // `125fb380-0072e373`, the old Ref form: Haxall rejects the unit name. The lexer here
            // takes any letters after a number as a unit and does not know the unit database.
            "src/test/testAxon/fan/EvalTest.fan:58",
            // `return` with its value on the next line. A `return` with no value is accepted
            // on purpose (see Axon.bnf), so the value is read as the next expression.
            "src/test/testAxon/fan/EvalTest.fan:812",
        )

        /** The text of each code block of a Markdown text that opens with three backticks and `axon`. */
        fun markdownAxonBlocks(markdown: String): List<String> {
            val out = ArrayList<String>()
            val lines = markdown.lines()
            var i = 0
            while (i < lines.size) {
                if (lines[i++].trim() != "```axon") continue
                val block = ArrayList<String>()
                while (i < lines.size && lines[i].trim() != "```") block.add(lines[i++])
                i++
                out.add(block.joinToString("\n"))
            }
            return out
        }

        /**
         * The share of project files that may have a parse error. The files are private, so
         * there is no list of names here. On 3239 files the parser reports 14, and the Axon
         * parser of SkySpark 3.1 rejects the same 14: `do ... while (x)`, a block with no
         * closing `end` (2), the `?.` and `?:` operators (2), a `{{name}}` template placeholder,
         * a parameter named `end`, a tag named `return` in a filter (2), a `(` that starts the
         * line after `a.name`, and a definition whose name starts with a capital letter (4).
         * The limit leaves room for two more such files.
         */
        const val PROJECT_MAX_ERROR_RATIO = 0.005

        /**
         * The Axon `src:` values of a trio text: `src: expr` on one line, or `src:` and an
         * indented block. A record with a `view` tag is left out: its `src` holds view settings.
         */
        fun trioSrcValues(trio: String): List<String> {
            val out = ArrayList<String>()
            val lines = trio.lines()
            var i = 0
            var recordStart = 0
            while (i < lines.size) {
                val line = lines[i++]
                if (line.startsWith("-")) recordStart = i
                if (!line.startsWith("src:")) continue
                val inline = line.removePrefix("src:").trim()
                val block = ArrayList<String>()
                if (inline.isEmpty()) {
                    while (i < lines.size && (lines[i].isBlank() || lines[i].startsWith(" ") || lines[i].startsWith("\t"))) block.add(lines[i++])
                }
                val record = lines.subList(recordStart, lines.size).takeWhile { !it.startsWith("-") }
                if (record.any { it == "view" || it.startsWith("view:") }) continue
                if (inline.isNotEmpty()) {
                    out.add(inline)
                    continue
                }
                val indent = block.filter { it.isNotBlank() }.minOfOrNull { l -> l.length - l.trimStart().length } ?: 0
                out.add(block.joinToString("\n") { if (it.length >= indent) it.substring(indent) else "" }.trimEnd())
            }
            return out
        }

        /**
         * The string literal that is the first argument of each call of one of [names] in the
         * Fantom text [fan], with the line of the call. Reads `"..."`, `"""..."""` and
         * `Str<|...|>`. A literal with `$` interpolation is left out: its text is not known.
         */
        fun fantomCallStrings(fan: String, names: Set<String>): List<Pair<Int, String>> {
            val out = ArrayList<Pair<Int, String>>()
            val call = Regex("""\b(\w+)\(\s*""")
            for (m in call.findAll(fan)) {
                if (m.groupValues[1] !in names) continue
                val text = fantomString(fan, m.range.last + 1) ?: continue
                out.add(fan.substring(0, m.range.first).count { it == '\n' } + 1 to text)
            }
            return out
        }

        /** The value of the Fantom string literal that starts at [at], null when there is none or it has `$`. */
        private fun fantomString(fan: String, at: Int): String? {
            if (fan.startsWith("Str<|", at)) {
                val end = fan.indexOf("|>", at + 5)
                return if (end < 0) null else fan.substring(at + 5, end)
            }
            val triple = fan.startsWith("\"\"\"", at)
            if (!triple && !fan.startsWith("\"", at)) return null
            val quote = if (triple) "\"\"\"" else "\""
            val sb = StringBuilder()
            var i = at + quote.length
            while (i < fan.length) {
                val c = fan[i]
                if (fan.startsWith(quote, i)) return sb.toString()
                if (c == '$' || (c == '\n' && !triple)) return null
                if (c != '\\') { sb.append(c); i++; continue }
                val e = fan.getOrNull(i + 1) ?: return null
                i += 2
                when (e) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'f' -> sb.append('\u000c')
                    'b' -> sb.append('\b')
                    'u' -> {
                        val code = fan.substring(i, minOf(i + 4, fan.length)).toIntOrNull(16) ?: return null
                        sb.append(code.toChar())
                        i += 4
                    }
                    else -> sb.append(e)
                }
            }
            return null
        }

        /**
         * The cases of a tree-sitter corpus file: a header between two `====` lines,
         * the source, a `---` line, the expected tree. Returns name and source.
         */
        fun treeSitterCases(text: String): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            val lines = text.lines()
            var i = 0
            while (i < lines.size) {
                if (!lines[i].startsWith("===")) { i++; continue }
                val name = lines.getOrNull(i + 1)?.trim().orEmpty()
                i += 2
                while (i < lines.size && !lines[i].startsWith("===")) i++
                i++
                val src = ArrayList<String>()
                while (i < lines.size && !lines[i].startsWith("---")) src.add(lines[i++])
                while (i < lines.size && !lines[i].startsWith("===")) i++
                out.add(name to src.joinToString("\n").trim('\n'))
            }
            return out
        }
    }
}
