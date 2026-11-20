package co.anbora.labs.fantom.ide.mcp

import co.anbora.labs.fantom.ide.compiler.FantomCompileResult
import co.anbora.labs.fantom.ide.compiler.FantomCompilerService
import co.anbora.labs.fantom.ide.run.test.FantOutputParser
import co.anbora.labs.fantom.lang.library.FantomBuildFile
import co.anbora.labs.fantom.lang.library.deps.FantomDependencyResolver
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.Key
import java.io.File

/**
 * Process work and answer text of the MCP tools `fantom_build` and `fantom_run_test`, with no
 * MCP server types. [run] starts a process with a timeout and an output cap; the report
 * functions are pure, so tests can feed them canned output.
 */
object FantomMcpRun {
    /** How long `fant` may run before the tool stops it. */
    const val TEST_TIMEOUT_MS = 5 * 60_000L

    /** Most characters of process output kept in memory; the rest is dropped. */
    const val OUTPUT_CAP = 1_000_000

    const val OFF = "Building and running tests is off for AI agents. Turn on \"Allow AI agents to build and run tests\" " +
        "in Settings | Languages & Frameworks | Fantom."
    const val BUSY = "a build or test run of this project is in progress, retry"
    const val NO_HOME = "no Fantom home with bin/fan. Set it in Settings | Languages & Frameworks | Fantom."

    private val identifier = Regex("""^[A-Za-z_][A-Za-z0-9_]*$""")

    /** True for a plain Fantom name: a pod, type or method, with no path or spec characters. */
    fun isIdentifier(text: String): Boolean = identifier.matches(text)

    /** What a process printed (stderr merged), capped at [OUTPUT_CAP]. */
    class Output(val text: String, val exitCode: Int, val timedOut: Boolean, val cancelled: Boolean, val truncated: Boolean)

