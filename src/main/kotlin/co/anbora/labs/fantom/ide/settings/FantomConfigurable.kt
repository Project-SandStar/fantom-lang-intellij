package co.anbora.labs.fantom.ide.settings

import co.anbora.labs.fantom.ide.imports.FantomUsingCandidates
import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomInstall
import co.anbora.labs.fantom.lang.library.FantomInstalls
import co.anbora.labs.fantom.lang.library.FantomSettings
import co.anbora.labs.fantom.lang.library.deps.FantomDependencies
import co.anbora.labs.fantom.lang.library.deps.FantomDependencyResolver
import co.anbora.labs.fantom.lang.pod.FantomPodStubActivity
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.RootsChangeRescanningInfo
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.io.File
import javax.swing.DefaultCellEditor
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JTable
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Settings | Languages & Frameworks | Fantom.
 *
 * ```
 * Roots
 *   Fantom root    [ ~/fantom   ] [Browse]
 *   Haxall root    [ ~/haxall   ] [Browse]
 *   SkySpark root  [ ~/skyspark ] [Browse]
 * Installs found                                    [+ Add folder]
 *   [x] SkySpark 4.0.7   Fantom 1.0.84 (auto) v   Haxall 4.0.7 (auto) v   sys.pod
 * Default build target  [ SkySpark 4.0.7 v ]
 * [x] Compile on save
 * Source roots for project pods                      [Add…] [Remove] [Rescan]
 *   ~/Code
 * Dependencies of this project
 *   Pod         Needs  Source                                   How
 *   haystack    3.0.28 Haxall 4.0.7 source                      auto
 *   bassgCommon 3.0+   bassgcommonv3 tag bassgCommon/4.0.6.2.2  nearest tag
 * ```
 *
 * A SkySpark row is also paired with a Haxall ([FantomInstalls.haxallFor]) whose sources
 * stand in for SkySpark's compiled pods. The dependencies table shows the picks of
 * [FantomDependencies] for the default target; its Source dropdown pins another source.
 *
 * Every install directly under the three roots, plus the custom folders, is a row
 * ([FantomInstalls.scan]). Each Haxall and SkySpark row is paired with a Fantom install
 * ([FantomInstalls.pair]); its dropdown picks another. Ticked rows feed the index.
 *
 * Before the first Apply the rows come from the old single-home settings
 * ([FantomSettings.migrate]): that home is the one ticked row, and the page counts as
 * unmodified, so opening it changes nothing. On an Apply that changes the indexed pods,
 * the roots change event is fired so
 * [co.anbora.labs.fantom.lang.library.FantomLibraryRootsProvider] is asked again.
 */
class FantomConfigurable(private val project: Project) : BoundConfigurable("Fantom") {

    /** Built in [createPanel]; read them only after `createComponent()`. */
    internal lateinit var fantomRootField: TextFieldWithBrowseButton
    internal lateinit var haxallRootField: TextFieldWithBrowseButton
    internal lateinit var skysparkRootField: TextFieldWithBrowseButton
    internal lateinit var table: JBTable
    internal lateinit var defaultTargetBox: ComboBox<FantomInstall>
    internal lateinit var compileOnSaveBox: JBCheckBox
    internal lateinit var depsTable: JBTable

    /** The "Source roots for project pods" list. */
    internal val sourceRootsModel = DefaultListModel<String>()

    /** Pins as they would be stored: pod to option id. */
    internal val pins = LinkedHashMap<String, String>()
    internal val depsModel = DepsTableModel()
    private var rescanRequested = false

    internal val model = InstallTableModel()

    /** What the last [reset] showed, to tell whether the page is modified. */
    private var shown: Snapshot? = null
    private var found: List<FantomInstalls.Found> = emptyList()
    private val choices = LinkedHashMap<String, FantomInstalls.Choice>()
    private val customFolders = ArrayList<File>()
    private var defaultPath: String? = null
    private var refreshing = false

