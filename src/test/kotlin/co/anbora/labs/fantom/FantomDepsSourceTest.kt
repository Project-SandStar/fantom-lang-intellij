package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.library.FantomBuildFile
import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomInstalls
import co.anbora.labs.fantom.lang.library.deps.FantomDepend
import co.anbora.labs.fantom.lang.library.deps.FantomDependencyResolver
import co.anbora.labs.fantom.lang.library.deps.FantomSourceScan
import co.anbora.labs.fantom.lang.library.deps.FantomTagChoice
import co.anbora.labs.fantom.lang.library.deps.FantomTagExport
import com.intellij.openapi.util.io.FileUtil
import junit.framework.TestCase
import java.io.File

/**
 * Dependencies from source (roadmap item 18): Haxall pairing of SkySpark installs, the
 * source-root scan and its grouping by git remote, tag choice, `depends` constraints and
 * the read-only tag export. Real-folder tests skip when their folder is missing.
 */
class FantomDepsSourceTest : TestCase() {
    private val user = File(System.getProperty("user.home"))
    private val code = File(user, "Code")
    private val visualytik = File(code, "2026/visualytik/bassgCommon")
    private val v3 = File(code, "2026/bassgcommonv3")
    private val temps = ArrayList<File>()

    override fun tearDown() {
        try {
            temps.forEach { it.walkTopDown().forEach { f -> f.setWritable(true) }; FileUtil.delete(it) }
        } finally {
            super.tearDown()
        }
    }

    private fun temp(name: String) = FileUtil.createTempDirectory(name, null).also { temps += it }

    private fun skip(vararg dirs: File): Boolean {
        val missing = dirs.filter { !it.isDirectory }
        if (missing.isNotEmpty()) println("${javaClass.simpleName}.$name skipped: missing $missing")
        return missing.isNotEmpty()
    }

    // ---- Haxall pairing ----

    private fun haxall(root: File, version: String): File =
        File(root, "haxall-$version").also { File(it, "src/core/haystack/fan").mkdirs() }

    private fun skyspark(root: File, version: String): File = File(root, "skyspark-$version").also {
        File(it, "lib/fan").mkdirs()
        File(it, "lib/fan/skyarcd.pod").writeText("")
        File(it, "bin").mkdirs()
        File(it, "bin/fan").writeText("#!/bin/sh\n")
    }

    fun testHaxallPairedByEqualVersionElseNearestLower() {
        val root = temp("pairs")
        haxall(root, "4.0.6"); val hx7 = haxall(root, "4.0.7"); haxall(root, "4.0.4")
        skyspark(root, "4.0.7"); skyspark(root, "4.0.8"); skyspark(root, "3.1.12")
        val rows = FantomInstalls.pair(FantomInstalls.scan(listOf(root)))
        val s407 = rows.single { it.dir.name == "skyspark-4.0.7" }
        assertEquals(hx7.path, s407.haxallPath)
        assertNull(s407.haxallWarning)
        assertFalse(s407.haxallChosen)
        val s408 = rows.single { it.dir.name == "skyspark-4.0.8" }
        assertEquals(hx7.path, s408.haxallPath)
        assertEquals("no Haxall 4.0.8; using Haxall 4.0.7", s408.haxallWarning)
        val s312 = rows.single { it.dir.name == "skyspark-3.1.12" }
        assertNull(s312.haxallPath)
        // a chosen Haxall wins and survives a save
        val hx4 = File(root, "haxall-4.0.4")
        val chosen = FantomInstalls.pair(FantomInstalls.scan(listOf(root)),
            mapOf(s407.path to FantomInstalls.Choice(true, null, false, hx4.path)))
        val c = chosen.single { it.path == s407.path }
        assertEquals(hx4.path, c.haxallPath)
        assertTrue(c.haxallChosen)
        assertEquals(hx4.path, FantomInstalls.choices(chosen, null)[s407.path]?.chosenHaxall)
    }

