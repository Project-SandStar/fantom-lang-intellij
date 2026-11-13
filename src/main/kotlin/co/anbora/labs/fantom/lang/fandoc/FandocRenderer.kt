package co.anbora.labs.fantom.lang.fandoc

/**
 * Fandoc to HTML for Quick Documentation. Pure Kotlin, no PSI.
 *
 * A port of the block rules of `fandoc::FandocParser` and the inline rules of
 * `fandoc::InlineParser` (Fantom 1.0.84). Where fandoc differs from Markdown,
 * fandoc wins:
 *
 * - `'x'` is code; a bare backtick `` `x` `` is a link (to a type, a slot, a doc
 *   or a URI). Links that are not URIs render as `<code>`.
 * - `[text]`uri`` is a link with a label. `[#id]` is an anchor and is dropped.
 * - `*em*`, `**strong**`, `'code'`, links and `[` only start after a space,
 *   `*`, `/` or `(` (or at the start of the text): `a*b` is plain text.
 * - A heading is a line followed by a line that starts with `###` (level 1),
 *   `***` (2), `===` (3) or `---` (4). `---` after a blank line is a rule.
 * - Bullets are `- ` (fandoc); `* ` is accepted too. Ordered items are
 *   `1. `, `a. `, `A. `, `i. `, `I. ` (digits, one letter, or roman numerals).
 * - A line indented two or more spaces past the block indent is preformatted;
 *   `pre>` ... `<pre` is preformatted with the common indent removed.
 * - `NOTE: text` (an all-capitals word and a colon) starts an admonition.
 * - Consecutive block quotes merge into one.
 * - When the inline parse of a block fails (an unclosed `'`, `` ` `` or `*`),
 *   the block is shown as plain text, as fandoc does. When the block parse
 *   fails, the whole comment is shown as `<pre>`.
 */
object FandocRenderer {

    /** Renders [lines] (comment lines with the `**` prefix removed) as HTML. */
    fun render(lines: List<String>): String {
        if (lines.all { it.isBlank() }) return ""
        val root = Elem("")
        try {
            BlockParser(lines).parse(root)
        } catch (e: RuntimeException) {
            root.kids.clear()
            root.add(Elem("pre").add(Text(lines.joinToString("\n"))))
        }
        val sb = StringBuilder()
        root.kids.forEach { write(it, sb) }
        return sb.toString()
    }

    /** Renders one fandoc string; lines are split on `\n`. */
    fun render(text: String): String = render(text.lines())

    // ---------------------------------------------------------------- model

    internal sealed class Node

    internal class Text(var str: String) : Node()

    internal open class Elem(val tag: String, val attr: String? = null) : Node() {
        val kids = ArrayList<Node>()

        fun add(node: Node): Elem {
            val last = kids.lastOrNull()
            if (node is Text && last is Text) { last.str += node.str; return this }
            if (node is Elem && node.tag == "blockquote" && last is Elem && last.tag == "blockquote") {
                last.kids += node.kids
                return this
            }
            kids += node
            return this
        }
    }

    internal class Link(val uri: String) : Elem("a")

    internal class Image(val alt: String) : Elem("img")

    // ---------------------------------------------------------------- html

    private fun write(node: Node, sb: StringBuilder) {
        when (node) {
            is Text -> sb.append(escape(node.str))
            is Image -> sb.append(escape(node.alt))
            is Link -> writeLink(node, sb)
            is Elem -> {
                sb.append('<').append(node.tag)
                if (node.attr != null) sb.append(' ').append(node.attr)
                sb.append('>')
                node.kids.forEach { write(it, sb) }
                sb.append("</").append(node.tag).append('>')
            }
        }
    }

    private fun writeLink(link: Link, sb: StringBuilder) {
        val external = isExternal(link.uri)
        val bare = link.kids.size == 1 && (link.kids[0] as? Text)?.str == link.uri
        if (external) {
            sb.append("<a href=\"").append(escapeAttr(link.uri)).append("\">")
            link.kids.forEach { write(it, sb) }
            sb.append("</a>")
        } else if (bare) {
            // `sys::Str.size`, `#size`, `Str`: a type or slot reference, shown as code.
            sb.append("<code>").append(escape(link.uri)).append("</code>")
        } else {
            link.kids.forEach { write(it, sb) }
        }
    }

