package co.anbora.labs.fantom.ide.mcp

import co.anbora.labs.fantom.ide.compiler.FantomCompilerDiagnostics
import co.anbora.labs.fantom.ide.compiler.FantomCompilerService
import co.anbora.labs.fantom.lang.FantomLanguage
import co.anbora.labs.fantom.lang.axon.AxonLanguage
import co.anbora.labs.fantom.lang.axon.psi.AxonNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.index.FantomDeclarationIndex
import co.anbora.labs.fantom.lang.index.FantomDeclarationKind
import co.anbora.labs.fantom.lang.library.FantomPodResolver
import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemDescriptorUtil
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * The answers behind the MCP tools in [FantomMcpToolset], with no MCP server types, so the
 * plugin and its tests load without the MCP Server plugin.
 *
 * Every function marked "read action" expects the caller to hold one. None of them reads
 * a file from outside the project: file arguments must be in project content, Find Usages
 * searches project files only, and a declaration outside the project is named, not quoted.
 */
object FantomMcpQueries {

    const val INDEXING = "indexing, retry"
    const val DEFAULT_LIMIT = 50
    const val MAX_LIMIT = 500
    private const val MAX_LINE_TEXT = 200

    data class Problem(
        val severity: HighlightSeverity,
        /** 1-based. */
        val line: Int,
        /** 1-based. */
        val column: Int,
        val message: String,
        /** `parser`, `compiler`, or the inspection short name. */
        val source: String,
    ) {
        fun render(): String = "${severityName(severity)} $line:$column $message [$source]"
    }

    // ---- files -------------------------------------------------------------------------

    /**
     * The file for a path the agent gave: absolute, or relative to the project folder or a
     * content root. Runs outside a read action: the lookup may list folders on disk. The
     * caller still checks [isProjectFile] in a read action.
     */
    fun findFile(path: String, basePath: String?, contentRoots: List<VirtualFile>): VirtualFile? {
        val text = path.trim().removePrefix("file://")
        if (text.isEmpty()) return null
        val p = try { Path.of(text) } catch (_: InvalidPathException) { return null }
        if (p.isAbsolute) return LocalFileSystem.getInstance().findFileByPath(p.normalize().toString())
        val rel = p.normalize().toString().replace('\\', '/')
        if (rel.startsWith("..")) return null
        if (basePath != null) {
            LocalFileSystem.getInstance().findFileByPath("$basePath/$rel")?.let { return it }
        }
        for (root in contentRoots) root.findFileByRelativePath(rel)?.let { return it }
        return null
    }

    /** Read action. */
    fun isProjectFile(project: Project, file: VirtualFile): Boolean =
        !file.isDirectory && ProjectFileIndex.getInstance(project).isInContent(file)

    /** Read action. The Fantom or Axon PSI file, or null for other files. */
    fun psiFile(project: Project, file: VirtualFile): PsiFile? {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        return psi.takeIf { isFantom(it) || isAxon(it) }
    }

    private fun isFantom(file: PsiFile) = file.language == FantomLanguage
    private fun isAxon(file: PsiFile) = file.language == AxonLanguage

    /** Read action. A short path for output: relative to the project folder or content root. */
    fun displayPath(project: Project, file: VirtualFile): String {
        val base = project.basePath
        if (base != null && file.path.startsWith("$base/")) return file.path.removePrefix("$base/")
        val root = ProjectFileIndex.getInstance(project).getContentRootForFile(file)
        return root?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.name
    }

    // ---- diagnostics -------------------------------------------------------------------

