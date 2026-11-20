package co.anbora.labs.fantom.ide.axon

import co.anbora.labs.fantom.lang.axon.AxonFileType
import co.anbora.labs.fantom.lang.axon.copy.AxonCopyException
import co.anbora.labs.fantom.lang.axon.copy.AxonFunction
import co.anbora.labs.fantom.lang.axon.proj.AxonFunctionPush
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent

/**
 * "Push to SkySpark" (task 4.34): saves the Axon function file and sends it to SkySpark
 * through the Axon server ([AxonFunctionPush]), in the background. It shows in the editor
 * menu and in the Project view menu of a `.axon` file, and has the shortcut
 * Ctrl+Alt+Shift+Y (Cmd+Alt+Shift+Y on macOS).
 *
 * It is greyed out with no server URL, no access key, on the read-only copy, and on a file
 * that is not a function of a project folder in a `proj` folder; the tooltip says why.
 */
class AxonPushAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val p = e.presentation
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val isAxon = file != null && !file.isDirectory && file.extension == "axon"
        // in a popup menu only for Axon files; elsewhere (shortcut, Find Action) always there
        if (project == null || (!isAxon && (e.place == ActionPlaces.EDITOR_POPUP || e.place == ActionPlaces.PROJECT_VIEW_POPUP))) {
            p.isEnabledAndVisible = false
            return
        }
        p.isVisible = true
        val why = AxonFunctionPush.whyNot(project, file)
        p.isEnabled = why == null
        p.description = why ?: AxonFunctionPush.targetOf(file)?.let { "Push ${it.name} to ${it.key} in SkySpark through the Axon server" }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        if (AxonFunctionPush.whyNot(project, file) != null) return
        val target = AxonFunctionPush.targetOf(file) ?: return
        val fdm = FileDocumentManager.getInstance()
        fdm.getDocument(file)?.let { fdm.saveDocument(it) }
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Pushing ${target.name} to SkySpark", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    when (val out = AxonFunctionPush.run(project, target, DialogUi(project)) { indicator.checkCanceled() }) {
                        is AxonFunctionPush.Outcome.Pushed -> notify(project, AxonFunctionPush.message(out.plan, out.result), NotificationType.INFORMATION)
                        is AxonFunctionPush.Outcome.TookSkySpark -> notify(project, "Took the SkySpark version of ${out.plan.name}. Your text is in .versions.", NotificationType.INFORMATION)
                        AxonFunctionPush.Outcome.Cancelled -> {}
                    }
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: AxonCopyException) {
                    notify(project, errorText(e), NotificationType.ERROR)
                } catch (e: Exception) {
                    notify(project, "The push failed: ${e.message ?: e.javaClass.simpleName}", NotificationType.ERROR)
                }
            }
        })
    }

    /** The questions of a push, as dialogs on the UI thread. The push thread waits. */
    private class DialogUi(private val project: Project) : AxonFunctionPush.Ui {
        override fun confirmNew(plan: AxonFunctionPush.Plan): Boolean = onUi {
            Messages.showOkCancelDialog(
                project,
                "${plan.name} is not in ${plan.key} in SkySpark yet. Push creates it there.",
                "Create Axon Function", "Create", Messages.getCancelButton(), Messages.getQuestionIcon(),
            ) == Messages.OK
        }

        override fun conflict(plan: AxonFunctionPush.Plan, server: AxonFunction?): AxonFunctionPush.Choice = onUi {
            val d = ConflictDialog(project, plan, server)
            d.show()
            when (d.exitCode) {
                ConflictDialog.OVERWRITE -> AxonFunctionPush.Choice.OVERWRITE
                ConflictDialog.TAKE -> AxonFunctionPush.Choice.TAKE_SKYSPARK
                else -> AxonFunctionPush.Choice.CANCEL
            }
        }

        private fun <T> onUi(work: () -> T): T {
            var out: Result<T>? = null
            ApplicationManager.getApplication().invokeAndWait({ out = runCatching(work) }, ModalityState.defaultModalityState())
            return out!!.getOrThrow()
        }
    }

    /** The conflict: a diff of the SkySpark version and the local file, and three buttons. */
    private class ConflictDialog(
        private val project: Project,
        private val plan: AxonFunctionPush.Plan,
        private val server: AxonFunction?,
    ) : DialogWrapper(project) {

        init {
            title = "${plan.name} Changed in SkySpark"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val f = DiffContentFactory.getInstance()
            val left = f.create(project, server?.src ?: "", AxonFileType)
            val right = f.create(project, plan.src, AxonFileType)
            val leftTitle = if (server == null) "SkySpark (the function is gone)" else "SkySpark (${server.mod ?: "no mod"})"
            val request = SimpleDiffRequest("${plan.name} in ${plan.key}", left, right, leftTitle, "Local file")
            val panel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
            panel.setRequest(request)
            return panel.component.also { it.preferredSize = Dimension(900, 520) }
        }

        override fun createActions(): Array<Action> = arrayOf(
            DialogWrapperExitAction("Overwrite SkySpark", OVERWRITE),
            DialogWrapperExitAction("Take SkySpark Version", TAKE).also { it.isEnabled = server != null },
            cancelAction,
        )

        companion object {
            const val OVERWRITE = NEXT_USER_EXIT_CODE
            const val TAKE = NEXT_USER_EXIT_CODE + 1
        }
    }

    companion object {
        const val ID = "Fantom.AxonPush"

        /** The text of a failed push; a read-only key and a refused key say what to do. */
        fun errorText(e: AxonCopyException): String = when (e.kind) {
            AxonCopyException.Kind.READ_ONLY -> "Your access key is read-only: it can sync but not push. Ask an admin of the Axon server for the user role."
            AxonCopyException.Kind.UNAUTHORIZED -> "The Axon server refused your access key. Check it on the dashboard Users page and in Settings | Languages & Frameworks | Fantom | Axon."
            else -> e.message ?: "The push failed."
        }

        private fun notify(project: Project, text: String, type: NotificationType) {
            if (project.isDisposed) return
            NotificationGroupManager.getInstance().getNotificationGroup("Fantom")
                ?.createNotification("Push to SkySpark", text, type)
                ?.notify(project)
        }
    }
}
