package co.anbora.labs.fantom.lang.library.deps

import co.anbora.labs.fantom.lang.library.FantomBuildFile
import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomInstall
import co.anbora.labs.fantom.lang.pod.FantomPodStubs
import java.io.File

/**
 * Picks a source for each `depends` entry of the open project, for one build target.
 *
 * Order, first that has the pod wins:
 * 1. the project itself (a pod it defines);
 * 2. a pin the user set on the dependencies table;
 * 3. sources of the ticked installs: the target's own, its paired Haxall's (SkySpark),
 *    its paired Fantom's ([base], which [co.anbora.labs.fantom.lang.library.FantomSettings]
 *    already orders);
 * 4. a project pod under the source roots ([FantomSourceScan]): per repository the tag for
 *    the target ([FantomTagChoice]), else the working copy; copies whose version does not
 *    meet the `depends` constraint are skipped. The repository with the best match wins
 *    (exact tag, then nearest tag, then working copy);
 * 5. a stub of the target's compiled pod;
 * 6. not found.
 * The `depends` constraint filters only source-root copies: Haxall 4.0.7 still serves
 * `haystack 3.0.28`, as SkySpark 4.0.7 does.
 *
 * Pure: it runs no git and exports nothing. A row that needs a tagged tree carries an
 * [Export] request; the caller runs [FantomTagExport] and builds the pod source.
 */
object FantomDependencyResolver {

    enum class How(val label: String) {
        AUTO("auto"), PINNED("pinned"), NEAREST_TAG("nearest tag"), STUB("stub"), NOT_FOUND("not found")
    }

    /** A tagged tree to export: [tag] of [clone], pod folder [relPath] inside it. */
    data class Export(val clone: File, val repo: String, val tag: String, val relPath: String)

    /** One choice of the pin dropdown: [id] is what the pin stores. */
    data class Option(val id: String, val label: String)

    data class Row(
        val pod: String,
        val needs: String,
        /** "Haxall 4.0.7 source", "bassgcommonv3 tag bassgCommon/4.0.6.2.2", "stub", "". */
        val source: String,
        val how: How,
        val warning: String? = null,
        /** The folder to index; null for [export] rows (until exported), for project pods and not found. */
        val podSource: FantomHome.PodSource? = null,
        val export: Export? = null,
        /** Picks for the pin dropdown; the first is "(auto)". */
        val options: List<Option> = emptyList(),
        val fromProject: Boolean = false,
    )

    const val AUTO_ID = "auto"
    const val STUB_ID = "stub"

    /** The SkySpark version tags are matched against: SkySpark and Haxall use their own; Fantom has none (working copy). */
    fun targetVersion(target: FantomInstall?): String? = when (target?.kind) {
        FantomHome.Kind.SKYSPARK, FantomHome.Kind.HAXALL -> target.version
        else -> null
    }

    /** "Haxall 4.0.7 source" style text for a pod folder. */
    private fun sourceText(src: FantomHome.PodSource, installs: List<FantomInstall>): String {
        if (FantomPodStubs.isStub(src)) return "stub"
        val owner = installs.flatMap { listOfNotNull(it.path, it.haxallPath, it.fantomPath) }.distinct()
            .firstOrNull { src.podDir.path.startsWith("$it/") }
        return if (owner != null) FantomHome.displayName(File(owner)) + " source" else src.podDir.path
    }

    fun resolve(
        deps: List<FantomBuildFile.Dependency>,
        target: FantomInstall?,
        installs: List<FantomInstall>,
        projectPods: Set<String>,
        base: List<FantomHome.PodSource>,
        scan: FantomSourceScan.Result?,
        pins: Map<String, String>,
        stubOf: (String) -> FantomHome.PodSource? = { pod -> stubFor(target, pod) },
    ): List<Row> {
        val byName = base.associateBy { it.pod }
        val version = targetVersion(target)
        return deps.distinctBy { it.pod }.map { dep ->
            val pod = dep.pod
            if (pod in projectPods) return@map Row(pod, dep.constraint, "this project", How.AUTO, fromProject = true)
            val installSrc = byName[pod]?.takeIf { !FantomPodStubs.isStub(it) }
            val stub = byName[pod]?.takeIf { FantomPodStubs.isStub(it) } ?: stubOf(pod)
            val repos = scan?.repos(pod) ?: emptyList()
            val options = ArrayList<Option>()
            options += Option(AUTO_ID, "(auto)")
            installSrc?.let { options += Option("src:" + it.podDir.path, sourceText(it, installs)) }
            for (repo in repos) {
                for (t in FantomTagChoice.all(repo.tags.keys, pod)) {
                    options += Option("tag:" + repo.key + "@" + t.name, "${repo.name} tag ${t.name}")
                }
                for (c in repo.copies) options += Option("src:" + c.dir.path, "${repo.name} working copy (${c.dir.path})")
            }
            stub?.let { options += Option(STUB_ID, "stub") }

            pins[pod]?.takeIf { it != AUTO_ID }?.let { pin ->
                pinned(pod, dep, pin, repos, stub, installs)?.let { return@map it.copy(options = options) }
            }
            if (installSrc != null) return@map Row(pod, dep.constraint, sourceText(installSrc, installs), How.AUTO, podSource = installSrc, options = options)
            fromRepos(pod, dep, repos, version)?.let { return@map it.copy(options = options) }
            if (stub != null) return@map Row(pod, dep.constraint, "stub", How.STUB, podSource = stub, options = options)
            Row(pod, dep.constraint, "", How.NOT_FOUND, options = options)
        }
    }

