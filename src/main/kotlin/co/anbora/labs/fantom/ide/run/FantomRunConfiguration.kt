package co.anbora.labs.fantom.ide.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMExternalizerUtil
import com.intellij.util.execution.ParametersListUtil
import org.jdom.Element
import java.io.File

/**
 * Runs `fan <target> <args>`. [target] is `pod::Type.method` (method defaults to `main`
 * on the Fantom side) or the path of a script file.
 */
class FantomRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<Element>(project, factory, name) {

    var target: String = ""
    var programArgs: String = ""
    var workingDir: String = ""
    var envs: MutableMap<String, String> = LinkedHashMap()
    var passParentEnvs: Boolean = true
    /** True once the "Build Fantom pod" step was given to this configuration; see [FantomBuildStepMigration]. */
    var buildStepAdded: Boolean = false

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = FantomRunSettingsEditor(project)

    override fun checkConfiguration() {
        if (target.isBlank()) throw RuntimeConfigurationError("Target is empty: use pod::Type.method or a script path")
    }

    override fun suggestedName(): String? {
        if (target.isBlank()) return null
        val afterPod = target.substringAfterLast("::")
        return if (afterPod.endsWith(".fan")) File(afterPod).name else afterPod
    }

    /** The parameters after `fan`: the target, then the program arguments. */
    fun parameters(): List<String> = listOf(target) + ParametersListUtil.parse(programArgs)

    /** `<home>/bin/fan <target> <args>`. Throws when there is no home. */
    @Throws(ExecutionException::class)
    fun buildCommandLine(home: File? = FantomCommandLine.home(project), podWorkDir: File? = null): GeneralCommandLine =
        FantomCommandLine.build(project, home, "fan", parameters(), workingDir, envs, passParentEnvs, podWorkDir)

    /** Runs in the pod's work folder when the "Build Fantom pod" step built it; see [FantomPodLaunch]. */
    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        FantomCommandLine.state(environment) { buildCommandLine(podWorkDir = FantomPodLaunch.builtWorkDir(environment)) }

    /**
     * `java <jdwp agent> ... fanx.tools.Fan <target> <args>`: the same program as [buildCommandLine],
     * with the JVM waiting for a debugger on `127.0.0.1:<port>`.
     *
     * `FAN_DEBUG=true` turns on `Sys.debug`, which makes the runtime emitter write a
     * `LocalVariableTable`, so the Variables view shows Fantom local names instead of `slot_N`.
     * A value the user set in the configuration wins.
     */
    @Throws(ExecutionException::class)
    fun buildDebugCommandLine(port: Int, home: File? = FantomCommandLine.home(project), podWorkDir: File? = null): GeneralCommandLine {
        val debugEnvs = LinkedHashMap<String, String>().apply { put(FAN_DEBUG, "true"); putAll(envs) }
        return FantomCommandLine.buildJava(
            project, home, parameters(), workingDir, debugEnvs, passParentEnvs, listOf(jdwpAgent(port)), podWorkDir = podWorkDir,
        )
    }

    /** The state the Debug runner starts: [buildDebugCommandLine] in a console, in the pod's work folder when it was built. */
    fun debugState(environment: ExecutionEnvironment, port: Int): RunProfileState =
        FantomCommandLine.state(environment) { buildDebugCommandLine(port, podWorkDir = FantomPodLaunch.builtWorkDir(environment)) }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        JDOMExternalizerUtil.writeField(element, TARGET, target)
        JDOMExternalizerUtil.writeField(element, ARGS, programArgs)
        JDOMExternalizerUtil.writeField(element, WORK_DIR, workingDir)
        JDOMExternalizerUtil.writeField(element, PASS_PARENT, passParentEnvs.toString())
        if (buildStepAdded) JDOMExternalizerUtil.writeField(element, BUILD_STEP, "true")
        EnvironmentVariablesComponent.writeExternal(element, envs)
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        target = JDOMExternalizerUtil.readField(element, TARGET, "")
        programArgs = JDOMExternalizerUtil.readField(element, ARGS, "")
        workingDir = JDOMExternalizerUtil.readField(element, WORK_DIR, "")
        passParentEnvs = JDOMExternalizerUtil.readField(element, PASS_PARENT, "true").toBoolean()
        buildStepAdded = JDOMExternalizerUtil.readField(element, BUILD_STEP, "false").toBoolean()
        val read = LinkedHashMap<String, String>()
        EnvironmentVariablesComponent.readExternal(element, read)
        envs = read
    }

    override fun clone(): RunConfiguration {
        val copy = super.clone() as FantomRunConfiguration
        copy.envs = LinkedHashMap(envs)
        return copy
    }

    companion object {
        /** The JDWP agent option: the JVM listens on [port] and waits for the debugger before `main`. */
        fun jdwpAgent(port: Int): String = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:$port"

        /** Read by `fan.sys.Sys.debug` (as `FAN_` + config name); turns on local variable tables. */
        const val FAN_DEBUG = "FAN_DEBUG"

        private const val TARGET = "target"
        private const val ARGS = "programArgs"
        private const val WORK_DIR = "workingDir"
        private const val PASS_PARENT = "passParentEnvs"
        private const val BUILD_STEP = "buildStepAdded"
    }
}
