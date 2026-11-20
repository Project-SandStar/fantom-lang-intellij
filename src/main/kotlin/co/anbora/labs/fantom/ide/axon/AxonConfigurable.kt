package co.anbora.labs.fantom.ide.axon

import co.anbora.labs.fantom.lang.axon.copy.AxonCopyException
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectCopy
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectKey
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectSettings
import co.anbora.labs.fantom.lang.axon.copy.AxonServerClient
import co.anbora.labs.fantom.lang.axon.copy.AxonServerSettings
import co.anbora.labs.fantom.lang.axon.copy.AxonServerToken
import co.anbora.labs.fantom.lang.axon.proj.AxonProjFolders
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.io.File
import java.util.concurrent.CancellationException
import javax.swing.JButton
import javax.swing.event.DocumentEvent

/**
 * Settings | Languages & Frameworks | Fantom | Axon: where the Axon project copy comes
 * from and which SkySpark project it is.
 *
 * - The server URL is in an application file that is not synced ([AxonServerSettings]).
 * - The token goes to the password store ([AxonServerToken]). The page never shows it: the
 *   field stays empty, and a line under it says whether a token is stored. An empty field
 *   keeps the stored token.
 * - "Test Connection" checks the URL and the token in the fields (or the stored token when
 *   the field is empty) with one call to the server, off the UI thread.
 * - The global proj folder is in the same application file as the URL. A line under it says
 *   how many connections and projects it holds; it is counted off the UI thread.
 * - The project key is in the workspace file ([AxonProjectSettings]).
 */
class AxonConfigurable(private val project: Project) : BoundConfigurable("Axon") {

    internal lateinit var urlField: JBTextField
    internal lateinit var tokenField: JBPasswordField
    internal lateinit var removeTokenBox: JBCheckBox
    internal lateinit var tokenState: JBLabel
    internal lateinit var testButton: JButton
    internal lateinit var testResult: JBLabel
    internal lateinit var projFolderField: TextFieldWithBrowseButton
    internal lateinit var projFolderCount: JBLabel
    internal lateinit var keyField: JBTextField

    /** Whether the password store holds a token; read off the UI thread when the page opens. */
    @Volatile
    internal var tokenStored: Boolean? = null

    /** Each test gets a number; a slow reply of an older test never overwrites a newer result. */
    private var testRun = 0

    /** Each count gets a number; only the newest one is shown. */
    @Volatile
    private var countRun = 0

    private val hasUi: Boolean get() = ::urlField.isInitialized
    private val settings: AxonProjectSettings get() = AxonProjectSettings.getInstance(project)

