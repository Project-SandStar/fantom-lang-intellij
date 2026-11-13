package co.anbora.labs.fantom.ide.compiler

import co.anbora.labs.fantom.lang.library.FantomBuildFile
import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomInstall
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
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.DefaultComboBoxModel
import javax.swing.Icon
import javax.swing.JComponent

/**
 * Build > Build Pod, and the gutter icon on `podName` in `build.fan`: runs
 * `fan build.fan compile` for the pod of the current file and reports the outcome in a
 * notification. The open files of the pod show the compiler's messages inline, as
 * compile-on-save does.
 *
 * With ticked installs in the settings, a menu lists them as targets ("Build for SkySpark
 * 4.0.7"), the last one used first; "Build with Options…" also offers to write the pod into
 * the target's `lib/fan`. See [FantomBuildTargets]. With no ticked install, the pod builds
 * with [FantomSettings.effectiveHome] at once.
 */
class FantomBuildPodAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val root = if (e.project != null && file != null) podRootOf(file) else null
        e.presentation.isEnabledAndVisible = root != null
        if (root != null) e.presentation.text = "Build Pod '${root.name}'…"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        run(project, file) { popup -> popup.showInBestPositionFor(e.dataContext) }
    }

    companion object {
        const val ID = "Fantom.BuildPod"
        const val OPTIONS = "Build with Options…"

        /** The folder of the `build.fan` that names the pod of [file], for local files only. */
        fun podRootOf(file: VirtualFile): VirtualFile? {
            if (file.fileSystem !is LocalFileSystem) return null
            return FantomPodRoot.find(file)
        }

        /** The gutter entry point: the target menu opens at the click. */
        fun run(project: Project, file: VirtualFile, event: MouseEvent?) =
            run(project, file) { popup -> if (event != null) popup.show(RelativePoint(event)) else popup.showCenteredInCurrentWindow(project) }

        /**
         * Builds the pod of [file]: with ticked installs, after a target is picked from the
         * menu that [show] places; else at once with the single home.
         */
        fun run(project: Project, file: VirtualFile, show: (JBPopup) -> Unit) {
            val root = podRootOf(file) ?: return
            val targets = FantomSettings.getInstance(project).buildTargets()
            if (targets.isEmpty()) {
                buildWithHome(project, root)
                return
            }
            val preferred = FantomBuildTargets.preferred(project)
            val items: List<Any> = targets + OPTIONS
            val step = object : BaseListPopupStep<Any>("Build Pod '${root.name}'", items) {
                override fun getTextFor(value: Any): String = when (value) {
                    is FantomInstall -> FantomBuildTargets.label(value) + (value.warning?.let { "  ($it)" } ?: "")
                    else -> value.toString()
                }
                override fun getIconFor(value: Any): Icon? =
                    if (value is FantomInstall && value.runner == null) AllIcons.General.Warning else null
                override fun isSelectable(value: Any): Boolean = value !is FantomInstall || value.runner != null
                override fun getDefaultOptionIndex(): Int = targets.indexOf(preferred).coerceAtLeast(0)
                override fun isSpeedSearchEnabled() = true
                override fun onChosen(selectedValue: Any, finalChoice: Boolean): PopupStep<*>? {
                    doFinalStep {
                        if (selectedValue is FantomInstall) quickBuild(project, root, selectedValue)
                        else optionsBuild(project, root, targets, preferred)
                    }
                    return FINAL_CHOICE
                }
            }
            show(JBPopupFactory.getInstance().createListPopup(step))
        }

        /** A target picked from the menu: nothing is written into the target; Haxall asks where its pods come from. */
        private fun quickBuild(project: Project, root: VirtualFile, target: FantomInstall) {
            val pods = if (target.kind == FantomHome.Kind.HAXALL) askHaxallPods(project, target) ?: return else HaxallPods.NONE
            queue(project, root, FantomBuildRequest(target, pods, writeIntoTarget = false))
        }

        /** The choices offered for a Haxall target, with their button texts. */
        fun haxallChoices(project: Project, target: FantomInstall): List<Pair<HaxallPods, String>> = buildList {
            FantomBuildTargets.borrowFrom(target)?.let {
                add(HaxallPods.BORROW to "Borrow Pods from ${FantomInstall.displayName(FantomHome.Kind.SKYSPARK, target.version, it.name)}")
            }
            if (FantomBuildTargets.haxallPodsBuilt(project, target)) add(HaxallPods.BUILT to "Use Haxall Pods Built Before")
            add(HaxallPods.BUILD_FIRST to "Build Haxall's Pods First")
        }

        private fun askHaxallPods(project: Project, target: FantomInstall): HaxallPods? {
            val choices = haxallChoices(project, target)
            val options = choices.map { it.second } + "Cancel"
            val i = Messages.showDialog(
                project,
                "${target.name} ships sources, not pods. A pod that depends on its pods needs them compiled. " +
                    "Building them first takes a few minutes; they go into a temp folder, not into the install.",
                "Build for ${target.name}", options.toTypedArray(), 0, Messages.getQuestionIcon(),
            )
            return choices.getOrNull(i)?.first
        }

        private fun optionsBuild(project: Project, root: VirtualFile, targets: List<FantomInstall>, preferred: FantomInstall?) {
            val dialog = BuildOptionsDialog(project, targets.filter { it.runner != null }, preferred)
            if (!dialog.showAndGet()) return
            val target = dialog.target ?: return
            queue(project, root, FantomBuildRequest(target, dialog.haxallPods, dialog.writeIntoTarget))
        }

        /** The single-home build, as before the install table. */
        private fun buildWithHome(project: Project, root: VirtualFile) {
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

        private fun queue(project: Project, root: VirtualFile, request: FantomBuildRequest) {
            FileDocumentManager.getInstance().saveAllDocuments()
            val podName = podNameOf(project, root)
            object : Task.Backgroundable(project, "Building pod $podName for ${request.target.name}", true) {
                override fun run(indicator: ProgressIndicator) {
                    val outcome = FantomBuildTargets.build(project, VfsUtilCore.virtualToIoFile(root), podName, request, indicator)
                    if (outcome.result == null && outcome.error != null) {
                        notify(project, outcome.error, NotificationType.ERROR)
                        return
                    }
                    val (text, type) = summary(podName, outcome.result)
                    val extra = when {
                        outcome.written != null -> "\nWrote ${outcome.written.path}; a running server loads it after a restart."
                        outcome.error != null -> "\n${outcome.error}"
                        else -> ""
                    }
                    notify(project, "$text for ${request.target.name}$extra",
                        if (outcome.error != null && type == NotificationType.INFORMATION) NotificationType.WARNING else type)
                }
            }.queue()
        }

        /** Runs the build with [home] and waits. */
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

    /** Target, Haxall pods and "Write pod into the target's lib/fan" (off by default) for one build. */
    private class BuildOptionsDialog(
        private val project: Project,
        private val targets: List<FantomInstall>,
        preferred: FantomInstall?,
    ) : DialogWrapper(project) {
        private val targetBox = ComboBox(DefaultComboBoxModel(targets.toTypedArray())).apply {
            renderer = SimpleListCellRenderer.create("") { it.name }
            selectedItem = targets.firstOrNull { it == preferred } ?: targets.firstOrNull()
        }
        private val podsBox = ComboBox<Pair<HaxallPods, String>>().apply {
            renderer = SimpleListCellRenderer.create("") { it.second }
        }
        private val writeBox = JBCheckBox("Write pod into the target's lib/fan", false)

        val target: FantomInstall? get() = targetBox.selectedItem as? FantomInstall
        val haxallPods: HaxallPods
            get() = (podsBox.selectedItem as? Pair<*, *>)?.first as? HaxallPods ?: HaxallPods.NONE
        val writeIntoTarget: Boolean get() = writeBox.isSelected

        init {
            title = "Build Pod"
            targetBox.addActionListener { updatePods() }
            updatePods()
            init()
        }

        private fun updatePods() {
            val t = target
            val choices = if (t != null && t.kind == FantomHome.Kind.HAXALL) haxallChoices(project, t) else emptyList()
            podsBox.model = DefaultComboBoxModel(choices.toTypedArray())
            podsBox.isEnabled = choices.isNotEmpty()
        }

        override fun createCenterPanel(): JComponent = panel {
            row("Target:") { cell(targetBox).align(AlignX.FILL) }
            row("Haxall pods:") { cell(podsBox).align(AlignX.FILL) }
            row {
                cell(writeBox).comment("Off: the pod stays in a temp folder. On: it is copied into the target's lib/fan; " +
                    "a running server loads it after a restart.")
            }
        }
    }
}
