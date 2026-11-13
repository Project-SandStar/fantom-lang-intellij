package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.settings.FantomConfigurable
import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomInstall
import co.anbora.labs.fantom.lang.library.FantomLibraryRootsProvider
import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.RootsChangeRescanningInfo
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import java.io.File

/**
 * The Fantom settings page: three roots, the install table, Add folder, the default build
 * target, and the migration of the single-home settings.
 */
class FantomConfigurableTest : BasePlatformTestCase() {

    private lateinit var configurable: FantomConfigurable
    private val user = File(System.getProperty("user.home"))
    private var temp: File? = null

    override fun setUp() {
        super.setUp()
        resetSettings()
        configurable = FantomConfigurable(project)
        configurable.createComponent()
        configurable.reset()
    }

    override fun tearDown() {
        try {
            configurable.disposeUIResources()
            resetSettings()
            FantomSettings.clearPodCache()
            WriteAction.runAndWait<RuntimeException> {
                ProjectRootManagerEx.getInstanceEx(project)
                    .makeRootsChange(EmptyRunnable.getInstance(), RootsChangeRescanningInfo.TOTAL_RESCAN)
            }
            temp?.let(FileUtil::delete)
        } finally {
            super.tearDown()
        }
    }

    private fun resetSettings() {
        val s = FantomSettings.getInstance(project)
        s.homePath = null
        s.sourcesPath = null
        s.installs = emptyList()
        s.defaultTarget = null
        s.lastBuildTarget = null
        s.fantomRoot = ""
        s.haxallRoot = ""
        s.skysparkRoot = ""
    }

    private fun row(folder: String): FantomInstall? = configurable.currentRows().firstOrNull { it.dir.name == folder }

    fun testOpeningThePageChangesNothing() {
        assertFalse(configurable.isModified)
        val home = FantomHome.detect() ?: return
        // migration: the detected home is the one ticked row and the default target
        val ticked = configurable.currentRows().filter { it.enabled }
        assertEquals(listOf(home.path), ticked.map { it.path })
        assertEquals(home.path, configurable.currentDefault())
    }

    fun testAddFolderRejectsANonInstall() {
        val dir = FileUtil.createTempDirectory("notahome", null).also { temp = it }
        assertEquals(FantomConfigurable.NOT_A_HOME, configurable.addFolder(dir))
        assertFalse(configurable.isModified)
    }

    /** A SkySpark dev build outside the roots, added as a custom folder, ticked and paired. */
    fun testAddCustomFolder() {
        val sky = File(user, "skyspark/skyspark-4.0.7")
        if (!sky.isDirectory || !File(user, "fantom/fantom-1.0.84").isDirectory) return
        val root = FileUtil.createTempDirectory("roots", null).also { temp = it }
        val dev = File(root, "dev/skyspark-4.0.8 dev1")
        File(dev, "lib/fan").mkdirs()
        File(dev, "bin").mkdirs()
        File(dev, "bin/fan").writeText("#!/bin/sh\n")
        File(sky, "lib/fan/sys.pod").copyTo(File(dev, "lib/fan/sys.pod"))
        File(dev, "lib/fan/skyarcd.pod").writeText("")
        configurable.fantomRootField.text = File(user, "fantom").path
        assertNull(configurable.addFolder(dev))
        val r = row("skyspark-4.0.8 dev1")!!
        assertTrue(r.custom && r.enabled)
        assertEquals("SkySpark 4.0.8 (skyspark-4.0.8 dev1)", r.name)
        assertEquals(FantomInstall.Pairing.AUTO, r.pairing)
        assertEquals(File(user, "fantom/fantom-1.0.84").path, r.fantomPath)
        configurable.apply()
        val saved = FantomSettings.getInstance(project).installs.first { it.path == dev.path }
        assertTrue(saved.custom && saved.enabled)
    }

    /** Ticking SkySpark 4.0.7 and making it the default: the index gets its pods' stubs and Fantom 1.0.84's sys; the runner is its own bin/fan. */
    fun testTickSkySparkAsDefaultTarget() {
        val sky = File(user, "skyspark/skyspark-4.0.7")
        val fantom = File(user, "fantom/fantom-1.0.84")
        if (!sky.isDirectory || !fantom.isDirectory) return
        configurable.setEnabled(sky.path, true)
        configurable.selectDefault(sky.path)
        assertTrue(configurable.isModified)
        configurable.apply()
        assertFalse(configurable.isModified)
        val s = FantomSettings.getInstance(project)
        assertEquals(sky.path, s.defaultTarget)
        assertEquals(sky, s.effectiveHome())
        assertEquals(fantom, s.effectiveSourcesHome())
        assertTrue(s.effectiveSources().contains(File(fantom, "src/sys/fan")))
        assertTrue(s.podsNeedingStubs().any { it.name == "skyarcd.pod" })
        // the single-home fields are left as they were
        assertNull(s.homePath)
    }

