package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.library.FantomHome
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Gate for the formatter: reformatting every `.fan` file of the `sys` and `util` pods must
 * change nothing. Skips when no Fantom home is found. Prints the count of differing files
 * and the first five diffs so a failure is easy to read in the Gradle output.
 */
class FantomFormatterCorpusTest : BasePlatformTestCase() {

    private val tag = "FantomFormatterCorpusTest:"

    /**
     * Files where the pod source itself breaks the two-space rule, so a correct formatter must
     * change them. `util/TestRunner.fan`: three statements after a triple-quoted string sit at
     * five spaces instead of four. `util/JsonOutStream.fan`: the first `if` of a chain is pushed
     * right by hand to line up with the `else if` lines below it.
     */
    private val KNOWN_CORPUS_ODDITIES = setOf("util/TestRunner.fan", "util/JsonOutStream.fan")

    fun testSysAndUtilAreStable() {
        val home = FantomHome.detect()
        if (home == null) {
            println("$tag no Fantom home, skipped")
            return
        }
        val files = listOf("sys", "util")
            .map { File(home, "src/$it/fan") }
            .flatMap { it.listFiles { f -> f.extension == "fan" }?.toList() ?: emptyList() }
            .sortedBy { it.path }
        assertTrue("$tag no .fan files under $home", files.isNotEmpty())

        val diffs = ArrayList<String>()
        var trailingOnly = 0
        val known = ArrayList<String>()
        for (f in files) {
            val before = f.readText()
            val after = reformat(f.name, before)
            if (after == before) continue
            val key = "${f.parentFile.parentFile.name}/${f.name}"
            when {
                trimEnds(after) == trimEnds(before) -> trailingOnly++
                key in KNOWN_CORPUS_ODDITIES -> known.add(key)
                else -> diffs.add(describe(f, before, after))
            }
        }
        println("$tag files=${files.size} differing=${diffs.size} trailingSpaceOnly=$trailingOnly knownOddities=$known")
        diffs.take(5).forEach(::println)
        assertEquals("$tag differing files:\n" + diffs.take(5).joinToString("\n"), 0, diffs.size)
    }

    /** The formatter strips trailing spaces on the lines it re-indents; the pods have one such line. */
    private fun trimEnds(s: String): String = s.lines().joinToString("\n") { it.trimEnd() }

    private fun reformat(name: String, text: String): String {
        myFixture.configureByText(name, text)
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(myFixture.file)
        }
        return myFixture.editor.document.text
    }

    private fun describe(f: File, before: String, after: String): String {
        val a = before.lines()
        val b = after.lines()
        val out = StringBuilder("$tag ${f.parentFile.parentFile.name}/${f.name}")
        var shown = 0
        var i = 0
        while (i < maxOf(a.size, b.size) && shown < 3) {
            val x = a.getOrNull(i)
            val y = b.getOrNull(i)
            if (x != y) {
                out.append("\n  ${i + 1}: -[").append(x).append("]\n  ${i + 1}: +[").append(y).append(']')
                shown++
            }
            i++
        }
        return out.toString()
    }
}
