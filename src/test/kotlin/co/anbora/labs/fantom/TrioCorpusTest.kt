package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.trio.psi.TrioDictVal
import co.anbora.labs.fantom.lang.trio.psi.TrioListVal
import co.anbora.labs.fantom.lang.trio.psi.TrioRecord
import co.anbora.labs.fantom.lang.trio.psi.TrioSrc
import co.anbora.labs.fantom.lang.trio.psi.TrioTag
import co.anbora.labs.fantom.lang.trio.psi.TrioTextBlock
import co.anbora.labs.fantom.lang.trio.psi.TrioTrioBlock
import co.anbora.labs.fantom.lang.trio.psi.TrioTypes
import co.anbora.labs.fantom.lang.trio.psi.TrioValue
import co.anbora.labs.fantom.lang.trio.psi.TrioZincBlock
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * The Trio parser against real files, read in place:
 *  - every `.trio` file of the Haxall 4.0.7 sources must parse with no error;
 *  - every snippet of the tree-sitter-trio test corpus must give the tree that tree-sitter gives.
 * Both skip when the files are not on this machine.
 */
class TrioCorpusTest : BasePlatformTestCase() {

    private val home = File(System.getProperty("user.home"))
    private val haxall = File(home, "haxall/haxall-4.0.7")
    private val treeSitterCorpus = File(home, "Code/mcpfantom/tree-sitter-trio/test/corpus")

    /** Files that do not parse clean yet, by path under the Haxall home, with the construct that fails. */
    private val knownBad: Map<String, String> = mapOf()

    fun testHaxallTrioFilesParseClean() {
        if (!TestInstalls.requireInstall(haxall, "the Haxall 4.0.7 sources")) return
        val files = haxall.walkTopDown().filter { it.isFile && it.extension == "trio" }.sortedBy { it.path }.toList()
        assertTrue("no .trio files under ${haxall.path}", files.isNotEmpty())

        var records = 0
        var tags = 0
        val bad = ArrayList<String>()
        val nowClean = ArrayList<String>()
        for (file in files) {
            val rel = file.relativeTo(haxall).path
            val psi = TrioTestSupport.parse(project, file.readText())
            records += PsiTreeUtil.findChildrenOfType(psi, TrioRecord::class.java).size
            tags += PsiTreeUtil.findChildrenOfType(psi, TrioTag::class.java).size
            val problems = TrioTestSupport.problems(psi)
            when {
                problems.isEmpty() && rel in knownBad -> nowClean += rel
                problems.isNotEmpty() && rel !in knownBad -> bad += "$rel: ${problems.take(3).joinToString("; ")}"
            }
        }
        println("TrioCorpusTest: ${files.size} files, $records records, $tags tags, ${bad.size} with errors, ${knownBad.size} allowed")
        assertTrue("files with syntax errors:\n" + bad.joinToString("\n"), bad.isEmpty())
        assertTrue("files in knownBad that parse clean now, remove them:\n" + nowClean.joinToString("\n"), nowClean.isEmpty())
        assertTrue("the corpus must have records", records > 0 && tags > records)
    }

