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

/** Editor for [FantomRunConfiguration]: target, program arguments, working dir, env vars. */
class FantomRunSettingsEditor(private val project: Project) : SettingsEditor<FantomRunConfiguration>() {
    private val targetField = JBTextField()
    private val argsField = JBTextField()
    private val workDirField = TextFieldWithBrowseButton()
    private val envComponent = EnvironmentVariablesComponent()

    override fun resetEditorFrom(config: FantomRunConfiguration) {
        targetField.text = config.target
        argsField.text = config.programArgs
        workDirField.text = config.workingDir
        envComponent.envs = config.envs
        envComponent.isPassParentEnvs = config.passParentEnvs
    }

    override fun applyEditorTo(config: FantomRunConfiguration) {
        config.target = targetField.text.trim()
        config.programArgs = argsField.text
        config.workingDir = workDirField.text.trim()
        config.envs = LinkedHashMap(envComponent.envs)
        config.passParentEnvs = envComponent.isPassParentEnvs
    }

    override fun createEditor(): JComponent {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Working Directory")
        workDirField.addBrowseFolderListener(project, descriptor)
        targetField.emptyText.text = "pod::Type.method or path/to/script.fan"
        return panel {
            row("Target:") { cell(targetField).align(AlignX.FILL) }
            row("Program arguments:") { cell(argsField).align(AlignX.FILL) }
            row("Working directory:") { cell(workDirField).align(AlignX.FILL) }
            row(envComponent.label.text) { cell(envComponent.component).align(AlignX.FILL) }
        }
    }
}
