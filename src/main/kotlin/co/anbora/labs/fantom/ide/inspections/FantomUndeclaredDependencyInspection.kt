package co.anbora.labs.fantom.ide.inspections

import co.anbora.labs.fantom.lang.core.psi.FantomUsingDef
import co.anbora.labs.fantom.lang.library.FantomBuildFile
import co.anbora.labs.fantom.lang.library.FantomSettings
import co.anbora.labs.fantom.lang.resolve.FantomUsingScope
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Warns on `using x` or `using x::T` when pod `x` is not in the `depends` of the file's
 * `build.fan`. The compiler would reject it; the IDE says so before a build.
 *
 * Quiet when there is no `build.fan`, when its `depends` is not a literal list or code
 * adds to it (`depends.addAll([...])`), for `sys`,
 * for the file's own pod, and inside `build.fan` itself (a script of the `build` pod).
 * The quick fix appends `"x 1.0"` to the `depends` list literal.
 */
class FantomUndeclaredDependencyInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val vf = holder.file.originalFile.virtualFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (vf.name == FantomBuildFile.FILE_NAME) return PsiElementVisitor.EMPTY_VISITOR
        val build = FantomBuildFile.forFile(vf, holder.project) ?: return PsiElementVisitor.EMPTY_VISITOR
        val depends = build.dependsPods ?: return PsiElementVisitor.EMPTY_VISITOR
        // Code adds to `depends` (`depends.addAll([...])`): the literal list is not the whole set.
        if (build.dependsChangedInCode) return PsiElementVisitor.EMPTY_VISITOR
        val ownPod = build.podName ?: FantomUsingScope.podOf(holder.file)
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is FantomUsingDef) return
                val pod = FantomUsingScope.podName(element) ?: return
                if (pod == FantomUsingScope.SYS || pod == ownPod || pod in depends) return
                val version = versionOf(holder.project, pod)
                holder.registerProblem(
                    element.usingPod.podSpec ?: element.usingPod,
                    "Pod '$pod' is not in the depends of build.fan",
                    AddDependencyFix(pod, version),
                )
            }
        }
    }

    /** Appends `"pod version"` to the literal `depends` list of the nearest `build.fan`. */
    class AddDependencyFix(private val pod: String, private val version: String) : LocalQuickFix {
        override fun getFamilyName(): String = "Add to depends"
        override fun getName(): String = "Add '$pod $version' to depends"

        // The edit goes to build.fan, not to the file under the caret.
        override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo =
            IntentionPreviewInfo.EMPTY

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val file = descriptor.psiElement?.containingFile ?: return
            val vf = file.originalFile.virtualFile ?: return
            val buildVf = FantomBuildFile.findBuildFile(vf) ?: return
            val buildPsi = PsiManager.getInstance(project).findFile(buildVf) ?: return
            addDependency(project, buildPsi, "$pod $version")
        }
    }

    companion object {
        private val versions = ConcurrentHashMap<String, String>()

        /**
         * Edits the `depends` list of [buildFile] to end with `"spec"`. A list written one
         * item per line gets the new item on its own line, aligned with the last one.
         * Returns false when `depends` is missing or not a literal.
         */
        fun addDependency(project: Project, buildFile: com.intellij.psi.PsiFile, spec: String): Boolean {
            val value = FantomBuildFile.assignedValue(buildFile, "depends") ?: return false
            if (FantomBuildFile.of(buildFile).depends == null) return false
            val docManager = PsiDocumentManager.getInstance(project)
            val document = docManager.getDocument(buildFile) ?: return false
            docManager.doPostponedOperationsAndUnblockDocument(document)
            val item = "\"" + StringUtil.escapeStringCharacters(spec) + "\""
            val items = FantomBuildFile.listLiteral(value)?.listItems?.exprList.orEmpty()
            val last = items.lastOrNull()
            if (last == null) {
                // `[,]` or `Str[,]`: replace the whole empty list.
                document.replaceString(value.textRange.startOffset, value.textRange.endOffset, "[$item]")
            } else {
                val end = last.textRange.endOffset
                val lastLine = document.getLineNumber(last.textRange.startOffset)
                val multiLine = items.size > 1 && document.getLineNumber(items[items.size - 2].textRange.startOffset) != lastLine
                val insert = if (multiLine) {
                    val column = last.textRange.startOffset - document.getLineStartOffset(lastLine)
                    ",\n" + " ".repeat(column) + item
                } else ", $item"
                document.insertString(end, insert)
            }
            docManager.commitDocument(document)
            return true
        }

        /**
         * The version to depend on, as `major.minor`: from `pod.version` in the `meta.props`
         * of `<home>/lib/fan/<pod>.pod`, else the `version` of the pod's source `build.fan`,
         * else `1.0`.
         */
        fun versionOf(project: Project, pod: String): String {
            val settings = FantomSettings.getInstance(project)
            val homes = listOfNotNull(settings.configuredHome(), settings.effectiveHome()).distinct()
            val key = homes.joinToString("|") { it.path } + "|" + pod
            versions[key]?.let { return it }
            val full = homes.firstNotNullOfOrNull { podMetaVersion(File(it, "lib/fan/$pod.pod")) }
                ?: sourceVersion(project, settings, pod)
            val version = full?.let(::majorMinor) ?: "1.0"
            versions[key] = version
            return version
        }

        fun reset() = versions.clear()

        private fun podMetaVersion(podFile: File): String? {
            if (!podFile.isFile) return null
            return runCatching {
                ZipFile(podFile).use { zip ->
                    val entry = zip.getEntry("meta.props") ?: return@use null
                    zip.getInputStream(entry).bufferedReader().useLines { lines ->
                        lines.firstOrNull { it.startsWith("pod.version=") }?.substringAfter("=")?.trim()
                    }
                }
            }.getOrNull()
        }

        private fun sourceVersion(project: Project, settings: FantomSettings, pod: String): String? {
            val source = settings.podSources().firstOrNull { it.pod == pod } ?: return null
            val build = LocalFileSystem.getInstance().findFileByIoFile(File(source.podDir, FantomBuildFile.FILE_NAME)) ?: return null
            val psi = PsiManager.getInstance(project).findFile(build) ?: return null
            return FantomBuildFile.of(psi).version
        }

        private fun majorMinor(version: String): String? {
            val parts = version.split('.').filter { it.isNotEmpty() }
            if (parts.isEmpty() || parts.any { p -> p.any { !it.isDigit() } }) return null
            return parts.take(2).let { if (it.size == 1) it + "0" else it }.joinToString(".")
        }
    }
}
