package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable

/** Runs [block] with the detected Fantom install attached to [project]; false when there is none. */
object FantomTestLibrary {
    fun with(project: Project, block: () -> Unit): Boolean {
        val home = FantomHome.detect() ?: run { println("skipped: no Fantom home"); return false }
        FantomSettings.getInstance(project).homePath = home.path
        try {
            WriteAction.runAndWait<RuntimeException> {
                ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(EmptyRunnable.getInstance(), false, true)
            }
            block()
        } finally {
            FantomSettings.getInstance(project).homePath = null
        }
        return true
    }
}
