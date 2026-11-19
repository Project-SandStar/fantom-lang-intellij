package co.anbora.labs.fantom.lang.axon.project

import co.anbora.labs.fantom.lang.axon.copy.AxonFunctionEntry
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectCache
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectCopy
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

/**
 * The first [AxonProjectFunctions]: the functions of the active Axon project copy (the
 * status bar pick, task 4.26). It takes every file, so it is registered last: a provider
 * that knows a file better (an open `proj` folder, task 4.38) answers first.
 *
 * It reads memory only: the index of the copy is in [AxonProjectCopy], and a name is
 * found in a hash map, so a project of 20,000 functions costs no more than one of ten.
 */
class AxonCopyProjectFunctions : AxonProjectFunctions {

    override fun functionSet(project: Project, file: VirtualFile?): AxonFunctionSet? {
        val service = AxonProjectCopy.getInstance(project)
        val copy = service.copy ?: return null
        return CopySet(service, copy)
    }

    private class CopySet(private val service: AxonProjectCopy, private val copy: AxonProjectCache.Copy) : AxonFunctionSet {

        override val title: String get() = copy.key.toString()

        override fun function(name: String): AxonProjectFunction? = copy.entry(name)?.let(::toFunction)

        override fun functions(): Collection<AxonProjectFunction> = copy.functions.map(::toFunction)

        override fun declaration(name: String, manager: PsiManager): PsiElement? =
            service.findFunctionFile(name)?.takeIf { it.isValid }?.let(manager::findFile)

        override fun functionOf(element: PsiElement): AxonProjectFunction? {
            val file = (element as? PsiFile)?.virtualFile ?: return null
            if (!service.isInCopy(file)) return null
            // the file name is the encoded function name: look it up in the index, not by decoding
            return copy.functions.firstOrNull { it.file == file.name }?.let(::toFunction)
        }

        private fun toFunction(e: AxonFunctionEntry) = AxonProjectFunction(e.name, e.doc)
    }
}
