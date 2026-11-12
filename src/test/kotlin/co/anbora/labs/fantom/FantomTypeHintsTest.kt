package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.hints.FantomTypeHintsProvider
import com.intellij.codeInsight.hints.InlayGroup
import com.intellij.codeInsight.hints.declarative.InlayHintsProviderExtensionBean
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.testFramework.utils.inlays.declarative.DeclarativeInlayHintsProviderTestCase

/** Inferred-type inlay hints after `x := ...` locals. */
class FantomTypeHintsTest : DeclarativeInlayHintsProviderTestCase() {

    private fun doTest(body: String) {
        doTestProvider(
            "A.fan",
            "class B { B next() { this } }\nclass A { Int count := 0; Str title() { \"t\" }\nVoid f() {\n$body\n} }",
            FantomTypeHintsProvider(),
        )
    }

    fun testLocalFromFieldShowsType() = doTest("y/*<# : Int #>*/ := count")

    fun testLiteralInitializerHidesHint() = doTest("s := \"x\"\ni := 1\nf := true\nl := [,]")

    fun testCtorCallHidesHint() = doTest("b := B()\nc := B { }")

    fun testCallChainShowsType() = doTest("b := B()\nn/*<# : B #>*/ := b.next()\nt/*<# : Str #>*/ := title()")

    fun testWrittenTypeHasNoHint() = doTest("Int y := count")

    fun testUnknownTypeHasNoHint() = doTest("z := unknownThing()")

    fun testRegisteredWithBundleAndGroup() {
        val ep = ExtensionPointName.create<InlayHintsProviderExtensionBean>("com.intellij.codeInsight.declarativeInlayProvider")
        val bean = ep.extensionList.first { it.providerId == "fantom.types" }
        assertEquals("fantom_lang", bean.language)
        assertEquals(InlayGroup.TYPES_GROUP, bean.requiredGroup())
        assertTrue(bean.isEnabledByDefault)
        assertEquals("Inferred types", bean.getProviderName())
        assertTrue(bean.getDescription().orEmpty().contains("inferred type"))
        assertTrue(bean.instance is FantomTypeHintsProvider)
    }
}