    private fun pinned(
        pod: String, dep: FantomBuildFile.Dependency, pin: String,
        repos: List<FantomSourceScan.Repo>, stub: FantomHome.PodSource?, installs: List<FantomInstall>,
    ): Row? = when {
        pin == STUB_ID -> stub?.let { Row(pod, dep.constraint, "stub", How.PINNED, podSource = it) }
        pin.startsWith("src:") -> {
            val dir = File(pin.removePrefix("src:"))
            podAt(dir, pod)?.let { src ->
                val repo = repos.firstOrNull { r -> r.copies.any { it.dir == dir } }
                val text = if (repo != null) "${repo.name} working copy" else sourceText(src, installs)
                Row(pod, dep.constraint, text, How.PINNED, podSource = src)
            }
        }
        pin.startsWith("tag:") -> {
            val body = pin.removePrefix("tag:")
            val key = body.substringBeforeLast('@')
            val tag = body.substringAfterLast('@')
            val repo = repos.firstOrNull { it.key == key }
            val clone = repo?.tags?.get(tag)
            if (repo != null && clone?.gitRoot != null) {
                Row(pod, dep.constraint, "${repo.name} tag $tag", How.PINNED, export = Export(clone.gitRoot, repo.name, tag, clone.relPath))
            } else null
        }
        else -> null
    }

    private data class RepoPick(val row: Row, val rank: Int, val version: String?)

    /** The best source-root pick for [pod]: exact tag, then nearest lower tag, then a working copy meeting the constraint. */
    private fun fromRepos(pod: String, dep: FantomBuildFile.Dependency, repos: List<FantomSourceScan.Repo>, target: String?): Row? {
        val picks = repos.mapNotNull { repo -> pick(pod, dep, repo, target) }
        return picks.sortedWith(compareBy<RepoPick> { it.rank }.thenComparator { a, b ->
            FantomDepend.compare(b.version ?: "0", a.version ?: "0")
        }).firstOrNull()?.row
    }

    private fun pick(pod: String, dep: FantomBuildFile.Dependency, repo: FantomSourceScan.Repo, target: String?): RepoPick? {
        val tags = repo.tags
        if (target != null) {
            FantomTagChoice.choose(tags.keys, pod, target, dep.constraint)?.let { c ->
                val clone = tags.getValue(c.tag.name)
                if (clone.gitRoot != null) {
                    val how = if (c.kind == FantomTagChoice.Kind.EXACT) How.AUTO else How.NEAREST_TAG
                    val row = Row(pod, dep.constraint, "${repo.name} tag ${c.tag.name}", how, c.warning,
                        export = Export(clone.gitRoot, repo.name, c.tag.name, clone.relPath))
                    return RepoPick(row, if (c.kind == FantomTagChoice.Kind.EXACT) 0 else 1, c.tag.version)
                }
            }
        }
        val copy = repo.copies.filter { FantomDepend.matches(it.version, dep.constraint) }
            .sortedWith { a, b -> FantomDepend.compare(b.version ?: "0", a.version ?: "0") }
            .firstOrNull() ?: return null
        val src = podAt(copy.dir, pod) ?: return null
        val why = when {
            target == null -> "the target has no SkySpark version"
            FantomTagChoice.all(tags.keys, pod).isEmpty() -> "no usable tags"
            else -> "no tag at or below $target"
        }
        val row = Row(pod, dep.constraint, "${repo.name} working copy (${copy.dir.path})", How.AUTO, "$why; using the working copy", podSource = src)
        return RepoPick(row, 2, copy.version)
    }

    /** The pod [pod] at [dir] (its `build.fan` folder), with its source sub folders as roots. */
    fun podAt(dir: File, pod: String): FantomHome.PodSource? =
        FantomHome.nestedPods(dir).firstOrNull { it.pod == pod }

    /** The stub of the target's compiled `<pod>.pod`, when it was generated. */
    fun stubFor(target: FantomInstall?, pod: String): FantomHome.PodSource? {
        val file = target?.dir?.let { File(it, "lib/fan/$pod.pod") }?.takeIf { it.isFile } ?: return null
        return FantomPodStubs.podSource(file)
    }
}
