package co.anbora.labs.fantom.lang.resolve

import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomUsingDef
import co.anbora.labs.fantom.lang.core.psi.FantomUsingPod
import co.anbora.labs.fantom.lang.library.FantomBuildFile
import co.anbora.labs.fantom.lang.library.FantomPodResolver
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import java.util.concurrent.ConcurrentHashMap

/**
 * What a file's `using` lines make visible.
 *
 * A type is visible from a file when its pod is `sys`, the file's own pod, a pod
 * imported whole (`using concurrent`), or when the type is imported by name
 * (`using concurrent::Actor`). An alias (`using a::Foo as Bar`) makes `Bar`
 * visible, not `Foo`. FFI usings (`using [java] ...`) and string pod specs are
 * ignored; they name no Fantom pod.
 */
object FantomUsingScope {

    /** `using pod::typeName [as alias]`, keyed by the name it makes visible. */
    data class TypeImport(val pod: String, val typeName: String)

    class Usings(
        /** The file's pod, or null when unknown. */
        val ownPod: String?,
        /** Pods imported whole, `sys` not included unless written. */
        val wholePods: Set<String>,
        /** Visible name to the import behind it. */
        val typeImports: Map<String, TypeImport>,
        /**
         * Pod names of the literal `depends` list of the file's `build.fan`; null when there
         * is no `build.fan` or its `depends` is not a literal list.
         */
        val depends: Set<String>? = null,
    ) {
        /**
         * True when [pod] is available to the file by its `build.fan`: `sys`, the file's own
         * pod, or a pod in `depends`. Every pod counts as available when [depends] is unknown.
         * This is a preference for completion and imports, never a resolve filter.
         */
        fun isPodAvailable(pod: String?): Boolean {
            if (pod == null || depends == null) return true
            return pod == SYS || pod == ownPod || pod in depends
        }

        /** True when [depends] is known and [pod] is not available by it. */
        fun isOutsideDepends(pod: String?): Boolean = !isPodAvailable(pod)

        /**
         * True when a type [typeName] of pod [typePod] can be named in the file. A type
         * whose pod is unknown counts as visible: no `using` could bring it in.
         */
        fun isVisible(typeName: String, typePod: String?): Boolean {
            if (typePod == null || typePod == SYS || typePod == ownPod || typePod in wholePods) return true
            // [typeName] is the name written in the file: the alias when there is one.
            return typeImports[typeName]?.pod == typePod
        }

        /** The by-name import behind a visible name (`Bar` for `using a::Foo as Bar`), or null. */
        fun importFor(visibleName: String): TypeImport? = typeImports[visibleName]
    }

    const val SYS = "sys"

    fun usingsOf(file: PsiFile): Usings =
        CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(
                compute(file), PsiModificationTracker.MODIFICATION_COUNT, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
            )
        }

    /** True when [typeDecl] can be named from [fromFile] without a pod prefix. */
    fun isVisible(typeDecl: FantomNamedElement, fromFile: PsiFile): Boolean {
        val name = typeDecl.name ?: return false
        return usingsOf(fromFile).isVisible(name, podOf(typeDecl))
    }

    /** Of several types that share a short name, those visible from [fromFile]; all when none is. */
    fun preferVisible(types: List<FantomNamedElement>, fromFile: PsiFile): List<FantomNamedElement> {
        if (types.size < 2) return types
        return types.filter { isVisible(it, fromFile) }.ifEmpty { types }
    }

    /** The pod of the file that holds [element], or null when unknown. */
    fun podOf(element: PsiElement): String? {
        val file = element.containingFile ?: return null
        return podOf(file)
    }

    fun podOf(file: PsiFile): String? {
        val vf = file.originalFile.virtualFile ?: file.viewProvider.virtualFile
        return podOf(vf, file.project)
    }

    /** [FantomPodResolver.podNameFor] with a per-folder memo that drops on VFS or PSI changes. */
    fun podOf(file: VirtualFile, project: Project): String? {
        val dir = (if (file.isDirectory) file else file.parent) ?: return null
        val memo = CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(
                ConcurrentHashMap<String, String>(),
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                PsiModificationTracker.MODIFICATION_COUNT,
            )
        }
        val pod = memo.getOrPut(dir.path) { FantomPodResolver.podNameFor(file, project) ?: NO_POD }
        return pod.takeIf { it != NO_POD }
    }

    /**
     * The pod names of the literal `depends` list of the `build.fan` above [file]; null when
     * there is no `build.fan` or `depends` is not a literal. A `build.fan` itself has none:
     * it is a script of the `build` pod, not part of the pod it describes.
     */
    fun dependsOf(file: PsiFile): Set<String>? {
        val vf = file.originalFile.virtualFile ?: file.viewProvider.virtualFile
        if (vf.name == FantomBuildFile.FILE_NAME) return null
        return FantomBuildFile.forFile(vf, file.project)?.dependsPods
    }

    /** The Fantom pod a `using` line names, or null for FFI and string specs. */
    fun podName(using: FantomUsingDef): String? = podName(using.usingPod)

    fun podName(usingPod: FantomUsingPod): String? {
        val spec = usingPod.podSpec ?: return null
        if (spec.ffiPodSpec != null || spec.str != null) return null
        val ids = spec.idList
        if (ids.isEmpty()) return null
        return ids.joinToString(".") { it.text }
    }

    /** The imported type name of `using pod::Type [as Alias]`, or null for a whole-pod import. */
    fun typeName(using: FantomUsingDef): String? = using.usingPod.idList.firstOrNull()?.text

    /** The alias of `using pod::Type as Alias`, or null. */
    fun aliasName(using: FantomUsingDef): String? = using.usingPod.idList.getOrNull(1)?.text

    /** The name a `using` line makes visible: the alias, else the type, else null for a whole pod. */
    fun visibleName(using: FantomUsingDef): String? = aliasName(using) ?: typeName(using)

    fun usingDefs(file: PsiFile): List<FantomUsingDef> = file.children.filterIsInstance<FantomUsingDef>()

    private const val NO_POD = ""

    private fun compute(file: PsiFile): Usings {
        val whole = LinkedHashSet<String>()
        val types = LinkedHashMap<String, TypeImport>()
        for (using in usingDefs(file)) {
            val pod = podName(using) ?: continue
            val type = typeName(using)
            if (type == null) whole += pod
            else types[aliasName(using) ?: type] = TypeImport(pod, type)
        }
        return Usings(podOf(file), whole, types, dependsOf(file))
    }
}
