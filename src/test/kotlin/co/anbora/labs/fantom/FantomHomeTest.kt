package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.library.FantomHome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Plain JUnit 4 test of [FantomHome]; no IDE fixture. */
class FantomHomeTest {

    private lateinit var tmp: File
    private var oldProperty: String? = null

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("fantom-home").toFile()
        oldProperty = System.getProperty(FantomHome.PROPERTY)
    }

    @After
    fun tearDown() {
        if (oldProperty == null) System.clearProperty(FantomHome.PROPERTY)
        else System.setProperty(FantomHome.PROPERTY, oldProperty)
        tmp.deleteRecursively()
    }

    private fun makeHome(dir: File, vararg pods: String): File {
        File(dir, "bin").mkdirs()
        File(dir, "bin/fan").writeText("#!/bin/sh\n")
        File(dir, "src").mkdirs()
        for (pod in pods) File(dir, "src/$pod/fan").mkdirs()
        return dir
    }

    @Test
    fun detectUsesSystemProperty() {
        val home = makeHome(File(tmp, "home"), "sys")
        System.setProperty(FantomHome.PROPERTY, home.path)
        assertEquals(home, FantomHome.detect())
    }

    @Test
    fun sourceRootsListsOnlyExistingFanDirs() {
        val home = makeHome(File(tmp, "home"), "sys", "util")
        File(home, "src/noFan").mkdirs()
        File(home, "src/README.txt").writeText("x")
        val roots = FantomHome.sourceRoots(home)
        assertEquals(listOf(File(home, "src/sys/fan"), File(home, "src/util/fan")), roots)
    }

    @Test
    fun isHomeNeedsBinFanAndSrc() {
        val noBin = File(tmp, "noBin").also { File(it, "src").mkdirs() }
        assertFalse(FantomHome.isHome(noBin))
        val noSrc = File(tmp, "noSrc").also { File(it, "bin").mkdirs(); File(it, "bin/fan").writeText("") }
        assertFalse(FantomHome.isHome(noSrc))
        assertTrue(FantomHome.isHome(makeHome(File(tmp, "ok"), "sys")))
    }

    @Test
    fun propertyPointingToNonHomeFallsThrough() {
        val missing = File(tmp, "missing")
        System.setProperty(FantomHome.PROPERTY, missing.path)
        val found = FantomHome.detect()
        // Either nothing on this machine, or a real home: never the missing folder.
        assertNotEquals(missing, found)
        if (found != null) assertTrue(FantomHome.isHome(found))
    }

    @Test
    fun versionedHomesSortNumerically() {
        for (v in listOf("1.0.84", "1.0.100", "1.0.9")) makeHome(File(tmp, "fantom-$v"), "sys")
        File(tmp, "fantom-notes").mkdirs()
        val names = FantomHome.versionedHomes(tmp).map { it.name }
        assertEquals(listOf("fantom-1.0.100", "fantom-1.0.84", "fantom-1.0.9"), names)
    }
}
