package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.fandoc.FandocRenderer
import junit.framework.TestCase

/** One case per fandoc construct, following `fandoc::FandocTest` where it applies. */
class FandocRendererTest : TestCase() {

    private fun html(vararg lines: String) = FandocRenderer.render(lines.toList())

    fun testEmpty() {
        assertEquals("", html())
        assertEquals("", html("", "  "))
    }

    fun testParagraphs() {
        assertEquals("<p>a b c</p><p>d</p>", html("a b", "c", "", "d"))
    }

    fun testEmphasis() {
        assertEquals("<p>alpha <em>foo</em> beta</p>", html("alpha *foo* beta"))
    }

    fun testStrong() {
        assertEquals("<p>alpha <strong>foo</strong> beta</p>", html("alpha **foo** beta"))
        assertEquals("<p><em>winter <strong>really</strong> sucks</em>!</p>", html("*winter **really** sucks*!"))
    }

    fun testStarsWithoutLeadingSpaceArePlainText() {
        assertEquals("<p>a*b and a * b</p>", html("a*b and a * b"))
    }

    fun testCodeSpans() {
        assertEquals("<p>Use <code>x &lt; y</code> here</p>", html("Use 'x < y' here"))
        assertEquals("<p>Brian's</p>", html("Brian's"))
        assertEquals("<p><code>*a*</code></p>", html("'*a*'"))
    }

    fun testBareBacktickLinksAreCode() {
        assertEquals("<p>See <code>sys::Str.size</code> and <code>#size</code>.</p>", html("See `sys::Str.size` and `#size`."))
    }

    fun testLinks() {
        assertEquals("<p>Check <a href=\"http://cool/?a=1&amp;b=2\">cool site</a>!</p>", html("Check [cool site]`http://cool/?a=1&b=2`!"))
        assertEquals("<p><a href=\"https://x/\">https://x/</a></p>", html("`https://x/`"))
        assertEquals("<p>see the size</p>", html("see [the size]`#size`"))
        assertEquals("<p>x [] y</p>", html("x [] y"))
    }

    fun testAnchorAndImage() {
        assertEquals("<p>some text</p>", html("[#xyz]some text"))
        assertEquals("<p>alpha x beta</p>", html("alpha ![x]`img.png` beta"))
    }

    fun testBulletLists() {
        assertEquals("<ul><li>a</li><li>b</li></ul>", html("- a", "- b"))
        assertEquals("<ul><li>a</li><li>b</li></ul>", html("* a", "* b"))
        assertEquals("<ul><li>a more</li></ul>", html("- a", "  more"))
        assertEquals("<ul><li>a<ul><li>b</li></ul></li><li>c</li></ul>", html("- a", "  - b", "- c"))
        assertEquals("<p>intro</p><ul><li><code>x</code></li></ul>", html("intro", "", "- 'x'"))
    }

    fun testNumberedLists() {
        assertEquals("<ol><li>one</li><li>two</li></ol>", html("1. one", "2. two"))
        assertEquals("<ol type=\"a\"><li>x</li><li>y</li></ol>", html("a. x", "b. y"))
        assertEquals("<ol type=\"I\"><li>x</li><li>y</li></ol>", html("I. x", "II. y"))
    }

    fun testIndentedPre() {
        assertEquals("<p>Code:</p><pre>a+b\n  c &lt; d</pre>", html("Code:", "  a+b", "    c < d"))
        assertEquals("<p>a</p><pre>foo\n\nbar</pre><p>b</p>", html("a", "", "  foo", "", "  bar", "", "b"))
    }

    fun testExplicitPre() {
        assertEquals("<pre> x\ny\n</pre>", html("pre>", "  x", " y", "<pre"))
        assertEquals("<pre>- a\n\n- b\n</pre>", html("pre>", "- a", "", "- b", "<pre"))
    }

    fun testHeadings() {
        assertEquals(
            "<h3>Title</h3><h4>Sub</h4><p>text</p><h5>Three</h5><h6>Four</h6>",
            html("Title", "#####", "Sub", "****", "text", "", "Three", "====", "Four", "----")
        )
        // An underline after a blank line is not a heading.
        assertEquals("<p>a</p><p>#### b</p>", html("a", "", "####", "b"))
    }

    fun testRule() {
        assertEquals("<p>Foo</p><hr><p>Bar</p>", html("Foo", "", "---", "Bar"))
    }

    fun testBlockQuote() {
        assertEquals("<blockquote><p>a b</p><p>c</p></blockquote>", html("> a", "> b", "", "> c"))
        assertEquals("<blockquote><p>a b c</p></blockquote><p>p2</p>", html("> a", "b c", "", "p2"))
    }

    fun testAdmonition() {
        assertEquals("<p><b>NOTE:</b> be careful</p>", html("NOTE: be careful"))
        assertEquals("<p>Note: lower case</p>", html("Note: lower case"))
    }

    fun testEscaping() {
        assertEquals("<p>a &lt; b &amp; c &gt; d \"q\"</p>", html("a < b & c > d \"q\""))
        assertEquals("<p><code>&lt;script&gt;</code> <code>List&lt;Str&gt;</code></p>", html("'<script>' `List<Str>`"))
        assertEquals("<pre>&lt;b&gt;</pre>", html("  <b>"))
    }

    fun testUnclosedInlineFallsBackToText() {
        assertEquals("<p>Unclosed 'code here</p>", html("Unclosed 'code here"))
        assertEquals("<p>gamma * in *.java, delta.</p>", html("gamma * in *.java,", "delta."))
    }

    fun testBalancedOnOddInput() {
        for (doc in listOf("<pre", "---", "####", "- ", "> ", "1. ", "**", "***", "pre>", "- > x", "a\n----\nb")) {
            val out = FandocRenderer.render(doc)
            assertTrue("$doc -> $out", FandocHtml.balanced(out))
        }
    }
}

/** Test helper: checks that every open tag in rendered fandoc is closed in order. */
object FandocHtml {
    private val tag = Regex("<(/?)([a-zA-Z0-9]+)[^>]*>")
    private val void = setOf("br", "hr", "img")

    fun balanced(html: String): Boolean {
        val stack = ArrayList<String>()
        for (m in tag.findAll(html)) {
            val name = m.groupValues[2].lowercase()
            if (name in void) continue
            if (m.groupValues[1].isEmpty()) stack += name
            else if (stack.isEmpty() || stack.removeAt(stack.size - 1) != name) return false
        }
        return stack.isEmpty()
    }
}