    private val settings: FantomSettings get() = FantomSettings.getInstance(project)

    private data class Snapshot(
        val roots: List<String>, val rows: List<FantomInstall>, val defaultTarget: String?, val compileOnSave: Boolean,
        val sourceRoots: List<String>, val pins: Map<String, String>,
    )

    override fun createPanel(): DialogPanel {
        fantomRootField = rootField("Fantom Root")
        haxallRootField = rootField("Haxall Root")
        skysparkRootField = rootField("SkySpark Root")
        table = JBTable(model).apply {
            setShowGrid(false)
            rowSelectionAllowed = true
            columnModel.getColumn(ENABLED).maxWidth = JBUI.scale(30)
            columnModel.getColumn(FANTOM).cellRenderer = FantomCellRenderer()
            columnModel.getColumn(FANTOM).cellEditor = DefaultCellEditor(fantomCombo())
            columnModel.getColumn(HAXALL).cellRenderer = HaxallCellRenderer()
            columnModel.getColumn(HAXALL).cellEditor = DefaultCellEditor(fantomCombo())
            visibleRowCount = 8
        }
        depsTable = JBTable(depsModel).apply {
            setShowGrid(false)
            columnModel.getColumn(DEP_SOURCE).cellEditor = DepSourceEditor()
            columnModel.getColumn(DEP_SOURCE).cellRenderer = DepSourceRenderer()
            visibleRowCount = 6
        }
        project.messageBus.connect(disposable!!).subscribe(FantomDependencies.TOPIC, FantomDependencies.Listener { snap ->
            ApplicationManager.getApplication().invokeLater { depsModel.setRows(snap.rows) }
        })
        depsModel.setRows(FantomDependencies.getInstance(project).snapshot.rows)
        val sourceRootsList = JBList(sourceRootsModel).apply { visibleRowCount = 3 }
        defaultTargetBox = ComboBox<FantomInstall>().apply {
            renderer = FantomTextRenderer<FantomInstall> { it.name }
            addActionListener { if (!refreshing) defaultPath = (selectedItem as? FantomInstall)?.path }
        }
        reset()
        return panel {
            group("Roots") {
                row("Fantom root:") { cell(fantomRootField).align(AlignX.FILL) }
                row("Haxall root:") { cell(haxallRootField).align(AlignX.FILL) }
                row("SkySpark root:") { cell(skysparkRootField).align(AlignX.FILL) }
            }
            row("Installs found") {
                button("Add Folder…") { chooseFolder() }.align(AlignX.RIGHT)
            }
            row {
                cell(JBScrollPane(table)).align(Align.FILL)
                    .comment("Ticked installs feed the index. Haxall and SkySpark are paired with the Fantom install " +
                        "that supplies sys and the compiler; pick another in the Fantom column.")
            }.resizableRow()
            row("Default build target:") {
                cell(defaultTargetBox).align(AlignX.FILL)
                    .comment("The install run configurations, tests and compile-on-save use.")
            }
            row {
                compileOnSaveBox = checkBox("Compile on save")
                    .comment("Runs bin/fan build.fan compile into a temp folder; it never writes into an install.")
                    .component
                compileOnSaveBox.isSelected = settings.compileOnSave
            }
            row("Source roots for project pods") {
                button("Add…") { chooseSourceRoot() }
                button("Remove") { sourceRootsList.selectedIndices.reversed().forEach { sourceRootsModel.remove(it) } }
                button("Rescan") { rescanRequested = true; FantomDependencies.getInstance(project).refresh(rescan = true) }
            }
            row {
                cell(JBScrollPane(sourceRootsList)).align(AlignX.FILL)
                    .comment("Folders searched for pods your project depends on (only build.fan and git data are read). " +
                        "Copies of one repository are grouped by their git remote; a tag matching the build target is used.")
            }
            row("Dependencies of this project") {}
            row {
                cell(JBScrollPane(depsTable)).align(Align.FILL)
                    .comment("From the project's build.fan depends, for the default build target. Order: this project, " +
                        "install and Haxall source, tagged project pods, stubs. Pick a Source to pin it.")
            }.resizableRow()
        }
    }

