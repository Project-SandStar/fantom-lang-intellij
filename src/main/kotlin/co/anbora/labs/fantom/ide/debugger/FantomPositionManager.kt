package co.anbora.labs.fantom.ide.debugger

import co.anbora.labs.fantom.lang.FantomFileType
import co.anbora.labs.fantom.lang.library.FantomPodResolver
import com.intellij.debugger.NoDataException
import com.intellij.debugger.PositionManager
import com.intellij.debugger.PositionManagerFactory
import com.intellij.debugger.SourcePosition
import com.intellij.debugger.engine.DebugProcess
import com.intellij.debugger.requests.ClassPrepareRequestor
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.sun.jdi.AbsentInformationException
import com.sun.jdi.Location
import com.sun.jdi.ReferenceType
import com.sun.jdi.request.ClassPrepareRequest
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps `.fan` source lines to JVM locations and back.
 *
 * The Fantom JVM runtime emits each type `Foo` of pod `p` as the class `fan.p.Foo`, closures as
 * `fan.p.Foo$method$N`, with `SourceFile` set to the `.fan` file name and a line number table in
 * `.fan` lines. So a class belongs to a file when its name starts with `fan.<pod>.` and its source
 * name is the file name.
 *
 * A standalone script (a `.fan` file outside any pod, run as `fan Foo.fan`) is compiled into a pod
 * with a generated name: the file's base name with only ASCII letters kept (and digits after the
 * first character), then `_<n>` from a per-process counter (`fanx.util.EnvScripts.generatePodName`,
 * the same in Fantom 1.0.78 to 1.0.84). So `Foo.fan` runs as `fan.Foo_0.Foo`. The `SourceFile` of
 * a script class is the full path of the script, not only its name. Script classes are matched by
 * that pod pattern plus the source name.
 */
class FantomPositionManager(private val process: DebugProcess) : PositionManager {
    private val files = ConcurrentHashMap<Pair<String, String>, VirtualFile>()

    override fun isAcceptedFileType(fileType: FileType): Boolean = fileType == FantomFileType

    override fun getSourcePosition(location: Location?): SourcePosition? {
        if (location == null) throw NoDataException.INSTANCE
        val pod = podOfClass(location.declaringType().name()) ?: throw NoDataException.INSTANCE
        val source = try {
            location.sourceName()
        } catch (_: AbsentInformationException) {
            throw NoDataException.INSTANCE
        }
        if (!source.endsWith(".fan")) throw NoDataException.INSTANCE
        val line = location.lineNumber()
        val psi = ReadAction.computeBlocking<PsiFile?, RuntimeException> { findFile(pod, source) } ?: throw NoDataException.INSTANCE
        return SourcePosition.createFromLine(psi, (line - 1).coerceAtLeast(0))
    }

    override fun getAllClasses(position: SourcePosition): List<ReferenceType> {
        val (pod, name) = podAndName(position)
        return process.virtualMachineProxy.allClasses().filter {
            inPod(it.name(), pod, name) && it.isPrepared && sourceMatches(sourceName(it), name)
        }
    }

    override fun locationsOfLine(type: ReferenceType, position: SourcePosition): List<Location> {
        val (_, name) = podAndName(position)
        if (!sourceMatches(sourceName(type), name)) return emptyList()
        return try {
            type.locationsOfLine(position.line + 1)
        } catch (_: AbsentInformationException) {
            emptyList()
        }
    }

    override fun createPrepareRequest(requestor: ClassPrepareRequestor, position: SourcePosition): ClassPrepareRequest? {
        val (pod, name) = podAndName(position)
        val pattern = if (pod != null) "fan.$pod.*" else "fan.${scriptPodBase(name)}_*"
        val request = process.requestsManager.createClassPrepareRequest(requestor, pattern) ?: return null
        runCatching { request.addSourceNameFilter(if (pod != null) name else "*$name") }
        return request
    }

