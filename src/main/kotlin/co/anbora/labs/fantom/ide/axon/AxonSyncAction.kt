package co.anbora.labs.fantom.ide.axon

import co.anbora.labs.fantom.lang.axon.copy.AxonProjectCopy
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectLibraryRootsProvider.AxonLibrary
import co.anbora.labs.fantom.lang.axon.copy.AxonServerSettings
import co.anbora.labs.fantom.lang.axon.copy.AxonServerToken
import co.anbora.labs.fantom.lang.axon.copy.AxonSyncTarget
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * "Sync Axon Functions" (task 4.39): the Axon server reads the active project from
 * SkySpark (`sync=true`) and the plugin writes its copy again, in the background. A
 * notification lists the functions added, changed and removed.
 *
 * In the Tools menu it is always there. In the Project view it shows on the project root,
 * on the `Axon: <instance>/<project>` library node (and its folder), and on a file that an
 * [AxonSyncTarget] claims. It is disabled with no server URL, no access key or no active
 * project; the description says why.
 */
class AxonSyncAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val p = e.presentation
        val project = e.project
        if (project == null) {
            p.isEnabledAndVisible = false
            return
        }
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val match = AxonSyncTarget.find(project, file)
        if (e.place == ActionPlaces.PROJECT_VIEW_POPUP && match == null && !onAxonNode(project, e, file)) {
            p.isEnabledAndVisible = false
            return
        }
        p.isVisible = true
        val why = whyDisabled(project, match)
        p.isEnabled = why == null
        p.description = why ?: match?.let { "Sync ${it.target.key} from SkySpark into ${it.target.folder.name}" }
            ?: "Sync ${AxonProjectCopy.getInstance(project).activeKey} from SkySpark through the Axon server"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val match = AxonSyncTarget.find(project, e.getData(CommonDataKeys.VIRTUAL_FILE))
        if (whyDisabled(project, match) != null) return
        AxonProjectCopy.getInstance(project).sync(match)
    }

    companion object {
        const val ID = "Fantom.AxonSync"

        const val NO_SERVER = "Set the Axon server URL in Settings | Languages & Frameworks | Fantom | Axon"
        const val NO_KEY = "Set the access key of the Axon server in Settings | Languages & Frameworks | Fantom | Axon"
        const val NO_PROJECT = "Pick an Axon project in the status bar first"

        /** Why the action is disabled, or null when it can run. Memory only. */
        fun whyDisabled(project: Project, match: AxonSyncTarget.Match?): String? {
            val copy = AxonProjectCopy.getInstance(project)
            if (!copy.canSync()) return NO_SERVER
            // the password store can be slow or ask the user: the last value read is used here
            if (AxonServerSettings.getInstance().url.isNotEmpty() && AxonServerToken.storedHint() == false) return NO_KEY
            if (match == null && copy.activeKey == null) return NO_PROJECT
            return null
        }

        /** The project root, the Axon library node, or the folder of the copy. */
        private fun onAxonNode(project: Project, e: AnActionEvent, file: VirtualFile?): Boolean {
            if (file != null) {
                if (file.isDirectory && file.path == project.basePath) return true
                if (file in ProjectRootManager.getInstance(project).contentRoots) return true
                if (file == AxonProjectCopy.getInstance(project).libraryRoot) return true
            }
            val items = e.getData(PlatformCoreDataKeys.SELECTED_ITEMS) ?: return false
            return items.any { it is AxonLibrary || (it is AbstractTreeNode<*> && it.value is AxonLibrary) }
        }
    }
}
