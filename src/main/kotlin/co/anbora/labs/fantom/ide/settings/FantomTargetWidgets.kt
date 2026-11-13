package co.anbora.labs.fantom.ide.settings

import co.anbora.labs.fantom.lang.library.FantomInstall
import co.anbora.labs.fantom.lang.library.FantomSettings
import co.anbora.labs.fantom.lang.library.deps.FantomDependencies
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

/** Text and popup of [FantomTargetWidget]. */
object FantomTargetWidgets {

    /** "Fantom: SkySpark 4.0.7", or "Fantom: no target". */
    fun text(project: Project): String =
        "Fantom: " + (FantomSettings.getInstance(project).defaultInstall()?.name ?: "no target")

    /** The ticked installs; picking one switches the build target. Null when none is ticked. */
    fun popup(project: Project): JBPopup? {
        val targets = FantomSettings.getInstance(project).buildTargets()
        if (targets.isEmpty()) return null
        val current = FantomSettings.getInstance(project).defaultInstall()
        val step = object : BaseListPopupStep<FantomInstall>("Fantom Build Target", targets) {
            override fun getTextFor(value: FantomInstall): String = value.name
            override fun getDefaultOptionIndex(): Int = targets.indexOfFirst { it.path == current?.path }.coerceAtLeast(0)
            override fun onChosen(selectedValue: FantomInstall, finalChoice: Boolean): PopupStep<*>? {
                doFinalStep { FantomDependencies.getInstance(project).switchTarget(selectedValue.path) }
                return FINAL_CHOICE
            }
        }
        return JBPopupFactory.getInstance().createListPopup(step)
    }
}

/** Shows [FantomTargetWidget] when at least one install is ticked. */
class FantomTargetWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = FantomTargetWidget.ID
    override fun getDisplayName(): String = "Fantom Build Target"
    override fun isAvailable(project: Project): Boolean = FantomSettings.getInstance(project).buildTargets().isNotEmpty()
    override fun createWidget(project: Project): StatusBarWidget = FantomTargetWidget(project)
}
