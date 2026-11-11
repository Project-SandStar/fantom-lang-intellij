package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.FantomParserDefinition
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase
import java.io.File

/**
 * Parses every `.fan` file under the folders in the `fantom.corpus` system
 * property (set from the `FANTOM_CORPUS` env var by Gradle) and reports how
 * many files have parse errors or bad characters.
 *
 * The test passes unless `fantom.corpus.failOnErrors=true`. Its job is to
 * measure, and to write `build/reports/fantom-corpus.txt` with one line per
 * file that has errors, plus the first error of each file.
 */
class FantomCorpusParsingTest : ParsingTestCase("", "fan", FantomParserDefinition()) {

    override fun getTestDataPath(): String = "src/test/testData"
    override fun skipSpaces(): Boolean = false
    override fun includeRanges(): Boolean = true

    fun testCorpus() {
        val roots = System.getProperty("fantom.corpus", "")
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map(::File)
            .filter { it.isDirectory }
        if (roots.isEmpty()) {
            println("FANTOM_CORPUS not set or no folder found; nothing to parse")
            return
        }

        val files = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "fan" }.toList()
        }.sortedBy { it.path }
        assertTrue("no .fan files under $roots", files.isNotEmpty())

        val results = files.map { parseOne(it) }
        val withErrors = results.filter { it.errors > 0 || it.badChars > 0 }
        val totalLines = results.sumOf { it.lines }
        val totalErrors = results.sumOf { it.errors }
        val totalBad = results.sumOf { it.badChars }

        val report = buildString {
            appendLine("files=${results.size} lines=$totalLines errors=$totalErrors badChars=$totalBad filesWithErrors=${withErrors.size}")
            appendLine()
            appendLine("# top error messages")
            results.flatMap { it.messages }
                .groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }
                .take(25)
                .forEach { appendLine("${it.value}\t${it.key}") }
            appendLine()
            appendLine("# per file: errors badChars lines path | first error")
            withErrors.sortedByDescending { it.errors + it.badChars }.forEach {
                appendLine("${it.errors}\t${it.badChars}\t${it.lines}\t${it.path} | ${it.first}")
            }
        }
        val out = File("build/reports/fantom-corpus.txt")
        out.parentFile.mkdirs()
        out.writeText(report)
        println(report.lineSequence().first())
        println("report: ${out.absolutePath}")

        if (System.getProperty("fantom.corpus.failOnErrors", "false").toBoolean()) {
            assertEquals("files with parse errors", 0, withErrors.size)
        }
    }

    private data class Result(
        val path: String,
        val lines: Int,
        val errors: Int,
        val badChars: Int,
        val first: String,
        val messages: List<String>,
    )

    private fun parseOne(file: File): Result {
        val text = file.readText()
        val psi: PsiFile = createPsiFile(file.name, text)
        ensureParsed(psi)
        val errors = PsiTreeUtil.collectElementsOfType(psi, PsiErrorElement::class.java).toList()
        val bad = mutableListOf<PsiElement>()
        PsiTreeUtil.processElements(psi) {
            if (it is LeafPsiElement && it.elementType == TokenType.BAD_CHARACTER) bad += it
            true
        }
        val first = when {
            errors.isNotEmpty() -> describe(text, errors.first().textOffset, errors.first().errorDescription)
            bad.isNotEmpty() -> describe(text, bad.first().textOffset, "bad character '${bad.first().text}'")
            else -> ""
        }
        val messages = errors.map { it.errorDescription } + bad.map { "bad character '${it.text}'" }
        return Result(file.path, text.count { it == '\n' } + 1, errors.size, bad.size, first, messages)
    }

    private fun describe(text: String, offset: Int, message: String): String {
        val line = text.substring(0, offset.coerceAtMost(text.length)).count { it == '\n' } + 1
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        val snippet = text.substring(lineStart, lineEnd).trim().take(80)
        return "line $line: $message :: $snippet"
    }
}
