package co.anbora.labs.fantom.lang.axon.copy

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * What one "Sync Axon Functions" did: the functions added, changed (another hash) and
 * removed, by name, and how many there are now. [conflicts] are local files that were
 * kept instead of the SkySpark version (proj-folder mode only; the read-only copy has none).
 *
 * [unchanged] is null for the read-only copy. A proj-folder sync sets it, and then the
 * message counts the unchanged functions, says "moved to .versions" for [removed] and
 * names the `.skyspark` files of the conflicts. [keptGone] are functions gone in SkySpark
 * whose local files were edited: they stay where they are.
 */
data class AxonSyncResult(
    val key: AxonProjectKey,
    val added: List<String>,
    val changed: List<String>,
    val removed: List<String>,
    val total: Int,
    val conflicts: List<String> = emptyList(),
    val unchanged: List<String>? = null,
    val keptGone: List<String> = emptyList(),
) {
    /** "Axon: demo/site synced, 1 added, 2 changed, 0 removed (12 functions)." and the names. */
    fun message(): String = if (unchanged != null) folderMessage(unchanged) else copyMessage()

    /** "Axon: demo/site synced into its folder, 1 added, 2 changed, 9 unchanged, 1 moved to .versions, 1 conflict (12 functions)." */
    private fun folderMessage(same: List<String>): String {
        val sb = StringBuilder("Axon: $key synced into its folder, ${added.size} added, ${changed.size} changed, ${same.size} unchanged")
        sb.append(", ${removed.size} moved to .versions")
        sb.append(", ").append(if (conflicts.size == 1) "1 conflict" else "${conflicts.size} conflicts")
        sb.append(" (").append(if (total == 1) "1 function" else "$total functions").append(").")
        names("Added", added, sb)
        names("Changed", changed, sb)
        names("Moved to .versions", removed, sb)
        names("Conflicts, local file kept and the SkySpark version written as <name>.axon.skyspark or <name>.trio.skyspark", conflicts, sb)
        names("Gone in SkySpark but edited locally, kept in place", keptGone, sb)
        return sb.toString()
    }

    private fun copyMessage(): String {
        val sb = StringBuilder("Axon: $key synced, ${added.size} added, ${changed.size} changed, ${removed.size} removed")
        if (conflicts.isNotEmpty()) sb.append(", ${conflicts.size} kept local")
        sb.append(" (").append(if (total == 1) "1 function" else "$total functions").append(").")
        names("Added", added, sb)
        names("Changed", changed, sb)
        names("Removed", removed, sb)
        names("Kept local", conflicts, sb)
        return sb.toString()
    }

    /**
     * [message] for a notification. Notification text is HTML: the names come from the
     * server, so `<` and `&` are escaped, and each line break becomes `<br>`.
     */
    fun html(): String = message()
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .replace("\n", "<br>")

    private fun names(label: String, list: List<String>, sb: StringBuilder) {
        if (list.isEmpty()) return
        sb.append('\n').append(label).append(": ").append(list.take(MAX_NAMES).joinToString(", "))
        if (list.size > MAX_NAMES) sb.append(", and ${list.size - MAX_NAMES} more")
        sb.append('.')
    }

    companion object {
        const val MAX_NAMES = 5

        /** Compares two name to hash maps: the functions before and after the sync. */
        fun diff(key: AxonProjectKey, before: Map<String, String>, after: Map<String, String>): AxonSyncResult =
            AxonSyncResult(
                key,
                added = after.keys.filter { it !in before }.sorted(),
                changed = after.filter { (n, h) -> before[n] != null && before[n] != h }.keys.sorted(),
                removed = before.keys.filter { it !in after }.sorted(),
                total = after.size,
            )
    }
}

/**
 * The hook for proj-folder mode (task 4.38): a sync that writes into a folder of the
 * Axon server's `proj` folder instead of the read-only copy. The plugin's implementation
 * is `AxonProjFolderSync`; an implementation must never overwrite a local change, must
 * keep old files in `.versions/` and must write through the VFS.
 *
 * "Sync Axon Functions" asks every implementation for the file that was clicked. The
 * first that returns a [Target] gets the functions the server fetched with `sync=true`;
 * the read-only copy is then not touched.
 */
interface AxonSyncTarget {

    /**
     * The SkySpark project and the folder that [file] belongs to, or null. Called in the
     * action's update, on a background thread in a read action: memory only, no disk
     * walk and no network.
     */
    fun targetFor(project: Project, file: VirtualFile): Target?

    /**
     * [target] made exact before the fetch, for example with the key that a metadata file
     * on disk names. Called on a background thread, outside any read action: it may read
     * the disk. The default keeps [target].
     */
    fun prepare(project: Project, target: Target, check: () -> Unit): Target = target

    /**
     * Writes [list] (the functions of [Target.key], fetched with `sync=true`) into
     * [Target.folder]. Called on a background thread, outside any read action. Calls
     * [check] often: it throws when the developer cancels.
     */
    fun write(project: Project, target: Target, list: AxonFunctionList, check: () -> Unit): AxonSyncResult

    /** A SkySpark project and the local folder of its functions. */
    data class Target(val key: AxonProjectKey, val folder: File)

    /** A [Target] and the implementation that claimed it. */
    class Match(val hook: AxonSyncTarget, val target: Target)

    companion object {
        val EP_NAME: ExtensionPointName<AxonSyncTarget> = ExtensionPointName.create("co.anbora.labs.fantom.axonSyncTarget")

        /** The first implementation that claims [file], or null. Memory only. */
        fun find(project: Project, file: VirtualFile?): Match? {
            if (file == null) return null
            for (hook in EP_NAME.extensionList) {
                hook.targetFor(project, file)?.let { return Match(hook, it) }
            }
            return null
        }
    }
}
