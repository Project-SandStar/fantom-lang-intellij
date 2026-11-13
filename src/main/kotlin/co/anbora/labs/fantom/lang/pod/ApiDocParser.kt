package co.anbora.labs.fantom.lang.pod

/**
 * Reads the `doc/<Type>.apidoc` files that the Fantom compiler writes into a `.pod`
 * (see `compiler::ApiDocWriter` and `compilerDoc::ApiDocParser`).
 *
 * The format:
 * ```
 * == Etc                       type header
 * base=sys::Obj                meta lines name=value
 * flags=const public
 * @sys::Js                     facets, one per line; with values: @qname{ ... }
 *                              blank line, then fandoc up to the next "-- "
 * Etc is the utility ...
 *
 * -- emptyDict(                method: one param per line, then ") returnType"
 * ) haystack::Dict
 * flags=const public static
 *
 * Get the empty Dict.
 *
 * -- emptyTags [sys::Str:sys::Obj?]:=[Str:Obj?][:]     field: name type[:=expr]
 * ```
 * A fandoc line that starts with `-- ` is written as `--- `; the reader drops one `-`.
 * Pure Kotlin, no IDE classes.
 */
object ApiDocParser {

    data class Facet(val qname: String, val values: List<Pair<String, String>> = emptyList())

    data class Param(val name: String, val type: String, val default: String?)

    enum class SlotKind { FIELD, METHOD, CTOR }

    data class Slot(
        val name: String,
        val kind: SlotKind,
        val flags: Set<String>,
        /** Field type, or method return type; qualified (`sys::Str?`). */
        val type: String,
        /** Field initializer, verbatim; null when none. `...` means "not written". */
        val init: String?,
        val params: List<Param>,
        /** Setter protection of a field (`private`), or null. */
        val setter: String?,
        val facets: List<Facet>,
        val doc: String?,
        val line: Int?,
    )

    data class TypeDoc(
        val name: String,
        val flags: Set<String>,
        /** Direct base first, then its bases; empty for mixins. */
        val base: List<String>,
        val mixins: List<String>,
        val facets: List<Facet>,
        val doc: String?,
        val file: String?,
        val line: Int?,
        val slots: List<Slot>,
    ) {
        val isMixin get() = "mixin" in flags
        val isEnum get() = "enum" in flags
        val isFacet get() = "facet" in flags
    }

    /** Parses the text of one `.apidoc` file. Throws [IllegalArgumentException] on a bad header. */
    fun parse(text: String): TypeDoc {
        val r = Reader(text.replace("\r\n", "\n").split('\n'))
        val header = r.next() ?: throw IllegalArgumentException("empty apidoc")
        require(header.startsWith("== ")) { "expected '== <Type>', got: $header" }
        val name = header.removePrefix("== ").trim()
        val meta = r.readMeta()
        val facets = r.readFacets()
        val doc = r.readDoc()
        val slots = ArrayList<Slot>()
        while (true) {
            val line = r.peek() ?: break
            if (!line.startsWith("-- ")) { r.next(); continue }
            slots += r.readSlot()
        }
        val loc = meta["loc"]
        val file = loc?.substringBefore(':')?.takeIf { it.isNotEmpty() }
        return TypeDoc(
            name = name,
            flags = flagsOf(meta["flags"]),
            base = words(meta["base"]),
            mixins = words(meta["mixins"]),
            facets = facets,
            doc = doc,
            file = file,
            line = lineOf(loc),
            slots = slots,
        )
    }

    private fun words(s: String?): List<String> = s?.split(' ')?.filter { it.isNotEmpty() } ?: emptyList()

    private fun flagsOf(s: String?): Set<String> = words(s).toCollection(LinkedHashSet())

    /** `File.fan:15/12` or `:25/23` to 15 or 25. */
    private fun lineOf(loc: String?): Int? =
        loc?.substringAfter(':', "")?.substringBefore('/')?.toIntOrNull()

    /** Splits `name type[:=expr]` into name, type and the expression (null when none). */
    private fun splitTyped(s: String): Triple<String, String, String?> {
        val sp = s.indexOf(' ')
        val name = if (sp < 0) s else s.substring(0, sp)
        val rest = if (sp < 0) "" else s.substring(sp + 1)
        val eq = rest.indexOf(":=")
        return if (eq < 0) Triple(name, rest.trim(), null)
        else Triple(name, rest.substring(0, eq).trim(), rest.substring(eq + 2))
    }

    private class Reader(val lines: List<String>) {
        var pos = 0

        fun peek(): String? = if (pos < lines.size) lines[pos] else null
        fun next(): String? = if (pos < lines.size) lines[pos++] else null

        /** `name=value` lines up to a facet, a blank line or a slot. */
        fun readMeta(): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            while (true) {
                val l = peek() ?: break
                if (l.isEmpty() || l.startsWith("@") || l.startsWith("-- ")) break
                val eq = l.indexOf('=')
                if (eq <= 0) break
                out[l.substring(0, eq)] = l.substring(eq + 1)
                pos++
            }
            return out
        }

        fun readFacets(): List<Facet> {
            val out = ArrayList<Facet>()
            while (true) {
                val l = peek() ?: break
                if (!l.startsWith("@")) break
                pos++
                if (l.endsWith("{")) {
                    val values = ArrayList<Pair<String, String>>()
                    while (true) {
                        val v = next() ?: break
                        if (v == "}") break
                        val eq = v.indexOf('=')
                        if (eq > 0) values += v.substring(0, eq) to v.substring(eq + 1)
                    }
                    out += Facet(l.substring(1, l.length - 1), values)
                } else {
                    out += Facet(l.substring(1))
                }
            }
            return out
        }

        /** A blank line, then fandoc up to the next `-- ` line. Null when there is none. */
        fun readDoc(): String? {
            if (peek()?.isEmpty() != true) return null
            pos++
            val buf = ArrayList<String>()
            while (true) {
                val l = peek() ?: break
                if (l.startsWith("-- ")) break
                pos++
                buf += if (l.startsWith("--- ")) l.substring(1) else l
            }
            while (buf.isNotEmpty() && buf.last().isBlank()) buf.removeAt(buf.size - 1)
            while (buf.isNotEmpty() && buf.first().isBlank()) buf.removeAt(0)
            return if (buf.isEmpty()) null else buf.joinToString("\n")
        }

        fun readSlot(): Slot {
            val head = next()!!.removePrefix("-- ")
            val params = ArrayList<Param>()
            val isMethod = head.endsWith("(")
            val name: String
            var type: String
            var init: String? = null
            if (isMethod) {
                name = head.substring(0, head.length - 1)
                type = "sys::Void"
                while (true) {
                    val l = next() ?: break
                    if (l.startsWith(")")) { type = l.substring(1).trim(); break }
                    val (pn, pt, pd) = splitTyped(l)
                    params += Param(pn, pt, pd)
                }
            } else {
                val t = splitTyped(head)
                name = t.first; type = t.second; init = t.third
            }
            val meta = readMeta()
            val facets = readFacets()
            val doc = readDoc()
            val flags = flagsOf(meta["flags"])
            val kind = when {
                !isMethod -> SlotKind.FIELD
                "new" in flags -> SlotKind.CTOR
                else -> SlotKind.METHOD
            }
            // A method's const flag is a compiler artefact; drop it.
            val cleaned = if (isMethod) flags - "const" else flags
            return Slot(name, kind, cleaned, type, init, params, meta["set"], facets, doc, lineOf(meta["loc"]))
        }
    }
}