    /**
     * Read action. The problems the editor shows at weak warning or above: parse errors (after
     * the platform error filters) and the enabled inspections of the file's language, at the
     * level of the current inspection profile.
     */
    fun problems(project: Project, file: PsiFile): List<Problem> {
        val doc = file.viewProvider.document ?: return emptyList()
        val out = ArrayList<Problem>()
        val filters = HighlightErrorFilter.EP_NAME.getExtensions(project)
        for (e in SyntaxTraverser.psiTraverser(file)) {
            if (e !is PsiErrorElement) continue
            if (filters.any { !it.shouldHighlightErrorElement(e) }) continue
            out += problem(doc, e.textRange.startOffset, HighlightSeverity.ERROR, e.errorDescription, "parser")
        }
        out += inspectionProblems(project, file, doc)
        return out.sortedWith(compareBy({ it.line }, { it.column }, { it.message }))
    }

    private fun inspectionProblems(project: Project, file: PsiFile, doc: Document): List<Problem> {
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
        val manager = InspectionManager.getInstance(project)
        val out = ArrayList<Problem>()
        for (tools in profile.getAllEnabledInspectionTools(project)) {
            val wrapper = tools.getEnabledTool(file) as? LocalInspectionToolWrapper ?: continue
            if (wrapper.language != file.language.id) continue
            val key = HighlightDisplayKey.find(wrapper.shortName) ?: continue
            val level = profile.getErrorLevel(key, file)
            val holder = ProblemsHolder(manager, file, false)
            val visitor = wrapper.tool.buildVisitor(holder, false)
            for (e in SyntaxTraverser.psiTraverser(file)) e.accept(visitor)
            for (d in holder.results) {
                val severity = severityOf(d, level) ?: continue
                if (severity < HighlightSeverity.WEAK_WARNING) continue
                val element = d.psiElement ?: continue
                val start = element.textRange.startOffset + (d.textRangeInElement?.startOffset ?: 0)
                val message = StringUtil.removeHtmlTags(ProblemDescriptorUtil.renderDescriptionMessage(d, element))
                out += problem(doc, start, severity, message, wrapper.shortName)
            }
        }
        return out
    }

    private fun severityOf(d: ProblemDescriptor, level: HighlightDisplayLevel): HighlightSeverity? =
        when (d.highlightType) {
            ProblemHighlightType.ERROR, ProblemHighlightType.GENERIC_ERROR -> HighlightSeverity.ERROR
            ProblemHighlightType.WARNING -> HighlightSeverity.WARNING
            ProblemHighlightType.WEAK_WARNING -> HighlightSeverity.WEAK_WARNING
            ProblemHighlightType.INFORMATION -> null
            else -> level.severity
        }

    /**
     * The compiler messages for [file] when the last compile of its pod is newer than the
     * file, as the editor shows them; null when there is no fresh compile. Never starts the
     * compiler. Call it outside a read action: it reads `build.fan` to find the pod.
     */
    fun compilerProblems(project: Project, file: PsiFile): List<Problem>? {
        val vf = file.virtualFile ?: return null
        val service = FantomCompilerService.getInstance(project)
        val podRoot = service.podRootFor(vf)
        val result = if (podRoot != null) {
            if (!service.isFresh(file)) return null
            service.result(podRoot) ?: return null
        } else {
            // Compile-on-save off: the editor shows the last Build Pod result while the file is unchanged.
            val built = service.builtRootFor(vf) ?: return null
            val builtAt = service.builtAt(built) ?: return null
            if (vf.timeStamp > builtAt || isUnsaved(vf)) return null
            service.result(built) ?: return null
        }
        return result.forFile(FantomCompilerDiagnostics.canonical(vf.path))
            .map { Problem(it.severity, it.line, it.column, it.message, "compiler") }
    }

    private fun isUnsaved(vf: VirtualFile): Boolean {
        val fdm = FileDocumentManager.getInstance()
        val doc = fdm.getCachedDocument(vf) ?: return false
        return fdm.isDocumentUnsaved(doc)
    }

    private fun problem(doc: Document, offset: Int, severity: HighlightSeverity, message: String, source: String): Problem {
        val o = offset.coerceIn(0, doc.textLength)
        val line = doc.getLineNumber(o)
        return Problem(severity, line + 1, o - doc.getLineStartOffset(line) + 1, message, source)
    }

