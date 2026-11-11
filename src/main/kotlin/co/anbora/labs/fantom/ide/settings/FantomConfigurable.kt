package co.anbora.labs.fantom.ide.settings

import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.RootsChangeRescanningInfo
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.io.File
import javax.swing.JLabel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Settings | Languages & Frameworks | Fantom. One field: the Fantom home.
 *
 * An empty field means "detect it" ([FantomHome.detect]). A non-empty field must pass
 * [FantomHome.isHome]; otherwise [apply] throws and nothing is stored. On a successful
 * apply the roots change event is fired so [co.anbora.labs.fantom.lang.library.FantomLibraryRootsProvider]
 * is asked again and the library re-indexes.
 */
class FantomConfigurable(private val project: Project) : BoundConfigurable("Fantom") {

    /** Built in [createPanel]; read it only after `createComponent()`. */
    internal lateinit var homeField: TextFieldWithBrowseButton
    private lateinit var detectedLabel: JLabel

    private val settings: FantomSettings get() = FantomSettings.getInstance(project)

    override fun createPanel(): DialogPanel {
        homeField = TextFieldWithBrowseButton()
        detectedLabel = JLabel()
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Fantom Home")
            .withDescription("Folder with bin/fan and src/")
        homeField.addBrowseFolderListener(project, descriptor)
        homeField.textField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateDetectedLabel()
            override fun removeUpdate(e: DocumentEvent) = updateDetectedLabel()
            override fun changedUpdate(e: DocumentEvent) = updateDetectedLabel()
        })
        detectedLabel.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        detectedLabel.font = JBUI.Fonts.smallFont()
        updateDetectedLabel()
        return panel {
            row("Fantom home:") {
                cell(homeField)
                    .align(AlignX.FILL)
                    .validationOnInput { field -> validateHome(field.text)?.let { error(it) } }
                    .validationOnApply { field -> validateHome(field.text)?.let { error(it) } }
            }
            row("") {
                cell(detectedLabel)
            }
        }
    }

    private fun updateDetectedLabel() {
        detectedLabel.text = when {
            homeField.text.isNotBlank() -> " "
            else -> FantomHome.detect()?.let { "Detected: ${it.path}" }
                ?: "Not found; set ${FantomHome.ENV} or pick a folder"
        }
    }

    private fun validateHome(text: String): String? {
        if (text.isBlank()) return null
        return if (FantomHome.isHome(File(text))) null else NOT_A_HOME
    }

    private val hasUi: Boolean get() = ::homeField.isInitialized

    override fun isModified(): Boolean = hasUi && homeField.text.trim() != (settings.homePath ?: "")

    override fun reset() {
        if (!hasUi) return
        homeField.text = settings.homePath ?: ""
        updateDetectedLabel()
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        if (!hasUi) return
        val text = homeField.text.trim()
        validateHome(text)?.let { throw ConfigurationException(it) }
        if (text == (settings.homePath ?: "")) return
        settings.homePath = text.ifBlank { null }
        WriteAction.runAndWait<RuntimeException> {
            ProjectRootManagerEx.getInstanceEx(project)
                .makeRootsChange(EmptyRunnable.getInstance(), RootsChangeRescanningInfo.TOTAL_RESCAN)
        }
    }

    companion object {
        const val ID = "co.anbora.labs.fantom.settings"
        const val NOT_A_HOME = "Not a Fantom home: needs bin/fan and src/"
    }
}
