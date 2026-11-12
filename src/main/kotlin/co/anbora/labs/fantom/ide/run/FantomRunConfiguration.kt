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
    fun buildCommandLine(home: File? = FantomCommandLine.home(project)): GeneralCommandLine =
        FantomCommandLine.build(project, home, "fan", parameters(), workingDir, envs, passParentEnvs)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        FantomCommandLine.state(environment) { buildCommandLine() }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        JDOMExternalizerUtil.writeField(element, TARGET, target)
        JDOMExternalizerUtil.writeField(element, ARGS, programArgs)
        JDOMExternalizerUtil.writeField(element, WORK_DIR, workingDir)
        JDOMExternalizerUtil.writeField(element, PASS_PARENT, passParentEnvs.toString())
        EnvironmentVariablesComponent.writeExternal(element, envs)
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        target = JDOMExternalizerUtil.readField(element, TARGET, "")
        programArgs = JDOMExternalizerUtil.readField(element, ARGS, "")
        workingDir = JDOMExternalizerUtil.readField(element, WORK_DIR, "")
        passParentEnvs = JDOMExternalizerUtil.readField(element, PASS_PARENT, "true").toBoolean()
        val read = LinkedHashMap<String, String>()
        EnvironmentVariablesComponent.readExternal(element, read)
        envs = read
    }

    override fun clone(): RunConfiguration {
        val copy = super.clone() as FantomRunConfiguration
        copy.envs = LinkedHashMap(envs)
        return copy
    }

    private companion object {
        const val TARGET = "target"
        const val ARGS = "programArgs"
        const val WORK_DIR = "workingDir"
        const val PASS_PARENT = "passParentEnvs"
    }
}
