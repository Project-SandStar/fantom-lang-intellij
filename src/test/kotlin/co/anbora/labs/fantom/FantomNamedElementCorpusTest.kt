package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Parses the first 20 files of the Fantom `sys` pod and checks that every
 * [FantomNamedElement] has a non-null, non-empty, identifier-shaped name.
 * Skips when the Fantom source tree is not on this machine.
 */
class FantomNamedElementCorpusTest : BasePlatformTestCase() {

    private val sysDir = File(System.getProperty("fantom.sys.dir", "/Users/alper/fantom/fantom-1.0.84/src/sys/fan"))
    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")

    private data class Bad(val file: String, val cls: String, val offset: Int, val name: String?, val text: String, val fileHasErrors: Boolean)

    fun testSysPodNames() {
        if (!sysDir.isDirectory) {
            println("FantomNamedElementCorpusTest: $sysDir missing, skipped")
            return
        }
        val files = sysDir.listFiles { f -> f.isFile && f.extension == "fan" }
            ?.sortedBy { it.name }
            ?.take(20)
            .orEmpty()
        assertFalse("no .fan files in $sysDir", files.isEmpty())

        var total = 0
        val perClass = sortedMapOf<String, Int>()
        val bad = mutableListOf<Bad>()
        var filesWithErrors = 0

        for (f in files) {
            val text = StringUtil.convertLineSeparators(f.readText())
            val psi = myFixture.configureByText(f.name, text)
            val hasErrors = PsiTreeUtil.hasErrorElements(psi)
            if (hasErrors) filesWithErrors++
            val named = PsiTreeUtil.findChildrenOfType(psi, FantomNamedElement::class.java)
            total += named.size
            for (e in named) {
                val cls = e.javaClass.simpleName.removeSuffix("Impl")
                perClass.merge(cls, 1, Int::plus)
                val name = e.name
                if (name.isNullOrEmpty() || !identifier.matches(name)) {
                    bad += Bad(f.name, cls, e.textOffset, name, e.text.lineSequence().first().take(80), hasErrors)
                }
            }
        }

        println("FantomNamedElementCorpusTest: files=${files.size} filesWithParseErrors=$filesWithErrors namedElements=$total bad=${bad.size} perClass=$perClass")
        assertTrue("expected named elements in ${files.size} sys files", total > 0)
        if (bad.isNotEmpty()) {
            val report = bad.joinToString("\n") {
                "  ${it.file}@${it.offset} ${it.cls} name=${it.name} parseErrors=${it.fileHasErrors} :: ${it.text}"
            }
            fail("${bad.size} named elements with bad names out of $total:\n$report")
        }
    }
}
