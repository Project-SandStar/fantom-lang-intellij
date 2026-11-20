package co.anbora.labs.fantom.ide.natives

import co.anbora.labs.fantom.ide.presentation.FantomPresentation
import co.anbora.labs.fantom.lang.core.psi.FantomFieldDef
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.resolve.FantomScope
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager

/**
 * Natives (docLang `JavaScript.md` "Natives", and the Java peer pattern). A slot marked
 * `native` is written in another language: in the pod's Java folder as a method of
 * `<Type>Peer.java` (or of `<Type>.java` for a `native class`), and in its JS folder in
 * `<Type>Peer.js` (or `<Type>.js`). The folders are the `javaDirs` and `jsDirs` of the
 * pod's `build.fan`; without them, `java/` and `js/` when they exist.
 *
 * The lookup is by file name and text (`name(` in the file), so it needs neither the Java
 * nor the JavaScript plugin. It reads the VFS only, which is cached.
 */
object FantomNatives {

    enum class Kind(val label: String, val buildField: String, val defaultDir: String, val ext: String) {
        JAVA("Java", "javaDirs", "java", "java"),
        JS("JS", "jsDirs", "js", "js"),
    }

    /** True when [slot] is a method or field marked `native`. */
    fun isNative(slot: PsiElement): Boolean =
        (slot is FantomMethodDef || slot is FantomFieldDef) && "native" in FantomPresentation.modifiers(slot as FantomNamedElement)

    /** The pod folder of [file]: the nearest folder above it that holds a `build.fan`. */
    fun podDir(file: VirtualFile?): VirtualFile? {
        var dir = file?.parent
        while (dir != null) {
            if (dir.findChild("build.fan") != null) return dir
            dir = dir.parent
        }
        return null
    }

    /**
     * The folders of [kind] the pod's `build.fan` declares (`javaDirs = [`java/`]`).
     * [declaredOnly] false falls back to `java/` or `js/` when `build.fan` names none.
     */
    fun dirs(pod: VirtualFile, kind: Kind, declaredOnly: Boolean): List<VirtualFile> {
        val build = pod.findChild("build.fan") ?: return emptyList()
        val text = try { VfsUtilCore.loadText(build) } catch (_: java.io.IOException) { return emptyList() }
        val list = Regex("""\b${kind.buildField}\s*=\s*\[([^\]]*)]""").find(text)?.groupValues?.get(1)
        if (list != null) {
            return Regex("`([^`]+)`").findAll(list).mapNotNull { pod.findFileByRelativePath(it.groupValues[1].trimEnd('/')) }
                .filter { it.isDirectory }.toList()
        }
        if (declaredOnly) return emptyList()
        return listOfNotNull(pod.findChild(kind.defaultDir)?.takeIf { it.isDirectory })
    }

    /** The places [slot] (a native slot) is written in [kind]'s folders: one element per hit. */
    fun implementations(slot: FantomNamedElement, kind: Kind, declaredOnly: Boolean = false): List<PsiElement> {
        val name = slot.name ?: return emptyList()
        val typeName = FantomScope.enclosingType(slot)?.name ?: return emptyList()
        val pod = podDir(slot.containingFile?.originalFile?.virtualFile) ?: return emptyList()
        val files = listOf("${typeName}Peer.${kind.ext}", "$typeName.${kind.ext}")
        val psiManager = PsiManager.getInstance(slot.project)
        val out = ArrayList<PsiElement>()
        val call = Regex("""\b${Regex.escape(name)}\s*\(""")
        for (dir in dirs(pod, kind, declaredOnly)) {
            for (vf in findFiles(dir, files)) {
                val text = try { VfsUtilCore.loadText(vf) } catch (_: java.io.IOException) { continue }
                val hit = call.find(text) ?: continue
                val psi = psiManager.findFile(vf) ?: continue
                out += psi.findElementAt(hit.range.first) ?: psi
            }
        }
        return out
    }

    /** True when [kind]'s folders exist for the pod of [slot] (declared in `build.fan`). */
    fun hasDeclaredDirs(slot: FantomNamedElement, kind: Kind): Boolean {
        val pod = podDir(slot.containingFile?.originalFile?.virtualFile) ?: return false
        return dirs(pod, kind, declaredOnly = true).isNotEmpty()
    }

    // Files named one of [names] in [dir] and its subfolders (`js/es/`), up to three levels.
    private fun findFiles(dir: VirtualFile, names: List<String>, depth: Int = 0): List<VirtualFile> {
        val out = ArrayList<VirtualFile>()
        for (child in dir.children) {
            if (child.isDirectory) { if (depth < 3) out += findFiles(child, names, depth + 1) }
            else if (child.name in names) out += child
        }
        return out
    }
}
