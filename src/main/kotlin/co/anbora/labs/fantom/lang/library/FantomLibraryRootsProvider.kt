package co.anbora.labs.fantom.lang.library

import co.anbora.labs.fantom.ide.icons.FantomIcons
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import javax.swing.Icon

/**
 * Exposes the pod sources of every ticked install (or, with none ticked, of the Fantom
 * home and its Fantom sources install) as synthetic libraries, one per pod. The IDE then
 * indexes them, so `Str`, `echo`, `haystack::Etc` and the rest resolve from any project,
 * and each pod shows by name under External Libraries.
 *
 * The `fan.sys` Java classes come as one more library per `lib/java/sys.jar` (and
 * `jline.jar`), as binary roots, so the debugger finds them; see [FantomSettings.runtimeJars].
 *
 * The pods come from [FantomSettings.podSources]. Changing the settings must be
 * followed by a roots change event (the settings page does that).
 */
class FantomLibraryRootsProvider : AdditionalLibraryRootsProvider() {

    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        val fs = LocalFileSystem.getInstance()
        val settings = FantomSettings.getInstance(project)
        val pods = settings.podSources().mapNotNull { pod ->
            val roots = pod.roots.mapNotNull { fs.findFileByIoFile(it) }
            if (roots.isEmpty()) null else FantomLibrary(pod.pod, pod.podDir, roots)
        }
        val jars = settings.runtimeJars().groupBy { it.parentFile }.mapNotNull { (dir, files) ->
            val roots = files.mapNotNull { f -> fs.findFileByIoFile(f)?.let { JarFileSystem.getInstance().getJarRootForLocalFile(it) } }
            if (roots.isEmpty()) null else FantomJavaLibrary(dir, roots)
        }
        return pods + jars
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

    /** The Java runtime jars of one install's `lib/java`, as binary roots. Equality is by folder and roots. */
    class FantomJavaLibrary(val dir: File, private val jars: List<VirtualFile>) : SyntheticLibrary(), ItemPresentation {
        override fun getSourceRoots(): Collection<VirtualFile> = emptyList()
        override fun getBinaryRoots(): Collection<VirtualFile> = jars
        override fun getPresentableText(): String = "fan.sys (${dir.parentFile?.parentFile?.name ?: dir.name})"
        override fun getLocationString(): String = dir.path
        override fun getIcon(unused: Boolean): Icon = FantomIcons.FILE
        override fun equals(other: Any?): Boolean = other is FantomJavaLibrary && other.dir == dir && other.jars == jars
        override fun hashCode(): Int = 31 * dir.hashCode() + jars.hashCode()
    }
}
