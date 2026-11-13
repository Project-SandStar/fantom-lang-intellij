package co.anbora.labs.fantom

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * `checkHighlighting` for inspection tests. The annotator adds silent annotations with
 * a custom INFORMATION-level severity that the platform's checker reports as "extra",
 * so this base compares only the infos at WEAK WARNING and above against the
 * `<warning descr="...">` and `<weak_warning descr="...">` markers in the text.
 */
abstract class FantomInspectionTestBase : BasePlatformTestCase() {

    private val marker = Regex("""<(warning|weak_warning) descr="([^"]*)">|</(warning|weak_warning)>""")

    protected fun checkWarnings(markedText: String, fileName: String = "A.fan") {
        val expected = ArrayList<String>()
        val open = ArrayDeque<Triple<Int, String, String>>()
        val plain = StringBuilder()
        var last = 0
        for (m in marker.findAll(markedText)) {
            plain.append(markedText, last, m.range.first)
            last = m.range.last + 1
            if (m.groupValues[1].isNotEmpty()) {
                open.addLast(Triple(plain.length, m.groupValues[1], m.groupValues[2]))
            } else {
                val (start, kind, descr) = open.removeLast()
                expected += "$kind ${plain.substring(start)}@$start: $descr"
            }
        }
        plain.append(markedText, last, markedText.length)
        // A path such as `foo/fan/A.fan` goes into the project at that path.
        if ('/' in fileName) myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(fileName, plain.toString()).virtualFile)
        else myFixture.configureByText(fileName, plain.toString())
        val actual = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).map { info ->
            val kind = if (info.severity == HighlightSeverity.WEAK_WARNING) "weak_warning" else info.severity.name.lowercase()
            "$kind ${info.text}@${info.startOffset}: ${info.description}"
        }
        assertEquals(expected.sorted().joinToString("\n"), actual.sorted().joinToString("\n"))
    }
}
