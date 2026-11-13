package co.anbora.labs.fantom.lang.library.deps

import co.anbora.labs.fantom.lang.library.FantomBuildFile
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds the pods under the "source roots for project pods" (default `~/Code`).
 *
 * The scan reads only `build.fan` files (`podName`, the first `version = Version(...)`,
 * the first `depends = [...]` literal) and git metadata (`.git/config`, `HEAD`,
 * `refs/tags`, `packed-refs`); it starts no process. Hidden folders, `node_modules`,
 * `build`, `lib` and symbolic links are skipped; a pod folder is not walked further.
 *
 * Copies of one repository are grouped by [remoteKey]: the remote's path without host,
 * scheme or `.git`. The result is cached by root list until [clear]. Never call [scan]
 * on the UI thread.
 */
object FantomSourceScan {

    /** One pod folder found under a source root. */
    data class Pod(
        val pod: String,
        val dir: File,
        /** The `build.fan` fallback version, `${name}` filled from `static const Int name := N`; null when unreadable. */
        val version: String?,
        val depends: List<FantomBuildFile.Dependency>,
        /** The git working tree root, or null when the pod is not in a git repository. */
        val gitRoot: File?,
        /** [remoteKey] of the `origin` remote, else the canonical [gitRoot] path, else [dir]. */
        val repoKey: String,
        val remote: String?,
        val branch: String?,
        /** Every tag name of the clone. */
        val tags: List<String>,
        /** [dir] relative to [gitRoot], "" when the pod is the root; [dir]'s name when not in git. */
        val relPath: String,
    )

    /** All copies of one repository that define [pod]. */
    data class Repo(val key: String, val pod: String, val copies: List<Pod>) {
        /** Tag name to the first clone that has it. */
        val tags: Map<String, Pod> get() = LinkedHashMap<String, Pod>().also { m ->
            for (c in copies) for (t in c.tags) m.putIfAbsent(t, c)
        }
        /** Last part of [key]: `bassgcommonv3`. */
        val name: String get() = key.trimEnd('/').substringAfterLast('/').ifEmpty { pod }
    }

    data class Result(val pods: List<Pod>, val buildFiles: Int, val millis: Long) {
        /** The repositories that define [pod], each with its copies. */
        fun repos(pod: String): List<Repo> =
            pods.filter { it.pod == pod }.groupBy { it.repoKey }.map { (k, v) -> Repo(k, pod, v) }

        fun podNames(): Set<String> = pods.mapTo(LinkedHashSet()) { it.pod }
    }

    private val cache = ConcurrentHashMap<String, Result>()

    fun clear() = cache.clear()

    /** The cached scan of [roots], or null when it was not run yet. */
    fun cached(roots: List<File>): Result? = cache[key(roots)]

    /** The scan of [roots], from the cache when there. */
    fun scan(roots: List<File>): Result = cache.getOrPut(key(roots)) { scanNow(roots) }

    private fun key(roots: List<File>) = roots.joinToString("|") { it.path }

    private val SKIP = setOf(
        "node_modules", "build", "lib", "out", "target", "gen", "dist", "vendor", "venv", "__pycache__",
        "site-packages", "bower_components", "coverage", "Pods", "DerivedData", "tmp", "temp", "logs",
    )

    /** Names that are files, so the walk needs no stat for them. */
    private val FILE_EXT = Regex(
        """.*\.(fan|fwt|fog|axon|trio|js|mjs|cjs|ts|tsx|jsx|json|md|txt|html?|css|scss|less|xml|ya?ml|toml|props|csv|""" +
            """png|jpe?g|gif|svg|ico|webp|bmp|pdf|zip|gz|tgz|7z|pod|jar|class|java|kt|kts|py|pyc|php|rb|go|rs|c|h|cpp|""" +
            """sh|bat|cmd|ps1|log|lock|map|woff2?|ttf|eot|otf|mp4|mov|mp3|wav|sql|db|sqlite|doc|docx|xls|xlsx|ppt|pptx|""" +
            """bin|dat|o|a|so|dylib|dll|exe|iml|gradle|properties|cfg|ini|conf|env|bak|old|orig|rst|tex|swift|m|cs)""",
        RegexOption.IGNORE_CASE,
    )

