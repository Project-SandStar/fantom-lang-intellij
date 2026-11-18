package co.anbora.labs.fantom.lang.axon.copy

import co.anbora.labs.fantom.lang.axon.AxonFileType
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.WritingAccessProvider
import javax.swing.Icon

/**
 * Shows the Axon project copy under External Libraries as "Axon: instance/project". The
 * root is the `func` folder of the copy in the IDE system folder: outside the project
 * content, so version control never sees it.
 *
 * It reads [AxonProjectCopy.libraryRoot] and [AxonProjectCopy.activeKey] only: memory, no
 * disk and no network. [AxonProjectCopy] fires the roots change when the root changes.
 */
class AxonProjectLibraryRootsProvider : AdditionalLibraryRootsProvider() {

    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        val copy = AxonProjectCopy.getInstance(project)
        val root = copy.libraryRoot ?: return emptyList()
        val key = copy.copy?.key ?: return emptyList()
        return listOf(AxonLibrary(key, root))
    }

    override fun getRootsToWatch(project: Project): Collection<VirtualFile> =
        listOfNotNull(AxonProjectCopy.getInstance(project).libraryRoot)

    /** The library of one copy. Equality is by key and root. */
    class AxonLibrary(val key: AxonProjectKey, private val root: VirtualFile) : SyntheticLibrary(), ItemPresentation {
        override fun getSourceRoots(): Collection<VirtualFile> = listOf(root)
        override fun getPresentableText(): String = "Axon: $key"
        override fun getLocationString(): String? = null
        override fun getIcon(unused: Boolean): Icon? = AxonFileType.icon
        override fun equals(other: Any?): Boolean = other is AxonLibrary && other.key == key && other.root == root
        override fun hashCode(): Int = 31 * key.hashCode() + root.hashCode()
    }
}

/**
 * The files of the copy cannot be made writable in the IDE: they are replaced at the next
 * fetch, so an edit would be lost. (The files are read-only on disk as well; this holds
 * where that bit does not, and stops "Clear Read-Only Status".)
 */
class AxonProjectCopyWritingAccessProvider(private val project: Project) : WritingAccessProvider() {

    override fun requestWriting(files: Collection<VirtualFile>): Collection<VirtualFile> {
        val copy = AxonProjectCopy.getInstance(project)
        return files.filter { copy.isInCopy(it) }
    }

    override fun isPotentiallyWritable(file: VirtualFile): Boolean = !AxonProjectCopy.getInstance(project).isInCopy(file)

    override fun getReadOnlyMessage(): String =
        "This file is a read-only copy of an Axon function. It is replaced at the next refresh."
}

/** After a project opens: registers the Axon copy that is on disk. No network. */
class AxonProjectCopyStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        if (AxonProjectSettings.getInstance(project).activeKey == null) return
        AxonProjectCopy.getInstance(project).load()
    }
}
