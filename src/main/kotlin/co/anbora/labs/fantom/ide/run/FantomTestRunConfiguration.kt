package co.anbora.labs.fantom.ide.run

import co.anbora.labs.fantom.ide.run.test.FantomDebuggableTestProfile
import co.anbora.labs.fantom.ide.run.test.FantomTestRunState
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
import org.jdom.Element
import java.io.File

/** Runs `fant <pod>[::Class[.method]]`. */
class FantomTestRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<Element>(project, factory, name), FantomDebuggableTestProfile {

    var pod: String = ""
    var testClass: String = ""
    var testMethod: String = ""
    var workingDir: String = ""
    var envs: MutableMap<String, String> = LinkedHashMap()
    var passParentEnvs: Boolean = true
    /** True once the "Build Fantom pod" step was given to this configuration; see [FantomBuildStepMigration]. */
    var buildStepAdded: Boolean = false

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = FantomTestSettingsEditor(project)

    override fun checkConfiguration() {
        if (pod.isBlank()) throw RuntimeConfigurationError("Pod is empty")
        if (testClass.isBlank() && testMethod.isNotBlank()) throw RuntimeConfigurationError("Test method needs a test class")
    }

    /** `pod`, `pod::Class` or `pod::Class.method`. */
    fun testSpec(): String = buildString {
        append(pod.trim())
        if (testClass.isNotBlank()) {
            append("::").append(testClass.trim())
            if (testMethod.isNotBlank()) append('.').append(testMethod.trim())
        }
    }

    override fun suggestedName(): String? = when {
        pod.isBlank() -> null
        testClass.isBlank() -> pod
        testMethod.isBlank() -> testClass
        else -> "$testClass.$testMethod"
    }

    /** `<home>/bin/fant <specs>`, by default this configuration's [testSpec]. Throws when there is no home. */
    @Throws(ExecutionException::class)
    fun buildCommandLine(
        home: File? = FantomCommandLine.home(project),
        specs: List<String> = listOf(testSpec()),
        podWorkDir: File? = null,
    ): GeneralCommandLine =
        FantomCommandLine.build(project, home, "fant", specs, workingDir, envs, passParentEnvs, podWorkDir)

    /**
     * The `java` command that `bin/fant` would exec, with a JDWP agent that waits on [port] and
     * `FAN_DEBUG=true` so the runtime keeps Fantom local names (see [FantomRunConfiguration.buildDebugCommandLine]).
     * The main class comes from the script: `Fant` or `Fan util::TestRunner`; see [FantomCommandLine.fantLauncher].
     */
    @Throws(ExecutionException::class)
    fun buildDebugCommandLine(
        port: Int,
        home: File? = FantomCommandLine.home(project),
        specs: List<String> = listOf(testSpec()),
        podWorkDir: File? = null,
    ): GeneralCommandLine {
        val fantomHome = home ?: throw ExecutionException(FantomCommandLine.NO_HOME)
        val (mainClass, leading) = FantomCommandLine.fantLauncher(fantomHome)
        val debugEnvs = LinkedHashMap<String, String>().apply { put(FantomRunConfiguration.FAN_DEBUG, "true"); putAll(envs) }
        return FantomCommandLine.buildJava(
            project, fantomHome, leading + specs, workingDir, debugEnvs, passParentEnvs,
            listOf(FantomRunConfiguration.jdwpAgent(port)), mainClass, podWorkDir,
        )
    }

    /** The state the Debug runner starts: [buildDebugCommandLine] with the results in the test tree. */
    override fun debugState(environment: ExecutionEnvironment, port: Int): RunProfileState =
        FantomTestRunState(environment, this, listOf(testSpec())) { specs ->
            buildDebugCommandLine(port, specs = specs, podWorkDir = FantomPodLaunch.builtWorkDir(environment))
        }

    /** Results go to the test tree; see [FantomTestRunState]. */
    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        FantomTestRunState(environment, this, listOf(testSpec()))

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        JDOMExternalizerUtil.writeField(element, POD, pod)
        JDOMExternalizerUtil.writeField(element, CLASS, testClass)
        JDOMExternalizerUtil.writeField(element, METHOD, testMethod)
        JDOMExternalizerUtil.writeField(element, WORK_DIR, workingDir)
        JDOMExternalizerUtil.writeField(element, PASS_PARENT, passParentEnvs.toString())
        if (buildStepAdded) JDOMExternalizerUtil.writeField(element, BUILD_STEP, "true")
        EnvironmentVariablesComponent.writeExternal(element, envs)
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        pod = JDOMExternalizerUtil.readField(element, POD, "")
        testClass = JDOMExternalizerUtil.readField(element, CLASS, "")
        testMethod = JDOMExternalizerUtil.readField(element, METHOD, "")
        workingDir = JDOMExternalizerUtil.readField(element, WORK_DIR, "")
        passParentEnvs = JDOMExternalizerUtil.readField(element, PASS_PARENT, "true").toBoolean()
        buildStepAdded = JDOMExternalizerUtil.readField(element, BUILD_STEP, "false").toBoolean()
        val read = LinkedHashMap<String, String>()
        EnvironmentVariablesComponent.readExternal(element, read)
        envs = read
    }

    override fun clone(): RunConfiguration {
        val copy = super.clone() as FantomTestRunConfiguration
        copy.envs = LinkedHashMap(envs)
        return copy
    }

    private companion object {
        const val POD = "pod"
        const val CLASS = "testClass"
        const val METHOD = "testMethod"
        const val WORK_DIR = "workingDir"
        const val PASS_PARENT = "passParentEnvs"
        private const val BUILD_STEP = "buildStepAdded"
    }
}