    fun severityName(s: HighlightSeverity): String = when {
        s >= HighlightSeverity.ERROR -> "ERROR"
        s >= HighlightSeverity.WARNING -> "WARNING"
        else -> "WEAK_WARNING"
    }

    // ---- resolve -----------------------------------------------------------------------

    /** Read action. The offset from [offset], or else from 1-based [line] and [column]; null when out of range. */
    fun offsetOf(file: PsiFile, offset: Int, line: Int, column: Int): Int? {
        val doc = file.viewProvider.document ?: return null
        if (offset >= 0) return offset.takeIf { it <= doc.textLength }
        if (line < 1 || line > doc.lineCount) return null
        val start = doc.getLineStartOffset(line - 1)
        val o = start + (column - 1).coerceAtLeast(0)
        return o.takeIf { it <= doc.getLineEndOffset(line - 1) }
    }

    /**
     * Read action. What Go to Declaration opens for [offset]: the targets of the reference
     * there, or the declaration whose name is there. Tries the character before the offset
     * too, so an offset just after a name works, as in the editor.
     */
    fun targetsAt(file: PsiFile, offset: Int): List<PsiElement> {
        for (o in listOf(offset, offset - 1)) {
            if (o < 0) continue
            val ref = file.findReferenceAt(o)
            if (ref != null) {
                val found = resolve(ref)
                if (found.isNotEmpty()) return found
            }
            declarationNamedAt(file, o)?.let { return listOf(it) }
        }
        return emptyList()
    }

    private fun resolve(ref: PsiReference): List<PsiElement> =
        if (ref is PsiPolyVariantReference) ref.multiResolve(false).mapNotNull { it.element }.distinct()
        else listOfNotNull(ref.resolve())

    private fun declarationNamedAt(file: PsiFile, offset: Int): PsiElement? {
        var e = file.findElementAt(offset)
        while (e != null && e !is PsiFile) {
            if (e is PsiNameIdentifierOwner && e.nameIdentifier?.textRange?.containsOffset(offset) == true) return e
            e = e.parent
        }
        return null
    }

    private val qualifiedName = Regex("""^(?:([A-Za-z_]\w*)::)?([A-Za-z_]\w*)(?:\.([A-Za-z_]\w*))?$""")

    /** A parsed `pod::Type.slot`, `Type.slot` or `Type`. */
    data class QualifiedName(val pod: String?, val type: String, val slot: String?)

    /** The parts of [text], or null when it does not have the shape `pod::Type.slot`, `Type.slot` or `Type`. */
    fun parseQualifiedName(text: String): QualifiedName? {
        val m = qualifiedName.matchEntire(text.trim()) ?: return null
        return QualifiedName(m.groupValues[1].ifEmpty { null }, m.groupValues[2], m.groupValues[3].ifEmpty { null })
    }

    /**
     * Read action. Fantom declarations for [name], from the declaration index (project and
     * library sources). Does not check the pod: that needs `build.fan`, which the caller
     * reads outside the read action, see [podName].
     */
    fun findByQualifiedName(project: Project, name: QualifiedName): List<FantomNamedElement> {
        val scope = GlobalSearchScope.allScope(project)
        return if (name.slot == null) {
            FantomDeclarationIndex.findDeclarations(name.type, project, scope) { it.isType }
        } else {
            FantomDeclarationIndex.findDeclarations(name.slot, project, scope) { !it.isType }
                .filter { FantomDeclarationIndex.enclosingTypeName(it) == name.type }
        }
    }

    /** The pod of a Fantom file. Not in a read action: it may read `build.fan`. */
    fun podName(project: Project, file: VirtualFile): String? = FantomPodResolver.podNameFor(file, project)

    // ---- usages ------------------------------------------------------------------------

