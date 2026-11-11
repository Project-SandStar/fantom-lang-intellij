package co.anbora.labs.fantom.ide.settings

import co.anbora.labs.fantom.lang.FantomFileType
import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * After a project opens: when it has `.fan` files but no Fantom home can be found,
 * show one balloon that links to the Fantom settings page. Shown once per project.
 */
class FantomHomeStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val settings = FantomSettings.getInstance(project)
        if (settings.state.noHomeNoticeShown) return
        if (settings.effectiveHome() != null) return
        val hasFantomFiles = smartReadAction(project) {
            FileTypeIndex.containsFileOfType(FantomFileType, GlobalSearchScope.projectScope(project))
        }
        if (!hasFantomFiles) return
        val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID) ?: return
        settings.state.noHomeNoticeShown = true
        group.createNotification(
            "No Fantom installation found. Library navigation into sys is off.",
            NotificationType.WARNING
        ).addAction(NotificationAction.createSimpleExpiring("Configure") {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, FantomConfigurable::class.java)
        }).notify(project)
    }

    companion object {
        const val GROUP_ID = "Fantom"
    }
}
