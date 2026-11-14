package co.anbora.labs.fantom.ide.run

import com.intellij.execution.BeforeRunTask
import com.intellij.execution.RunManager
import com.intellij.execution.RunManagerEx
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Gives the "Build Fantom pod" step to Fantom configurations saved before the step existed.
 * The platform only adds a before-launch step to new configurations, so without this every
 * old configuration keeps running against the installed pod. Each configuration gets the
 * step once ([FantomRunConfiguration.buildStepAdded]); a user who removes it later keeps it
 * removed.
 */
class FantomBuildStepMigration : ProjectActivity {
    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().invokeLater({ migrate(project) }, project.disposed)
    }

    companion object {
        /** Adds the step to every Fantom configuration that never had it; returns how many changed. */
        fun migrate(project: Project): Int {
            if (project.isDisposed) return 0
            val manager = RunManagerEx.getInstanceEx(project)
            var changed = 0
            for (settings in RunManager.getInstance(project).allSettings) {
                val cfg = settings.configuration
                if (!needsStep(cfg)) continue
                val tasks: MutableList<BeforeRunTask<*>> = manager.getBeforeRunTasks(cfg).toMutableList()
                if (tasks.none { it is FantomBuildBeforeRunTask }) tasks.add(0, FantomBuildBeforeRunTask())
                manager.setBeforeRunTasks(cfg, tasks)
                markAdded(cfg)
                changed++
            }
            return changed
        }

        private fun needsStep(cfg: RunConfiguration): Boolean = when (cfg) {
            is FantomRunConfiguration -> !cfg.buildStepAdded
            is FantomTestRunConfiguration -> !cfg.buildStepAdded
            else -> false
        }

        private fun markAdded(cfg: RunConfiguration) {
            when (cfg) {
                is FantomRunConfiguration -> cfg.buildStepAdded = true
                is FantomTestRunConfiguration -> cfg.buildStepAdded = true
            }
        }
    }
}
