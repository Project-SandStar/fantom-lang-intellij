package co.anbora.labs.fantom.ide.axon

import co.anbora.labs.fantom.lang.axon.copy.AxonProjectCopy
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectKey
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectSettings
import co.anbora.labs.fantom.lang.axon.copy.AxonServerSettings
import co.anbora.labs.fantom.lang.axon.copy.AxonServerToken
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel

/**
 * Settings | Languages & Frameworks | Fantom | Axon: where the Axon project copy comes
 * from and which SkySpark project it is.
 *
 * - The server URL is in an application file that is not synced ([AxonServerSettings]).
 * - The token goes to the password store ([AxonServerToken]). The page never shows it and
 *   never reads it: an empty field keeps the stored token.
 * - The fallback folder and the project key are in the workspace file ([AxonProjectSettings]).
 */
class AxonConfigurable(private val project: Project) : BoundConfigurable("Axon") {

    internal lateinit var urlField: JBTextField
    internal lateinit var tokenField: JBPasswordField
    internal lateinit var removeTokenBox: JBCheckBox
    internal lateinit var fallbackField: TextFieldWithBrowseButton
    internal lateinit var keyField: JBTextField

    private val hasUi: Boolean get() = ::urlField.isInitialized
    private val settings: AxonProjectSettings get() = AxonProjectSettings.getInstance(project)

    override fun createPanel(): DialogPanel {
        urlField = JBTextField()
        tokenField = JBPasswordField()
        removeTokenBox = JBCheckBox("Remove the stored token")
        fallbackField = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Axon Fallback Folder"))
        }
        keyField = JBTextField()
        reset()
        return panel {
            group("Axon Server") {
                row("Server URL:") {
                    cell(urlField).align(AlignX.FILL)
                        .comment("The base URL of the Axon server, with http:// or https://. Empty: the fallback folder is read.")
                }
                row("Token:") {
                    cell(tokenField).align(AlignX.FILL)
                        .comment("Kept in the IDE password store, never in a settings file. Leave empty to keep the stored token.")
                }
                row { cell(removeTokenBox) }
            }
            group("Fallback Folder") {
                row("proj folder:") {
                    cell(fallbackField).align(AlignX.FILL)
                        .comment("The proj folder of a local Axon server: proj/<instance>/<project>/func/<name>.axon. Read only when no server URL is set.")
                }
            }
            group("Project") {
                row("Instance/project:") {
                    cell(keyField).align(AlignX.FILL)
                        .comment("The SkySpark project to copy, as instance/project. Or pick it from the status bar entry \u201cAxon\u201d. " +
                            "Empty drops the copy. This and the fallback folder are stored in the workspace file, which is not shared.")
                }
            }
        }
    }

    private fun keyText(): String = keyField.text.trim()

    override fun isModified(): Boolean = hasUi && (
        urlField.text.trim().trimEnd('/') != AxonServerSettings.getInstance().url ||
            tokenField.password.isNotEmpty() || removeTokenBox.isSelected ||
            fallbackField.text.trim() != settings.fallbackPath ||
            keyText() != settings.activeKey?.toString().orEmpty()
        )

    override fun reset() {
        if (!hasUi) return
        urlField.text = AxonServerSettings.getInstance().url
        tokenField.text = ""
        removeTokenBox.isSelected = false
        fallbackField.text = settings.fallbackPath
        keyField.text = settings.activeKey?.toString().orEmpty()
    }

    override fun apply() {
        if (!hasUi) return
        val url = urlField.text.trim().trimEnd('/')
        if (url.isNotEmpty() && !(url.startsWith("http://") || url.startsWith("https://")))
            throw ConfigurationException("The Axon server URL must start with http:// or https://.")
        val keyText = keyText()
        val key = AxonProjectKey.parse(keyText)
        if (keyText.isNotEmpty() && key == null)
            throw ConfigurationException("Write the Axon project as instance/project.")

        val server = AxonServerSettings.getInstance()
        val sourceChanged = url != server.url || fallbackField.text.trim() != settings.fallbackPath
        server.url = url
        settings.fallbackPath = fallbackField.text
        // the password store can be slow and can ask for the keychain: not on the UI thread
        val typed = String(tokenField.password)
        val remove = removeTokenBox.isSelected
        val tokenChanged = typed.isNotEmpty() || remove
        tokenField.text = ""
        removeTokenBox.isSelected = false

        val copy = AxonProjectCopy.getInstance(project)
        val before = settings.activeKey
        val app = ApplicationManager.getApplication()
        val after = Runnable {
            when {
                key == null -> if (before != null) copy.drop()
                key != before || copy.copy == null || sourceChanged -> copy.select(key)
            }
        }
        if (tokenChanged) app.executeOnPooledThread {
            AxonServerToken.set(if (typed.isNotEmpty()) typed else null)
            after.run()
        } else after.run()
        // the status bar entry shows only with a source or a project: re-check it now
        if (!app.isUnitTestMode) project.service<StatusBarWidgetsManager>().updateWidget(AxonProjectWidgetFactory::class.java)
    }
}