    private fun isExternal(uri: String): Boolean =
        uri.startsWith("http://") || uri.startsWith("https://") || uri.startsWith("mailto:") || uri.startsWith("ftp://")

    fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun escapeAttr(s: String): String = escape(s).replace("\"", "&quot;")

    // ---------------------------------------------------------------- blocks

    private enum class LineType { NONE, EOF, BLANK, PRE_START, PRE_END, H1, H2, H3, H4, HR, NORMAL, UL, UL_STAR, OL, BLOCKQUOTE }

    private class FandocError(msg: String) : RuntimeException(msg)

    private class BlockParser(private val lines: List<String>) {
        private var lineIndex = 0
        private var cur: String = ""
        private var curt = LineType.NONE
        private var curIndent = 0
        private var curStart = 0
        private var hasCur = false
        private var peek: String? = null
        private var peekt = LineType.NONE
        private var peekIndent = 0
        private var peekStart = 0

        init {
            consume()
            consume()
        }

        fun parse(root: Elem) {
            skipBlankLines()
            while (curt != LineType.EOF) root.add(topBlock())
        }

        private fun topBlock(): Node = when (peekt) {
            LineType.H1, LineType.H2, LineType.H3, LineType.H4 -> heading()
            else -> block(0)
        }

        private fun heading(): Node {
            val level = peekt.ordinal - LineType.H1.ordinal + 1
            // Quick Documentation already has a title; start fandoc level 1 at <h3>.
            val h = Elem("h" + minOf(level + 2, 6))
            curStart = 0
            formattedText(h)
            consume()
            skipBlankLines()
            (h.kids.firstOrNull() as? Text)?.let { it.str = it.str.trim() }
            return h
        }

        private fun block(indent: Int): Node = when (curt) {
            LineType.OL -> ol()
            LineType.UL, LineType.UL_STAR -> ul()
            LineType.BLOCKQUOTE -> Elem("blockquote").add(formattedText(Elem("p")))
            LineType.PRE_START -> preExplicit()
            LineType.HR -> { consume(); skipBlankLines(); Elem("hr") }
            LineType.NORMAL -> if (curIndent >= indent + 2) pre() else para()
            LineType.EOF, LineType.NONE, LineType.BLANK -> throw FandocError("unexpected $curt")
            // A stray heading underline or `<pre`: fandoc fails the whole doc; show it as text.
            else -> formattedText(Elem("p"))
        }

        private fun para(): Node {
            val p = Elem("p")
            val first = cur.trim().split(Regex("\\s+")).first()
            if (first.length > 1 && first.endsWith(":")) {
                val word = first.dropLast(1)
                if (word.all { it in 'A'..'Z' }) {
                    p.add(Elem("b").add(Text("$word:")))
                    p.add(Text(" "))
                    curStart = cur.indexOf(':') + 1
                }
            }
            return formattedText(p)
        }

        private fun preExplicit(): Node {
            consume()
            while (curt == LineType.BLANK) consume()
            val body = ArrayList<String>()
            var indent = Int.MAX_VALUE
            while (curt != LineType.PRE_END && curt != LineType.EOF) {
                if (curt != LineType.BLANK) {
                    var i = 0
                    while (i < cur.length && cur[i] == ' ') i++
                    indent = minOf(indent, i)
                }
                body += cur
                consume()
            }
            if (curt != LineType.EOF) consume()
            while (curt == LineType.BLANK) consume()
            val buf = StringBuilder()
            for (line in body) {
                if (line.length > indent) buf.append(line.substring(indent))
                buf.append('\n')
            }
            return Elem("pre").add(Text(buf.toString()))
        }

        private fun pre(): Node {
            val indent = curIndent
            val buf = StringBuilder(cur.substring(indent))
            consume()
            while (true) {
                while (curt == LineType.NORMAL && curIndent >= indent) {
                    buf.append('\n').append(cur.substring(indent))
                    consume()
                }
                var blanks = 0
                while (curt == LineType.BLANK) { consume(); blanks++ }
                if (curt == LineType.NORMAL && curIndent >= indent) repeat(blanks) { buf.append('\n') }
                else break
            }
            return Elem("pre").add(Text(buf.toString()))
        }

        private fun ol(): Node {
            val c = cur.trim()[0]
            val type = when {
                c == 'I' -> "I"
                c == 'i' -> "i"
                c in 'A'..'Z' -> "A"
                c in 'a'..'z' -> "a"
                else -> null
            }
            return listItems(Elem("ol", type?.let { "type=\"$it\"" }), curt, curIndent)
        }

