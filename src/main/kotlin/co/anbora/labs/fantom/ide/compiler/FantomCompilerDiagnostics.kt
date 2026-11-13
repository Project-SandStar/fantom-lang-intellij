package co.anbora.labs.fantom.ide.compiler

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.lang.annotation.HighlightSeverity
import java.io.File

/** One compiler message. [line] and [column] are 1-based; a file-level message has line 1, column 1. */
data class FantomDiagnostic(
    val path: String,
    val line: Int,
    val column: Int,
    val severity: HighlightSeverity,
    val message: String,
)

/** The outcome of one `fan build.fan compile` run for a pod. */
class FantomCompileResult(
    val podRoot: File,
    /** `System.currentTimeMillis()` when the compile started. */
    val time: Long,
    /** Canonical source path to its diagnostics. */
    val byFile: Map<String, List<FantomDiagnostic>>,
    val exitCode: Int,
    /** True when the process ran and printed a build result; false on timeout or launch failure. */
    val completed: Boolean,
) {
    fun forFile(canonicalPath: String): List<FantomDiagnostic> = byFile[canonicalPath] ?: emptyList()
    val isEmpty: Boolean get() = byFile.isEmpty()
}

/**
 * The compiler command and its output format.
 *
 * Command: `<home>/bin/fan <podRoot>/build.fan compile`, run in a private work folder that
 * holds an empty `fan.props`. The `fan.props` file switches the runtime to `util::PathEnv`
 * with that folder as work dir, so the pod lands in `<workDir>/lib/fan/` and never in the
 * user's install. `FAN_ENV` and `FAN_ENV_PATH` are dropped from the environment so they
 * cannot override the file.
 *
 * Diagnostics (`compiler::CompilerLog.compilerErr`, `Loc.toLocStr`):
 * ```
 * /abs/Foo.fan(3,23): Unknown variable 'nme'
 * /abs/Foo.fan(5,19): WARN Deprecated slot 'podx::Foo.old'
 * /abs/build.fan: Cannot resolve depend: pod 'nopepod' not found
 * ```
 * The exit code is 0 on success (warnings allowed) and 255 on `BUILD FAILED`.
 */
object FantomCompilerDiagnostics {
    private val located = Regex("""^(.+?\.fan)\((\d+)(?:,(\d+))?\): (WARN )?(.*)$""")
    private val fileOnly = Regex("""^(/.+?\.fan|[A-Za-z]:\\.+?\.fan): (.*)$""")

    /** One output line to a diagnostic, or null for progress lines like `  Compile [podx]`. */
    fun parseLine(line: String): FantomDiagnostic? {
        val l = line.trimEnd()
        if (l.isEmpty() || l.startsWith(" ")) return null
        located.matchEntire(l)?.let { m ->
            val (path, ln, col, warn, msg) = m.destructured
            return FantomDiagnostic(
                canonical(path),
                ln.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                col.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                if (warn.isNotEmpty()) HighlightSeverity.WARNING else HighlightSeverity.ERROR,
                msg.trim(),
            )
        }
        fileOnly.matchEntire(l)?.let { m ->
            val (path, msg) = m.destructured
            if (msg.startsWith("WARN ")) return FantomDiagnostic(canonical(path), 1, 1, HighlightSeverity.WARNING, msg.removePrefix("WARN ").trim())
            return FantomDiagnostic(canonical(path), 1, 1, HighlightSeverity.ERROR, msg.trim())
        }
        return null
    }

    fun parse(output: String): Map<String, List<FantomDiagnostic>> =
        output.lineSequence().mapNotNull(::parseLine).groupBy { it.path }

    fun canonical(path: String): String = runCatching { File(path).canonicalPath }.getOrDefault(path)

    /** The command line; [workDir] must exist and hold `fan.props`. */
    fun commandLine(home: File, buildFile: File, workDir: File): GeneralCommandLine {
        val bin = File(home, "bin")
        val cmd = GeneralCommandLine(File(bin, "fan").path)
            .withParameters(buildFile.path, "compile")
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withWorkDirectory(workDir)
            .withCharset(Charsets.UTF_8)
        cmd.environment.remove("FAN_ENV")
        cmd.environment.remove("FAN_ENV_PATH")
        val parentPath = cmd.parentEnvironment["PATH"].orEmpty()
        cmd.withEnvironment("PATH", listOf(bin.path, parentPath).filter { it.isNotBlank() }.joinToString(File.pathSeparator))
        return cmd
    }

    /** The private work folder of [podRoot] under [root]; the same path for every target. */
    fun workDirFor(root: File, podRoot: File): File =
        File(root, podRoot.name.ifBlank { "pod" } + "-" + Integer.toHexString(podRoot.canonicalPath.hashCode()))

    /**
     * Creates the private work folder with its `fan.props`; returns null when it cannot be made.
     * The file is written on every run: [podPath] becomes its `path=` line (folders searched for
     * `lib/fan` pods after the work dir and before the runner's home), so the pods one build
     * borrowed never leak into the next.
     */
    fun prepareWorkDir(root: File, podRoot: File, podPath: List<File> = emptyList()): File? {
        val dir = workDirFor(root, podRoot)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        if (!writeProps(dir, podPath)) return null
        return dir
    }

    /** Writes `<dir>/fan.props` for [podPath]; false on failure. */
    fun writeProps(dir: File, podPath: List<File>): Boolean {
        val text = buildString {
            append("// IntelliJ Fantom plugin: PathEnv work dir for compiler diagnostics\n")
            if (podPath.isNotEmpty()) append("path=").append(podPath.joinToString(";") { it.absolutePath.replace('\\', '/') }).append('\n')
        }
        return runCatching { File(dir, "fan.props").writeText(text) }.isSuccess
    }
}
