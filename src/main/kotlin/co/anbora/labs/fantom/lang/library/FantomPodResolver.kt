package co.anbora.labs.fantom.lang.library

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * The pod a `.fan` file belongs to.
 *
 * Rule 1: the `podName = "x"` string in the nearest `build.fan` up the directory tree.
 * Rule 2: the parent of the `fan` folder: `src/<pod>/fan/X.fan` -> `<pod>`.
 * Null when neither rule applies, which marks a standalone script.
 */
object FantomPodResolver {
    private val podNameRegex = Regex("""podName\s*=\s*"([^"]+)"""")

    fun podNameFor(file: VirtualFile, project: Project): String? {
        var dir: VirtualFile? = if (file.isDirectory) file else file.parent
        while (dir != null) {
            val build = dir.findChild("build.fan")
            if (build != null && !build.isDirectory) {
                val text = runCatching { VfsUtilCore.loadText(build) }.getOrNull()
                val name = text?.let { podNameRegex.find(it)?.groupValues?.get(1) }
                if (name != null) return name
            }
            dir = dir.parent
        }
        val parent = file.parent ?: return null
        if (parent.name == "fan") return parent.parent?.name?.takeIf { it.isNotEmpty() }
        return null
    }
}