    /**
     * Runs [cmd] and waits, at most [timeoutMs]. Keeps at most [cap] characters of output.
     * Kills the process on timeout or when [cancelled] turns true. Blocks the caller: never
     * call it on the UI thread or in a read action.
     */
    fun run(cmd: GeneralCommandLine, timeoutMs: Long, cap: Int, cancelled: () -> Boolean): Output {
        val handler = KillableProcessHandler(cmd)
        val text = StringBuilder()
        var truncated = false
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType == ProcessOutputTypes.SYSTEM) return
                synchronized(text) {
                    val room = cap - text.length
                    when {
                        room <= 0 -> truncated = true
                        event.text.length > room -> { text.append(event.text, 0, room); truncated = true }
                        else -> text.append(event.text)
                    }
                }
            }
        })
        handler.startNotify()
        val end = System.currentTimeMillis() + timeoutMs
        var timedOut = false
        var stopped = false
        while (!handler.waitFor(200)) {
            if (cancelled()) { stopped = true; break }
            if (System.currentTimeMillis() > end) { timedOut = true; break }
        }
        if (timedOut || stopped) {
            handler.destroyProcess()
            handler.waitFor(5_000)
        }
        val out = synchronized(text) { text.toString() }
        return Output(out, handler.exitCode ?: -1, timedOut, stopped, synchronized(text) { truncated })
    }

    // ---- build -------------------------------------------------------------------------

    /**
     * The answer for one build: a head line, then one line per compiler message,
     * `SEVERITY path:line:column message`. [shown] shortens a canonical source path.
     */
    fun buildReport(pod: String, result: FantomCompileResult?, shown: (String) -> String, max: Int): String {
        if (result == null) return "pod $pod: the build did not run"
        if (!result.completed) {
            return "pod $pod: FAILED, the build did not finish (launch failure, or timeout after " +
                "${FantomCompilerService.TIMEOUT_MS / 1000} s)"
        }
        val all = result.byFile.values.flatten().sortedWith(compareBy({ it.path }, { it.line }, { it.column }))
        val errors = all.count { it.severity == HighlightSeverity.ERROR }
        val warnings = all.size - errors
        val passed = errors == 0 && result.exitCode == 0
        val head = buildString {
            append("pod ").append(pod).append(if (passed) ": built" else ": FAILED")
            append(", ").append(count(errors, "error")).append(", ").append(count(warnings, "warning"))
            if (!passed && errors == 0) append(" (exit ").append(result.exitCode).append(", no message with a place)")
        }
        val lines = all.map { "${FantomMcpQueries.severityName(it.severity)} ${shown(it.path)}:${it.line}:${it.column} ${it.message}" }
        return list(head, lines, max)
    }

    // ---- tests -------------------------------------------------------------------------

    /** The tests in `fant` output, both printers, read by the test tree's own parser. */
    fun parseFant(text: String): List<FantOutputParser.Result> {
        val parser = FantOutputParser()
        for (line in text.lineSequence()) parser.feed(line)
        parser.finish()
        return parser.results
    }

    /** `  pod::Type.method (File.fan:12)`: one frame of a Fantom stack trace. */
    private val frame = Regex("""^\s*(\S+::\S+) \(([^():\s/\\]+\.fan):(\d+)\)\s*$""")

    /** The file name and line of a failed test: its own frame, else the first frame of its type. */
    fun failurePlace(result: FantOutputParser.Result): Pair<String, Int>? {
        val frames = result.details.lineSequence().mapNotNull { frame.matchEntire(it) }.toList()
        val type = result.qname.substringBeforeLast('.')
        val m = frames.firstOrNull { it.groupValues[1] == result.qname }
            ?: frames.firstOrNull { it.groupValues[1].startsWith("$type.") }
            ?: return null
        return m.groupValues[2] to m.groupValues[3].toInt()
    }

    /**
     * The answer for one `fant` run: a head line, then `PASS qname` or
     * `FAIL qname path:line message` per test. [where] maps a file name to its path.
     */
    fun testReport(spec: String, results: List<FantOutputParser.Result>, out: Output, where: (String) -> String, max: Int): String {
        val failed = results.count { it.failed }
        val passed = results.size - failed
        val ok = results.isNotEmpty() && failed == 0 && !out.timedOut && !out.cancelled
        val head = buildString {
            append("fant ").append(spec).append(if (ok) ": passed" else ": FAILED")
            append(", ").append(passed).append(" passed, ").append(failed).append(" failed")
            if (out.timedOut) append(", stopped after ").append(TEST_TIMEOUT_MS / 1000).append(" s")
            if (out.cancelled) append(", stopped")
            if (results.isEmpty()) append(", no test ran (exit ").append(out.exitCode).append(')')
            if (out.truncated) append(", output cut at ").append(OUTPUT_CAP).append(" characters")
        }
        val lines = results.map { r ->
            if (!r.failed) "PASS ${r.qname}" else {
                val place = failurePlace(r)?.let { (file, line) -> " ${where(file)}:$line" } ?: ""
                "FAIL ${r.qname}$place ${r.message ?: "Test failed"}"
            }
        }.ifEmpty {
            out.text.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList().takeLast(20)
        }
        return list(head, lines, max)
    }

    /** `.fan` files under [root] by file name, as shown by [shown]; first one wins. */
    fun sourceFiles(root: File, shown: (String) -> String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        root.walkTopDown()
            .onEnter { it == root || (!it.name.startsWith(".") && it.name != "build" && it.name != "lib") }
            .filter { it.isFile && it.name.endsWith(".fan") }
            .take(5_000)
            .forEach { out.putIfAbsent(it.name, shown(it.path)) }
        return out
    }

    // ---- deps --------------------------------------------------------------------------

    /**
     * The answer for one pod's `depends`: a head line with the build target, then one line per
     * entry, `pod constraint -> source (how) [warning]`. [projectPath] gives the folder of a
     * pod the project defines.
     */
    fun depsReport(
        pod: String,
        depends: List<FantomBuildFile.Dependency>?,
        changedInCode: Boolean,
        rows: List<FantomDependencyResolver.Row>,
        projectPods: Set<String>,
        target: String?,
        notScanned: Boolean,
        projectPath: (String) -> String?,
    ): String {
        val head = "pod $pod depends, build target ${target ?: "none set"}"
        if (depends == null) return "$head\ndepends is not a literal list in build.fan; it is built by code"
        val byPod = rows.associateBy { it.pod }
        val lines = depends.map { d ->
            val needs = if (d.constraint.isBlank()) d.pod else "${d.pod} ${d.constraint}"
            val src = when {
                d.pod in projectPods -> "this project" + (projectPath(d.pod)?.let { " ($it)" } ?: "")
                else -> byPod[d.pod]?.let { r ->
                    val where = r.podSource?.podDir?.path?.let { " ($it)" } ?: ""
                    val source = r.source.ifBlank { r.how.label }
                    "$source$where, ${r.how.label}" + (r.warning?.let { " [$it]" } ?: "")
                } ?: if (notScanned) "not resolved yet (dependencies are scanned at project open)" else "not resolved"
            }
            "$needs -> $src"
        }
        val tail = if (changedInCode) listOf("note: build.fan changes depends in code; this list may be partial") else emptyList()
        return list("$head: ${count(depends.size, "pod")}", lines + tail, Int.MAX_VALUE)
    }

    fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

    fun list(header: String, lines: List<String>, max: Int): String {
        val sb = StringBuilder(header)
        for (l in lines.take(max)) sb.append('\n').append(l)
        if (lines.size > max) sb.append("\n... ").append(lines.size - max).append(" more; raise limit (max ")
            .append(FantomMcpQueries.MAX_LIMIT).append(')')
        return sb.toString()
    }
}
