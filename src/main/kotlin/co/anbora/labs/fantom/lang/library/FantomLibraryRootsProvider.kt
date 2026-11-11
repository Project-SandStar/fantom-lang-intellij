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
 * Exposes the Fantom installation's pod sources (`src/<pod>/fan`) as one synthetic
 * library. The IDE then indexes them, so `Str`, `echo` and every other `sys` name
 * resolves from any project, and they show under External Libraries.
 *
 * The home comes from [FantomSettings.effectiveHome]. Changing it must be followed by
 * a roots change event (the settings page does that) so the provider is asked again.
 */
class FantomLibraryRootsProvider : AdditionalLibraryRootsProvider() {

    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        val home = FantomSettings.getInstance(project).effectiveHome() ?: return emptyList()
        val roots = sourceRootFiles(home)
        if (roots.isEmpty()) return emptyList()
        return listOf(FantomLibrary(home, roots))
    }

    override fun getRootsToWatch(project: Project): Collection<VirtualFile> {
        val home = FantomSettings.getInstance(project).effectiveHome() ?: return emptyList()
        return sourceRootFiles(home)
    }

    private fun sourceRootFiles(home: File): List<VirtualFile> {
        val fs = LocalFileSystem.getInstance()
        return FantomHome.sourceRoots(home).mapNotNull { fs.findFileByIoFile(it) }
    }

    /** One library per Fantom home. Equality is by home path so the IDE does not rescan needlessly. */
    class FantomLibrary(val home: File, private val roots: List<VirtualFile>) : SyntheticLibrary(), ItemPresentation {
        override fun getSourceRoots(): Collection<VirtualFile> = roots
        override fun getPresentableText(): String = "Fantom " + (versionOf(home) ?: home.name)
        override fun getIcon(unused: Boolean): Icon = FantomIcons.FILE
        override fun equals(other: Any?): Boolean = other is FantomLibrary && other.home == home
        override fun hashCode(): Int = home.hashCode()

        private fun versionOf(home: File): String? =
            Regex("fantom-(\\d+(?:\\.\\d+)*)").matchEntire(home.name)?.groupValues?.get(1)
    }
}