    override fun createPanel(): DialogPanel {
        urlField = JBTextField()
        tokenField = JBPasswordField()
        removeTokenBox = JBCheckBox("Remove the stored token")
        tokenState = JBLabel()
        testButton = JButton("Test Connection")
        testResult = JBLabel()
        projFolderField = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Global Proj Folder"))
        }
        projFolderCount = JBLabel()
        projFolderField.textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = countProjFolder()
        })
        keyField = JBTextField()
        testButton.addActionListener { runTest() }
        reset()
        return panel {
            group("Axon Server") {
                row("Server URL:") {
                    cell(urlField).align(AlignX.FILL)
                        .comment("The base URL of the Axon server, with http:// or https://. Empty: the global proj folder is read.")
                }
                row("Access key:") {
                    cell(tokenField).align(AlignX.FILL)
                        .comment("Your access key from the Axon dashboard (Users page). Kept in the IDE password store, never in a settings file. Leave empty to keep the stored key.")
                }
                row("") { cell(tokenState) }
                row { cell(removeTokenBox) }
                row {
                    cell(testButton)
                    cell(testResult)
                }
            }
            group("Global Proj Folder") {
                row("Global proj folder:") {
                    cell(projFolderField).align(AlignX.FILL)
                        .comment("The proj folder of the Axon server: proj/<connection>/<project>/func/<name>.axon. " +
                            "An Axon file under it uses the functions of its own project. The project copy reads it when no server URL is set. " +
                            "For all IDE projects; not synced.")
                }
                row("") { cell(projFolderCount) }
            }
            group("Project") {
                row("Instance/project:") {
                    cell(keyField).align(AlignX.FILL)
                        .comment("The SkySpark project to copy, as instance/project. Or pick it from the status bar entry “Axon”. " +
                            "Empty drops the copy. Stored in the workspace file, which is not shared.")
                }
            }
        }
    }

    private fun keyText(): String = keyField.text.trim()

    override fun isModified(): Boolean = hasUi && (
        urlField.text.trim().trimEnd('/') != AxonServerSettings.getInstance().url ||
            tokenField.password.isNotEmpty() || removeTokenBox.isSelected ||
            projFolderField.text.trim() != AxonServerSettings.getInstance().projFolder ||
            keyText() != settings.activeKey?.toString().orEmpty()
        )

    override fun reset() {
        if (!hasUi) return
        urlField.text = AxonServerSettings.getInstance().url
        tokenField.text = ""
        removeTokenBox.isSelected = false
        projFolderField.text = AxonServerSettings.getInstance().projFolder
        keyField.text = settings.activeKey?.toString().orEmpty()
        testResult.text = ""
        testResult.icon = null
        refreshTokenState()
        countProjFolder()
    }

    /** Counts the connections and projects in the folder of the field, off the UI thread. */
    internal fun countProjFolder() {
        val path = projFolderField.text.trim()
        val run = ++countRun
        if (path.isEmpty()) { projFolderCount.text = "Not set."; projFolderCount.icon = null; return }
        projFolderCount.text = "Counting projects\u2026"
        projFolderCount.icon = null
        val app = ApplicationManager.getApplication()
        val job = Runnable {
            val n = runCatching { AxonProjFolders.count(File(AxonProjFolders.normalize(path))) { if (run != countRun) throw CancellationException() } }
            if (n.exceptionOrNull() is CancellationException) return@Runnable
            app.invokeLater({ if (run == countRun && hasUi) showCount(n.getOrNull()) }, ModalityState.any())
        }
        if (app.isUnitTestMode) job.run() else app.executeOnPooledThread(job)
    }

    private fun showCount(n: AxonProjFolders.Companion.Count?) {
        when {
            n == null -> { projFolderCount.text = "The folder does not exist."; projFolderCount.icon = AllIcons.General.Warning }
            n.projects == 0 -> { projFolderCount.text = "No project found: no <connection>/<project>/.sync-metadata.json."; projFolderCount.icon = AllIcons.General.Warning }
            else -> {
                projFolderCount.text = "${n.connections} connection${if (n.connections == 1) "" else "s"}, ${n.projects} project${if (n.projects == 1) "" else "s"}."
                projFolderCount.icon = AllIcons.General.InspectionsOK
            }
        }
    }

    /** Reads whether a key is stored, off the UI thread (the password store can be slow). */
    internal fun refreshTokenState() {
        showTokenState()
        val app = ApplicationManager.getApplication()
        if (app.isUnitTestMode) {
            tokenStored = AxonServerToken.get() != null
            showTokenState()
            return
        }
        app.executeOnPooledThread {
            val stored = AxonServerToken.get() != null
            app.invokeLater({ tokenStored = stored; if (hasUi) showTokenState() }, ModalityState.any())
        }
    }

    internal fun showTokenState() {
        when (tokenStored) {
            null -> { tokenState.text = "Checking the password store…"; tokenState.icon = null }
            true -> if (AxonServerToken.memoryOnlyHint() == true) {
                tokenState.text = MEMORY_ONLY_TEXT
                tokenState.icon = AllIcons.General.Warning
            } else {
                tokenState.text = "An access key is stored. Type a new one to replace it."
                tokenState.icon = AllIcons.General.InspectionsOK
            }
            false -> { tokenState.text = "No access key is stored."; tokenState.icon = AllIcons.General.Warning }
        }
    }

    /** Tests the URL and the key in the fields; the stored key when the field is empty. */
    private fun runTest() {
        val url = urlField.text.trim().trimEnd('/')
        if (url.isEmpty()) { showResult(false, "Enter the server URL first."); return }
        if (!(url.startsWith("http://") || url.startsWith("https://"))) { showResult(false, "The URL must start with http:// or https://."); return }
        val typed = String(tokenField.password).trim()
        val run = ++testRun
        testButton.isEnabled = false
        testResult.icon = AllIcons.Process.Step_1
        testResult.text = "Testing…"
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val token = typed.ifEmpty { AxonServerToken.get() }
            val (ok, text) = testConnection(url, token, typedKey = typed.isNotEmpty())
            app.invokeLater({
                if (run != testRun || !hasUi) return@invokeLater
                testButton.isEnabled = true
                showResult(ok, text)
            }, ModalityState.any())
        }
    }

    private fun showResult(ok: Boolean, text: String) {
        testResult.icon = if (ok) AllIcons.General.InspectionsOK else AllIcons.General.Error
        testResult.text = text
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
        val folder = projFolderField.text.trim()
        val folderChanged = folder != server.projFolder
        val sourceChanged = url != server.url || (url.isEmpty() && folderChanged)
        server.url = url
        server.projFolder = folder
        if (folderChanged) AxonProjFolders.getInstance().settingChanged()
        // the password store can be slow and can ask for the keychain: not on the UI thread
        val typed = String(tokenField.password)
        val remove = removeTokenBox.isSelected
        val tokenChanged = typed.isNotEmpty() || remove
        tokenField.text = ""
        removeTokenBox.isSelected = false
        // show the new state at once; the store write below confirms it
        if (tokenChanged) { tokenStored = typed.isNotEmpty(); showTokenState() }

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

    companion object {
        /** Shown when the password store keeps the key in memory only (no keychain on this host). */
        internal const val MEMORY_ONLY_TEXT =
            "The access key is kept in memory only and is lost when the IDE closes: this host has no keychain. " +
                "Use KeePass in Settings | Appearance & Behavior | System Settings | Passwords to keep it."

        /**
         * One call to the server with [token]. Returns ok and a line for the page. Blocks: call
         * it off the UI thread. [typedKey] says whether the key came from the field (not yet
         * saved) or from the password store, so the text can say which one was refused.
         */
        internal fun testConnection(url: String, token: String?, typedKey: Boolean): Pair<Boolean, String> {
            if (token.isNullOrBlank()) return false to "No access key: type one, or save one first."
            return try {
                val n = AxonServerClient(url, token, connectTimeoutMs = 8_000, readTimeoutMs = 20_000).checkConnection()
                val which = if (typedKey) "The typed key" else "The stored key"
                true to "$which works: the server lists $n project${if (n == 1) "" else "s"}."
            } catch (e: AxonCopyException) {
                val which = if (typedKey) "the typed key" else "the stored key"
                false to when (e.kind) {
                    AxonCopyException.Kind.UNAUTHORIZED -> "The server refused $which. Check it on the dashboard Users page."
                    else -> e.message ?: "The test failed."
                }
            } catch (e: Exception) {
                false to "The test failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }
}
