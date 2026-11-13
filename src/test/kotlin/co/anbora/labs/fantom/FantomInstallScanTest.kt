package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomInstall
import co.anbora.labs.fantom.lang.library.FantomInstalls
import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.openapi.util.io.FileUtil
import junit.framework.TestCase
import java.io.File

/**
 * The install scanner and pairing rules, on the real roots of this machine (each test
 * skips when its folders are missing) and on fake folders.
 */
class FantomInstallScanTest : TestCase() {
    private val user = File(System.getProperty("user.home"))
    private val fantomRoot = File(user, "fantom")
    private val haxallRoot = File(user, "haxall")
    private val skysparkRoot = File(user, "skyspark")

    private fun realRows(): List<FantomInstall>? {
        if (!fantomRoot.isDirectory || !skysparkRoot.isDirectory) {
            println("FantomInstallScanTest skipped: no ~/fantom or ~/skyspark")
            return null
        }
        return FantomInstalls.pair(FantomInstalls.scan(listOf(fantomRoot, haxallRoot, skysparkRoot)))
    }

    private fun row(rows: List<FantomInstall>, folder: String) = rows.firstOrNull { it.dir.name == folder }

    fun testVersionHelpers() {
        assertEquals("1.0.84", FantomInstalls.fantomPart("1.0.84.4007"))
        assertEquals("4.0.7", FantomInstalls.skysparkFromBuild("1.0.84.4007"))
        assertEquals("3.1.11", FantomInstalls.skysparkFromBuild("1.0.81.3111"))
        assertEquals("3.0.29", FantomInstalls.skysparkFromBuild("1.0.76.29"))
        assertTrue(FantomInstalls.compareVersions("1.0.84", "1.0.9") > 0)
    }

    /** Every SkySpark on this machine pairs with the Fantom of the first three numbers of its sys.pod version. */
    fun testSkySparkPairingTable() {
        val rows = realRows() ?: return
        val expected = mapOf(
            "skyspark-3.0.16" to "1.0.71", "skyspark-3.0.24" to "1.0.74", "skyspark-3.0.29" to "1.0.76",
            "skyspark-3.1.4" to "1.0.78", "skyspark-3.1.6" to "1.0.78", "skyspark-3.1.7" to "1.0.78",
            "skyspark-3.1.8" to "1.0.78", "skyspark-3.1.8 2" to "1.0.78", "skyspark-3.1.8Bugra" to "1.0.78",
            "skyspark-3.1.9" to "1.0.79", "skyspark-3.1.11" to "1.0.81", "skyspark-3.1.12" to "1.0.82",
            "skyspark-4.0.4" to "1.0.82", "skyspark-4.0.6" to "1.0.83", "skyspark-4.0.7" to "1.0.84",
        )
        for ((folder, fantom) in expected) {
            val r = row(rows, folder) ?: continue
            assertEquals(folder, FantomHome.Kind.SKYSPARK, r.kind)
            assertEquals(folder, fantom, r.fantomVersion)
            val paired = File(fantomRoot, "fantom-$fantom")
            if (paired.isDirectory) {
                assertEquals(folder, FantomInstall.Pairing.AUTO, r.pairing)
                assertEquals(folder, paired.path, r.fantomPath)
            } else {
                assertEquals(folder, FantomInstall.Pairing.MISSING, r.pairing)
                assertEquals(folder, "Fantom $fantom missing", r.warning)
                assertNull(folder, r.runner)
            }
        }
        row(rows, "skyspark-4.0.7")?.let { assertEquals("SkySpark 4.0.7", it.name) }
        row(rows, "skyspark-3.1.8 2")?.let { assertEquals("3.1.8", it.version) }
        // zips, proj, projectBackup and skyspark-noAuth (no sys.pod) are not installs
        assertNull(row(rows, "proj"))
        assertNull(row(rows, "skyspark-3.0.29.zip"))
        assertNull(row(rows, "skyspark-noAuth"))
    }

    /** SkySpark 3.0.16, 3.0.24 and 3.0.29 need Fantoms that are not installed. */
    fun testOldSkySparkShowsMissingFantom() {
        val rows = realRows() ?: return
        val r = row(rows, "skyspark-3.0.29") ?: return
        assertEquals(FantomInstall.Pairing.MISSING, r.pairing)
        assertEquals("Fantom 1.0.76 missing", r.warning)
        assertNull(r.runner)
        val missing = rows.filter { it.kind == FantomHome.Kind.SKYSPARK && it.pairing == FantomInstall.Pairing.MISSING }
            .map { it.dir.name }.toSet()
        for (old in listOf("skyspark-3.0.16", "skyspark-3.0.24", "skyspark-3.0.29")) {
            if (File(skysparkRoot, old).isDirectory) assertTrue(old, old in missing)
        }
    }

