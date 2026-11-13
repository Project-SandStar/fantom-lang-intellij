package co.anbora.labs.fantom.lang.pod

import co.anbora.labs.fantom.ide.imports.FantomUsingCandidates
import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.RootsChangeRescanningInfo
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.io.File

/**
 * After a project opens: writes the missing `.fan` stubs for the home's compiled pods
 * (see [FantomPodStubs]) in a background task, then refreshes the stub folder and fires
 * a roots change so the new stubs are indexed. Does nothing when every stub is current.
 */
class FantomPodStubActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        schedule(project)
    }

    companion object {
        private val LOG = logger<FantomPodStubActivity>()

        /**
         * Starts a background task that writes the missing stubs of the home's compiled pods.
         * Call after the project opens and after the home changes. Off in unit tests, which
         * call [generate] themselves.
         */
        fun schedule(project: Project) {
            if (ApplicationManager.getApplication().isUnitTestMode) return
            val missing = FantomSettings.getInstance(project).podsNeedingStubs().filter { FantomPodStubs.needsGeneration(it) }
            if (missing.isEmpty()) return
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Reading Fantom pods", true) {
                override fun run(indicator: ProgressIndicator) {
                    generate(project, missing, indicator)
                }
            })
        }

        /** Writes the stubs of [pods], then makes them visible to the IDE. */
        fun generate(project: Project, pods: List<File>, indicator: ProgressIndicator?) {
            indicator?.isIndeterminate = false
            pods.forEachIndexed { i, pod ->
                indicator?.checkCanceled()
                indicator?.text2 = pod.name
                indicator?.fraction = i.toDouble() / pods.size
                runCatching { FantomPodStubs.generate(pod) }
                    .onFailure { LOG.info("Cannot write stubs for ${pod.path}: ${it.message}") }
            }
            refresh(project)
        }

        /** Refreshes the stub folder, clears the pod cache and fires a roots change. */
        fun refresh(project: Project) {
            val base = FantomPodStubs.defaultBase()
            base.mkdirs()
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(base)?.let {
                VfsUtil.markDirtyAndRefresh(false, true, true, it)
            }
            FantomSettings.clearPodCache()
            FantomUsingCandidates.reset()
            if (project.isDisposed) return
            WriteAction.runAndWait<RuntimeException> {
                if (!project.isDisposed) {
                    ProjectRootManagerEx.getInstanceEx(project)
                        .makeRootsChange(EmptyRunnable.getInstance(), RootsChangeRescanningInfo.TOTAL_RESCAN)
                }
            }
        }
    }
}
