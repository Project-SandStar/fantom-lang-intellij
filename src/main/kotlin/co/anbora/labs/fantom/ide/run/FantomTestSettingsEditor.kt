package co.anbora.labs.fantom.ide.run

import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/** Editor for [FantomTestRunConfiguration]: pod, class, method, working dir, env vars. */
class FantomTestSettingsEditor(private val project: Project) : SettingsEditor<FantomTestRunConfiguration>() {
    private val podField = JBTextField()
    private val classField = JBTextField()
    private val methodField = JBTextField()
    private val workDirField = TextFieldWithBrowseButton()
    private val envComponent = EnvironmentVariablesComponent(project)

    override fun resetEditorFrom(config: FantomTestRunConfiguration) {
        podField.text = config.pod
        classField.text = config.testClass
        methodField.text = config.testMethod
        workDirField.text = config.workingDir
        envComponent.envs = config.envs
        envComponent.isPassParentEnvs = config.passParentEnvs
    }

    override fun applyEditorTo(config: FantomTestRunConfiguration) {
        config.pod = podField.text.trim()
        config.testClass = classField.text.trim()
        config.testMethod = methodField.text.trim()
        config.workingDir = workDirField.text.trim()
        config.envs = LinkedHashMap(envComponent.envs)
        config.passParentEnvs = envComponent.isPassParentEnvs
    }

    override fun createEditor(): JComponent {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Working Directory")
        workDirField.addBrowseFolderListener(project, descriptor)
        classField.emptyText.text = "optional: whole pod when empty"
        methodField.emptyText.text = "optional: whole class when empty"
        return panel {
            row("Pod:") { cell(podField).align(AlignX.FILL) }
            row("Test class:") { cell(classField).align(AlignX.FILL) }
            row("Test method:") { cell(methodField).align(AlignX.FILL) }
            row("Working directory:") { cell(workDirField).align(AlignX.FILL) }
            row(envComponent.label.text) { cell(envComponent.component).align(AlignX.FILL) }
        }
    }
}
