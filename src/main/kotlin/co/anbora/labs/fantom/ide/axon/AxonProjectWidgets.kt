package co.anbora.labs.fantom.ide.axon

import co.anbora.labs.fantom.lang.axon.copy.AxonProjectCopy
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectInfo
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectSettings
import co.anbora.labs.fantom.lang.axon.copy.AxonServerSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import co.anbora.labs.fantom.lang.axon.proj.AxonProjFolders
import co.anbora.labs.fantom.lang.axon.AxonFileType
import co.anbora.labs.fantom.lang.trio.TrioFileType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.vfs.VirtualFile
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

    /**
     * Where the file in the editor is in a `proj` folder, or null when there is no such file
     * or it is outside any `proj` layout. Memory and VFS only.
     */
    fun editorPlace(project: Project): AxonProjFolders.Place? {
        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return null
        // only Axon and trio files: any other file never walks up its folders on the UI thread
        if (file.fileType != AxonFileType && file.fileType != TrioFileType) return null
        return AxonProjFolders.getInstance().placeOf(file).takeIf { it != AxonProjFolders.Place.Outside }
    }

    /**
     * "Axon: demo/site": the project of the file in the editor when it is in a `proj` folder,
     * else the chosen project copy, or "Axon: none".
     */
    fun text(project: Project): String = when (val place = editorPlace(project)) {
        is AxonProjFolders.Place.InProject -> "Axon: " + AxonProjFolders.getInstance().keyOf(place.folder)
        AxonProjFolders.Place.Copy -> "Axon: no project"
        else -> "Axon: " + (AxonProjectCopy.getInstance(project).activeKey?.toString() ?: "none")
    }

    fun tooltip(project: Project): String {
        when (val place = editorPlace(project)) {
            is AxonProjFolders.Place.InProject -> return "This file is in the Axon project ${AxonProjFolders.getInstance().keyOf(place.folder)} " +
                "(folder ${place.folder.name}): its names resolve to the functions of this project."
            AxonProjFolders.Place.Copy -> return "This file is in a backup or version folder: it has no Axon project."
            else -> {}
        }
        val copy = AxonProjectCopy.getInstance(project)
        val key = copy.activeKey ?: return "No Axon project copy. Click to pick a SkySpark project."
        val c = copy.copy ?: return "Axon project $key: no local copy. Click and choose Refresh."
        val n = c.functions.size
        val from = if (c.source == "folder") "the global proj folder" else "the Axon server"
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
        dialog.rememberValue?.let { settings.pickerConnection = it }
        if (ok) dialog.chosen?.let { copy.select(it.key) }
    }
}

/**
 * Shows [AxonProjectWidget] when an Axon source (a server URL or a global proj folder) is
 * set, a project is chosen, or the IDE project folder is in a `proj` layout.
 */
class AxonProjectWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = AxonProjectWidget.ID
    override fun getDisplayName(): String = "Axon Project"
    override fun isAvailable(project: Project): Boolean {
        val copy = AxonProjectCopy.getInstance(project)
        if (copy.isConfigured() || copy.activeKey != null) return true
        if (AxonServerSettings.getInstance().projFolder.isNotEmpty()) return true
        val dir = project.guessProjectDir() ?: return false
        return AxonProjFolders.getInstance().placeOf(dir) != AxonProjFolders.Place.Outside || hasProjectFolderBelow(dir)
    }

    /**
     * The IDE project is a whole proj folder or one connection: a project folder is one or two
     * levels down. VFS only, and at most [MAX_LOOK] folders are looked at, so a large IDE
     * project does not list its whole tree.
     */
    private fun hasProjectFolderBelow(dir: VirtualFile): Boolean {
        var looked = 0
        for (c in dir.children) {
            if (!c.isDirectory || c.name.startsWith(".")) continue
            if (++looked > MAX_LOOK) return false
            if (c.findChild(AxonProjFolders.METADATA) != null) return true
            for (g in c.children) {
                if (!g.isDirectory || g.name.startsWith(".")) continue
                if (++looked > MAX_LOOK) return false
                if (g.findChild(AxonProjFolders.METADATA) != null) return true
            }
        }
        return false
    }

    private companion object {
        const val MAX_LOOK = 200
    }

    override fun createWidget(project: Project): StatusBarWidget = AxonProjectWidget(project)
}