    fun scanNow(roots: List<File>): Result {
        val start = System.currentTimeMillis()
        val out = java.util.concurrent.ConcurrentLinkedQueue<Pod>()
        val count = java.util.concurrent.atomic.AtomicInteger()
        val gitCache = ConcurrentHashMap<String, java.util.Optional<GitInfo>>()

        class Visit(val dir: File, val depth: Int) : java.util.concurrent.RecursiveAction() {
            override fun compute() {
                if (depth > 12) return
                val names = dir.list() ?: return
                if ("build.fan" in names) {
                    count.incrementAndGet()
                    val info = runCatching { readBuildFile(File(dir, FantomBuildFile.FILE_NAME).readText()) }.getOrNull()
                    if (info?.podName != null) {
                        val git = gitRootOf(dir)?.let { root ->
                            gitCache.computeIfAbsent(root.path) { java.util.Optional.ofNullable(readGit(root)) }.orElse(null)
                        }
                        val rel = git?.root?.let { dir.relativeTo(it).path } ?: dir.name
                        out += Pod(info.podName, dir, info.version, info.depends, git?.root,
                            git?.remote?.let(::remoteKey) ?: git?.root?.let(::canonical) ?: canonical(dir),
                            git?.remote, git?.branch, git?.tags ?: emptyList(), rel)
                        return
                    }
                }
                val subs = ArrayList<Visit>()
                for (name in names) {
                    if (name.startsWith(".") || name in SKIP || FILE_EXT.matches(name)) continue
                    val c = File(dir, name)
                    if (!Files.isDirectory(c.toPath(), LinkOption.NOFOLLOW_LINKS)) continue
                    subs += Visit(c, depth + 1)
                }
                invokeAll(subs)
            }
        }
        val pool = java.util.concurrent.ForkJoinPool(8)
        try {
            for (root in roots) if (root.isDirectory) pool.invoke(Visit(root, 0))
        } finally {
            pool.shutdown()
        }
        return Result(out.sortedBy { it.dir.path }, count.get(), System.currentTimeMillis() - start)
    }

    /** What the scan reads from a `build.fan`. */
    data class BuildInfo(val podName: String?, val version: String?, val depends: List<FantomBuildFile.Dependency>)

    private val POD_NAME = Regex("""\bpodName\s*=\s*"([^"]+)"""")
    private val VERSION = Regex("""\bversion\s*=\s*Version(?:\.fromStr)?\s*\(\s*"([^"]*)"\s*\)""")
    private val DEPENDS = Regex("""\bdepends\s*=\s*(?:Str)?\[([^\]]*)]""")
    private val STR = Regex(""""([^"]*)"""")
    private val CONST = Regex("""\bconst\s+(?:static\s+)?Int\s+(\w+)\s*:=\s*(\d+)|\bstatic\s+const\s+Int\s+(\w+)\s*:=\s*(\d+)""")
    private val INTERP = Regex("""\$\{(\w+)}|\$(\w+)""")

    /** Reads a `build.fan` text: the first `podName`, `version` and `depends`, comments removed. */
    fun readBuildFile(text: String): BuildInfo {
        val code = stripComments(text)
        val consts = HashMap<String, String>()
        CONST.findAll(code).forEach { m ->
            val name = m.groupValues[1].ifEmpty { m.groupValues[3] }
            val value = m.groupValues[2].ifEmpty { m.groupValues[4] }
            consts.putIfAbsent(name, value)
        }
        val pod = POD_NAME.find(code)?.groupValues?.get(1)
        val version = VERSION.find(code)?.groupValues?.get(1)?.let { raw ->
            val filled = INTERP.replace(raw) { m -> consts[m.groupValues[1].ifEmpty { m.groupValues[2] }] ?: "\u0000" }
            filled.takeIf { '\u0000' !in it && FantomDepend.parseVersion(it) != null }
        }
        val depends = DEPENDS.find(code)?.groupValues?.get(1)?.let { body ->
            STR.findAll(body).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.map { spec ->
                val i = spec.indexOfFirst(Char::isWhitespace)
                if (i < 0) FantomBuildFile.Dependency(spec, "") else FantomBuildFile.Dependency(spec.substring(0, i), spec.substring(i).trim())
            }.toList()
        } ?: emptyList()
        return BuildInfo(pod, version, depends)
    }

    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .lineSequence().map { line ->
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("**")) "" else line
            }.joinToString("\n")

    /**
     * The repository path of a remote URL, lower case, with no scheme, user, host or `.git`:
     * `https://gitlab.bas.co/openbassg/skyspark/bassgcommonv3` and
     * `git@gitlab.com:openbassg/skyspark/bassgcommonv3.git` both give `openbassg/skyspark/bassgcommonv3`.
     */
    fun remoteKey(url: String): String {
        var u = url.trim()
        val scheme = u.indexOf("://")
        u = if (scheme >= 0) {
            u.substring(scheme + 3).substringAfter('/', "")
        } else if (u.contains(':') && !u.startsWith("/")) {
            u.substringAfter(':')
        } else if (!u.startsWith("/") && u.substringBefore('/').contains('.')) {
            u.substringAfter('/', "") // host/path with no scheme
        } else u
        return u.trim('/').removeSuffix(".git").trim('/').lowercase()
    }

    private fun canonical(f: File): String = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)

