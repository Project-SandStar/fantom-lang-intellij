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
 *  - every `.trio` file of a local SkySpark project export must parse with no error.
 * For the Haxall files and the export, the tag names of each record must also be the names
 * that the line rules of `haystack::TrioReader` give, so a block that ends at the wrong line fails.
 * All three skip when the files are not on this machine. The messages of the export test
 * give counts only, and no file name or text of a file.
 */
class TrioCorpusTest : BasePlatformTestCase() {

    private val home = File(System.getProperty("user.home"))
    private val haxall = File(home, "haxall/haxall-4.0.7")
    private val treeSitterCorpus = File(home, "Code/mcpfantom/tree-sitter-trio/test/corpus")
    private val projectExport = File(home, "Code/axon-mcp-server/proj")

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

    /** The blocks of the Haxall files end where `TrioReader` ends them. */
    fun testHaxallTagNamesMatchTheLineRules() {
        if (!TestInstalls.requireInstall(haxall, "the Haxall 4.0.7 sources")) return
        val files = haxall.walkTopDown().filter { it.isFile && it.extension == "trio" }.sortedBy { it.path }.toList()
        val bad = files.filter { !sameTagNames(it.readText().replace("\r\n", "\n")) }.map { it.relativeTo(haxall).path }
        println("TrioCorpusTest: ${files.size} Haxall files against the line rules, ${bad.size} differ")
        assertTrue("files with other tag names than TrioReader reads:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    /**
     * A SkySpark project export: function records with long `src` blocks, strings over
     * more than one line and dicts over indented lines. The files stay where they are.
     */
    fun testProjectExportParsesClean() {
        if (!TestInstalls.requireInstall(projectExport, "a SkySpark project export")) return
        val files = projectExport.walkTopDown()
            .onEnter { it.name != "node_modules" && it.name != ".git" }
            .filter { it.isFile && it.extension == "trio" }.toList()
        if (!TestInstalls.requireInstall(files.isNotEmpty(), "the .trio files of a SkySpark project export")) return

        var records = 0
        var withErrors = 0
        var otherNames = 0
        val kinds = HashMap<String, Int>()
        for (file in files) {
            val text = file.readText().replace("\r\n", "\n")
            val psi = TrioTestSupport.parse(project, text)
            records += PsiTreeUtil.findChildrenOfType(psi, TrioRecord::class.java).size
            val problems = TrioTestSupport.problems(psi)
            if (problems.isNotEmpty()) {
                withErrors++
                // The kind of the first problem, with the text of the file taken out.
                val kind = problems[0].substringAfter(": ").replace(Regex("'[^']*' unexpected"), "text unexpected").replace(Regex("got '.*"), "got text")
                kinds.merge(kind, 1, Int::plus)
            } else if (tagNames(psi) != lineRuleTagNames(text)) {
                otherNames++
            }
        }
        println("TrioCorpusTest: ${files.size} export files, $records records, $withErrors with errors, $otherNames with other tag names")
        val summary = kinds.entries.sortedByDescending { it.value }.joinToString("\n") { "${it.value} x ${it.key}" }
        assertEquals("export files with syntax errors, by kind:\n$summary", 0, withErrors)
        assertEquals("export files with other tag names than the line rules give", 0, otherNames)
        assertTrue("the export must have records", records > 0)
    }

    private fun sameTagNames(text: String): Boolean = tagNames(TrioTestSupport.parse(project, text)) == lineRuleTagNames(text)

    private fun tagNames(file: PsiFile): List<List<String?>> =
        PsiTreeUtil.getChildrenOfTypeAsList(file, TrioRecord::class.java).map { r -> r.tagList.map { it.name } }

    /**
     * The tag names of each record by the line rules of `haystack::TrioReader` (`readTag` and
     * `readIndentedText`), with no grammar. Two forms that project exports write and
     * `TrioReader` does not read are added: a quoted string that closes on a later line,
     * and a lone `{` (read as a lone `[`).
     */
    private fun lineRuleTagNames(text: String): List<List<String?>> {
        val lines = text.split("\n")
        val records = ArrayList<List<String?>>()
        var tags = ArrayList<String?>()
        var i = 0
        fun closes(s: String): Boolean = s.replace(Regex("\\\\."), "").contains('"')
        while (i < lines.size) {
            val line = lines[i++]
            if (line.startsWith("-")) {
                if (tags.isNotEmpty()) records += tags
                tags = ArrayList()
                continue
            }
            if (line.isBlank() || line.startsWith("//")) continue
            val colon = line.indexOf(':')
            if (colon < 0) {
                tags += line.trim()
                continue
            }
            tags += line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            when {
                value.isEmpty() || value == "[" || value == "{" || value == "Zinc:" || value == "Trio:" ->
                    // readIndentedText: all lines up to the first line of two or more characters that starts at column 0
                    while (i < lines.size && !(lines[i].length > 1 && !lines[i][0].isWhitespace())) i++
                value.startsWith("\"") && !closes(value.substring(1)) ->
                    while (i < lines.size && !closes(lines[i++])) Unit
            }
        }
        if (tags.isNotEmpty()) records += tags
        return records
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