    /** Read action. The declaration whose usages to find at [offset]: the one named there, or the one the name there resolves to. */
    fun usageTargetAt(file: PsiFile, offset: Int): PsiElement? {
        for (o in listOf(offset, offset - 1)) {
            if (o < 0) continue
            declarationNamedAt(file, o)?.let { return it }
        }
        return targetsAt(file, offset).firstOrNull()
    }

    /**
     * Read action. Find Usages: the references to [target] in project files. The search is
     * the one IDE Find Usages runs: the project files, cut down to the use scope of [target]
     * (an Axon local stays in its function, a proj-folder function in its project folder).
     */
    fun usages(project: Project, target: PsiElement): List<PsiReference> {
        val scope = GlobalSearchScope.projectScope(project)
        return ReferencesSearch.search(target, scope).findAll()
            .filter { it.element.containingFile?.virtualFile?.let { vf -> isProjectFile(project, vf) } == true }
            .sortedWith(compareBy({ it.element.containingFile.virtualFile.path }, { offsetOf(it) }))
    }

    fun offsetOf(ref: PsiReference): Int = ref.element.textRange.startOffset + ref.rangeInElement.startOffset

    // ---- output ------------------------------------------------------------------------

    /**
     * Read action. `kind name` of a declaration, with the Fantom qualified name when it has
     * one. [pods] maps a file to its pod, found before the read action with [podName].
     */
    fun describe(element: PsiElement, pods: Map<VirtualFile, String?>): String = when (element) {
        is FantomNamedElement -> {
            val kind = FantomDeclarationKind.of(element)
            val name = element.name ?: "?"
            when {
                kind == null -> "local $name"
                kind.isType -> "${kind.name.lowercase()} ${qualify(element, name, pods)}"
                else -> {
                    val owner = FantomDeclarationIndex.enclosingTypeName(element)
                    val qn = if (owner == null) name else "${qualify(element, owner, pods)}.$name"
                    "${kind.name.lowercase()} $qn"
                }
            }
        }
        is AxonNamedElement -> "axon ${element.name ?: "?"}"
        is PsiNamedElement -> "${element.javaClass.simpleName.removeSuffix("Impl")} ${element.name ?: "?"}"
        else -> element.javaClass.simpleName
    }

    private fun qualify(element: PsiElement, type: String, pods: Map<VirtualFile, String?>): String {
        val vf = element.containingFile?.virtualFile ?: return type
        val pod = pods[vf] ?: return type
        return "$pod::$type"
    }

    /** Read action. The files whose pod [describe] needs: those of the Fantom elements. */
    fun fantomFiles(elements: Collection<PsiElement>): Set<VirtualFile> =
        elements.filterIsInstance<FantomNamedElement>().mapNotNull { it.containingFile?.virtualFile }.toSet()

    /**
     * Read action. `path:line:column` and the source line for a place in a project file;
     * only `(outside project)` for anything else, so no library or JDK text goes out.
     */
    fun location(project: Project, element: PsiElement, offset: Int): String {
        val file = element.containingFile ?: return "(no file)"
        val vf = file.virtualFile ?: return "(no file)"
        if (!isProjectFile(project, vf)) return "(outside project)"
        val doc = file.viewProvider.document ?: return displayPath(project, vf)
        val o = offset.coerceIn(0, doc.textLength)
        val line = doc.getLineNumber(o)
        val col = o - doc.getLineStartOffset(line) + 1
        val text = doc.getText(com.intellij.openapi.util.TextRange(doc.getLineStartOffset(line), doc.getLineEndOffset(line)))
            .trim().take(MAX_LINE_TEXT)
        return "${displayPath(project, vf)}:${line + 1}:$col  $text"
    }

    /** The offset of a declaration's name, else of the element. */
    fun nameOffset(element: PsiElement): Int =
        (element as? PsiNameIdentifierOwner)?.nameIdentifier?.textRange?.startOffset ?: element.textRange.startOffset

    fun clampLimit(limit: Int): Int = if (limit <= 0) DEFAULT_LIMIT else limit.coerceAtMost(MAX_LIMIT)
}