    /**
     * The pod and file name of a position in a `.fan` file; [NoDataException] for anything else.
     * The pod is null for a standalone script, whose pod name is generated at run time.
     */
    private fun podAndName(position: SourcePosition): Pair<String?, String> {
        val file = position.file
        if (file.fileType != FantomFileType) throw NoDataException.INSTANCE
        val vf = file.virtualFile ?: file.originalFile.virtualFile ?: throw NoDataException.INSTANCE
        val pod = ReadAction.computeBlocking<String?, RuntimeException> { FantomPodResolver.podNameFor(vf, file.project) }
        return pod to vf.name
    }

    private fun findFile(pod: String, sourcePath: String): PsiFile? {
        val project = process.project
        val source = fileName(sourcePath)
        val cached = files[pod to sourcePath]?.takeIf { it.isValid }
        val vf = cached ?: scriptFile(pod, sourcePath)?.also { files[pod to sourcePath] = it } ?: run {
            val candidates = FilenameIndex.getVirtualFilesByName(source, GlobalSearchScope.allScope(project))
            val wanted = if (isScriptPod(pod, source)) null else pod
            val inPod = candidates.filter { FantomPodResolver.podNameFor(it, project) == wanted }
            (inPod.firstOrNull() ?: candidates.singleOrNull())?.also { files[pod to sourcePath] = it }
        } ?: return null
        return PsiManager.getInstance(project).findFile(vf)
    }

    /**
     * The script at [sourcePath] when it is a path and [pod] is its generated pod. The runtime may
     * report a canonical path (`/private/var/...` on macOS), so a project file with the same
     * canonical path wins over a second VFS entry for the same file.
     */
    private fun scriptFile(pod: String, sourcePath: String): VirtualFile? {
        val name = fileName(sourcePath)
        if (sourcePath == name || !isScriptPod(pod, name)) return null
        val canonical = runCatching { File(sourcePath).canonicalPath }.getOrNull() ?: return null
        val project = process.project
        return FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.allScope(project))
            .firstOrNull { runCatching { File(it.path).canonicalPath }.getOrNull() == canonical }
            ?: LocalFileSystem.getInstance().findFileByPath(sourcePath.replace('\\', '/'))
    }

    companion object {
        /** The last segment of a `SourceFile` value: a plain name for pod classes, a path for scripts. */
        fun fileName(source: String): String = source.substringAfterLast('/').substringAfterLast('\\')

        /** True when the `SourceFile` [source] is the file [name], alone or as the last path segment. */
        fun sourceMatches(source: String?, name: String): Boolean = source != null && fileName(source) == name

        /** `fan.<pod>.<Type>...` -> `<pod>`; null for other classes. `fan.sys` passes here and fails the source name check. */
        fun podOfClass(className: String): String? {
            if (!className.startsWith("fan.")) return null
            val pod = className.removePrefix("fan.").substringBefore('.', "")
            return pod.takeIf { it.isNotEmpty() }
        }

        /**
         * The fixed part of the pod name Fantom generates for the script [fileName]: the base name
         * with only ASCII letters, and digits after the first character (`EnvScripts.generatePodName`).
         */
        fun scriptPodBase(fileName: String): String {
            val base = fileName.substringBeforeLast('.')
            return buildString {
                base.forEachIndexed { i, c ->
                    if (c in 'a'..'z' || c in 'A'..'Z' || (i > 0 && c in '0'..'9')) append(c)
                }
            }
        }

        /** True when [pod] is a generated script pod for [fileName]: `<scriptPodBase>_<n>`. */
        fun isScriptPod(pod: String, fileName: String): Boolean {
            val base = scriptPodBase(fileName)
            if (!pod.startsWith(base + "_")) return false
            val n = pod.substring(base.length + 1)
            return n.isNotEmpty() && n.all { it in '0'..'9' }
        }

        /** True when [className] belongs to [pod], or to a script pod for [fileName] when [pod] is null. */
        private fun inPod(className: String, pod: String?, fileName: String): Boolean {
            if (pod != null) return className.startsWith("fan.$pod.")
            val classPod = podOfClass(className) ?: return false
            return isScriptPod(classPod, fileName)
        }

        private fun sourceName(type: ReferenceType): String? =
            try {
                type.sourceName()
            } catch (_: AbsentInformationException) {
                null
            }
    }
}

class FantomPositionManagerFactory : PositionManagerFactory() {
    override fun createPositionManager(process: DebugProcess): PositionManager = FantomPositionManager(process)
}
