package co.anbora.labs.fantom.ide.settings

import co.anbora.labs.fantom.ide.compiler.FantomBuildTargets
import co.anbora.labs.fantom.lang.library.FantomInstall
import co.anbora.labs.fantom.lang.library.FantomSettings
import co.anbora.labs.fantom.lang.library.deps.FantomDependencies
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.WindowManager

/**
 * The status bar entry "Fantom: SkySpark 4.0.7": the current build target. A click lists
 * the ticked installs; picking one calls [FantomDependencies.switchTarget], which re-maps
 * every dependency (Haxall pairing, tag choice) and fires a roots change.
 */
class FantomTargetWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.MultipleTextValuesPresentation {

    private var statusBar: StatusBar? = null

    init {
        project.messageBus.connect(this).subscribe(FantomDependencies.TOPIC, object : FantomDependencies.Listener {
            override fun dependenciesChanged(snapshot: FantomDependencies.Snapshot) = update()
            override fun targetChanged(path: String) = update()
        })
    }

    override fun ID(): String = ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getSelectedValue(): String = text(project)

    override fun getTooltipText(): String = "Fantom build target: the install the index, runs and compile on save use. Click to switch."

    override fun getPopup(): JBPopup? {
        val targets = FantomSettings.getInstance(project).buildTargets()
        if (targets.isEmpty()) return null
        val current = FantomSettings.getInstance(project).defaultInstall()
        val step = object : BaseListPopupStep<FantomInstall>("Fantom Build Target", targets) {
            override fun getTextFor(value: FantomInstall): String = FantomBuildTargets.label(value)
            override fun getDefaultOptionIndex(): Int = targets.indexOfFirst { it.path == current?.path }.coerceAtLeast(0)
            override fun onChosen(selectedValue: FantomInstall, finalChoice: Boolean): PopupStep<*>? {
                doFinalStep { FantomDependencies.getInstance(project).switchTarget(selectedValue.path) }
                return FINAL_CHOICE
            }
        }
        return JBPopupFactory.getInstance().createListPopup(step)
    }

    private fun update() {
        val bar = statusBar ?: WindowManager.getInstance().getStatusBar(project) ?: return
        bar.updateWidget(ID)
    }

    override fun dispose() {
        statusBar = null
    }

    companion object {
        const val ID = "co.anbora.labs.fantom.target"

        /** "Fantom: SkySpark 4.0.7", or "Fantom: no target". */
        fun text(project: Project): String =
            "Fantom: " + (FantomSettings.getInstance(project).defaultInstall()?.name ?: "no target")
    }
}

/** Shows [FantomTargetWidget] when at least one install is ticked. */
class FantomTargetWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = FantomTargetWidget.ID
    override fun getDisplayName(): String = "Fantom Build Target"
    override fun isAvailable(project: Project): Boolean = FantomSettings.getInstance(project).buildTargets().isNotEmpty()
    override fun createWidget(project: Project): StatusBarWidget = FantomTargetWidget(project)
}
