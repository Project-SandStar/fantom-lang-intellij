package co.anbora.labs.fantom.lang.library

import co.anbora.labs.fantom.ide.icons.FantomIcons
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import javax.swing.Icon

/**
 * Exposes the pod sources of the Fantom home (and, for a Haxall or SkySpark home, of
 * the Fantom sources install) as synthetic libraries, one per pod. The IDE then
 * indexes them, so `Str`, `echo`, `haystack::Etc` and the rest resolve from any
 * project, and each pod shows by name under External Libraries.
 *
 * The pods come from [FantomSettings.podSources]. Changing the settings must be
 * followed by a roots change event (the settings page does that).
 */
class FantomLibraryRootsProvider : AdditionalLibraryRootsProvider() {

    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        val fs = LocalFileSystem.getInstance()
        return FantomSettings.getInstance(project).podSources().mapNotNull { pod ->
            val roots = pod.roots.mapNotNull { fs.findFileByIoFile(it) }
            if (roots.isEmpty()) null else FantomLibrary(pod.pod, pod.podDir, roots)
        }
    }

    override fun getRootsToWatch(project: Project): Collection<VirtualFile> {
        val fs = LocalFileSystem.getInstance()
        return FantomSettings.getInstance(project).effectiveSources().mapNotNull { fs.findFileByIoFile(it) }
    }

    /** One library per pod, shown by pod name. Equality is by pod folder and roots. */
    class FantomLibrary(val pod: String, val podDir: File, private val roots: List<VirtualFile>) :
        SyntheticLibrary(), ItemPresentation {
        override fun getSourceRoots(): Collection<VirtualFile> = roots
        override fun getPresentableText(): String = pod
        override fun getLocationString(): String = podDir.path
        override fun getIcon(unused: Boolean): Icon = FantomIcons.FILE
        override fun equals(other: Any?): Boolean =
            other is FantomLibrary && other.pod == pod && other.podDir == podDir && other.roots == roots
        override fun hashCode(): Int = 31 * pod.hashCode() + podDir.hashCode()
    }
}
