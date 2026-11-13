package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.compiler.FantomCompileOnOpenListener
import co.anbora.labs.fantom.ide.compiler.FantomCompileTrigger
import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * The open and save listeners run on the EDT. The pod-root lookup (a folder walk up to a
 * `build.fan` that it reads) must run in a background read action, not on the EDT.
 */
class FantomCompileTriggerTest : BasePlatformTestCase() {
    private var podDir: File? = null

    override fun setUp() {
        super.setUp()
        FantomSettings.getInstance(project).compileOnSave = true
        FantomCompileTrigger.resetLookupRecord()
    }

    override fun tearDown() {
        try {
            podDir?.let { FileUtil.delete(it) }
        } finally {
            super.tearDown()
        }
    }

    private fun podFile(): com.intellij.openapi.vfs.VirtualFile {
        val dir = FileUtil.createTempDirectory("fantom-trigger", null, true)
        File(dir, "build.fan").writeText("class Build : BuildPod { new make() { podName = \"trig\" } }\n")
        File(dir, "fan/sub").mkdirs()
        File(dir, "fan/sub/Foo.fan").writeText("class Foo {}\n")
        podDir = dir
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(dir, "fan/sub/Foo.fan"))!!
    }

    fun testLookupRunsOffEdtAndFindsPodRoot() {
        val vf = podFile()
        assertTrue(ApplicationManager.getApplication().isDispatchThread)
        var found: File? = null
        val promise = FantomCompileTrigger.withPodRoot(project, vf) { _, root -> found = root }
        PlatformTestUtil.waitForPromise(promise)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(false, FantomCompileTrigger.lastLookupRanOnEdt())
        assertEquals(FileUtil.toCanonicalPath(podDir!!.canonicalPath), FileUtil.toCanonicalPath(found!!.canonicalPath))
    }

    fun testOpenListenerDoesNotLookUpOnEdt() {
        val vf = podFile()
        FantomCompileOnOpenListener().fileOpened(FileEditorManager.getInstance(project), vf)
        PlatformTestUtil.waitWithEventsDispatching("pod-root lookup did not run", { FantomCompileTrigger.lastLookupRanOnEdt() != null }, 10)
        assertEquals(false, FantomCompileTrigger.lastLookupRanOnEdt())
    }
}
