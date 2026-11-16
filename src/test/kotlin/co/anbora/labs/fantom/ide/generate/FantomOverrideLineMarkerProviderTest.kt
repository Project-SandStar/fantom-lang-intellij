package co.anbora.labs.fantom.ide.generate

import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.resolve.FantomScope
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * [FantomOverrideLineMarkerProvider] called on each leaf: which name tokens get an
 * inheritance icon, the tooltip, and the slots the icon jumps to.
 */
class FantomOverrideLineMarkerProviderTest : BasePlatformTestCase() {

    private val base = """
        mixin Shape
        {
          abstract Float area()
          virtual Str name() { "s" }
          abstract Int sides
          Str plain() { "p" }
        }
    """.trimIndent() + "\n"

    /** "token text -> marker" for every leaf that gets one. */
    private fun markers(text: String): List<Pair<PsiElement, LineMarkerInfo<*>>> {
        myFixture.configureByText("A.fan", text)
        val provider = FantomOverrideLineMarkerProvider()
        return PsiTreeUtil.collectElements(myFixture.file) { it.firstChild == null }
            .mapNotNull { leaf -> provider.getLineMarkerInfo(leaf)?.let { leaf to it } }
    }

    private fun lineOf(leaf: PsiElement): Int = myFixture.editor.document.getLineNumber(leaf.textRange.startOffset)

    private fun label(e: PsiElement): String = "${FantomScope.enclosingType(e)?.name}.${(e as FantomNamedElement).name}"

    private fun targets(info: LineMarkerInfo<*>): Set<String> =
        (info as RelatedItemLineMarkerInfo<*>).createGotoRelatedItems().map { label(it.element!!) }.toSet()

    fun testOverridingIconOnMethodAndField() {
        val found = markers(base + "class Sq : Shape\n{\n  override Float area() { 1f }\n  override Int sides := 4\n}\n")
        val overriding = found.filter { it.second.icon == AllIcons.Gutter.OverridingMethod }
        assertEquals(listOf("area", "sides"), overriding.map { it.first.text })
        assertEquals(listOf("Overrides Shape.area", "Overrides Shape.sides"), overriding.map { it.second.lineMarkerTooltip })
        assertEquals(setOf("Shape.area"), targets(overriding[0].second))
        // The icon sits on the name token of the overriding slot.
        assertTrue(overriding.all { lineOf(it.first) > 6 })
    }

    fun testOverriddenIconCountsTypes() {
        val found = markers(
            base + "class Sq : Shape { override Float area() { 1f } override Int sides := 4 }\n" +
                "class Tri : Shape { override Float area() { 2f } override Int sides := 3 }\n",
        )
        val overridden = found.filter { it.second.icon == AllIcons.Gutter.OverridenMethod }
        val byName = overridden.associate { it.first.text to it.second }
        assertEquals(setOf("area", "sides"), byName.keys)
        assertEquals("Overridden in 2 types", byName["area"]!!.lineMarkerTooltip)
        assertEquals(setOf("Sq.area", "Tri.area"), targets(byName["area"]!!))
    }

    /** A virtual slot nobody overrides, and a plain slot, get no icon. */
    fun testNoIconWithoutOverride() {
        val found = markers(base + "class Sq : Shape { override Float area() { 1f } override Int sides := 4 }\n")
        val names = found.map { it.first.text }
        assertFalse(names.toString(), "name" in names)
        assertFalse(names.toString(), "plain" in names)
    }

    /** `override` with no base slot on the type chain gets no icon. */
    fun testOverrideWithoutBase() {
        assertEmpty(markers("class Lone\n{\n  override Str missing() { \"x\" }\n}\n"))
    }

    /** Only name tokens: the `override` keyword, the type and the body give nothing. */
    fun testOnlyNameTokens() {
        val found = markers(base + "class Sq : Shape { override Float area() { 1f } }\n")
        assertTrue(found.all { it.first.text == "area" })
    }
}