    fun testRealSkySpark407PairsWithHaxall407() {
        val sky = File(user, "skyspark/skyspark-4.0.7")
        val hx = File(user, "haxall/haxall-4.0.7")
        if (skip(sky, hx)) return
        val rows = FantomInstalls.pair(FantomInstalls.scan(listOf(File(user, "fantom"), File(user, "haxall"), File(user, "skyspark"))))
        assertEquals(hx.path, rows.single { it.path == sky.path }.haxallPath)
        assertEquals(listOf("haystack"), FantomHome.podSources(hx).map { it.pod }.filter { it == "haystack" })
        assertTrue(File(hx, "src/core/haystack/fan/Number.fan").isFile)
        assertTrue(File(hx, "src/core/xeto/fan/Marker.fan").isFile)
    }

    // ---- remote grouping ----

    fun testRemoteKeyIgnoresHostSchemeAndGitSuffix() {
        val a = FantomSourceScan.remoteKey("https://gitlab.bas.co/openbassg/skyspark/bassgcommonv3")
        assertEquals("openbassg/skyspark/bassgcommonv3", a)
        assertEquals(a, FantomSourceScan.remoteKey("git@gitlab.com:openbassg/skyspark/bassgcommonv3.git"))
        assertEquals(a, FantomSourceScan.remoteKey("ssh://git@gitlab.bas.co:2222/openbassg/skyspark/bassgcommonv3.git"))
        assertEquals(a, FantomSourceScan.remoteKey("gitlab.bas.co/openbassg/skyspark/bassgcommonv3"))
    }

    fun testOriginUrlAndBuildFileReading() {
        val cfg = "[core]\n\tbare = false\n[remote \"upstream\"]\n\turl = x:y/z\n[remote \"origin\"]\n\turl = git@h:a/b.git\n"
        assertEquals("git@h:a/b.git", FantomSourceScan.originUrl(cfg))
        val text = """
            class Build : BuildPod {
              static const Int apiLevel := 2
              new make() {
                podName = "bassgCommon"
                version = Version("3.1.12.${'$'}{apiLevel}.0")
                // depends = ["nope 1.0"]
                depends = ["sys 3.0", "haystack 3.0.28"]
              }
              override Void compile() {
                depends = depends.map |d| { d }
                version = Version("9.9")
              }
            }
        """.trimIndent()
        val info = FantomSourceScan.readBuildFile(text)
        assertEquals("bassgCommon", info.podName)
        assertEquals("3.1.12.2.0", info.version)
        assertEquals(listOf(FantomBuildFile.Dependency("sys", "3.0"), FantomBuildFile.Dependency("haystack", "3.0.28")), info.depends)
    }

    /** The bassgCommon copies under ~/Code: the visualytik clone and bassgcommonv3 group into one repo though their hosts differ. */
    fun testRealScanGroupsCopiesByRemotePath() {
        if (skip(code, visualytik, v3)) return
        FantomSourceScan.clear()
        val r = FantomSourceScan.scan(listOf(code))
        println("FantomDepsSourceTest: scanned ${r.buildFiles} build.fan files, ${r.pods.size} pods in ${r.millis} ms")
        val copies = r.pods.filter { it.pod == "bassgCommon" }
        println("FantomDepsSourceTest: bassgCommon copies: " + copies.joinToString { "${it.dir} [${it.repoKey}]" })
        val repo = r.repos("bassgCommon").single { it.key == "openbassg/skyspark/bassgcommonv3" }
        val roots = repo.copies.mapNotNull { it.gitRoot }.toSet()
        assertTrue(roots.toString(), visualytik in roots)
        assertTrue(roots.toString(), v3 in roots)
        assertTrue(repo.tags.keys.containsAll(listOf("bassgCommon/4.0.6.2.2", "3.0.27.1")))
        assertEquals(visualytik, repo.tags.getValue("bassgCommon/4.0.6.2.2").gitRoot)
        assertTrue("scan took ${r.millis} ms", r.millis < 10_000)
        // the second call comes from the cache
        assertSame(r, FantomSourceScan.scan(listOf(code)))
    }

    // ---- tag choice ----

