package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.library.FantomHome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [FantomHome.kindOf] on the real installs of this machine (each check is skipped when
 * its folder is missing) and on small fake trees.
 */
class FantomHomeKindTest {

    private val user = System.getProperty("user.home")
    private val fantom = File(user, "fantom/fantom-1.0.84")
    private val haxall = File(user, "haxall/haxall-4.0.7")
    private val skyspark = File(user, "skyspark/skyspark-3.0.29")

    private fun present(dir: File): Boolean =
        dir.isDirectory.also { if (!it) println("FantomHomeKindTest: ${dir.path} missing, check skipped") }

    @Test
    fun fantomInstallIsFantom() {
        if (!present(fantom)) return
        assertEquals(FantomHome.Kind.FANTOM, FantomHome.kindOf(fantom))
        assertTrue(FantomHome.isSources(fantom))
        assertTrue(FantomHome.podSources(fantom).any { it.pod == "sys" })
    }

    @Test
    fun haxallCheckoutIsHaxall() {
        if (!present(haxall)) return
        assertEquals(FantomHome.Kind.HAXALL, FantomHome.kindOf(haxall))
        assertFalse(FantomHome.isSources(haxall))
        assertEquals("Haxall 4.0.7", FantomHome.displayName(haxall))
    }

    @Test
    fun skysparkInstallIsSkySpark() {
        if (!present(skyspark)) return
        assertEquals(FantomHome.Kind.SKYSPARK, FantomHome.kindOf(skyspark))
        assertTrue(FantomHome.hasLauncher(skyspark))
        assertTrue(FantomHome.podSources(skyspark).isEmpty())
    }

    @Test
    fun haxallPodsAreFoundAtAnyDepthAndCoverEverySource() {
        if (!present(haxall)) return
        val t0 = System.nanoTime()
        val pods = FantomHome.podSources(haxall)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val haystack = pods.single { it.pod == "haystack" }
        assertEquals(File(haxall, "src/core/haystack"), haystack.podDir)
        assertTrue(haystack.roots.contains(File(haxall, "src/core/haystack/fan")))
        assertTrue(pods.any { it.pod == "xeto" })
        // Every .fan file under src/ is under some root, except scripts that sit next to a
        // pod's build.fan (build.fan itself, core/pi/gen.fan, core/xeto/genjava.fan).
        val src = File(haxall, "src")
        val podDirs = pods.map { it.podDir }.toSet()
        val all = src.walkTopDown()
            .filter { it.isFile && it.extension == "fan" && it.name != "build.fan" && it.parentFile !in podDirs }
            .toSet()
        val covered = pods.flatMap { p -> p.roots.flatMap { r -> r.walkTopDown().filter { it.isFile && it.extension == "fan" }.toList() } }.toSet()
        val missed = all - covered
        println("haxall-4.0.7: ${pods.size} pods, ${pods.sumOf { it.roots.size }} roots, ${covered.size}/${all.size} files, walk ${ms}ms")
        if (missed.isNotEmpty()) println("not under a root: " + missed.take(20).joinToString { it.relativeTo(src).path })
        assertTrue("missed: ${missed.take(5)}", missed.isEmpty())
    }

    @Test
    fun fakeTrees() {
        val tmp = Files.createTempDirectory("fantom-kind").toFile()
        try {
            val sky = File(tmp, "sky").also { File(it, "lib/fan").mkdirs(); File(it, "lib/fan/skyspark.pod").writeText("") }
            assertEquals(FantomHome.Kind.SKYSPARK, FantomHome.kindOf(sky))
            val hx = File(tmp, "hx").also { File(it, "src").mkdirs() }
            File(hx, "src/build.fan").writeText("// Haxall top level build\n")
            assertEquals(FantomHome.Kind.HAXALL, FantomHome.kindOf(hx))
            File(hx, "src/grp/pod1/fan").mkdirs()
            File(hx, "src/grp/build.fan").writeText("class Build : BuildGroup {}")
            File(hx, "src/grp/pod1/build.fan").writeText("podName = \"podOne\"")
            File(hx, "src/grp/pod1/fan/A.fan").writeText("class A {}")
            File(hx, "src/grp/pod1/test").mkdirs()
            File(hx, "src/grp/pod1/test/ATest.fan").writeText("class ATest {}")
            File(hx, "src/grp/pod1/res").mkdirs()
            val pods = FantomHome.podSources(hx)
            assertEquals(listOf("podOne"), pods.map { it.pod })
            assertEquals(listOf("fan", "test"), pods.single().roots.map { it.name })
            assertNull(FantomHome.kindOf(File(tmp, "missing")))
            assertNull(FantomHome.kindOf(File(tmp, "empty").also { it.mkdirs() }))
        } finally {
            tmp.deleteRecursively()
        }
    }
}