    private fun chooseSourceRoot() {
        val vf = FileChooser.chooseFile(
            FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Add Source Root for Project Pods"), project, null,
        ) ?: return
        val path = VfsUtilCore.virtualToIoFile(vf).path
        if (!sourceRootsModel.contains(path)) sourceRootsModel.addElement(path)
    }

    internal fun currentSourceRoots(): List<String> = (0 until sourceRootsModel.size).map { sourceRootsModel[it] }

    /** Pins [pod] to the option [id]; "auto" removes the pin. */
    internal fun pin(pod: String, id: String) {
        if (id == FantomDependencyResolver.AUTO_ID) pins.remove(pod) else pins[pod] = id
        depsModel.fireTableDataChanged()
    }

    private fun rootField(title: String) = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle(title))
        textField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = rescan()
            override fun removeUpdate(e: DocumentEvent) = rescan()
            override fun changedUpdate(e: DocumentEvent) = rescan()
        })
    }

    private fun fantomCombo() = ComboBox<FantomOption>().apply {
        renderer = FantomTextRenderer<FantomOption> { it.label }
    }

    /** One choice of the Fantom column: a Fantom install, or "(auto)" when [path] is null. */
    data class FantomOption(val path: String?, val label: String) {
        override fun toString() = label
    }

    private val hasUi: Boolean get() = ::fantomRootField.isInitialized

    private fun rootTexts(): List<String> = if (!hasUi) emptyList()
        else listOf(fantomRootField, haxallRootField, skysparkRootField).map { it.text.trim() }

    /** Scans the roots again, keeping the ticks and choices made so far. */
    internal fun rescan() {
        if (!hasUi || refreshing) return
        found = FantomInstalls.scan(rootTexts().filter { it.isNotEmpty() }.map(::File), customFolders)
        refresh()
    }

    /** Pairs [found] with [choices] and shows the result. */
    private fun refresh() {
        refreshing = true
        try {
            model.setRows(FantomInstalls.pair(found, choices))
            val combo = (table.columnModel.getColumn(FANTOM).cellEditor as DefaultCellEditor).component as ComboBox<*>
            @Suppress("UNCHECKED_CAST")
            (combo as ComboBox<FantomOption>).model = DefaultComboBoxModel(
                (listOf(FantomOption(null, "(auto)")) + FantomInstalls.fantomChoices(model.rows).map { FantomOption(it.path, it.name) })
                    .toTypedArray()
            )
            val hxCombo = (table.columnModel.getColumn(HAXALL).cellEditor as DefaultCellEditor).component as ComboBox<*>
            @Suppress("UNCHECKED_CAST")
            (hxCombo as ComboBox<FantomOption>).model = DefaultComboBoxModel(
                (listOf(FantomOption(null, "(auto)")) + FantomInstalls.haxallChoices(model.rows).map { FantomOption(it.path, it.name) })
                    .toTypedArray()
            )
            val targets = model.rows.filter { it.enabled }
            defaultTargetBox.model = DefaultComboBoxModel(targets.toTypedArray())
            defaultTargetBox.selectedItem = targets.firstOrNull { it.path == defaultPath } ?: targets.firstOrNull()
        } finally {
            refreshing = false
        }
    }

    /** The rows as they would be stored. */
    internal fun currentRows(): List<FantomInstall> = model.rows

    /** The default target as it would be stored: the chosen one if ticked, else the first ticked row. */
    internal fun currentDefault(): String? {
        val ticked = model.rows.filter { it.enabled }
        return ticked.firstOrNull { it.path == defaultPath }?.path ?: ticked.firstOrNull()?.path
    }

    internal fun setEnabled(path: String, enabled: Boolean) {
        val c = choices[path] ?: FantomInstalls.Choice(false, null, false)
        choices[path] = c.copy(enabled = enabled)
        refresh()
    }

    /** Pairs the SkySpark row at [path] with the Haxall at [haxallPath]; null goes back to the automatic match. */
    internal fun chooseHaxall(path: String, haxallPath: String?) {
        val c = choices[path] ?: FantomInstalls.Choice(false, null, false)
        choices[path] = c.copy(chosenHaxall = haxallPath)
        refresh()
    }

    /** Pairs the row at [path] with the Fantom at [fantomPath]; null goes back to the automatic match. */
    internal fun choose(path: String, fantomPath: String?) {
        val c = choices[path] ?: FantomInstalls.Choice(false, null, false)
        choices[path] = c.copy(chosenFantom = fantomPath)
        refresh()
    }

    internal fun selectDefault(path: String) {
        defaultPath = path
        refresh()
    }

    private fun chooseFolder() {
        val vf = FileChooser.chooseFile(
            FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Add Fantom, Haxall or SkySpark Folder"),
            project, null,
        ) ?: return
        addFolder(VfsUtilCore.virtualToIoFile(vf))?.let { Messages.showErrorDialog(project, it, "Add Folder") }
    }

    /** Adds [dir] as a custom, ticked row. Returns an error text when it is not an install. */
    internal fun addFolder(dir: File): String? {
        if (FantomHome.kindOf(dir) == null) return NOT_A_HOME
        if (customFolders.none { it.path == dir.path }) customFolders += dir
        choices[dir.path] = (choices[dir.path] ?: FantomInstalls.Choice(true, null, true)).copy(enabled = true, custom = true)
        rescan()
        return null
    }

    private fun snapshot() = Snapshot(rootTexts(), currentRows(), currentDefault(), compileOnSaveBox.isSelected,
        currentSourceRoots(), LinkedHashMap(pins))

    override fun isModified(): Boolean = hasUi && snapshot() != shown

    override fun reset() {
        if (!hasUi) return
        val persisted = settings.installs
        val migration = if (persisted.isEmpty()) settings.migrate() else null
        refreshing = true
        try {
            fantomRootField.text = settings.fantomRoot
            haxallRootField.text = settings.haxallRoot
            skysparkRootField.text = settings.skysparkRoot
        } finally {
            refreshing = false
        }
        choices.clear()
        choices.putAll(FantomInstalls.choices(persisted, migration))
        customFolders.clear()
        customFolders.addAll(FantomInstalls.customFolders(persisted, migration))
        defaultPath = settings.defaultTarget ?: migration?.defaultTarget
        if (::compileOnSaveBox.isInitialized) compileOnSaveBox.isSelected = settings.compileOnSave
        sourceRootsModel.clear()
        settings.sourceRoots.forEach(sourceRootsModel::addElement)
        pins.clear()
        pins.putAll(settings.pins)
        rescan()
        shown = if (::compileOnSaveBox.isInitialized) snapshot() else null
    }

    override fun apply() {
        if (!hasUi) return
        val before = indexKey()
        val (fantom, haxall, skyspark) = rootTexts()
        settings.fantomRoot = fantom
        settings.haxallRoot = haxall
        settings.skysparkRoot = skyspark
        settings.installs = currentRows()
        settings.defaultTarget = currentDefault()
        settings.compileOnSave = compileOnSaveBox.isSelected
        val rootsChanged = settings.sourceRoots != currentSourceRoots().ifEmpty { listOf(FantomSettings.DEFAULT_SOURCE_ROOT) }
        settings.sourceRoots = currentSourceRoots()
        settings.pins = pins
        shown = snapshot()
        FantomSettings.clearPodCache()
        // targets, Haxall pairing, roots and pins all change the dependency picks
        FantomDependencies.getInstance(project).refresh(rescan = rootsChanged || rescanRequested)
        rescanRequested = false
        // the status bar entry shows only with a ticked install: re-check it now
        project.service<StatusBarWidgetsManager>().updateWidget(FantomTargetWidgetFactory::class.java)
        if (indexKey() == before) return
        FantomUsingCandidates.reset()
        WriteAction.runAndWait<RuntimeException> {
            ProjectRootManagerEx.getInstanceEx(project)
                .makeRootsChange(EmptyRunnable.getInstance(), RootsChangeRescanningInfo.TOTAL_RESCAN)
        }
        FantomPodStubActivity.schedule(project)
    }

    /** What the index depends on: the pod folders and the runner. */
    private fun indexKey(): List<String> =
        settings.podSources().map { it.podDir.path } +
            listOf(settings.effectiveHome()?.path ?: "", settings.podsNeedingStubs().size.toString())

    /** The install table: tick, name, paired Fantom, source of the match. */
    inner class InstallTableModel : AbstractTableModel() {
        var rows: List<FantomInstall> = emptyList()
            private set

        fun setRows(value: List<FantomInstall>) {
            rows = value
            fireTableDataChanged()
        }

        override fun getRowCount() = rows.size
        override fun getColumnCount() = 5
        override fun getColumnName(column: Int) = when (column) {
            ENABLED -> ""
            NAME -> "Install"
            FANTOM -> "Fantom"
            HAXALL -> "Haxall"
            else -> "Match"
        }

        override fun getColumnClass(column: Int): Class<*> =
            if (column == ENABLED) java.lang.Boolean::class.java else String::class.java

        override fun isCellEditable(row: Int, column: Int) =
            column == ENABLED || (column == FANTOM && rows[row].kind != FantomHome.Kind.FANTOM) ||
                (column == HAXALL && rows[row].kind == FantomHome.Kind.SKYSPARK)

        override fun getValueAt(row: Int, column: Int): Any {
            val r = rows[row]
            return when (column) {
                ENABLED -> r.enabled
                NAME -> r.name + if (r.custom) "   (custom folder)" else ""
                FANTOM -> fantomText(r)
                HAXALL -> haxallText(r)
                else -> r.matchSource
            }
        }

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            val r = rows.getOrNull(row) ?: return
            when (column) {
                ENABLED -> setEnabled(r.path, value == true)
                FANTOM -> if (value is FantomOption) choose(r.path, value.path)
                HAXALL -> if (value is FantomOption) chooseHaxall(r.path, value.path)
            }
        }
    }

    /** "(itself)", "Fantom 1.0.84 (auto)", "Fantom 1.0.84" or the warning. */
    private fun fantomText(r: FantomInstall): String = when (r.pairing) {
        FantomInstall.Pairing.SELF -> "(itself)"
        FantomInstall.Pairing.AUTO -> "Fantom ${r.fantomVersion} (auto)"
        FantomInstall.Pairing.CHOSEN -> r.fantomPath?.let { p ->
            model.rows.firstOrNull { it.path == p }?.name ?: File(p).name
        } ?: ""
        FantomInstall.Pairing.MISSING -> r.warning ?: ""
    }

    /** "", "none", "Haxall 4.0.7 (auto)", "Haxall 4.0.4", or "Haxall 4.0.7 (auto; no Haxall 4.0.8; ...)". */
    internal fun haxallText(r: FantomInstall): String {
        if (r.kind != FantomHome.Kind.SKYSPARK) return ""
        val p = r.haxallPath ?: return "none"
        val name = model.rows.firstOrNull { it.path == p }?.name ?: FantomHome.displayName(File(p))
        return when {
            r.haxallChosen -> name
            r.haxallWarning != null -> "$name (auto; ${r.haxallWarning})"
            else -> "$name (auto)"
        }
    }

    private inner class HaxallCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val r = model.rows.getOrNull(table.convertRowIndexToModel(row))
            icon = if (r?.haxallWarning != null && !r.haxallChosen) AllIcons.General.Warning else null
            return c
        }
    }

    /** The dependencies table: Pod, Needs, Source (a dropdown to pin), How. */
    inner class DepsTableModel : AbstractTableModel() {
        var rows: List<FantomDependencyResolver.Row> = emptyList()
            private set

        fun setRows(value: List<FantomDependencyResolver.Row>) {
            rows = value
            fireTableDataChanged()
        }

        override fun getRowCount() = rows.size
        override fun getColumnCount() = 4
        override fun getColumnName(column: Int) = when (column) {
            DEP_POD -> "Pod"
            DEP_NEEDS -> "Needs"
            DEP_SOURCE -> "Source"
            else -> "How"
        }

        override fun isCellEditable(row: Int, column: Int) = column == DEP_SOURCE && rows[row].options.size > 1

        override fun getValueAt(row: Int, column: Int): Any {
            val r = rows[row]
            val pinned = pins[r.pod]
            return when (column) {
                DEP_POD -> r.pod
                DEP_NEEDS -> r.needs
                DEP_SOURCE -> if (pinned != null && r.how != FantomDependencyResolver.How.PINNED)
                    (r.options.firstOrNull { it.id == pinned }?.label ?: pinned) + "  (pinned on Apply)"
                else r.source.ifEmpty { "-" }
                else -> r.how.label + (r.warning?.let { " ($it)" } ?: "")
            }
        }

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            val r = rows.getOrNull(row) ?: return
            if (column == DEP_SOURCE && value is FantomDependencyResolver.Option) pin(r.pod, value.id)
        }
    }

    private inner class DepSourceEditor : DefaultCellEditor(ComboBox<FantomDependencyResolver.Option>()) {
        @Suppress("UNCHECKED_CAST")
        private val box get() = component as ComboBox<FantomDependencyResolver.Option>

        init {
            box.renderer = FantomTextRenderer<FantomDependencyResolver.Option> { it.label }
        }

        override fun getTableCellEditorComponent(table: JTable, value: Any?, isSelected: Boolean, row: Int, column: Int): Component {
            val r = depsModel.rows[table.convertRowIndexToModel(row)]
            box.model = DefaultComboBoxModel(r.options.toTypedArray())
            box.selectedItem = r.options.firstOrNull { it.id == (pins[r.pod] ?: FantomDependencyResolver.AUTO_ID) }
            return box
        }

        override fun getCellEditorValue(): Any? = box.selectedItem
    }

    private inner class DepSourceRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val r = depsModel.rows.getOrNull(table.convertRowIndexToModel(row))
            icon = when (r?.how) {
                FantomDependencyResolver.How.NOT_FOUND -> AllIcons.General.Error
                FantomDependencyResolver.How.NEAREST_TAG -> AllIcons.General.Warning
                else -> if (r?.warning != null) AllIcons.General.Warning else null
            }
            return c
        }
    }

    private inner class FantomCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val r = model.rows.getOrNull(table.convertRowIndexToModel(row))
            icon = if (r?.pairing == FantomInstall.Pairing.MISSING) AllIcons.General.Warning else null
            return c
        }
    }

    companion object {
        const val ID = "co.anbora.labs.fantom.settings"
        const val NOT_A_HOME = "Not an install. Fantom needs bin/fan and src/sys/fan; " +
            "Haxall needs src/core; SkySpark needs lib/fan/skyarcd.pod and bin/fan"
        const val ENABLED = 0
        const val NAME = 1
        const val FANTOM = 2
        const val HAXALL = 3
        const val MATCH = 4
        const val DEP_POD = 0
        const val DEP_NEEDS = 1
        const val DEP_SOURCE = 2
        const val DEP_HOW = 3
    }
}
