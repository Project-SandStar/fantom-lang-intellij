package co.anbora.labs.fantom.ide.compiler

import co.anbora.labs.fantom.lang.library.FantomBuildFile
import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.icons.AllIcons
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * Build > Build Pod, and the gutter icon on `podName` in `build.fan`: runs
 * `fan build.fan compile` for the pod of the current file through [FantomCompilerService]
 * and reports the outcome in a notification. The open files of the pod show the
 * compiler's messages inline, as compile-on-save does.
 */
class FantomBuildPodAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val root = if (e.project != null && file != null) podRootOf(file) else null
        e.presentation.isEnabledAndVisible = root != null
        if (root != null) e.presentation.text = "Build Pod '${root.name}'"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        run(project, file)
    }

    companion object {
        const val ID = "Fantom.BuildPod"

        /** The folder of the `build.fan` that names the pod of [file], for local files only. */
        fun podRootOf(file: VirtualFile): VirtualFile? {
            if (file.fileSystem !is LocalFileSystem) return null
            return FantomPodRoot.find(file)
        }

        /** Saves all documents, then builds the pod of [file] in the background. */
        fun run(project: Project, file: VirtualFile) {
            val root = podRootOf(file) ?: return
            val home = FantomSettings.getInstance(project).effectiveHome()
            if (home == null) {
                notify(project, "Build Pod: no Fantom home with bin/fan. Set it in Settings | Languages & Frameworks | Fantom.", NotificationType.ERROR)
                return
            }
            FileDocumentManager.getInstance().saveAllDocuments()
            val podName = podNameOf(project, root)
            object : Task.Backgroundable(project, "Building pod $podName", true) {
                override fun run(indicator: ProgressIndicator) {
                    val result = build(project, VfsUtilCore.virtualToIoFile(root), home, indicator)
                    val (text, type) = summary(podName, result)
                    notify(project, text, type)
                }
            }.queue()
        }

        /** Runs the build and waits for it. */
        fun build(project: Project, podRoot: File, home: File, indicator: ProgressIndicator?): FantomCompileResult? =
            FantomCompilerService.getInstance(project).buildPod(podRoot, home, indicator)

        fun podNameOf(project: Project, root: VirtualFile): String =
            ReadAction.computeBlocking<String?, RuntimeException> {
                root.findChild(FantomBuildFile.FILE_NAME)?.let { FantomBuildFile.forFile(it, project)?.podName }
            } ?: root.name

        /** The notification text and type for a build result. */
        fun summary(podName: String, result: FantomCompileResult?): Pair<String, NotificationType> {
            if (result == null || !result.completed) {
                return "Build of pod '$podName' did not finish (launch failure or timeout)" to NotificationType.ERROR
            }
            val all = result.byFile.values.flatten()
            val errors = all.filter { it.severity == HighlightSeverity.ERROR }
            val warnings = all.size - errors.size
            val warnText = if (warnings > 0) ", $warnings warning${if (warnings == 1) "" else "s"}" else ""
            if (errors.isEmpty() && result.exitCode == 0) {
                return "Pod '$podName' built$warnText" to (if (warnings > 0) NotificationType.WARNING else NotificationType.INFORMATION)
            }
            val first = errors.firstOrNull()?.let { "\n${File(it.path).name}:${it.line}: ${it.message}" } ?: ""
            val count = errors.size
            return "Pod '$podName' failed: $count error${if (count == 1) "" else "s"}$warnText$first" to NotificationType.ERROR
        }

        private fun notify(project: Project, text: String, type: NotificationType) {
            if (project.isDisposed) return
            NotificationGroupManager.getInstance().getNotificationGroup("Fantom")
                .createNotification(text, type)
                .setIcon(AllIcons.Actions.Compile)
                .notify(project)
        }
    }
}