    /** The nearest folder at or above [dir] that has a `.git` folder or file. */
    fun gitRootOf(dir: File): File? {
        var d: File? = dir
        while (d != null) {
            if (File(d, ".git").exists()) return d
            d = d.parentFile
        }
        return null
    }

    data class GitInfo(val root: File, val gitDir: File, val commonDir: File, val remote: String?, val branch: String?, val tags: List<String>)

    /** Reads git metadata of the working tree at [root] without running git. Follows a `.git` file's `gitdir:`. */
    fun readGit(root: File): GitInfo? {
        val dotGit = File(root, ".git")
        val gitDir = when {
            dotGit.isDirectory -> dotGit
            dotGit.isFile -> runCatching {
                val line = dotGit.readText().lineSequence().first { it.startsWith("gitdir:") }.removePrefix("gitdir:").trim()
                File(line).let { if (it.isAbsolute) it else File(root, line) }
            }.getOrNull() ?: return null
            else -> return null
        }
        val common = File(gitDir, "commondir").takeIf { it.isFile }?.let { f ->
            val p = f.readText().trim()
            File(p).let { if (it.isAbsolute) it else File(gitDir, p) }
        } ?: gitDir
        val remote = runCatching { originUrl(File(common, "config").readText()) }.getOrNull()
        val head = runCatching { File(gitDir, "HEAD").readText().trim() }.getOrNull()
        val branch = head?.takeIf { it.startsWith("ref:") }?.removePrefix("ref:")?.trim()?.removePrefix("refs/heads/")
        return GitInfo(root, gitDir, common, remote, branch, tags(common))
    }

    /** The `url` of `[remote "origin"]`, else of the first remote. */
    fun originUrl(config: String): String? {
        var section: String? = null
        val urls = LinkedHashMap<String, String>()
        for (raw in config.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) { section = line; continue }
            val s = section ?: continue
            if (!s.startsWith("[remote")) continue
            val m = Regex("""^url\s*=\s*(.+)$""").find(line) ?: continue
            urls.putIfAbsent(s, m.groupValues[1].trim())
        }
        return urls["[remote \"origin\"]"] ?: urls.values.firstOrNull()
    }

    /** Tag names from `refs/tags` (nested folders count: `bassgCommon/4.0.6.2.2`) and `packed-refs`. */
    fun tags(commonDir: File): List<String> {
        val out = LinkedHashSet<String>()
        val refs = File(commonDir, "refs/tags")
        if (refs.isDirectory) {
            refs.walkTopDown().filter { it.isFile }.forEach { out += it.relativeTo(refs).invariantSeparatorsPath }
        }
        val packed = File(commonDir, "packed-refs")
        if (packed.isFile) {
            runCatching {
                packed.readLines().forEach { line ->
                    val i = line.indexOf(" refs/tags/")
                    if (i > 0 && !line.startsWith("#") && !line.startsWith("^")) out += line.substring(i + " refs/tags/".length).trim()
                }
            }
        }
        return out.toList()
    }
}