        private fun ul(): Node = listItems(Elem("ul"), curt, curIndent)

        private fun listItems(list: Elem, listType: LineType, listIndent: Int): Node {
            while (curt != LineType.EOF) {
                if (curt == listType && curIndent == listIndent) {
                    list.add(formattedText(Elem("li")))
                } else if (curIndent >= listIndent) {
                    (list.kids.last() as Elem).add(block(listIndent))
                } else {
                    break
                }
            }
            return list
        }

        private fun formattedText(elem: Elem): Elem {
            val startIndent = curStart
            val isBlockQuote = curt == LineType.BLOCKQUOTE
            val buf = StringBuilder(tail(cur, curStart).trim())
            consume()
            while (curStart <= startIndent &&
                (curt == LineType.NORMAL || (isBlockQuote && curt == LineType.BLOCKQUOTE))) {
                buf.append('\n').append(tail(cur, curStart).trim())
                consume()
            }
            skipBlankLines()
            val text = buf.toString().replace('\n', ' ')
            val before = elem.kids.size
            try {
                InlineParser(text).parse(elem)
            } catch (e: FandocError) {
                while (elem.kids.size > before) elem.kids.removeAt(elem.kids.size - 1)
                elem.add(Text(text))
            }
            return elem
        }

        private fun tail(s: String, from: Int): String = if (from >= s.length) "" else s.substring(from)

        private fun skipBlankLines() {
            while (curt == LineType.BLANK) consume()
        }

        private fun consume() {
            cur = peek ?: ""
            hasCur = peek != null
            curt = peekt
            curIndent = peekIndent
            curStart = peekStart
            // Fandoc counts "no line yet" as not blank; here the first line is never a heading underline.
            val curNotBlank = hasCur && curt != LineType.BLANK
            val p = if (lineIndex < lines.size) lines[lineIndex++] else null
            peek = p
            peekIndent = 0
            peekStart = 0
            peekt = when {
                p == null -> LineType.EOF
                p.isBlank() -> LineType.BLANK
                p.startsWith("pre>") -> LineType.PRE_START
                p.startsWith("<pre") -> LineType.PRE_END
                p.startsWith("###") && curNotBlank -> LineType.H1
                p.startsWith("***") && curNotBlank -> LineType.H2
                p.startsWith("===") && curNotBlank -> LineType.H3
                p.startsWith("---") && curNotBlank -> LineType.H4
                p.startsWith("---") && curt == LineType.BLANK -> LineType.HR
                else -> normalType(p)
            }
        }

        private fun normalType(p: String): LineType {
            var type = LineType.NORMAL
            while (p[peekIndent].isWhitespace()) peekIndent++
            if (peekIndent + 2 < p.length) {
                if (p[peekIndent] == '-' && p[peekIndent + 1].isWhitespace()) {
                    type = LineType.UL
                    peekIndent += 2
                    peekStart = peekIndent
                } else if (p[peekIndent] == '*' && p[peekIndent + 1] == ' ') {
                    type = LineType.UL_STAR
                    peekIndent += 2
                    peekStart = peekIndent
                }
                if (isOrderedListMark(p, peekIndent)) {
                    type = LineType.OL
                    peekIndent += 2
                    peekStart = p.indexOf('.') + 2
                } else if (p.getOrNull(peekIndent) == '>' && p.getOrNull(peekIndent + 1)?.isWhitespace() == true) {
                    type = LineType.BLOCKQUOTE
                    peekIndent += 2
                    peekStart = peekIndent
                } else {
                    peekStart = peekIndent
                }
            }
            return type
        }

        /** `digits. `, `letter. ` or `roman. ` at [i]. */
        private fun isOrderedListMark(line: String, i: Int): Boolean {
            val c = line.getOrNull(i) ?: return false
            if (!isAlphaNum(c)) return false
            val dot = line.indexOf(". ", i)
            if (dot < 0) return false
            val mark = line.substring(i, dot)
            return if (mark[0] in '0'..'9') mark.all { it in '0'..'9' }
            else mark.withIndex().all { (index, ch) -> ch in "IVXivx" || index == 0 }
        }

        private fun isAlphaNum(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
    }

    // ---------------------------------------------------------------- inline

