package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.hints.FantomParameterHintsProvider
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.codeInsight.hints.InlayGroup
import com.intellij.codeInsight.hints.declarative.InlayHintsProviderExtensionBean
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.testFramework.utils.inlays.declarative.DeclarativeInlayHintsProviderTestCase
import com.intellij.util.indexing.FileBasedIndex
import java.io.File

/** Parameter name inlay hints before call arguments (`addLine(sku: "A-1", qty: 2)`). */
class FantomParameterHintsTest : DeclarativeInlayHintsProviderTestCase() {

    private var home: File? = null

    override fun tearDown() {
        try {
            if (home != null) FantomSettings.getInstance(project).homePath = null
        } finally {
            super.tearDown()
        }
    }

    private val order = """
        class Order {
          Void addLine(Str sku, Int qty, Float price) {}
          Void set(Str key, Obj val) {}
          Void one(Str name) {}
          new make(Str sku, Int qty := 1) {}
          Str sku := ""
        }
    """.trimIndent()

    private fun doTest(body: String) {
        doTestProvider(
            "A.fan",
            "$order\nclass A {\nStr sku := \"\"; Int qty := 0; Float price := 0f; Order o := Order()\nVoid f(Str[] names) {\n$body\n} }",
            FantomParameterHintsProvider(),
        )
    }

    fun testLiteralsGetNames() = doTest("o.addLine(/*<# sku: #>*/\"A-1\", /*<# qty: #>*/2, /*<# price: #>*/9.5f)")

    fun testCallsAndArithmeticGetNames() =
        doTest("o.addLine(/*<# sku: #>*/sku.upper, /*<# qty: #>*/qty + 1, /*<# price: #>*/price * 2f)")

    fun testArgumentNamedLikeParamHasNoHint() = doTest("o.addLine(sku, qty, price)\no.addLine(o.sku, qty, price)")

    fun testMixedArgumentsHintOnlyTheUnnamed() = doTest("o.addLine(sku, /*<# qty: #>*/2, price)")

    fun testOneParameterHasNoHint() = doTest("o.one(\"x\")\necho(sku)")

    fun testOperatorLikeSlotHasNoHint() = doTest("o.set(\"k\", 1)")

    fun testClosureArgumentHasNoHint() = doTest("names.each |n| {}\nnames.each(|n| {})")

    fun testCtorCallGetsNames() = doTest("Order(/*<# sku: #>*/\"A-1\", /*<# qty: #>*/3)\nOrder(/*<# sku: #>*/\"A-1\")")

    fun testUnresolvedCallHasNoHint() = doTest("unknown(\"a\", 1)\no.unknown(\"a\", 1)")

    fun testLibraryCallGetsNames() {
        if (!enableLibrary()) return
        doTestProvider(
            "A.fan",
            "class A { Void f(Str s) {\ns.replace(/*<# from: #>*/\"a\", /*<# to: #>*/\"b\")\ns.padl(/*<# width: #>*/5)\n} }",
            FantomParameterHintsProvider(),
        )
    }

    fun testRegisteredWithBundleAndGroup() {
        val ep = ExtensionPointName.create<InlayHintsProviderExtensionBean>("com.intellij.codeInsight.declarativeInlayProvider")
        val bean = ep.extensionList.first { it.providerId == "fantom.parameters" }
        assertEquals("fantom_lang", bean.language)
        assertEquals(InlayGroup.TYPES_GROUP, bean.requiredGroup())
        assertTrue(bean.isEnabledByDefault)
        assertEquals("Parameter names", bean.getProviderName())
        assertTrue(bean.getDescription().orEmpty().contains("parameter name"))
        assertTrue(bean.instance is FantomParameterHintsProvider)
        // Both Fantom providers sit under the same settings node.
        val types = ep.extensionList.first { it.providerId == "fantom.types" }
        assertEquals(types.requiredGroup(), bean.requiredGroup())
    }

    /** Enables the Fantom home as a library; false (test skipped) when none is installed. */
    private fun enableLibrary(): Boolean {
        val h = FantomHome.detect() ?: run {
            println("FantomParameterHintsTest: skipped, no Fantom home found")
            return false
        }
        home = h
        FantomSettings.getInstance(project).homePath = h.path
        WriteAction.runAndWait<RuntimeException> {
            ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(EmptyRunnable.getInstance(), false, true)
        }
        FileBasedIndex.getInstance().ensureUpToDate(FantomDeclarationIndex.NAME, project, null)
        return true
    }
}
