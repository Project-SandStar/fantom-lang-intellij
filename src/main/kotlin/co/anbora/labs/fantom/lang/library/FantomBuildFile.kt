package co.anbora.labs.fantom.lang.library

import co.anbora.labs.fantom.lang.core.psi.FantomAssignExpr
import co.anbora.labs.fantom.lang.core.psi.FantomList
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil

/**
 * What a `build.fan` declares, read from its PSI.
 *
 * Only plain assignments in the file are read: `podName = "x"`, `version = Version("1.0")`,
 * `depends = ["sys 1.0", ...]`, `srcDirs = [`fan/`]` and `resDirs = [`res/`]`. A piece is
 * null when it is missing or not a literal (a variable, a method call, string
 * interpolation, a list built by code). The result is cached on the PSI file, so it is
 * computed again only when the file's modification stamp changes.
 */
class FantomBuildFile(
    val podName: String?,
    /** The version string, from `Version("1.0")` or `Version.fromStr("1.0")`. */
    val version: String?,
    /** The literal `depends` list, or null when there is none or it is not a literal. */
    val depends: List<Dependency>?,
    val srcDirs: List<String>?,
    val resDirs: List<String>?,
    /** Offset of the `podName` assignment target, for the gutter icon. -1 when unknown. */
    val podNameOffset: Int,
) {
    /** One `depends` entry: `"concurrent 1.0+"` is pod `concurrent`, constraint `1.0+`. */
    data class Dependency(val pod: String, val constraint: String)

    /** The pod names of [depends], or null when [depends] is null. */
    val dependsPods: Set<String>? get() = depends?.mapTo(LinkedHashSet()) { it.pod }

    companion object {
        const val FILE_NAME = "build.fan"

        private val STRING = Regex("^\"((?:[^\"\\\\$]|\\\\[^$])*)\"$")
        private val URI = Regex("^`([^`$]*)`$")
        private val VERSION = Regex("^Version(?:\\.fromStr)?\\s*\\(\\s*\"([^\"\\\\$]*)\"\\s*\\)$")
        private val EMPTY_LIST = Regex("^(?:\\w+)?\\[\\s*,\\s*]$")

        /** The parsed build file, cached on [file] by its modification stamp. */
        fun of(file: PsiFile): FantomBuildFile =
            CachedValuesManager.getCachedValue(file) {
                CachedValueProvider.Result.create(parse(file), file)
            }

        /** The nearest `build.fan` up the folder tree of [file], or null. */
        fun findBuildFile(file: VirtualFile): VirtualFile? {
            var dir: VirtualFile? = if (file.isDirectory) file else file.parent
            while (dir != null) {
                val build = dir.findChild(FILE_NAME)
                if (build != null && !build.isDirectory) return build
                dir = dir.parent
            }
            return null
        }

        /** The parsed nearest `build.fan` of [file], or null when there is none. */
        fun forFile(file: VirtualFile, project: Project): FantomBuildFile? {
            val build = findBuildFile(file) ?: return null
            val psi = PsiManager.getInstance(project).findFile(build) ?: return null
            return of(psi)
        }

        /** The value expression of the first `name = ...` assignment in [file], or null. */
        fun assignedValue(file: PsiFile, name: String): PsiElement? =
            assignments(file)[name]

        private fun assignments(file: PsiFile): Map<String, PsiElement> {
            val out = LinkedHashMap<String, PsiElement>()
            for (assign in PsiTreeUtil.findChildrenOfType(file, FantomAssignExpr::class.java)) {
                val op = assign.assignOp ?: continue
                if (op.text != "=") continue
                val target = assign.ifExpr
                val value = assign.assignExpr ?: continue
                val name = target.text.trim().removePrefix("this.")
                if (!name.matches(Regex("\\w+"))) continue
                out.putIfAbsent(name, value)
            }
            return out
        }

        fun parse(file: PsiFile): FantomBuildFile {
            val values = assignments(file)
            val podName = values["podName"]?.let { STRING.matchEntire(it.text.trim())?.groupValues?.get(1) }
            val version = values["version"]?.let { VERSION.matchEntire(it.text.trim())?.groupValues?.get(1) }
            val depends = values["depends"]?.let { stringList(it, STRING) }?.map(::dependency)
            val srcDirs = values["srcDirs"]?.let { stringList(it, URI) }
            val resDirs = values["resDirs"]?.let { stringList(it, URI) }
            val podNameOffset = values["podName"]?.let { (it.parent as? FantomAssignExpr)?.ifExpr?.textOffset } ?: -1
            return FantomBuildFile(podName, version, depends, srcDirs, resDirs, podNameOffset)
        }

        /** The list literal [value] is, when it is exactly one; null otherwise. */
        fun listLiteral(value: PsiElement): FantomList? {
            val range = value.textRange
            return PsiTreeUtil.findChildrenOfType(value, FantomList::class.java)
                .firstOrNull { it.textRange == range }
        }

        /** The items of a literal list whose every item matches [item], or null. */
        private fun stringList(value: PsiElement, item: Regex): List<String>? {
            val text = value.text.trim()
            if (EMPTY_LIST.matches(text)) return emptyList()
            val list = listLiteral(value) ?: return null
            val exprs = list.listItems.exprList
            return exprs.map { item.matchEntire(it.text.trim())?.groupValues?.get(1) ?: return null }
        }

        private fun dependency(spec: String): Dependency {
            val trimmed = spec.trim()
            val space = trimmed.indexOfFirst { it.isWhitespace() }
            return if (space < 0) Dependency(trimmed, "")
            else Dependency(trimmed.substring(0, space), trimmed.substring(space).trim())
        }
    }
}
