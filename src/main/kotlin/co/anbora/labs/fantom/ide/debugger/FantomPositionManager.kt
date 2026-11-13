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
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.sun.jdi.AbsentInformationException
import com.sun.jdi.Location
import com.sun.jdi.ReferenceType
import com.sun.jdi.request.ClassPrepareRequest
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps `.fan` source lines to JVM locations and back.
 *
 * The Fantom JVM runtime emits each type `Foo` of pod `p` as the class `fan.p.Foo`, closures as
 * `fan.p.Foo$method$N`, with `SourceFile` set to the `.fan` file name and a line number table in
 * `.fan` lines. So a class belongs to a file when its name starts with `fan.<pod>.` and its source
 * name is the file name.
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
        val prefix = "fan.$pod."
        return process.virtualMachineProxy.allClasses().filter {
            it.name().startsWith(prefix) && it.isPrepared && sourceName(it) == name
        }
    }

    override fun locationsOfLine(type: ReferenceType, position: SourcePosition): List<Location> {
        val (_, name) = podAndName(position)
        if (sourceName(type) != name) return emptyList()
        return try {
            type.locationsOfLine(position.line + 1)
        } catch (_: AbsentInformationException) {
            emptyList()
        }
    }

    override fun createPrepareRequest(requestor: ClassPrepareRequestor, position: SourcePosition): ClassPrepareRequest? {
        val (pod, name) = podAndName(position)
        val request = process.requestsManager.createClassPrepareRequest(requestor, "fan.$pod.*") ?: return null
        runCatching { request.addSourceNameFilter(name) }
        return request
    }

    /** The pod and file name of a position in a `.fan` file; [NoDataException] for anything else. */
    private fun podAndName(position: SourcePosition): Pair<String, String> {
        val file = position.file
        if (file.fileType != FantomFileType) throw NoDataException.INSTANCE
        val vf = file.virtualFile ?: file.originalFile.virtualFile ?: throw NoDataException.INSTANCE
        val pod = ReadAction.computeBlocking<String?, RuntimeException> { FantomPodResolver.podNameFor(vf, file.project) }
            ?: throw NoDataException.INSTANCE
        return pod to vf.name
    }

    private fun findFile(pod: String, source: String): PsiFile? {
        val project = process.project
        val cached = files[pod to source]?.takeIf { it.isValid }
        val vf = cached ?: run {
            val candidates = FilenameIndex.getVirtualFilesByName(source, GlobalSearchScope.allScope(project))
            val inPod = candidates.filter { FantomPodResolver.podNameFor(it, project) == pod }
            (inPod.firstOrNull() ?: candidates.singleOrNull())?.also { files[pod to source] = it }
        } ?: return null
        return PsiManager.getInstance(project).findFile(vf)
    }

    companion object {
        /** `fan.<pod>.<Type>...` -> `<pod>`; null for other classes. `fan.sys` passes here and fails the source name check. */
        fun podOfClass(className: String): String? {
            if (!className.startsWith("fan.")) return null
            val pod = className.removePrefix("fan.").substringBefore('.', "")
            return pod.takeIf { it.isNotEmpty() }
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