    private class InlineParser(private val src: String) {
        private var pos = 0
        private var last = ' '.code
        private var cur = if (src.isNotEmpty()) src[0].code else -1
        private var peek = if (src.length > 1) src[1].code else -1
        private val stack = ArrayList<Elem>()

        fun parse(parent: Elem) {
            while (cur > 0) segment(parent)
        }

        private fun segment(parent: Elem) {
            stack += parent
            val child: Node? = if (isSpace(last) || last == '*'.code || last == '/'.code || last == '('.code) {
                when (cur) {
                    '\''.code -> code()
                    '`'.code -> link()
                    '['.code -> annotation()
                    '*'.code -> if (peek == '*'.code) strong() else emphasis()
                    '!'.code -> if (peek == '['.code) image() else text()
                    else -> text()
                }
            } else text()
            if (child != null) parent.add(child)
            stack.removeAt(stack.size - 1)
        }

        private fun isTextEnd(): Boolean = when (cur) {
            '\''.code, '`'.code, '['.code -> isSpace(last) || last == '('.code
            '!'.code -> peek == '['.code && isSpace(last)
            '*'.code -> when (stack.last().tag) {
                "strong" -> peek == '*'.code || isSpace(last)
                "em" -> true
                else -> isSpace(last)
            }
            else -> false
        }

        private fun text(): Text {
            val buf = StringBuilder()
            buf.append(cur.toChar())
            consume()
            while (cur > 0 && !isTextEnd()) {
                buf.append(cur.toChar())
                consume()
            }
            return Text(buf.toString())
        }

        private fun code(): Node {
            val buf = StringBuilder()
            consume()
            while (cur != '\''.code) {
                if (cur <= 0) throw FandocError("Invalid code")
                buf.append(cur.toChar())
                consume()
            }
            consume()
            return Elem("code").add(Text(buf.toString()))
        }

        private fun emphasis(): Node {
            if (peek <= 0 || isSpace(peek) && peekPeek() != '*'.code) return text()
            val em = Elem("em")
            consume()
            while (cur != '*'.code || peek == '*'.code) {
                if (cur <= 0) throw FandocError("Invalid *emphasis*")
                segment(em)
            }
            consume()
            return em
        }

        private fun strong(): Node {
            val strong = Elem("strong")
            consume()
            consume()
            while (cur != '*'.code || peek != '*'.code) {
                if (cur <= 0) throw FandocError("Invalid **strong**")
                segment(strong)
            }
            consume()
            consume()
            return strong
        }

        private fun link(): Node {
            val uri = uri()
            return Link(uri).add(Text(uri))
        }

        private fun annotation(): Node? {
            if (peek <= 0 || peek == ']'.code) return text()
            val body: Node
            if (peek == '!'.code && peekPeek() == '['.code) {
                consume() // [
                body = image()
                if (cur != ']'.code) throw FandocError("Invalid img link")
                consume() // ]
            } else {
                val s = brackets()
                if (s.startsWith("#")) return null // anchor id
                body = Text(s)
            }
            if (cur == '`'.code) return Link(uri()).add(body)
            throw FandocError("Invalid annotation []")
        }

        private fun image(): Node {
            consume() // !
            val alt = brackets()
            if (cur == '['.code) brackets() // size
            uri()
            return Image(alt)
        }

        private fun uri(): String {
            if (cur != '`'.code) throw FandocError("Invalid uri")
            consume()
            val buf = StringBuilder()
            while (cur != '`'.code) {
                if (cur <= 0) throw FandocError("Invalid uri")
                buf.append(cur.toChar())
                consume()
            }
            consume()
            return buf.toString()
        }

        private fun brackets(): String {
            if (cur != '['.code) throw FandocError("Invalid []")
            consume()
            val buf = StringBuilder()
            while (cur != ']'.code) {
                if (cur <= 0) throw FandocError("Invalid []")
                buf.append(cur.toChar())
                consume()
            }
            consume()
            return buf.toString()
        }

        private fun consume() {
            last = cur
            cur = peek
            pos++
            peek = if (pos + 1 < src.length) src[pos + 1].code else -1
        }

        private fun peekPeek(): Int = if (pos + 2 < src.length) src[pos + 2].code else -1

        private fun isSpace(c: Int) = c == ' '.code || c == '\t'.code || c == '\n'.code || c == '\r'.code
    }
}
