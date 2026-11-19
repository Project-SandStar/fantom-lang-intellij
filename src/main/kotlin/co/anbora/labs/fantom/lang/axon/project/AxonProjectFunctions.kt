package co.anbora.labs.fantom.lang.axon.project

import co.anbora.labs.fantom.ide.icons.FantomIcons
import co.anbora.labs.fantom.lang.axon.psi.AxonFunctionSource
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import java.util.concurrent.atomic.AtomicReference

/**
 * One function of an Axon project: its name and its doc (fandoc), as the project stores them.
 * The doc can come from [docSource] on first use, so a name lookup does not read it.
 *
 * A doc is cached once found. A null is cached only when [docFinal] says so before and after
 * the read: a source that answers null while the IDE indexes is asked again later.
 */
class AxonProjectFunction(
    val name: String,
    private val docSource: () -> String?,
    private val docFinal: () -> Boolean = { true },
) {
    constructor(name: String, doc: String?) : this(name, { doc })

    private val cached = AtomicReference<Any?>(NOT_READ)

    val doc: String?
        get() {
            val c = cached.get()
            if (c !== NOT_READ) return c as String?
            val finalBefore = docFinal()
            val v = docSource()
            if (v != null || (finalBefore && docFinal())) cached.compareAndSet(NOT_READ, v)
            return v
        }

    /** The first line of the doc that is not blank, or null. Completion shows it as tail text. */
    val summary: String? get() = doc?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }

    private companion object {
        val NOT_READ = Any()
    }
}

/**
 * The functions of one Axon project. A lookup by name must be fast: a project can hold
 * 20,000 functions, and the resolver asks for every name use of a file.
 * Every call runs in a read action: none may touch the network or block on a lock.
 */
interface AxonFunctionSet {
    /** A short name of the project for the developer: `demo/site`. */
    val title: String

    /** The function [name], or null when the project has none. */
    fun function(name: String): AxonProjectFunction?

    /** Every function of the project, for completion. */
    fun functions(): Collection<AxonProjectFunction>

    /** The declaration that Ctrl+click opens for [name]: the `.axon` file of the function. */
    fun declaration(name: String, manager: PsiManager): PsiElement?

    /** The function that [element] declares (an element that [declaration] returned), or null. */
    fun functionOf(element: PsiElement): AxonProjectFunction?
}

/**
 * Says which Axon project a file belongs to, and so which project functions a name in that
 * file can call. Registered at `co.anbora.labs.fantom.axonProjectFunctions`. The first
 * provider that knows the file wins: a provider for the files of a `proj` folder (task 4.38)
 * goes first; the active project copy, which takes every other file, goes last.
 */
interface AxonProjectFunctions {
    /** The functions of the Axon project of [file], or null when this provider does not know the file. */
    fun functionSet(project: Project, file: VirtualFile?): AxonFunctionSet?

    companion object {
        val EP_NAME: ExtensionPointName<AxonProjectFunctions> =
            ExtensionPointName.create("co.anbora.labs.fantom.axonProjectFunctions")

        /**
         * The functions of the Axon project that [context] belongs to, or null. Axon injected in
         * a Fantom string or a trio file belongs to the project of the host file; the copy of a
         * file that completion works on belongs to the project of the original file.
         */
        fun of(context: PsiElement): AxonFunctionSet? {
            val project = context.project
            if (project.isDefault) return null
            val file = context.containingFile ?: return null
            val top = InjectedLanguageManager.getInstance(project).getTopLevelFile(file) ?: file
            val vFile = top.originalFile.virtualFile ?: top.viewProvider.virtualFile
            for (provider in EP_NAME.extensionList) {
                provider.functionSet(project, vFile)?.let { return it }
            }
            return null
        }
    }
}

/**
 * Bridges [AxonProjectFunctions] to the resolver: a name that is not a local, a parameter or
 * a function of the file resolves to the function of the file's Axon project.
 */
class AxonProjectFunctionSource : AxonFunctionSource {

    override fun findFunctions(name: String, context: PsiElement): Collection<PsiElement> {
        val set = AxonProjectFunctions.of(context) ?: return emptyList()
        if (set.function(name) == null) return emptyList()
        return listOfNotNull(set.declaration(name, PsiManager.getInstance(context.project)))
    }

    override fun functionNames(context: PsiElement): Collection<String> =
        AxonProjectFunctions.of(context)?.functions()?.map { it.name }.orEmpty()

    override fun lookups(context: PsiElement): Collection<LookupElement> {
        val set = AxonProjectFunctions.of(context) ?: return emptyList()
        return set.functions().map { f ->
            ProgressManager.checkCanceled() // up to 20,000 items: let a new keystroke stop the list
            var lookup = LookupElementBuilder.create(f.name).withIcon(FantomIcons.FUNCTION).withTypeText(set.title, true)
            f.summary?.let { lookup = lookup.withTailText("  $it", true) }
            lookup
        }
    }
}
