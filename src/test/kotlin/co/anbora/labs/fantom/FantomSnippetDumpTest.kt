package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.FantomParserDefinition
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase
import java.io.File

/**
 * Debug helper. Set `fantom.snippets` to a folder; every `*.fan` file in it is
 * parsed and its PSI tree is written next to it as `*.tree.txt`. Errors are
 * printed to stdout. Never fails.
 */
class FantomSnippetDumpTest : ParsingTestCase("", "fan", FantomParserDefinition()) {

    override fun getTestDataPath(): String = "src/test/testData"
    override fun skipSpaces(): Boolean = true
    override fun includeRanges(): Boolean = false

    fun testDump() {
        val dir = System.getProperty("fantom.snippets", "").takeIf { it.isNotBlank() }?.let(::File) ?: return
        val files = dir.listFiles { f -> f.extension == "fan" }?.sortedBy { it.name } ?: return
        for (f in files) {
            val text = f.readText()
            val psi = createPsiFile(f.name, text)
            ensureParsed(psi)
            val errors = PsiTreeUtil.collectElementsOfType(psi, PsiErrorElement::class.java)
            File(f.path.removeSuffix(".fan") + ".tree.txt").writeText(toParseTreeText(psi, skipSpaces(), includeRanges()))
            println("SNIPPET ${f.name}: errors=${errors.size}" + errors.take(3).joinToString("") { e ->
                val line = text.substring(0, e.textOffset).count { it == '\n' } + 1
                "\n    line $line @${e.textOffset}: ${e.errorDescription}"
            })
        }
    }
}
