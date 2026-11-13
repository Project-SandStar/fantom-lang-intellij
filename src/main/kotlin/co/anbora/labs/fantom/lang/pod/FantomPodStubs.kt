package co.anbora.labs.fantom.lang.pod

import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.pod.ApiDocParser.SlotKind
import co.anbora.labs.fantom.lang.pod.ApiDocParser.TypeDoc
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

/**
 * Turns a compiled `.pod` (no sources) into read-only `.fan` stubs, one per public type,
 * built from the `doc/<Type>.apidoc` files inside the pod. The stubs are then indexed like
 * any pod source, so `haystack::Etc` resolves, completes and shows its fandoc.
 *
 * Layout: `<base>/<pod>-<version>-<hash of the pod path>/<pod>/fan/<Type>.fan`. The path
 * ends in `<pod>/fan` because `FantomPodResolver.podNameFor` takes the pod name from the
 * parent of `fan`. A marker file (written last) holds the pod's size and mtime; a stub
 * folder without a matching marker counts as not generated.
 */
object FantomPodStubs {

    private val LOG = logger<FantomPodStubs>()

    const val MARKER = ".fantom-pod-stub"

    /** Default folder for the stubs: `<IDE system>/fantom-pod-stubs`. */
    fun defaultBase(): File = File(PathManager.getSystemPath(), "fantom-pod-stubs")

    /** The `.pod` files of [home] that may need stubs: every `lib/fan/<pod>.pod`, sorted. */
    fun podFiles(home: File): List<File> =
        (File(home, "lib/fan").listFiles { f -> f.isFile && f.name.endsWith(".pod") } ?: emptyArray())
            .sortedBy { it.name }

    fun podName(pod: File): String = pod.name.removeSuffix(".pod")

    private fun hash(pod: File): String {
        val md = MessageDigest.getInstance("SHA-1").digest(pod.absolutePath.toByteArray())
        return md.take(5).joinToString("") { "%02x".format(it) }
    }

    private fun markerText(pod: File) = "size=${pod.length()}\nmtime=${pod.lastModified()}\n"

    private fun candidates(pod: File, base: File): List<File> {
        val name = podName(pod)
        val h = hash(pod)
        return (base.listFiles { f -> f.isDirectory } ?: emptyArray())
            .filter { it.name.startsWith("$name-") && it.name.endsWith("-$h") }
    }

    /** The up to date stub folder of [pod] (the one holding `<pod>/fan`), or null. */
    fun existingStub(pod: File, base: File = defaultBase()): File? = candidates(pod, base).firstOrNull {
        val m = File(it, MARKER)
        m.isFile && runCatching { m.readText() }.getOrNull() == markerText(pod)
    }

    /** A [FantomHome.PodSource] for the up to date stubs of [pod], or null when there are none. */
    fun podSource(pod: File, base: File = defaultBase()): FantomHome.PodSource? {
        val dir = existingStub(pod, base) ?: return null
        val podDir = File(dir, podName(pod))
        val fan = File(podDir, "fan")
        if (!fan.isDirectory) return null // a pod without apidoc
        return FantomHome.PodSource(podName(pod), podDir, listOf(fan))
    }

    /** True when [pod] has no up to date stubs under [base]. */
    fun needsGeneration(pod: File, base: File = defaultBase()): Boolean = existingStub(pod, base) == null

