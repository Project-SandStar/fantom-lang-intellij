package co.anbora.labs.fantom.ide.axon

import co.anbora.labs.fantom.lang.axon.copy.AxonProjectInfo
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectKey

/**
 * Grouping and filtering of the Axon project picker. No Swing: [AxonProjectPickerDialog]
 * shows what this returns. A connection is an instance; a null instance stands for the
 * "All connections" row.
 *
 * A project matches a search when the search text is in its instance name, in its project
 * name, or in `instance/project` (case-insensitive). A blank search matches every project.
 * A connection is shown when one of its projects matches; "All connections" is always shown.
 */
class AxonProjectPickerModel(projects: List<AxonProjectInfo>) {

    /** One row of the left pane: [matching] of [total] projects match the search. */
    data class Connection(val instance: String?, val matching: Int, val total: Int) {
        val isAll: Boolean get() = instance == null
    }

    /** Every project, sorted by instance, then by project name, case-insensitive. */
    val projects: List<AxonProjectInfo> = projects.sortedWith(PROJECT_ORDER)

    /** The projects of each instance, in the order of [instances]. */
    private val byInstance: Map<String, List<AxonProjectInfo>> = this.projects.groupBy { it.key.instance }

    /** The instance names, sorted case-insensitive. */
    val instances: List<String> = byInstance.keys.sortedWith(NAME_ORDER)

    /** "All connections" first, then each connection with a matching project. */
    fun connections(search: String): List<Connection> {
        val q = normalize(search)
        val out = ArrayList<Connection>(instances.size + 1)
        out += Connection(null, projects.count { matches(it, q) }, projects.size)
        for (instance in instances) {
            val list = byInstance.getValue(instance)
            val n = list.count { matches(it, q) }
            if (q == null || n > 0) out += Connection(instance, n, list.size)
        }
        return out
    }

    /** The matching projects of [instance], or of every instance when it is null. */
    fun projects(instance: String?, search: String): List<AxonProjectInfo> {
        val q = normalize(search)
        val list = if (instance == null) projects else byInstance[instance].orEmpty()
        return if (q == null) list else list.filter { matches(it, q) }
    }

    /**
     * The connection to select when the picker opens: the one of the [active] project when
     * that project is in the list; else the [remembered] one (as [remember] wrote it) when
     * it still exists; else "All connections" (null).
     */
    fun initialInstance(active: AxonProjectKey?, remembered: String?): String? {
        if (active != null && projects.any { it.key == active }) return active.instance
        if (remembered == null || remembered == ALL) return null
        return remembered.takeIf { it in byInstance }
    }

    companion object {
        /** What [remember] stores for "All connections". An instance name holds no slash. */
        const val ALL = "/"

        const val ALL_LABEL = "All connections"

        private val NAME_ORDER: Comparator<String> = String.CASE_INSENSITIVE_ORDER.thenComparing(naturalOrder())

        private val PROJECT_ORDER: Comparator<AxonProjectInfo> =
            compareBy<AxonProjectInfo, String>(NAME_ORDER) { it.key.instance }.thenBy(NAME_ORDER) { it.key.project }

        /** The text to store for the selected connection; null stands for "All connections". */
        fun remember(instance: String?): String = instance ?: ALL

        /** The trimmed search, or null when it is blank. */
        fun normalize(search: String?): String? = search?.trim()?.takeIf { it.isNotEmpty() }

        /** True when [info] matches the search [q]; a null [q] matches everything. */
        fun matches(info: AxonProjectInfo, q: String?): Boolean {
            if (q == null) return true
            val k = info.key
            return k.instance.contains(q, ignoreCase = true) ||
                k.project.contains(q, ignoreCase = true) ||
                k.toString().contains(q, ignoreCase = true)
        }

        /** The name of a connection row. */
        fun connectionName(c: Connection): String = c.instance ?: ALL_LABEL

        /** "12" without a search, "3/12" with one. */
        fun connectionCount(c: Connection, search: String): String =
            if (normalize(search) == null) "${c.total}" else "${c.matching}/${c.total}"

        /** The project name; "instance / project" when every connection is shown. */
        fun projectName(info: AxonProjectInfo, allConnections: Boolean): String =
            if (allConnections) "${info.key.instance} / ${info.key.project}" else info.key.project

        /** "12 functions, synced 2026-01-02T03:04:05Z"; no sync part when the time is unknown. */
        fun projectDetail(info: AxonProjectInfo): String {
            val n = info.functionCount
            val count = "$n function${if (n == 1) "" else "s"}"
            val sync = info.lastSync?.takeIf { it.isNotBlank() }
            return if (sync == null) count else "$count, synced $sync"
        }
    }
}
