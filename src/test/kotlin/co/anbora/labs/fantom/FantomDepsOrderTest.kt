package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.settings.FantomTargetWidgets
import co.anbora.labs.fantom.lang.library.FantomInstall
import co.anbora.labs.fantom.lang.library.FantomInstalls
import co.anbora.labs.fantom.lang.library.FantomSettings
import co.anbora.labs.fantom.lang.library.deps.FantomDependencies
import co.anbora.labs.fantom.lang.library.deps.FantomDependencyResolver
import co.anbora.labs.fantom.lang.pod.FantomPodStubs
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.RootsChangeRescanningInfo
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Pod source order with a SkySpark target: the project's own pod, then the paired Haxall's
 * source, then a stub; and the build target switch re-mapping a tagged dependency. Uses
 * the installs and `~/Code` of this machine; skips when they are missing.
 */
class FantomDepsOrderTest : BasePlatformTestCase() {
    private val user = File(System.getProperty("user.home"))
    private val sky407 = File(user, "skyspark/skyspark-4.0.7")
    private val sky3112 = File(user, "skyspark/skyspark-3.1.12")
    private val hx407 = File(user, "haxall/haxall-4.0.7")
    private val fantom = File(user, "fantom/fantom-1.0.84")
    private val bassg = File(user, "Code/2026/visualytik/bassgCommon")
    private val temps = ArrayList<File>()

    private val settings get() = FantomSettings.getInstance(project)

    override fun tearDown() {
        try {
            settings.installs = emptyList()
            settings.defaultTarget = null
            settings.dependencyOverrides = emptyMap()
            settings.projectPods = emptySet()
            settings.pins = emptyMap()
            FantomSettings.clearPodCache()
            WriteAction.runAndWait<RuntimeException> {
                ProjectRootManagerEx.getInstanceEx(project)
                    .makeRootsChange(EmptyRunnable.getInstance(), RootsChangeRescanningInfo.TOTAL_RESCAN)
            }
            temps.forEach { it.walkTopDown().forEach { f -> f.setWritable(true) }; FileUtil.delete(it) }
        } finally {
            super.tearDown()
        }
    }

    private fun missing(vararg dirs: File): Boolean {
        val m = dirs.filter { !it.isDirectory }
        if (m.isNotEmpty()) println("FantomDepsOrderTest.$name skipped: missing $m")
        return m.isNotEmpty()
    }

    /** Ticks [ticked] (first is the default target) from the real roots, paired as the settings page pairs them. */
    private fun tick(vararg ticked: File): List<FantomInstall> {
        val rows = FantomInstalls.pair(FantomInstalls.scan(listOf(File(user, "fantom"), File(user, "haxall"), File(user, "skyspark"))))
            .map { r -> r.copy(enabled = ticked.any { it.path == r.path }) }
        settings.installs = rows
        settings.defaultTarget = ticked.first().path
        FantomSettings.clearPodCache()
        return rows
    }

    fun testSkySparkPodsComeFromHaxallSourceBeforeStubs() {
        if (missing(sky407, hx407, fantom)) return
        val rows = tick(sky407)
        assertEquals(hx407.path, rows.single { it.path == sky407.path }.haxallPath)
        val pods = settings.podSources()
        val haystack = pods.single { it.pod == "haystack" }
        assertEquals(File(hx407, "src/core/haystack"), haystack.podDir)
        assertFalse(FantomPodStubs.isStub(haystack))
        assertTrue(File(haystack.podDir, "fan/Number.fan").isFile)
        assertTrue(File(haystack.podDir, "fan/CsvReader.fan").isFile)
        assertEquals(File(hx407, "src/core/xeto"), pods.single { it.pod == "xeto" }.podDir)
        // skyarcd has no source anywhere: a stub or nothing, never a source folder
        pods.firstOrNull { it.pod == "skyarcd" }?.let { assertTrue(FantomPodStubs.isStub(it)) }
        assertFalse(settings.podsNeedingStubs().any { it.name == "haystack.pod" })
        assertEquals(pods.size, pods.map { it.pod }.toSet().size)
        // a pod the project defines itself is never a library
        settings.projectPods = setOf("haystack")
        assertNull(settings.podSources().firstOrNull { it.pod == "haystack" })
    }

