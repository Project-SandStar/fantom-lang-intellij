package co.anbora.labs.fantom.ide.run

import com.intellij.execution.BeforeRunTask
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import javax.swing.Icon

/** The "Build Fantom pod" step of a Fantom run configuration. */
class FantomBuildBeforeRunTask : BeforeRunTask<FantomBuildBeforeRunTask>(FantomBuildBeforeRunTaskProvider.ID) {
    init {
        isEnabled = true
    }
}

/**
 * Before launch: "Build Fantom pod". On by default for new Fantom and Fantom Test
 * configurations, like Java's "Build". Compiles the configuration's pod from the current
 * sources into its private work folder with the current build target, and the launch then
 * runs against that pod (see [FantomPodLaunch]). A failed build stops the launch; the
 * compiler's messages show in the editor and in a notification. A script target (a `.fan`
 * path) or a pod without a `build.fan` in the project is left alone.
 */
class FantomBuildBeforeRunTaskProvider : BeforeRunTaskProvider<FantomBuildBeforeRunTask>() {
    override fun getId(): Key<FantomBuildBeforeRunTask> = ID

    override fun getName(): String = NAME

    override fun getIcon(): Icon = AllIcons.Actions.Compile

    override fun getTaskIcon(task: FantomBuildBeforeRunTask): Icon = AllIcons.Actions.Compile

    override fun getDescription(task: FantomBuildBeforeRunTask): String = NAME

    override fun createTask(runConfiguration: RunConfiguration): FantomBuildBeforeRunTask? = when (runConfiguration) {
        is FantomRunConfiguration -> { runConfiguration.buildStepAdded = true; FantomBuildBeforeRunTask() }
        is FantomTestRunConfiguration -> { runConfiguration.buildStepAdded = true; FantomBuildBeforeRunTask() }
        else -> null
    }

    override fun canExecuteTask(configuration: RunConfiguration, task: FantomBuildBeforeRunTask): Boolean =
        FantomPodLaunch.configurationOf(configuration) != null

    override fun executeTask(
        context: DataContext,
        configuration: RunConfiguration,
        environment: ExecutionEnvironment,
        task: FantomBuildBeforeRunTask,
    ): Boolean {
        val project = environment.project
        FantomPodLaunch.recordBuilt(environment, null)
        // the platform runs every listed task; a task switched off in the XML stays listed
        if (!task.isEnabled) return true
        ApplicationManager.getApplication().invokeAndWait { FileDocumentManager.getInstance().saveAllDocuments() }
        val indicator = ProgressManager.getInstance().progressIndicator
        return when (val outcome = FantomPodLaunch.build(project, configuration, indicator)) {
            FantomPodLaunch.Outcome.NotApplicable -> true
            is FantomPodLaunch.Outcome.Built -> {
                FantomPodLaunch.recordBuilt(environment, outcome.workDir)
                true
            }
            is FantomPodLaunch.Outcome.Failed -> {
                if (!project.isDisposed) {
                    NotificationGroupManager.getInstance().getNotificationGroup("Fantom")
                        .createNotification("${outcome.message}\nThe launch was stopped.", NotificationType.ERROR)
                        .setIcon(AllIcons.Actions.Compile)
                        .notify(project)
                }
                false
            }
        }
    }

    companion object {
        val ID: Key<FantomBuildBeforeRunTask> = Key.create("Fantom.BuildPod.BeforeRun")
        const val NAME = "Build Fantom pod"
    }
}
