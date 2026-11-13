package co.anbora.labs.fantom.ide.run

import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project
import com.intellij.util.EnvironmentUtil
import java.io.File

/** Command line building shared by both Fantom run configurations. */
object FantomCommandLine {
    /** The main class of `bin/fan`. */
    const val FAN_MAIN = "fanx.tools.Fan"
    const val NO_HOME = "No Fantom installation configured. Set it in Settings | Languages & Frameworks | Fantom"

    /**
     * `<home>/bin/<tool> <params>` with [envs] applied and `<home>/bin` first on PATH.
     * A blank [workingDir] means the project base folder.
     */
    @Throws(ExecutionException::class)
    fun build(
        project: Project,
        home: File?,
        tool: String,
        params: List<String>,
        workingDir: String?,
        envs: Map<String, String>,
        passParentEnvs: Boolean,
    ): GeneralCommandLine {
        val fantomHome = home ?: throw ExecutionException(NO_HOME)
        val bin = File(fantomHome, "bin")
        val cmd = GeneralCommandLine(File(bin, tool).path)
            .withParameters(params)
            .withParentEnvironmentType(
                if (passParentEnvs) GeneralCommandLine.ParentEnvironmentType.CONSOLE
                else GeneralCommandLine.ParentEnvironmentType.NONE
            )
            .withEnvironment(envs)
        val dir = workingDir?.takeIf { it.isNotBlank() } ?: project.basePath
        if (dir != null) cmd.withWorkDirectory(dir)
        val parentPath = envs["PATH"] ?: (if (passParentEnvs) EnvironmentUtil.getValue("PATH") else null)
        val path = listOfNotNull(bin.path, parentPath?.takeIf { it.isNotBlank() }).joinToString(File.pathSeparator)
        cmd.withEnvironment("PATH", path)
        return cmd
    }

    /**
     * The `java` command that `bin/fanlaunch` would exec for `fan <params>`, with [jvmArgs] added
     * before the class path. `fanlaunch` reads `JAVA_OPTIONS` from `etc/sys/config.props` and
     * overwrites any value in the environment, so JVM options (such as a JDWP agent) only get in
     * through a direct command line like this one.
     */
    @Throws(ExecutionException::class)
    fun buildJava(
        project: Project,
        home: File?,
        params: List<String>,
        workingDir: String?,
        envs: Map<String, String>,
        passParentEnvs: Boolean,
        jvmArgs: List<String>,
        mainClass: String = FAN_MAIN,
    ): GeneralCommandLine {
        val fantomHome = home ?: throw ExecutionException(NO_HOME)
        fun env(name: String): String? =
            envs[name]?.takeIf { it.isNotBlank() } ?: (if (passParentEnvs) EnvironmentUtil.getValue(name) else null)?.takeIf { it.isNotBlank() }

        val java = javaExecutable(::env) ?: throw ExecutionException(
            "Cannot find java. Set JAVA_HOME or FAN_JAVA, or put java on the PATH"
        )
        val fanCp = env("FAN_CP")?.takeIf { File(it).isDirectory }
            ?: listOf("sys.jar", "jline.jar").joinToString(File.pathSeparator) { File(fantomHome, "lib/java/$it").path }
        val classPath = listOfNotNull(fanCp, env("CLASSPATH")).joinToString(File.pathSeparator)

        val cmd = build(project, home, "fan", params, workingDir, envs, passParentEnvs)
        cmd.exePath = java
        cmd.parametersList.clearAll()
        cmd.addParameters(configJavaOptions(fantomHome))
        cmd.addParameters(jvmArgs)
        cmd.addParameters("-cp", classPath, "-Dfan.home=${fantomHome.path}", mainClass)
        cmd.addParameters(params)
        return cmd
    }

    /**
     * The main class and leading arguments that `<home>/bin/fant` passes to `fanlaunch`:
     * `fanx.tools.Fant` up to Fantom 1.0.80, `fanx.tools.Fan util::TestRunner` from 1.0.81.
     * Read from the last `fanlaunch <Tool> [args] "$@"` line of the script; `Fant` when the
     * script is missing or has no such line.
     */
    fun fantLauncher(home: File): Pair<String, List<String>> {
        val script = File(home, "bin/fant")
        val line = runCatching { script.readLines() }.getOrDefault(emptyList())
            .map { it.trim() }
            .lastOrNull { it.startsWith("fanlaunch ") }
            ?: return "fanx.tools.Fant" to emptyList()
        val words = com.intellij.util.execution.ParametersListUtil.parse(line.removePrefix("fanlaunch "))
            .filter { it != "\$@" }
        val tool = words.firstOrNull()?.takeIf { it.isNotBlank() } ?: return "fanx.tools.Fant" to emptyList()
        return "fanx.tools.$tool" to words.drop(1)
    }

    /** `FAN_JAVA`, then `JAVA_HOME/bin/java`, then `java` on the PATH, as `fanlaunch` does. */
    private fun javaExecutable(env: (String) -> String?): String? {
        env("FAN_JAVA")?.let { return it }
        val exe = if (com.intellij.openapi.util.SystemInfo.isWindows) "java.exe" else "java"
        env("JAVA_HOME")?.let { File(it, "bin/$exe") }?.takeIf { it.canExecute() }?.let { return it.path }
        val path = env("PATH") ?: return null
        return path.split(File.pathSeparator).map { File(it, exe) }.firstOrNull { it.canExecute() }?.path
    }

    /** The last `java.options=` line of `<home>/etc/sys/config.props`, split like a shell would. */
    fun configJavaOptions(home: File): List<String> {
        val props = File(home, "etc/sys/config.props")
        if (!props.isFile) return emptyList()
        val line = runCatching { props.readLines() }.getOrDefault(emptyList())
            .filterNot { it.startsWith("#") || it.startsWith("//") }
            .lastOrNull { it.trimStart().startsWith("java.options=") } ?: return emptyList()
        return com.intellij.util.execution.ParametersListUtil.parse(line.substringAfter('=').trim())
    }

    /** The home as the project settings see it. */
    fun home(project: Project): File? = FantomSettings.getInstance(project).effectiveHome()

    /** A console state that runs [commandLine] and lets the user stop it. */
    fun state(environment: ExecutionEnvironment, commandLine: () -> GeneralCommandLine): CommandLineState =
        object : CommandLineState(environment) {
            @Throws(ExecutionException::class)
            override fun startProcess(): ProcessHandler {
                val handler = KillableColoredProcessHandler(commandLine())
                ProcessTerminatedListener.attach(handler)
                return handler
            }
        }
}
