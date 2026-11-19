package co.anbora.labs.fantom.ide.axon

import co.anbora.labs.fantom.lang.axon.copy.AxonProjectCopy
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectInfo
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

/** Text and popup of [AxonProjectWidget]. Everything here reads memory only. */
object AxonProjectWidgets {

    /** What the popup offers. */
    enum class Item(val label: String) {
        PICK("Pick Axon Project\u2026"),
        REFRESH("Refresh"),
        DROP("Drop Axon Copy"),
        SETTINGS("Axon Settings\u2026"),
    }

    /** "Axon: demo/site", or "Axon: none". */
    fun text(project: Project): String = "Axon: " + (AxonProjectCopy.getInstance(project).activeKey?.toString() ?: "none")

    fun tooltip(project: Project): String {
        val copy = AxonProjectCopy.getInstance(project)
        val key = copy.activeKey ?: return "No Axon project copy. Click to pick a SkySpark project."
        val c = copy.copy ?: return "Axon project $key: no local copy. Click and choose Refresh."
        val n = c.functions.size
        val from = if (c.source == "folder") "the fallback folder" else "the Axon server"
        return "Read-only copy of $key: $n function${if (n == 1) "" else "s"} from $from" +
            (c.syncedAt?.let { ", synced $it" } ?: "") + ". Click to pick, refresh or drop."
    }

    /** Pick needs a source; Refresh and Drop need a chosen project. */
    fun items(project: Project): List<Item> {
        val copy = AxonProjectCopy.getInstance(project)
        val out = ArrayList<Item>()
        if (copy.isConfigured()) out += Item.PICK
        if (copy.activeKey != null) {
            if (copy.isConfigured()) out += Item.REFRESH
            out += Item.DROP
        }
        out += Item.SETTINGS
        return out
    }

    fun popup(project: Project): JBPopup {
        val step = object : BaseListPopupStep<Item>("Axon Project Copy", items(project)) {
            override fun getTextFor(value: Item): String = value.label
            override fun onChosen(selectedValue: Item, finalChoice: Boolean): PopupStep<*>? {
                doFinalStep { run(project, selectedValue) }
                return FINAL_CHOICE
            }
        }
        return JBPopupFactory.getInstance().createListPopup(step)
    }

    fun run(project: Project, item: Item) {
        val copy = AxonProjectCopy.getInstance(project)
        when (item) {
            Item.PICK -> copy.listProjects { list -> choose(project, list) }
            Item.REFRESH -> copy.refresh()
            Item.DROP -> copy.drop()
            Item.SETTINGS -> ShowSettingsUtil.getInstance().showSettingsDialog(project, AxonConfigurable::class.java)
        }
    }

    /** Shows the picker; the project picked is fetched. Called on the UI thread, after the list came in. */
    private fun choose(project: Project, list: List<AxonProjectInfo>) {
        if (project.isDisposed) return
        if (list.isEmpty()) {
            NotificationGroupManager.getInstance().getNotificationGroup("Fantom")
                ?.createNotification("Axon project copy", "The source lists no project.", NotificationType.WARNING)
                ?.notify(project)
            return
        }
        val copy = AxonProjectCopy.getInstance(project)
        val settings = AxonProjectSettings.getInstance(project)
        val dialog = AxonProjectPickerDialog(project, AxonProjectPickerModel(list), copy.activeKey, settings.pickerConnection)
        val ok = dialog.showAndGet()
        if (project.isDisposed) return
        settings.pickerConnection = dialog.rememberValue
        if (ok) dialog.chosen?.let { copy.select(it.key) }
    }
}

/** Shows [AxonProjectWidget] when an Axon source is set or a project is chosen. */
class AxonProjectWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = AxonProjectWidget.ID
    override fun getDisplayName(): String = "Axon Project Copy"
    override fun isAvailable(project: Project): Boolean {
        val copy = AxonProjectCopy.getInstance(project)
        return copy.isConfigured() || copy.activeKey != null
    }
    override fun createWidget(project: Project): StatusBarWidget = AxonProjectWidget(project)
}