    /** The demo's dependencies: Haxall source, stub or not found, and bassgCommon from a tag. */
    fun testDependencyRowsAndTargetSwitch() {
        if (missing(sky407, sky3112, hx407, fantom, bassg)) return
        tick(sky407, sky3112)
        val proj = FileUtil.createTempDirectory("demo", null).also { temps += it }
        File(proj, "demoExt").mkdirs()
        File(proj, "demoExt/build.fan").writeText(
            """
            class Build : BuildPod {
              new make() {
                podName = "demoExt"
                version = Version("1.0")
                depends = ["sys 1.0", "haystack 3.0.28", "folio 3.0.28", "axon 3.0.28", "skyarcd 3.0.28",
                           "connExt 3.0.28", "bassgCommon 3.0+"]
                srcDirs = [`fan/`]
              }
            }
            """.trimIndent()
        )
        val cache = FileUtil.createTempDirectory("tagcache", null).also { temps += it }
        val deps = FantomDependencies.getInstance(project)
        val snap = deps.refreshNow(listOf(proj), cache)
        fun row(s: FantomDependencies.Snapshot, pod: String) = s.rows.single { it.pod == pod }
        println("FantomDepsOrderTest: " + snap.rows.joinToString("; ") { "${it.pod}=${it.source} [${it.how.label}]" })
        assertEquals(setOf("demoExt"), snap.projectPods)
        assertEquals("Haxall 4.0.7 source", row(snap, "haystack").source)
        assertEquals(FantomDependencyResolver.How.AUTO, row(snap, "folio").how)
        assertEquals(FantomDependencyResolver.How.NOT_FOUND, row(snap, "connExt").how)
        assertTrue(row(snap, "skyarcd").how in setOf(FantomDependencyResolver.How.STUB, FantomDependencyResolver.How.NOT_FOUND))
        val b407 = row(snap, "bassgCommon")
        assertEquals(FantomDependencyResolver.How.NEAREST_TAG, b407.how)
        assertEquals("no 4.0.7 tag; using bassgCommon/4.0.6.2.2", b407.warning)
        assertTrue(b407.podSource!!.podDir.path.startsWith(cache.path))
        assertEquals(b407.podSource!!.podDir, settings.podSources().single { it.pod == "bassgCommon" }.podDir)
        assertEquals("Fantom: SkySpark 4.0.7", FantomTargetWidgets.text(project))

        // the status bar switch: SkySpark 3.1.12 re-maps bassgCommon to its exact tag
        deps.switchTarget(sky3112.path)
        assertEquals(sky3112.path, settings.defaultTarget)
        assertEquals("Fantom: SkySpark 3.1.12", FantomTargetWidgets.text(project))
        val snap2 = deps.refreshNow(listOf(proj), cache)
        val b3112 = row(snap2, "bassgCommon")
        assertEquals(FantomDependencyResolver.How.AUTO, b3112.how)
        assertTrue(b3112.source, b3112.source.endsWith("bassgCommon/3.1.12.2.2"))
        assertEquals(b3112.podSource!!.podDir, settings.podSources().single { it.pod == "bassgCommon" }.podDir)

        // a pin wins over the automatic pick
        settings.pins = mapOf("bassgCommon" to "tag:openbassg/skyspark/bassgcommonv3@bassgCommon/3.1.6.2.2")
        val pinned = row(deps.refreshNow(listOf(proj), cache), "bassgCommon")
        assertEquals(FantomDependencyResolver.How.PINNED, pinned.how)
        assertTrue(pinned.source, pinned.source.endsWith("bassgCommon/3.1.6.2.2"))
    }
}
