package co.anbora.labs.fantom.lang.library.deps

import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * After a project opens with ticked installs: pairs the saved rows with a Haxall again
 * (rows saved by 3.0.0 have none), then resolves the dependencies, see [FantomDependencies].
 * Runs off the UI thread.
 */
class FantomDependenciesStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        if (FantomSettings.getInstance(project).buildTargets().isEmpty()) return
        val deps = FantomDependencies.getInstance(project)
        deps.repairInstalls()
        deps.refresh()
    }
}
