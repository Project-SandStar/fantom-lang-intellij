package co.anbora.labs.fantom.ide.axon

import co.anbora.labs.fantom.ide.axon.AxonProjectPickerModel.Connection
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectInfo
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectKey
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent

/**
 * Picks an Axon project: a search field, the connections on the left and the projects of
 * the selected connection on the right. Works on the list it is given; no network, no disk.
 *
 * [active] is the current project, preselected. [remembered] is the connection stored by
 * the last picker ([AxonProjectPickerModel.remember]); [rememberValue] is the one to store now.
 */
class AxonProjectPickerDialog(
    project: Project?,
    private val model: AxonProjectPickerModel,
    private val active: AxonProjectKey?,
    remembered: String?,
) : DialogWrapper(project) {

    internal val searchField = PickerSearchField()
    private val connectionModel = CollectionListModel<Connection>()
    private val projectModel = CollectionListModel<AxonProjectInfo>()
    internal val connectionList = JBList(connectionModel)
    internal val projectList = JBList(projectModel)

    /** True while the lists are filled from code, so the selection listeners do nothing. */
    private var updating = false

    /** The connection the user selected last (null: "All connections"). A search does not change it. */
    private var userInstance: String? = model.initialInstance(active, remembered)

    /** The project picked with OK, Enter or a double-click. */
    var chosen: AxonProjectInfo? = null
        private set

    /** True once the user selected a connection; a search or the start selection does not count. */
    private var userChoseConnection = false

    /**
     * What to store as the last connection, or null to keep the stored one: only a
     * connection the user selected is stored, so opening and closing the window changes nothing.
     */
    val rememberValue: String? get() = if (userChoseConnection) AxonProjectPickerModel.remember(userInstance) else null

    private val search: String get() = searchField.text

    init {
        title = "Axon Project"
        connectionList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        projectList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        connectionList.cellRenderer = ConnectionRenderer()
        projectList.cellRenderer = ProjectRenderer()

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refilter()
        })
        searchField.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_UP -> { moveSelection(-1); e.consume() }
                    KeyEvent.VK_DOWN -> { moveSelection(1); e.consume() }
                    KeyEvent.VK_ENTER -> { pick(); e.consume() }
                }
            }
        })
        connectionList.addListSelectionListener { e ->
            if (updating || e.valueIsAdjusting) return@addListSelectionListener
            val c = connectionList.selectedValue ?: return@addListSelectionListener
            userInstance = c.instance
            userChoseConnection = true
            refreshProjects()
        }
        // a click on the row a search selected fires no selection event; it is still a choice
        connectionList.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) = connectionClicked()
        })
        projectList.addListSelectionListener { updateOk() }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                if (projectList.selectedValue == null) return false
                pick()
                return true
            }
        }.installOn(projectList)

        refilter()
        init()
    }

    override fun createCenterPanel(): JComponent {
        val splitter = OnePixelSplitter(false, "Fantom.AxonProjectPicker.split", 0.3f).apply {
            firstComponent = JBScrollPane(connectionList)
            secondComponent = JBScrollPane(projectList)
        }
        // the selection was made before the lists had a size; scroll to it once they are on screen
        scrollToSelectionWhenShown(connectionList)
        scrollToSelectionWhenShown(projectList)
        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(searchField, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
            preferredSize = JBUI.size(720, 460)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = searchField.textEditor

    override fun getDimensionServiceKey(): String = "Fantom.AxonProjectPicker"

    override fun doOKAction() {
        val info = projectList.selectedValue ?: return
        chosen = info
        super.doOKAction()
    }

    private fun scrollToSelectionWhenShown(list: JList<*>) {
        list.addHierarchyListener(object : HierarchyListener {
            override fun hierarchyChanged(e: HierarchyEvent) {
                if ((e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong()) == 0L || !list.isShowing) return
                list.removeHierarchyListener(this)
                SwingUtilities.invokeLater {
                    val i = list.selectedIndex
                    if (i >= 0) list.ensureIndexIsVisible(i)
                }
            }
        })
    }

    /** The user clicked the connection list: the selected row becomes the user's connection. */
    internal fun connectionClicked() {
        val c = connectionList.selectedValue ?: return
        userInstance = c.instance
        userChoseConnection = true
    }

    /** Moves the project selection by [delta] rows, within the list. */
    internal fun moveSelection(delta: Int) {
        val n = projectModel.size
        if (n == 0) return
        val i = projectList.selectedIndex
        val next = if (i < 0) (if (delta > 0) 0 else n - 1) else (i + delta).coerceIn(0, n - 1)
        projectList.selectedIndex = next
        projectList.ensureIndexIsVisible(next)
    }

    /** Picks the selected project, like OK. Does nothing with no selection. */
    internal fun pick() {
        if (projectList.selectedValue != null) doOKAction()
    }

    /** Applies the search to both panes. Keeps the user's connection when it still shows. */
    private fun refilter() {
        val connections = model.connections(search)
        updating = true
        try {
            connectionModel.replaceAll(connections)
            val i = connections.indexOfFirst { it.instance == userInstance }.takeIf { it >= 0 } ?: 0
            connectionList.selectedIndex = i
            connectionList.ensureIndexIsVisible(i)
        } finally {
            updating = false
        }
        refreshProjects()
    }

    /** Fills the right pane; keeps the selected project, else the active one, else the first. */
    private fun refreshProjects() {
        val previous = projectList.selectedValue?.key
        val list = model.projects(connectionList.selectedValue?.instance, search)
        projectModel.replaceAll(list)
        val i = list.indexOfFirst { it.key == previous }.takeIf { it >= 0 }
            ?: list.indexOfFirst { it.key == active }.takeIf { it >= 0 }
            ?: if (list.isEmpty()) -1 else 0
        if (i >= 0) {
            projectList.selectedIndex = i
            projectList.ensureIndexIsVisible(i)
        } else {
            projectList.clearSelection()
        }
        updateOk()
    }

    private fun updateOk() {
        isOKActionEnabled = projectList.selectedValue != null
    }

    /**
     * A search field whose Escape closes the window. The platform field clears its text on
     * Escape and keeps the window open, so the user had to press Escape twice.
     */
    internal class PickerSearchField : SearchTextField(false) {
        override fun toClearTextOnEscape(): Boolean = false

        /** What [toClearTextOnEscape] answers, for the tests. */
        internal val clearsTextOnEscape: Boolean get() = toClearTextOnEscape()
    }

    private inner class ConnectionRenderer : ColoredListCellRenderer<Connection>() {
        override fun customizeCellRenderer(list: JList<out Connection>, value: Connection?, index: Int, selected: Boolean, hasFocus: Boolean) {
            val c = value ?: return
            append(AxonProjectPickerModel.connectionName(c),
                if (c.isAll) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append("  " + AxonProjectPickerModel.connectionCount(c, search), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    private inner class ProjectRenderer : ColoredListCellRenderer<AxonProjectInfo>() {
        override fun customizeCellRenderer(list: JList<out AxonProjectInfo>, value: AxonProjectInfo?, index: Int, selected: Boolean, hasFocus: Boolean) {
            val info = value ?: return
            val all = connectionList.selectedValue?.isAll ?: true
            append(AxonProjectPickerModel.projectName(info, all))
            append("  " + AxonProjectPickerModel.projectDetail(info), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }
}
