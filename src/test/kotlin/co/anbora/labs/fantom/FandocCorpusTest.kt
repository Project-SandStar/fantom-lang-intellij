package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.documentation.FantomDocumentationProvider
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.fandoc.FandocRenderer
import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.resolve.FantomScope
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Renders the fandoc of every type and slot in `sys/fan/Str.fan` and `List.fan`
 * and checks that no exception is thrown and that the HTML tags balance.
 * Skips when the Fantom source tree is not on this machine.
 */
class FandocCorpusTest : BasePlatformTestCase() {

    private val sysDir = File(System.getProperty("fantom.sys.dir") ?: (FantomHome.detect()?.path + "/src/sys/fan"))

    fun testStrAndListFandoc() {
        if (!sysDir.isDirectory) {
            println("FandocCorpusTest: $sysDir missing, skipped")
            return
        }
        val provider = FantomDocumentationProvider()
        var docs = 0
        val tags = sortedMapOf<String, Int>()
        val bad = ArrayList<String>()
        for (name in listOf("Str.fan", "List.fan")) {
            val f = File(sysDir, name)
            if (!f.isFile) continue
            val psi = myFixture.configureByText(name, StringUtil.convertLineSeparators(f.readText()))
            for (decl in PsiTreeUtil.findChildrenOfType(psi, FantomNamedElement::class.java)) {
                if (!(FantomScope.isTypeDef(decl) || FantomScope.isSlot(decl))) continue
                val lines = FantomDocumentationProvider.fandocLines(decl)
                if (lines.isEmpty()) continue
                docs++
                val html = FandocRenderer.render(lines)
                if (!FandocHtml.balanced(html)) bad += "$name ${decl.name}: $html"
                Regex("<([a-z0-9]+)").findAll(html).forEach { tags.merge(it.groupValues[1], 1, Int::plus) }
                assertNotNull(provider.generateDoc(decl, null))
            }
        }
        println("FandocCorpusTest: docs=$docs tags=$tags bad=${bad.size}")
        assertTrue("expected fandoc in Str.fan and List.fan", docs > 50)
        assertTrue("expected <code> in rendered sys docs", (tags["code"] ?: 0) > 0)
        assertTrue("unbalanced:\n" + bad.take(10).joinToString("\n"), bad.isEmpty())
    }
}
