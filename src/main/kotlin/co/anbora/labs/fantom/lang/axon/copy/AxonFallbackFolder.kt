package co.anbora.labs.fantom.lang.axon.copy

import co.anbora.labs.fantom.lang.axon.copy.AxonCopyException.Kind
import co.anbora.labs.fantom.lang.axon.proj.AxonProjFolders
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A local `proj` folder of an Axon server, read in place and never written:
 *
 * ```
 * <proj>/<instance>/<project>/.sync-metadata.json
 * <proj>/<instance>/<project>/func/<name>.axon     the source
 * <proj>/<instance>/<project>/func/<name>.trio     optional: doc and mod
 * ```
 *
 * A project is a folder two levels down that holds `.sync-metadata.json`. The key comes
 * from the folder names, and a project is found by listing folders, never by building a
 * path from a key. Backup and version folders are skipped. `sync` has no meaning here:
 * the folder is what it is.
 */
class AxonFallbackFolder(private val root: File) : AxonFunctionSource {

    override val id: String get() = "folder"

    override fun listProjects(check: () -> Unit): List<AxonProjectInfo> {
        if (!root.isDirectory) throw AxonCopyException(Kind.NOT_CONFIGURED, "The global proj folder does not exist.")
        return projectDirs(check).map { (key, dir) ->
            val meta = metadata(dir)
            val count = (meta?.get("functionCount") as? Number)?.toInt() ?: (File(dir, FUNC).list()?.count { it.endsWith(AXON_EXT) } ?: 0)
            AxonProjectInfo(key, count, AxonJson.str(meta?.get("lastSync")))
        }
    }

    override fun fetch(key: AxonProjectKey, sync: Boolean, check: () -> Unit): AxonFunctionList {
        if (!root.isDirectory) throw AxonCopyException(Kind.NOT_CONFIGURED, "The global proj folder does not exist.")
        val dir = projectDirs(check).firstOrNull { it.first == key }?.second
            ?: throw AxonCopyException(Kind.UNKNOWN_PROJECT, "The global proj folder has no project $key.")
        val meta = metadata(dir)
        val metaFunctions = AxonJson.obj(meta?.get("functions"))
        val files = (File(dir, FUNC).listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(AXON_EXT) && it.name.length > AXON_EXT.length }
            .sortedBy { it.name }
        if (files.size > AxonCopyLimits.MAX_FUNCTIONS) throw AxonCopyException(Kind.TOO_LARGE, "The project has more than ${AxonCopyLimits.MAX_FUNCTIONS} functions.")
        var bytes = 0L
        val functions = files.map { file ->
            check()
            bytes += file.length()
            if (bytes > AxonCopyLimits.MAX_BYTES) throw AxonCopyException(Kind.TOO_LARGE, "The functions are larger than ${AxonCopyLimits.MAX_BYTES / (1024 * 1024)} MB.")
            val name = file.name.removeSuffix(AXON_EXT)
            val src = try { file.readText() } catch (e: Exception) { throw AxonCopyException(Kind.IO, "Cannot read ${file.name}.", e) }
            val trio = File(file.parentFile, "$name.trio").takeIf { it.isFile && it.length() < MAX_TRIO }
                ?.let { runCatching { trioTags(it.readText()) }.getOrNull() }
            val mod = isoOf(trio?.get("mod")) ?: isoOf(AxonJson.str(AxonJson.obj(metaFunctions?.get(name))?.get("lastModified")))
            AxonFunction(name, src, trio?.get("doc")?.takeIf { it.isNotBlank() }, mod, AxonCopyLimits.sha256(src))
        }
        return AxonFunctionList(key, AxonJson.str(meta?.get("lastSync")), functions).also(AxonCopyLimits::validate)
    }

    private fun projectDirs(check: () -> Unit): List<Pair<AxonProjectKey, File>> {
        val out = ArrayList<Pair<AxonProjectKey, File>>()
        for (instance in dirs(root)) {
            check()
            for (project in dirs(instance)) {
                if (!File(project, METADATA).isFile) continue
                out += AxonProjectKey(instance.name, project.name) to project
                if (out.size >= AxonCopyLimits.MAX_PROJECTS) return out
            }
        }
        return out
    }

    private fun dirs(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.startsWith(".") && !AxonProjFolders.isCopyFolder(it.name) }.sortedBy { it.name }

    private fun metadata(dir: File): Map<String, Any?>? {
        val f = File(dir, METADATA)
        if (!f.isFile || f.length() > AxonCopyLimits.MAX_BYTES) return null
        return runCatching { AxonJson.parseObject(f.readText()) }.getOrNull()
    }

    companion object {
        const val METADATA = ".sync-metadata.json"
        const val FUNC = "func"
        private const val AXON_EXT = ".axon"
        private const val MAX_TRIO = 8L * 1024 * 1024

        /**
         * The top-level tags of one trio record whose value is a string: `name:"x"`,
         * `name: x`, or `name:` followed by indented lines. Markers and nested values are
         * left out. Reading stops at the `---` that ends the record.
         */
        fun trioTags(text: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            val lines = text.split('\n').map { it.trimEnd('\r') }
            var i = 0
            while (i < lines.size) {
                val line = lines[i++]
                if (line.startsWith("---")) { if (out.isEmpty()) continue else break }
                if (line.isEmpty() || line[0] == ' ' || line[0] == '\t' || line.startsWith("//")) continue
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val name = line.substring(0, colon)
                if (!name.all { it.isLetterOrDigit() || it == '_' }) continue
                val value = line.substring(colon + 1).trim()
                if (value.isNotEmpty()) {
                    out[name] = if (value.startsWith("\"")) unquote(value) else value
                    continue
                }
                val body = ArrayList<String>()
                while (i < lines.size && (lines[i].isBlank() || lines[i][0] == ' ' || lines[i][0] == '\t')) {
                    val l = lines[i++]
                    body += if (l.startsWith("  ")) l.substring(2) else l.trimStart()
                }
                out[name] = body.joinToString("\n").trimEnd()
            }
            return out
        }

        private fun unquote(v: String): String {
            val sb = StringBuilder()
            var i = 1
            while (i < v.length) {
                val c = v[i++]
                if (c == '"') break
                if (c != '\\' || i >= v.length) { sb.append(c); continue }
                when (val e = v[i++]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        val code = if (i + 4 <= v.length) v.substring(i, i + 4).toIntOrNull(16) else null
                        if (code != null) { sb.append(code.toChar()); i += 4 } else sb.append("\\u")
                    }
                    else -> sb.append(e)
                }
            }
            return sb.toString()
        }

        private val LOCAL = DateTimeFormatter.ofPattern("M/d/yyyy, h:mm:ss a", Locale.ENGLISH)
        private val ISO = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?.*""")

        /**
         * [text] as an ISO time. The folder holds `11/10/2023, 7:07:07 PM UTC`; that
         * becomes `2023-11-10T19:07:07Z`. A value that is ISO already is kept. Anything
         * else is null.
         */
        fun isoOf(text: String?): String? {
            val t = text?.replace(' ', ' ')?.replace(' ', ' ')?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (ISO.matches(t)) return t
            if (!t.endsWith(" UTC")) return null
            return runCatching {
                DateTimeFormatter.ISO_INSTANT.format(LocalDateTime.parse(t.removeSuffix(" UTC").uppercase(Locale.ENGLISH), LOCAL).toInstant(ZoneOffset.UTC))
            }.getOrNull()
        }
    }
}
