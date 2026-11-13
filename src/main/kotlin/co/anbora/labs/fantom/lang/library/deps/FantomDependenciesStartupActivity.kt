package co.anbora.labs.fantom.lang.library.deps

import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** After a project opens with ticked installs: resolves its dependencies in the background, see [FantomDependencies]. */
class FantomDependenciesStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        if (FantomSettings.getInstance(project).buildTargets().isEmpty()) return
        FantomDependencies.getInstance(project).refresh()
    }
}