    /**
     * Writes the stubs of [pod] under [base] and returns the number of `.fan` files written.
     * Old folders of the same pod file are deleted first.
     */
    fun generate(pod: File, base: File = defaultBase()): Int {
        candidates(pod, base).forEach { deleteTree(it) }
        val name = podName(pod)
        var count = 0
        ZipFile(pod).use { zip ->
            val props = Properties()
            zip.getEntry("meta.props")?.let { e -> zip.getInputStream(e).use { props.load(it) } }
            val version = props.getProperty("pod.version") ?: "0"
            val dir = File(base, "$name-$version-${hash(pod)}")
            val fan = File(dir, "$name/fan")
            val docs = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("doc/") && it.name.endsWith(".apidoc") }
                .mapNotNull { e ->
                    val text = zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
                    runCatching { ApiDocParser.parse(text) }
                        .onFailure { LOG.info("Cannot read ${pod.name}!/${e.name}: ${it.message}") }
                        .getOrNull()
                }.toList()
            dir.mkdirs()
            if (docs.isNotEmpty()) {
                fan.mkdirs()
                val ownTypes = docs.mapTo(HashSet()) { it.name }
                for (t in docs) {
                    val f = File(fan, "${t.name}.fan")
                    f.writeText(StubWriter(name, version, ownTypes).write(t))
                    f.setWritable(false, false)
                    count++
                }
            }
            File(dir, MARKER).writeText(markerText(pod))
        }
        return count
    }

    private fun deleteTree(dir: File) {
        dir.walkBottomUp().forEach { it.setWritable(true); it.delete() }
    }

    /** Writes one type as Fantom source. */
    class StubWriter(private val pod: String, private val version: String, private val ownTypes: Set<String>) {

        private val qnameRegex = Regex("""\b([A-Za-z_]\w*)::([A-Za-z_]\w*)""")

        /** Java FFI types (`[java]java.lang.management::ThreadMXBean`) have no Fantom form; they become `Obj`. */
        private val ffiRegex = Regex("""\[java][\w.:]+""")

        /** Short name to the pods that use it in this file. */
        private val usedPods = LinkedHashSet<String>()
        private var ambiguous: Set<String> = emptySet()

        fun write(t: TypeDoc): String {
            prepare(t)
            val sb = StringBuilder()
            sb.append("** Generated from ").append(pod).append(".pod ").append(version).append("; read-only.\n")
            sb.append("\n")
            for (p in usedPods.filter { it != pod && it != "sys" }.sorted()) sb.append("using ").append(p).append('\n')
            if (usedPods.any { it != pod && it != "sys" }) sb.append('\n')
            doc(sb, t.doc, "")
            for (f in t.facets) facet(sb, f, "")
            sb.append(typeHeader(t)).append("\n{\n")
            val enumValues = if (t.isEnum) t.slots.filter { it.kind == SlotKind.FIELD && "enum" in it.flags } else emptyList()
            val rest = t.slots - enumValues.toSet()
            if (enumValues.isNotEmpty()) {
                enumValues.forEachIndexed { i, s ->
                    doc(sb, s.doc, "  ")
                    sb.append("  ").append(s.name)
                    sb.append(if (i < enumValues.size - 1) ",\n" else if (rest.isNotEmpty()) ";\n" else "\n")
                }
                if (rest.isNotEmpty()) sb.append('\n')
            }
            rest.forEachIndexed { i, s ->
                if (i > 0) sb.append('\n')
                slot(sb, t, s)
            }
            sb.append("}\n")
            return sb.toString()
        }

        private fun allTypeTexts(t: TypeDoc): Sequence<String> = sequence {
            yieldAll(t.base.take(1)); yieldAll(t.mixins)
            for (f in t.facets) yield(f.qname)
            for (s in t.slots) {
                yield(s.type)
                for (f in s.facets) yield(f.qname)
                for (p in s.params) yield(p.type)
            }
        }

        private fun prepare(t: TypeDoc) {
            val byShort = HashMap<String, MutableSet<String>>()
            for (text in allTypeTexts(t)) for (m in qnameRegex.findAll(ffiRegex.replace(text, "sys::Obj"))) {
                byShort.getOrPut(m.groupValues[2]) { HashSet() } += m.groupValues[1]
            }
            val amb = HashSet<String>()
            for ((short, pods) in byShort) {
                if (pods.size > 1) amb += short
                else if (pods.single() != pod && short in ownTypes) amb += short
            }
            ambiguous = amb
            for ((short, pods) in byShort) if (short !in amb) usedPods += pods
        }

        /** `sys::Str?` to `Str?`, except for names two pods share in this file. */
        private fun ty(qtype: String): String = qnameRegex.replace(ffiRegex.replace(qtype, "sys::Obj")) { m ->
            if (m.groupValues[2] in ambiguous && m.groupValues[1] != pod) m.value else m.groupValues[2]
        }

        private fun doc(sb: StringBuilder, doc: String?, indent: String) {
            if (doc == null) return
            for (line in doc.split('\n')) {
                sb.append(indent).append("**")
                if (line.isNotEmpty()) sb.append(' ').append(line)
                sb.append('\n')
            }
        }

        private fun facet(sb: StringBuilder, f: ApiDocParser.Facet, indent: String) {
            sb.append(indent).append('@').append(ty(f.qname))
            if (f.values.any { expr(it.second) != null }) {
                sb.append(" { ")
                sb.append(f.values.mapNotNull { v -> expr(v.second)?.let { "${v.first} = $it" } }.joinToString("; "))
                sb.append(" }")
            }
            sb.append('\n')
        }

        private val protection = listOf("public", "protected", "internal", "private")

        private fun typeHeader(t: TypeDoc): String {
            val mods = ArrayList<String>()
            protection.firstOrNull { it in t.flags }?.let { mods += it }
            if ("abstract" in t.flags && !t.isMixin) mods += "abstract"
            if (!t.isEnum && !t.isFacet) {
                if ("const" in t.flags) mods += "const"
                if ("final" in t.flags) mods += "final"
            }
            if ("native" in t.flags) mods += "native"
            mods += when {
                t.isMixin -> "mixin"
                t.isEnum -> "enum class"
                t.isFacet -> "facet class"
                else -> "class"
            }
            val supers = ArrayList<String>()
            val base = t.base.firstOrNull()
            if (base != null && !t.isEnum && !t.isFacet && base != "sys::Obj") supers += ty(base)
            t.mixins.mapTo(supers) { ty(it) }
            val head = mods.joinToString(" ") + " " + t.name
            return if (supers.isEmpty()) head else head + " : " + supers.joinToString(", ")
        }

        private fun slot(sb: StringBuilder, t: TypeDoc, s: ApiDocParser.Slot) {
            doc(sb, s.doc, "  ")
            for (f in s.facets) facet(sb, f, "  ")
            sb.append("  ")
            val mods = ArrayList<String>()
            protection.firstOrNull { it in s.flags }?.let { mods += it }
            for (m in listOf("abstract", "override", "virtual", "static")) if (m in s.flags) mods += m
            when (s.kind) {
                SlotKind.FIELD -> {
                    if ("const" in s.flags) mods += "const"
                    if ("native" in s.flags) mods += "native"
                    mods.forEach { sb.append(it).append(' ') }
                    sb.append(ty(s.type)).append(' ').append(s.name)
                    expr(s.init)?.let { sb.append(" := ").append(it) }
                    if (s.setter != null) sb.append(" { ").append(s.setter).append(" set }")
                    sb.append('\n')
                }
                SlotKind.CTOR -> {
                    mods.forEach { sb.append(it).append(' ') }
                    sb.append("new ").append(s.name).append('(').append(params(s)).append(") {}\n")
                }
                SlotKind.METHOD -> {
                    if ("once" in s.flags) mods += "once"
                    val abstract = "abstract" in s.flags
                    if (!abstract && "once" !in s.flags) mods += "native"
                    mods.forEach { sb.append(it).append(' ') }
                    sb.append(ty(s.type)).append(' ').append(s.name).append('(').append(params(s)).append(')')
                    if (!abstract && "once" in s.flags) sb.append(" { throw UnsupportedErr() }")
                    sb.append('\n')
                }
            }
        }

        /**
         * An apidoc expression as Fantom source, or null when it cannot be written back.
         * The compiler prints constructor calls as `Type.<ctor>(...)`, long values as `...`
         * and localized strings as `@key`.
         */
        private fun expr(e: String?): String? {
            if (e == null) return null
            val s = e.replace(".<ctor>(", "(")
            if (s.isBlank() || s.trimStart().startsWith("=") || "..." in s || "@" in s || "<ctor>" in s || "[java]" in s) return null
            return if (balanced(s)) s else null
        }

        /** True when quotes, parens, brackets and braces pair up (the writer cuts some values short). */
        private fun balanced(s: String): Boolean {
            val stack = ArrayDeque<Char>()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when (c) {
                    '"', '\'', '`' -> {
                        i++
                        while (i < s.length && s[i] != c) { if (s[i] == '\\') i++; i++ }
                        if (i >= s.length) return false
                    }
                    '(', '[', '{' -> stack.addLast(c)
                    ')' -> if (stack.removeLastOrNull() != '(') return false
                    ']' -> if (stack.removeLastOrNull() != '[') return false
                    '}' -> if (stack.removeLastOrNull() != '{') return false
                }
                i++
            }
            return stack.isEmpty()
        }

        private fun params(s: ApiDocParser.Slot): String = s.params.joinToString(", ") { p ->
            val head = ty(p.type) + " " + p.name
            if (p.default != null) "$head := ${expr(p.default) ?: "null"}" else head
        }
    }
}