    private val tags = listOf(
        "bassgCommon/4.0.6.2.2", "bassgCommon/4.0.6.2.1", "bassgCommon/3.1.12.2.2", "bassgCommon/3.1.12.2.1",
        "bassgCommon/3.1.11.2.2", "bassgCommon/3.1.10.2.2", "bassgCommon/3.1.9.2.2", "bassgCommon/3.1.8.2.2",
        "bassgCommon/3.1.6.2.2", "3.0.27.1", "3.0.27", "3.0.24", "licensedConnExt/4.0.7.1.0",
    )

    fun testTagChoiceExact() {
        val c = FantomTagChoice.choose(tags, "bassgCommon", "4.0.6")!!
        assertEquals("bassgCommon/4.0.6.2.2", c.tag.name)
        assertEquals(FantomTagChoice.Kind.EXACT, c.kind)
        assertNull(c.warning)
        assertEquals("bassgCommon/3.1.12.2.2", FantomTagChoice.choose(tags, "bassgCommon", "3.1.12")!!.tag.name)
    }

    fun testTagChoiceNearestLowerWithWarning() {
        val c = FantomTagChoice.choose(tags, "bassgCommon", "4.0.7")!!
        assertEquals("bassgCommon/4.0.6.2.2", c.tag.name)
        assertEquals(FantomTagChoice.Kind.NEAREST_LOWER, c.kind)
        assertEquals("no 4.0.7 tag; using bassgCommon/4.0.6.2.2", c.warning)
        // another pod's tag of the same repo is ignored
        assertNull(FantomTagChoice.parse("licensedConnExt/4.0.7.1.0", "bassgCommon"))
    }

    fun testBareTagRepo() {
        val bare = listOf("3.0.27.1", "3.0.27", "3.0.24")
        assertEquals("3.0.27.1", FantomTagChoice.choose(bare, "bassgCommon", "3.0.27")!!.tag.name)
        val c = FantomTagChoice.choose(bare, "bassgCommon", "4.0.7")!!
        assertEquals("3.0.27.1", c.tag.name)
        assertEquals(FantomTagChoice.Kind.NEAREST_LOWER, c.kind)
        assertNull(FantomTagChoice.choose(bare, "bassgCommon", "3.0.16"))
        assertNull(FantomTagChoice.choose(emptyList(), "bassgCommon", "4.0.7"))
    }

    // ---- depends ----

    fun testDependsConstraints() {
        assertTrue(FantomDepend.matches("3.0.27.1", "3.0+"))
        assertTrue(FantomDepend.matches("4.0.6.2.2", "3.0+"))
        assertFalse(FantomDepend.matches("2.9.1", "3.0+"))
        assertTrue(FantomDepend.matches("3.0.28", "3.0.28"))
        assertTrue(FantomDepend.matches("3.0.28.4", "3.0.28"))
        assertFalse(FantomDepend.matches("3.0.29", "3.0.28"))
        assertTrue(FantomDepend.matches("3.1.5", "3.0-3.1"))
        assertFalse(FantomDepend.matches("3.2", "3.0-3.1"))
        assertTrue(FantomDepend.matches("1.0", "2.0, 1.0"))
        assertTrue(FantomDepend.matches(null, ""))
        assertFalse(FantomDepend.matches(null, "1.0"))
        // tags below the constraint are skipped
        assertNull(FantomTagChoice.choose(listOf("3.0.27.1", "3.0.24"), "bassgCommon", "4.0.7", "3.1+"))
        assertEquals("bassgCommon/3.1.12.2.2", FantomTagChoice.choose(tags, "bassgCommon", "3.1.12", "3.1.12+")!!.tag.name)
    }