    /** Fantom 1.0.78 and 1.0.80 to 1.0.82 have no sys.pod: the version comes from the folder name. */
    fun testFantomVersionByFolderFallback() {
        val rows = realRows() ?: return
        for (v in listOf("1.0.78", "1.0.79", "1.0.80", "1.0.81", "1.0.82", "1.0.83", "1.0.84")) {
            val r = row(rows, "fantom-$v") ?: continue
            assertEquals(v, r.version)
            assertEquals(FantomInstall.Pairing.SELF, r.pairing)
            assertEquals("(itself)", r.path, r.fantomPath)
            val hasSys = File(r.dir, "lib/fan/sys.pod").isFile
            assertEquals(v, if (hasSys) "sys.pod" else "folder", r.matchSource)
        }
    }

    /** Haxall x.y.z takes the Fantom of SkySpark x.y.z. */
    fun testHaxallPairsBySkySparkVersion() {
        val rows = realRows() ?: return
        val hx = row(rows, "haxall-4.0.7") ?: return
        val sky = row(rows, "skyspark-4.0.7") ?: return
        assertEquals(FantomHome.Kind.HAXALL, hx.kind)
        assertEquals("1.0.84", hx.fantomVersion)
        assertEquals(sky.fantomPath, hx.fantomPath)
        assertEquals("SkySpark 4.0.7", hx.matchSource)
        assertEquals(sky.path, hx.skysparkPath)
        row(rows, "haxall-4.0.4")?.let { assertEquals("1.0.82", it.fantomVersion) }
        row(rows, "haxall-4.0.6")?.let { assertEquals("1.0.83", it.fantomVersion) }
    }

    /** Fake folders: Haxall with no SkySpark of its version asks the user; a chosen Fantom wins. */
    fun testFakeHaxallWithoutSkySparkAndChosenFantom() {
        val root = FileUtil.createTempDirectory("installs", null, true)
        try {
            val fantom = fakeFantom(root, "fantom-1.0.84")
            val hx = File(root, "haxall-9.9.9").apply { File(this, "src/core/haystack/fan").mkdirs() }
            val rows = FantomInstalls.pair(FantomInstalls.scan(listOf(root)))
            val r = rows.first { it.dir == hx }
            assertEquals(FantomInstall.Pairing.MISSING, r.pairing)
            assertEquals("Choose a Fantom", r.warning)
            val chosen = FantomInstalls.pair(FantomInstalls.scan(listOf(root)),
                mapOf(hx.path to FantomInstalls.Choice(true, fantom.path, false))).first { it.dir == hx }
            assertEquals(FantomInstall.Pairing.CHOSEN, chosen.pairing)
            assertEquals(fantom.path, chosen.fantomPath)
            assertEquals("1.0.84", chosen.fantomVersion)
            assertTrue(chosen.enabled)
            assertEquals(fantom, chosen.runner) // Haxall has no bin/fan: the paired Fantom runs
        } finally {
            FileUtil.delete(root)
        }
    }

    /** A home outside the roots becomes a custom row; one under a root does not. */
    fun testMigrationOfSingleHome() {
        val root = FileUtil.createTempDirectory("installs", null, true)
        val elsewhere = FileUtil.createTempDirectory("elsewhere", null, true)
        try {
            val fantom = fakeFantom(root, "fantom-1.0.84")
            val hx = File(elsewhere, "haxall-4.0.7").apply { File(this, "src/core/haystack/fan").mkdirs() }
            val m = FantomSettings.migrate(hx.path, fantom.path, listOf(root))!!
            assertEquals(hx.path, m.defaultTarget)
            assertEquals(hx, m.custom)
            assertEquals(FantomInstalls.Choice(true, fantom.path, true), m.choices[hx.path])
            val rows = FantomInstalls.rows(listOf(root), emptyList(), m)
            val r = rows.first { it.dir == hx }
            assertTrue(r.enabled && r.custom)
            assertEquals(FantomInstall.Pairing.CHOSEN, r.pairing)
            assertEquals(1, rows.count { it.enabled })

            val inRoot = FantomSettings.migrate(fantom.path, null, listOf(root))!!
            assertNull(inRoot.custom)
            assertEquals(FantomInstalls.Choice(true, null, false), inRoot.choices[fantom.path])
        } finally {
            FileUtil.delete(root)
            FileUtil.delete(elsewhere)
        }
    }

    private fun fakeFantom(root: File, name: String): File = File(root, name).apply {
        File(this, "bin").mkdirs()
        File(this, "bin/fan").writeText("#!/bin/sh\n")
        File(this, "src/sys/fan").mkdirs()
    }
}
