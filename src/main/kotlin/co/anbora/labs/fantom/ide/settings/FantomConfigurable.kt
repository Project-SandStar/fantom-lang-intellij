package co.anbora.labs.fantom.ide.settings

import co.anbora.labs.fantom.ide.imports.FantomUsingCandidates
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
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.io.File
import javax.swing.JLabel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Settings | Languages & Frameworks | Fantom.
 *
 * - Fantom home: a Fantom, Haxall or SkySpark home ([FantomHome.kindOf]). Empty means
 *   "detect it" ([FantomHome.detect]).
 * - Fantom sources: a plain Fantom install that supplies `sys` and the core pods when
 *   the home is Haxall or SkySpark. Empty means the newest `~/fantom/fantom-*`.
 *
 * An invalid non-empty field makes [apply] throw and nothing is stored. On a successful
 * apply that changed a path, the roots change event is fired so
 * [co.anbora.labs.fantom.lang.library.FantomLibraryRootsProvider] is asked again.
 */
class FantomConfigurable(private val project: Project) : BoundConfigurable("Fantom") {

    /** Built in [createPanel]; read it only after `createComponent()`. */
    internal lateinit var homeField: TextFieldWithBrowseButton
    internal lateinit var sourcesField: TextFieldWithBrowseButton
    internal lateinit var compileOnSaveBox: JBCheckBox
    internal lateinit var homeLabel: JLabel
    internal lateinit var sourcesLabel: JLabel

    private val settings: FantomSettings get() = FantomSettings.getInstance(project)

    override fun createPanel(): DialogPanel {
        homeField = TextFieldWithBrowseButton()
        sourcesField = TextFieldWithBrowseButton()
        homeLabel = hintLabel()
        sourcesLabel = hintLabel()
        homeField.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Fantom Home")
                .withDescription("Fantom, Haxall or SkySpark home")
        )
        sourcesField.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Fantom Sources")
                .withDescription("Plain Fantom install with src/sys/fan")
        )
        onChange(homeField) { updateLabels() }
        onChange(sourcesField) { updateLabels() }
        updateLabels()
        return panel {
            row("Fantom home:") {
                cell(homeField)
                    .align(AlignX.FILL)
                    .validationOnInput { field -> validateHome(field.text)?.let { error(it) } }
                    .validationOnApply { field -> validateHome(field.text)?.let { error(it) } }
            }
            row("") { cell(homeLabel) }
            row("Fantom sources:") {
                cell(sourcesField)
                    .align(AlignX.FILL)
                    .comment("Optional. Supplies sys and the core pods when the home is Haxall or SkySpark.")
                    .validationOnInput { field -> validateSources(field.text)?.let { error(it) } }
                    .validationOnApply { field -> validateSources(field.text)?.let { error(it) } }
            }
            row("") { cell(sourcesLabel) }
            row {
                compileOnSaveBox = checkBox("Compile the pod on save and show compiler errors in the editor")
                    .comment("Runs bin/fan build.fan compile into a temp folder; the Fantom install is not changed.")
                    .component
                compileOnSaveBox.isSelected = settings.compileOnSave
            }
        }
    }

    private fun hintLabel() = JLabel().apply {
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        font = JBUI.Fonts.smallFont()
    }

    private fun onChange(field: TextFieldWithBrowseButton, block: () -> Unit) {
        field.textField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = block()
            override fun removeUpdate(e: DocumentEvent) = block()
            override fun changedUpdate(e: DocumentEvent) = block()
        })
    }

    /** The home the fields describe: the typed one if valid, else the detected one. */
    private fun shownHome(): File? {
        val text = homeField.text.trim()
        if (text.isNotEmpty()) return File(text).takeIf(FantomHome::isHome)
        return FantomHome.detect()
    }

    private fun updateLabels() {
        if (!::sourcesLabel.isInitialized) return
        val home = shownHome()
        val kind = home?.let(FantomHome::kindOf)
        homeLabel.text = when {
            home == null && homeField.text.isBlank() -> "Not found; set ${FantomHome.ENV} or pick a folder"
            home == null -> " "
            else -> {
                val prefix = if (homeField.text.isBlank()) "Detected: ${home.path} (${kind?.label})" else "${kind?.label} home"
                when (kind) {
                    FantomHome.Kind.SKYSPARK -> "$prefix. $SKYSPARK_HINT"
                    FantomHome.Kind.HAXALL ->
                        if (FantomHome.hasLauncher(home)) "$prefix. Its pods are indexed; sys comes from the Fantom sources."
                        else "$prefix. It has no bin/fan; builds and tests use the Fantom sources' bin/fan."
                    else -> prefix
                }
            }
        }
        val sourcesText = sourcesField.text.trim()
        sourcesLabel.text = when {
            kind == FantomHome.Kind.FANTOM -> "Not used: the Fantom home has its own sources."
            sourcesText.isNotEmpty() -> " "
            else -> FantomHome.detectSources()?.let { "Detected: ${it.path}" }
                ?: "Not found; pick a Fantom install (fantom-1.0.x with src/sys)"
        }
    }

    private fun validateHome(text: String): String? {
        if (text.isBlank()) return null
        return if (FantomHome.isHome(File(text.trim()))) null else NOT_A_HOME
    }

    private fun validateSources(text: String): String? {
        if (text.isBlank()) return null
        return if (FantomHome.isSources(File(text.trim()))) null else NOT_SOURCES
    }

    private val hasUi: Boolean get() = ::homeField.isInitialized

    override fun isModified(): Boolean = hasUi &&
        (homeField.text.trim() != (settings.homePath ?: "") ||
            sourcesField.text.trim() != (settings.sourcesPath ?: "") ||
            compileOnSaveBox.isSelected != settings.compileOnSave)

    override fun reset() {
        if (!hasUi) return
        homeField.text = settings.homePath ?: ""
        sourcesField.text = settings.sourcesPath ?: ""
        compileOnSaveBox.isSelected = settings.compileOnSave
        updateLabels()
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        if (!hasUi) return
        val home = homeField.text.trim()
        val sources = sourcesField.text.trim()
        validateHome(home)?.let { throw ConfigurationException(it) }
        validateSources(sources)?.let { throw ConfigurationException(it) }
        settings.compileOnSave = compileOnSaveBox.isSelected
        if (home == (settings.homePath ?: "") && sources == (settings.sourcesPath ?: "")) return
        settings.homePath = home.ifBlank { null }
        settings.sourcesPath = sources.ifBlank { null }
        FantomSettings.clearPodCache()
        FantomUsingCandidates.reset()
        WriteAction.runAndWait<RuntimeException> {
            ProjectRootManagerEx.getInstanceEx(project)
                .makeRootsChange(EmptyRunnable.getInstance(), RootsChangeRescanningInfo.TOTAL_RESCAN)
        }
    }

    companion object {
        const val ID = "co.anbora.labs.fantom.settings"
        const val NOT_A_HOME = "Not a home. Fantom needs bin/fan and src/sys/fan; " +
            "Haxall needs src/core; SkySpark needs lib/fan/skyarcd.pod and bin/fan"
        const val NOT_SOURCES = "Not a Fantom install: needs src/sys/fan"
        const val SKYSPARK_HINT = "SkySpark ships compiled pods only; pod browsing comes in a later version. " +
            "Only the Fantom sources are indexed; run configurations and the compiler use its bin/fan."
    }
}