    /** Working copies whose build.fan version does not meet `depends` are skipped; with no tag the working copy is labelled. */
    fun testResolverSkipsCopiesBelowConstraint() {
        val root = temp("copies")
        fun pod(dir: String, version: String) = File(root, dir).also {
            File(it, "fan").mkdirs()
            File(it, "fan/A.fan").writeText("class A {}")
            File(it, "build.fan").writeText("class Build : BuildPod { new make() { podName = \"acme\"; version = Version(\"$version\") } }")
        }
        val old = pod("old/acme", "2.0.1")
        val new = pod("new/acme", "3.2.0")
        val scan = FantomSourceScan.scanNow(listOf(root))
        assertEquals(2, scan.repos("acme").size) // no git: each folder is its own repo
        val rows = FantomDependencyResolver.resolve(
            listOf(FantomBuildFile.Dependency("acme", "3.0+")), null, emptyList(), emptySet(), emptyList(), scan, emptyMap(), stubOf = { null }, compiled = { false },
        )
        val row = rows.single()
        assertEquals(new, row.podSource?.podDir)
        assertTrue(row.source, row.source.contains("working copy"))
        assertEquals(FantomDependencyResolver.How.AUTO, row.how)
        val none = FantomDependencyResolver.resolve(
            listOf(FantomBuildFile.Dependency("acme", "4.0+")), null, emptyList(), emptySet(), emptyList(), scan, emptyMap(), stubOf = { null }, compiled = { false },
        ).single()
        assertEquals(FantomDependencyResolver.How.NOT_FOUND, none.how)
        assertNotSame(old, new)
    }

    // ---- export ----

    private fun git(dir: File, vararg args: String): String = FantomTagExport.run(dir, listOf("git", *args)) ?: "<failed>"

    /** Exporting a tag leaves the clone's HEAD, branch and `git status` unchanged, and is reused while the tag's commit stays. */
    fun testTagExportIsReadOnly() {
        if (skip(visualytik)) return
        val headBefore = git(visualytik, "rev-parse", "HEAD")
        val branchBefore = git(visualytik, "rev-parse", "--abbrev-ref", "HEAD")
        val statusBefore = git(visualytik, "status", "--porcelain")
        val cache = temp("tagcache")
        val ex = FantomTagExport.export(visualytik, "bassgcommonv3", "bassgCommon/4.0.6.2.2", cache)!!
        assertFalse(ex.reused)
        assertTrue(File(ex.dir, "bassgCommon/build.fan").isFile)
        assertEquals(ex.commit, File(ex.dir, FantomTagExport.MARKER).readText())
        assertTrue(ex.dir.path.startsWith(cache.path))
        assertNotNull(FantomDependencyResolver.podAt(File(ex.dir, "bassgCommon"), "bassgCommon"))
        assertFalse(File(ex.dir, "bassgCommon/build.fan").canWrite())
        assertTrue(FantomTagExport.export(visualytik, "bassgcommonv3", "bassgCommon/4.0.6.2.2", cache)!!.reused)
        assertEquals(headBefore, git(visualytik, "rev-parse", "HEAD"))
        assertEquals(branchBefore, git(visualytik, "rev-parse", "--abbrev-ref", "HEAD"))
        assertEquals(statusBefore, git(visualytik, "status", "--porcelain"))
    }

    /** End to end on ~/Code: bassgCommon for SkySpark 4.0.7 is the 4.0.6.2.2 tag with a warning; for 3.1.12 the 3.1.12.2.2 tag. */
    fun testResolverPicksTagAcrossClones() {
        if (skip(code, visualytik, v3)) return
        val scan = FantomSourceScan.scan(listOf(code))
        val dep = listOf(FantomBuildFile.Dependency("bassgCommon", "3.0+"))
        fun rowFor(target: String): FantomDependencyResolver.Row {
            val sky = co.anbora.labs.fantom.lang.library.FantomInstall(FantomHome.Kind.SKYSPARK, "/x/skyspark-$target", target, null, null,
                co.anbora.labs.fantom.lang.library.FantomInstall.Pairing.MISSING, "")
            return FantomDependencyResolver.resolve(dep, sky, listOf(sky), emptySet(), emptyList(), scan, emptyMap(), stubOf = { null }, compiled = { false }).single()
        }
        val r407 = rowFor("4.0.7")
        assertEquals("bassgCommon/4.0.6.2.2", r407.export?.tag)
        assertEquals(FantomDependencyResolver.How.NEAREST_TAG, r407.how)
        assertEquals("no 4.0.7 tag; using bassgCommon/4.0.6.2.2", r407.warning)
        assertEquals(visualytik, r407.export?.clone)
        assertEquals(FantomDependencyResolver.How.AUTO, rowFor("4.0.6").how)
        assertEquals("bassgCommon/3.1.12.2.2", rowFor("3.1.12").export?.tag)
    }
}
