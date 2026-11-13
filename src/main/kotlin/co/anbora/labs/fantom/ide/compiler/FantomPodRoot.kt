package co.anbora.labs.fantom.ide.compiler

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/** The folder of the nearest `build.fan` that declares a `podName`, walking up from a file. */
object FantomPodRoot {
    private val podNameRegex = Regex("""podName\s*=\s*"([^"]+)"""")

    fun find(file: VirtualFile): VirtualFile? {
        var dir: VirtualFile? = if (file.isDirectory) file else file.parent
        while (dir != null) {
            val build = dir.findChild("build.fan")
            if (build != null && !build.isDirectory) {
                val text = runCatching { VfsUtilCore.loadText(build) }.getOrNull()
                if (text != null && podNameRegex.containsMatchIn(text)) return dir
            }
            dir = dir.parent
        }
        return null
    }
}