    /** Haxall's pods and SkySpark's: the default target's copy of a pod wins. */
    fun testDefaultTargetWinsOnDuplicatePods() {
        val hx = File(user, "haxall/haxall-4.0.7")
        val fantom = File(user, "fantom/fantom-1.0.84")
        if (!hx.isDirectory || !fantom.isDirectory) return
        configurable.setEnabled(hx.path, true)
        configurable.setEnabled(fantom.path, true)
        configurable.selectDefault(hx.path)
        configurable.apply()
        val s = FantomSettings.getInstance(project)
        assertEquals(fantom, s.effectiveHome()) // Haxall has no bin/fan: its paired Fantom runs
        val pods = s.podSources()
        assertEquals(pods.size, pods.map { it.pod }.distinct().size)
        assertTrue(pods.first { it.pod == "haystack" }.podDir.path.startsWith(hx.path))
        assertTrue(pods.first { it.pod == "sys" }.podDir.path.startsWith(fantom.path))
        assertTrue(FantomLibraryRootsProvider().getAdditionalProjectLibraries(project).isNotEmpty())
    }

    /** A chosen Fantom is stored and comes back on reset; "(auto)" goes back to the match. */
    fun testChosenFantomRoundTrip() {
        val sky = File(user, "skyspark/skyspark-4.0.7")
        val other = File(user, "fantom/fantom-1.0.83")
        if (!sky.isDirectory || !other.isDirectory) return
        configurable.setEnabled(sky.path, true)
        configurable.choose(sky.path, other.path)
        configurable.apply()
        val saved = FantomSettings.getInstance(project).installs.first { it.path == sky.path }
        assertEquals(FantomInstall.Pairing.CHOSEN, saved.pairing)
        assertEquals(other.path, saved.fantomPath)
        configurable.reset()
        assertEquals(other.path, row("skyspark-4.0.7")!!.fantomPath)
        configurable.choose(sky.path, null)
        assertEquals(FantomInstall.Pairing.AUTO, row("skyspark-4.0.7")!!.pairing)
    }

    /** The install list survives the XML round trip of `.idea/fantom.xml`. */
    fun testStateSerializationRoundTrip() {
        val s = FantomSettings.getInstance(project)
        s.installs = listOf(
            FantomInstall(FantomHome.Kind.SKYSPARK, "/x/skyspark-4.0.7", "4.0.7", "1.0.84", "/x/fantom-1.0.84",
                FantomInstall.Pairing.AUTO, "sys.pod", enabled = true),
            FantomInstall(FantomHome.Kind.HAXALL, "/x/haxall-4.0.7", "4.0.7", "1.0.84", "/y/fantom-1.0.83",
                FantomInstall.Pairing.CHOSEN, "SkySpark 4.0.7", enabled = false, custom = true, skysparkPath = "/x/skyspark-4.0.7"),
        )
        s.defaultTarget = "/x/skyspark-4.0.7"
        val xml = XmlSerializer.serialize(s.state)
        val back = XmlSerializer.deserialize(xml, FantomSettings.State::class.java)
        val restored = back.installs.mapNotNull { it.toInstall() }
        assertEquals(s.installs, restored)
        assertEquals("/x/skyspark-4.0.7", back.defaultTarget)
    }

    /** Migration from a single Haxall home with Fantom sources keeps both. */
    fun testMigrationFromHaxallHome() {
        val hx = File(user, "haxall/haxall-4.0.7")
        val fantom = File(user, "fantom/fantom-1.0.83")
        if (!hx.isDirectory || !fantom.isDirectory) return
        val s = FantomSettings.getInstance(project)
        s.homePath = hx.path
        s.sourcesPath = fantom.path
        val before = s.podSources().map { it.podDir.path }
        configurable.reset()
        assertFalse(configurable.isModified)
        val r = row("haxall-4.0.7")!!
        assertTrue(r.enabled)
        assertEquals(FantomInstall.Pairing.CHOSEN, r.pairing)
        assertEquals(fantom.path, r.fantomPath)
        assertEquals(1, configurable.currentRows().count { it.enabled })
        configurable.apply()
        assertEquals(hx.path, s.defaultTarget)
        assertEquals(hx.path, s.homePath)
        assertEquals(before.toSet(), s.podSources().map { it.podDir.path }.toSet())
    }
}
