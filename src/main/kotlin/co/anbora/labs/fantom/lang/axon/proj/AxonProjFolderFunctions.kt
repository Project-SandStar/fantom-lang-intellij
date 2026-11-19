package co.anbora.labs.fantom.lang.axon.proj

import co.anbora.labs.fantom.lang.axon.AxonFileType
import co.anbora.labs.fantom.lang.axon.project.AxonFunctionSet
import co.anbora.labs.fantom.lang.axon.project.AxonProjectFunction
import co.anbora.labs.fantom.lang.axon.project.AxonProjectFunctions
import co.anbora.labs.fantom.lang.axon.psi.AxonNamedElement
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.search.ScopeOptimizer
import com.intellij.psi.search.SearchScope

/**
 * The [AxonProjectFunctions] of a `proj` folder (task 4.38), registered ahead of the project
 * copy. A file in a project folder sees the functions of that project only: `func/<name>.axon`.
 * A file in a backup or version folder sees none. Any other file is left to the next provider.
 *
 * It reads memory and the VFS only ([AxonProjFolders]); the doc of a function comes from
 * [AxonTrioDocIndex] when it is first asked for.
 */
class AxonProjFolderFunctions : AxonProjectFunctions {

    override fun functionSet(project: Project, file: VirtualFile?): AxonFunctionSet? {
        if (file == null) return null
        val folders = AxonProjFolders.getInstance()
        return when (val place = folders.placeOf(file)) {
            is AxonProjFolders.Place.InProject -> FolderSet(project, folders, place.folder)
            AxonProjFolders.Place.Copy -> NoFunctions
            AxonProjFolders.Place.Outside -> null
        }
    }

    /** The functions of one project folder. */
    private class FolderSet(private val project: Project, private val folders: AxonProjFolders, private val folder: VirtualFile) : AxonFunctionSet {

        private val files: Map<String, VirtualFile> get() = folders.functionFiles(folder)

        override val title: String get() = folders.keyOf(folder).toString()

        override fun function(name: String): AxonProjectFunction? = files[name]?.let { toFunction(name, it) }

        override fun functions(): Collection<AxonProjectFunction> = files.map { (name, f) -> toFunction(name, f) }

        override fun declaration(name: String, manager: PsiManager): PsiElement? =
            files[name]?.takeIf { it.isValid }?.let(manager::findFile)

        override fun functionOf(element: PsiElement): AxonProjectFunction? {
            val file = (element as? PsiFile)?.virtualFile ?: return null
            val name = file.name.removeSuffix(".axon")
            return files[name]?.takeIf { it == file }?.let { toFunction(name, it) }
        }

        /** The doc comes from the index: a null while the IDE indexes is not final. */
        private fun toFunction(name: String, file: VirtualFile) = AxonProjectFunction(
            name,
            { file.parent?.findChild("$name.trio")?.let { AxonTrioDocIndex.doc(project, it, name) } },
            { !DumbService.isDumb(project) },
        )
    }

    /** A backup or version folder: no project, no functions. */
    private object NoFunctions : AxonFunctionSet {
        override val title: String get() = "backup"
        override fun function(name: String): AxonProjectFunction? = null
        override fun functions(): Collection<AxonProjectFunction> = emptyList()
        override fun declaration(name: String, manager: PsiManager): PsiElement? = null
        override fun functionOf(element: PsiElement): AxonProjectFunction? = null
    }
}

/**
 * Find Usages of an Axon function in a project folder searches that project folder only: a
 * function of the same name in another project is another function. A function in a backup
 * or version folder is searched in its own file only.
 */
class AxonProjFolderScopeOptimizer : ScopeOptimizer {
    override fun getRestrictedUseScope(element: PsiElement): SearchScope? {
        val file = when (element) {
            is PsiFile -> element.takeIf { it.fileType == AxonFileType }
            is AxonNamedElement -> element.containingFile
            else -> null
        } ?: return null
        val vFile = file.virtualFile ?: return null
        return when (val place = AxonProjFolders.getInstance().placeOf(vFile)) {
            is AxonProjFolders.Place.InProject -> GlobalSearchScopesCore.directoryScope(element.project, place.folder, true)
            AxonProjFolders.Place.Copy -> GlobalSearchScope.fileScope(file)
            AxonProjFolders.Place.Outside -> null
        }
    }
}
