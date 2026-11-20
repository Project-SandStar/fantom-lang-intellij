package co.anbora.labs.fantom.ide.natives

import co.anbora.labs.fantom.ide.presentation.FantomPresentation
import co.anbora.labs.fantom.lang.core.psi.FantomFieldDef
import co.anbora.labs.fantom.lang.core.psi.FantomMethodDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.library.FantomBuildFile
import co.anbora.labs.fantom.lang.resolve.FantomScope
import com.intellij.openapi.progress.ProgressManager
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
 * The lookup is by file name and text, so it needs neither the Java nor the JavaScript
 * plugin. A Java or JS method counts when the file holds `name(` (`now(self)`,
 * `long now(Clock self)`) or the old JS style `name = function(` / `name: function(`.
 * `build.fan` is read from its cached PSI ([FantomBuildFile]); peer files are read from
 * their file view provider, which uses the open document or the VFS content cache.
 */
object FantomNatives {

    enum class Kind(val label: String, val buildField: String, val defaultDir: String, val ext: String) {
        JAVA("Java", "javaDirs", "java", "java"),
        JS("JS", "jsDirs", "js", "js"),
    }

    /** True when [slot] is a method or field marked `native`. */
    fun isNative(slot: PsiElement): Boolean =
        (slot is FantomMethodDef || slot is FantomFieldDef) && "native" in FantomPresentation.modifiers(slot as FantomNamedElement)

    /**
     * The folders of [kind] the pod of [slot] declares in `build.fan` (`javaDirs = [`java/`]`).
     * [declaredOnly] false falls back to `java/` or `js/` when `build.fan` names none.
     * `javaDirs = Uri[,]` declares no folder.
     */
    fun dirs(slot: PsiElement, kind: Kind, declaredOnly: Boolean): List<VirtualFile> {
        val vf = slot.containingFile?.originalFile?.virtualFile ?: return emptyList()
        val buildVf = FantomBuildFile.findBuildFile(vf) ?: return emptyList()
        val pod = buildVf.parent ?: return emptyList()
        val build = FantomBuildFile.forFile(vf, slot.project) ?: return emptyList()
        val declared = when (kind) {
            Kind.JAVA -> build.javaDirs
            Kind.JS -> build.jsDirs
        }
        if (declared != null) {
            return declared.mapNotNull { pod.findFileByRelativePath(it.trimEnd('/')) }.filter { it.isDirectory }
        }
        if (declaredOnly) return emptyList()
        return listOfNotNull(pod.findChild(kind.defaultDir)?.takeIf { it.isDirectory })
    }

    /** The peer files of the type of [slot] in [kind]'s folders: `<Type>Peer.ext` or `<Type>.ext`. */
    fun peerFiles(slot: FantomNamedElement, kind: Kind, declaredOnly: Boolean = false): List<VirtualFile> {
        val typeName = FantomScope.enclosingType(slot)?.name ?: return emptyList()
        val names = listOf("${typeName}Peer.${kind.ext}", "$typeName.${kind.ext}")
        return dirs(slot, kind, declaredOnly).flatMap { findFiles(it, names) }
    }

    /** The places [slot] (a native slot) is written in [kind]'s folders: one element per hit. */
    fun implementations(slot: FantomNamedElement, kind: Kind, declaredOnly: Boolean = false): List<PsiElement> {
        val name = slot.name ?: return emptyList()
        val psiManager = PsiManager.getInstance(slot.project)
        val pattern = methodPattern(name)
        val out = ArrayList<PsiElement>()
        for (vf in peerFiles(slot, kind, declaredOnly)) {
            ProgressManager.checkCanceled()
            val view = psiManager.findViewProvider(vf) ?: continue
            val hit = pattern.find(view.contents) ?: continue
            val psi = view.getPsi(view.baseLanguage) ?: continue
            out += psi.findElementAt(hit.range.first) ?: psi
        }
        return out
    }

    /**
     * `name(` (not a call `.name(` and not part of a longer name), or `name = function(` /
     * `name: function(` (also `X.prototype.name = function(`).
     */
    internal fun methodPattern(name: String): Regex {
        val n = Regex.escape(name)
        return Regex("""(?<![\w$.])$n\s*\(|(?<![\w$])$n\s*[=:]\s*function\s*\(""")
    }

    // Files named one of [names] in [dir] and its subfolders (`js/es/`), up to three levels.
    private fun findFiles(dir: VirtualFile, names: List<String>, depth: Int = 0): List<VirtualFile> {
        val out = ArrayList<VirtualFile>()
        for (child in dir.children) {
            ProgressManager.checkCanceled()
            if (child.isDirectory) { if (depth < 3) out += findFiles(child, names, depth + 1) }
            else if (child.name in names) out += child
        }
        return out
    }
}
