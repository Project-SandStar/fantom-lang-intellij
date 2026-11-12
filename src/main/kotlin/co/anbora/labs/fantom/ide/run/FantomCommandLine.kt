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
