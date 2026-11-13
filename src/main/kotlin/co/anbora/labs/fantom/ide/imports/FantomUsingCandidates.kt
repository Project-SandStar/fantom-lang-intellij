package co.anbora.labs.fantom.ide.imports

import co.anbora.labs.fantom.lang.library.FantomHome
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Type name to the pods of a Fantom install whose sources have a `<Name>.fan` file.
 * Built from the file system, so it works before the install is indexed.
 */
object FantomUsingCandidates {
    private val cache = ConcurrentHashMap<String, Map<String, List<String>>>()

    fun podsFor(home: File, typeName: String): List<String> = podsFor(FantomHome.podSources(home), typeName)

    /** Same as above for an explicit pod list, such as [co.anbora.labs.fantom.lang.library.FantomSettings.podSources]. */
    fun podsFor(sources: List<FantomHome.PodSource>, typeName: String): List<String> {
        val key = sources.joinToString("|") { it.podDir.absolutePath }
        return cache.getOrPut(key) { scan(sources) }[typeName] ?: emptyList()
    }

    fun reset() = cache.clear()

    private fun scan(sources: List<FantomHome.PodSource>): Map<String, List<String>> {
        val out = HashMap<String, MutableList<String>>()
        for (source in sources) for (root in source.roots) {
            val pod = source.pod
            root.walkTopDown().filter { it.isFile && it.extension == "fan" }.forEach { f ->
                val pods = out.getOrPut(f.nameWithoutExtension) { ArrayList(1) }
                if (pod !in pods) pods += pod
            }
        }
        return out
    }
}