    fun testTreeSitterCorpusGivesTheSameTree() {
        if (!TestInstalls.requireInstall(treeSitterCorpus, "the tree-sitter-trio test corpus")) return
        val cases = treeSitterCorpus.listFiles { f -> f.extension == "txt" }.orEmpty().sortedBy { it.name }.flatMap { cases(it) }
        assertTrue("no test cases under ${treeSitterCorpus.path}", cases.isNotEmpty())

        val bad = ArrayList<String>()
        for (case in cases) {
            val psi = TrioTestSupport.parse(project, case.source)
            val problems = TrioTestSupport.problems(psi)
            val actual = sexp(psi)
            if (problems.isNotEmpty()) bad += "${case.name}: ${problems.joinToString("; ")}"
            else if (actual != case.expected) bad += "${case.name}:\n  tree-sitter: ${case.expected}\n  plugin:      $actual"
        }
        println("TrioCorpusTest: ${cases.size} tree-sitter cases, ${bad.size} differ")
        assertTrue("trees that differ from tree-sitter:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    private class Case(val name: String, val source: String, val expected: String)

    /** The cases of one tree-sitter corpus file: `===` name `===` source `---` tree. */
    private fun cases(file: File): List<Case> {
        val out = ArrayList<Case>()
        val lines = file.readText().replace("\r\n", "\n").split("\n")
        var i = 0
        fun isRule(line: String, c: Char) = line.length >= 10 && line.all { it == c }
        while (i < lines.size) {
            if (!isRule(lines[i], '=')) { i++; continue }
            val name = lines[i + 1].trim()
            i += 3
            val source = ArrayList<String>()
            // The source ends at the last `---` rule before the tree; a Trio separator is shorter.
            while (i < lines.size && !isRule(lines[i], '-')) source += lines[i++]
            i++
            val tree = ArrayList<String>()
            while (i < lines.size && !isRule(lines[i], '=')) tree += lines[i++]
            out += Case("${file.name}: $name", source.joinToString("\n"), normalize(tree.joinToString(" ")))
        }
        return out
    }

    private fun normalize(sexp: String): String = sexp.replace(Regex("\\s+"), " ").replace("( ", "(").replace(" )", ")").trim()

    /** The plugin tree in the node names of tree-sitter-trio. */
    private fun sexp(file: PsiFile): String {
        val out = StringBuilder("(source_file")
        var child = file.firstChild
        while (child != null) {
            when {
                child is PsiComment -> out.append(" (comment)")
                child.elementType == TrioTypes.SEPARATOR -> out.append(" (separator)")
                child is TrioRecord -> {
                    out.append(" (record")
                    for (tag in PsiTreeUtil.getChildrenOfTypeAsList(child, TrioTag::class.java)) out.append(' ').append(tagSexp(tag))
                    out.append(')')
                }
            }
            child = child.nextSibling
        }
        return out.append(')').toString()
    }

    private fun tagSexp(tag: TrioTag): String {
        val value = PsiTreeUtil.getChildOfType(tag, TrioValue::class.java) ?: return "(tag (name))"
        val multiLine = value.textContains('\n')
        return when (value) {
            is TrioTextBlock -> "(block_tag (name) (indented_text))"
            is TrioZincBlock -> "(block_tag (name) (zinc_block) (indented_text))"
            is TrioTrioBlock -> "(block_tag (name) (trio_block) (indented_text))"
            // tree-sitter has no `src` node: a `src` value is indented text, or the scalar on the tag line.
            is TrioSrc -> if (tag.textContains('\n')) "(block_tag (name) (indented_text))" else "(tag (name) (raw))"
            is TrioListVal -> if (multiLine) "(block_tag (name) (list_block) (indented_text))" else "(tag (name) (list))"
            is TrioDictVal -> if (multiLine) "(block_tag (name) (dict_block) (indented_text))" else "(tag (name) (dict))"
            else -> "(tag (name) (${scalarName(value)}))"
        }
    }

    private fun scalarName(value: PsiElement): String = when (value.node.elementType) {
        TrioTypes.STR_VAL -> "str"
        TrioTypes.RAW_VAL -> "raw"
        TrioTypes.NUMBER_VAL -> "number"
        TrioTypes.BOOL_VAL -> "bool"
        TrioTypes.NA_VAL -> "na"
        TrioTypes.REMOVE_VAL -> "remove"
        TrioTypes.REF_VAL -> "ref"
        TrioTypes.SYMBOL_VAL -> "symbol"
        TrioTypes.URI_VAL -> "uri"
        TrioTypes.DATE_VAL -> "date"
        TrioTypes.TIME_VAL -> "time"
        TrioTypes.DATE_TIME_VAL -> "date_time"
        TrioTypes.COORD_VAL, TrioTypes.XSTR_VAL -> "xstr"
        else -> value.node.elementType.toString()
    }
}
