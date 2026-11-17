package co.anbora.labs.fantom.lang.axon.copy

/**
 * A small JSON reader and writer for the Axon project copy: the replies of the Axon
 * server, `.sync-metadata.json` of a local `proj` folder, and the marker and index
 * files of the cache. Objects are [Map]s (in file order), arrays are [List]s, numbers
 * are [Double]s, and `null` is null.
 *
 * It is here so that the plugin does not depend on a JSON library of the platform:
 * those are internal modules of the IDE.
 */
object AxonJson {

    /** Thrown for text that is not JSON, or that nests deeper than [MAX_DEPTH]. */
    class Malformed(message: String) : Exception(message)

    private const val MAX_DEPTH = 64

    /** The value of [text]. Throws [Malformed]. */
    fun parse(text: CharSequence): Any? {
        val r = Reader(text)
        r.ws()
        val v = r.value(0)
        r.ws()
        if (r.pos != text.length) r.fail("text after the value")
        return v
    }

    /** As [parse], for a reply that must be an object. */
    fun parseObject(text: CharSequence): Map<String, Any?> =
        obj(parse(text)) ?: throw Malformed("not a JSON object")

    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

    fun list(v: Any?): List<Any?>? = v as? List<Any?>

    fun str(v: Any?): String? = v as? String

    /** [value] as JSON text. Maps, lists, strings, numbers, booleans and null. */
    fun write(value: Any?, indent: Boolean = true): String = StringBuilder().also { put(it, value, if (indent) 0 else -1) }.toString()

    private fun put(sb: StringBuilder, v: Any?, level: Int) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v)
            is Number -> {
                val d = v.toDouble()
                if (d.isNaN() || d.isInfinite()) sb.append("null")
                else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append(d.toLong())
                else sb.append(d)
            }
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, level + 1)
                    quote(sb, k.toString())
                    sb.append(if (level >= 0) ": " else ":")
                    put(sb, x, if (level >= 0) level + 1 else -1)
                }
                newline(sb, level)
                sb.append('}')
            }
            is Iterable<*> -> {
                val items = v.toList()
                if (items.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                items.forEachIndexed { i, x ->
                    if (i > 0) sb.append(',')
                    newline(sb, level + 1)
                    put(sb, x, if (level >= 0) level + 1 else -1)
                }
                newline(sb, level)
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun newline(sb: StringBuilder, level: Int) {
        if (level < 0) return
        sb.append('\n')
        repeat(level) { sb.append("  ") }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ' || c == ' ' || c == ' ') sb.append("\\u").append(String.format("%04x", c.code)) else sb.append(c)
        }
        sb.append('"')
    }

    private class Reader(val s: CharSequence) {
        var pos = 0

        fun fail(what: String): Nothing = throw Malformed("bad JSON at offset $pos: $what")

        fun ws() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("nested too deep")
            if (pos >= s.length) fail("end of text")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else fail("unexpected '$c'")
            }
        }

        private fun word(w: String, v: Any?): Any? {
            if (pos + w.length > s.length || s.subSequence(pos, pos + w.length).toString() != w) fail("unknown word")
            pos += w.length
            return v
        }

        private fun number(): Double {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos] in '0'..'9' || s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E' || s[pos] == '+' || s[pos] == '-')) pos++
            return s.subSequence(start, pos).toString().toDoubleOrNull() ?: fail("bad number")
        }

        private fun string(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("string without an end")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= s.length) fail("string without an end")
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("bad \\u escape")
                                val code = s.subSequence(pos, pos + 4).toString().toIntOrNull(16) ?: fail("bad \\u escape")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("bad escape")
                        }
                    }
                    c < ' ' -> fail("control character in a string")
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            pos++
            val out = ArrayList<Any?>()
            ws()
            if (pos < s.length && s[pos] == ']') { pos++; return out }
            while (true) {
                ws()
                out += value(depth + 1)
                ws()
                if (pos >= s.length) fail("array without an end")
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            pos++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (pos < s.length && s[pos] == '}') { pos++; return out }
            while (true) {
                ws()
                if (pos >= s.length || s[pos] != '"') fail("expected a name")
                val name = string()
                ws()
                if (pos >= s.length || s[pos++] != ':') fail("expected ':'")
                ws()
                out[name] = value(depth + 1)
                ws()
                if (pos >= s.length) fail("object without an end")
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> fail("expected ',' or '}'")
                }
            }
        }
    }
}
