package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.settings.FantomConfigurable
import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomLibraryRootsProvider
import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The Fantom settings page: a folder that is not a Fantom home is rejected and not stored;
 * a real home is stored and makes the library provider return the install's sources.
 */
class FantomConfigurableTest : BasePlatformTestCase() {

    private lateinit var configurable: FantomConfigurable

    override fun setUp() {
        super.setUp()
        FantomSettings.getInstance(project).homePath = null
        configurable = FantomConfigurable(project)
        configurable.createComponent()
        configurable.reset()
    }

    override fun tearDown() {
        try {
            configurable.disposeUIResources()
            val changed = FantomSettings.getInstance(project).homePath != null ||
                FantomSettings.getInstance(project).sourcesPath != null
            FantomSettings.getInstance(project).homePath = null
            FantomSettings.getInstance(project).sourcesPath = null
            if (changed) {
                FantomSettings.clearPodCache()
                com.intellij.openapi.application.WriteAction.runAndWait<RuntimeException> {
                    com.intellij.openapi.roots.ex.ProjectRootManagerEx.getInstanceEx(project)
                        .makeRootsChange(com.intellij.openapi.util.EmptyRunnable.getInstance(), com.intellij.openapi.project.RootsChangeRescanningInfo.TOTAL_RESCAN)
                }
            }
        } finally {
            super.tearDown()
        }
    }

    fun testEmptyFieldIsNotModified() {
        assertFalse(configurable.isModified)
    }

    fun testSourcesWithoutSysAreRejected() {
        val dir = FileUtil.createTempDirectory("notsources", null)
        configurable.sourcesField.text = dir.path
        assertTrue(configurable.isModified)
        try {
            configurable.apply()
            fail("apply() must throw for sources without src/sys/fan")
        } catch (e: ConfigurationException) {
            assertEquals(FantomConfigurable.NOT_SOURCES, e.message)
        }
        assertNull(FantomSettings.getInstance(project).sourcesPath)
    }

    fun testHaxallHomeWithFantomSources() {
        val haxall = java.io.File(System.getProperty("user.home"), "haxall/haxall-4.0.7")
        val fantom = java.io.File(System.getProperty("user.home"), "fantom/fantom-1.0.84")
        if (!haxall.isDirectory || !fantom.isDirectory) {
            println("FantomConfigurableTest.testHaxallHomeWithFantomSources skipped")
            return
        }
        configurable.homeField.text = haxall.path
        configurable.sourcesField.text = fantom.path
        assertTrue(configurable.homeLabel.text, configurable.homeLabel.text.startsWith("Haxall home"))
        configurable.apply()
        val settings = FantomSettings.getInstance(project)
        assertEquals(haxall.path, settings.homePath)
        assertEquals(fantom.path, settings.sourcesPath)
        assertFalse(configurable.isModified)
        val roots = settings.effectiveSources()
        assertTrue(roots.contains(java.io.File(haxall, "src/core/haystack/fan")))
        assertTrue(roots.contains(java.io.File(fantom, "src/sys/fan")))
    }

    fun testSkySparkHomeShowsHint() {
        val sky = java.io.File(System.getProperty("user.home"), "skyspark/skyspark-3.0.29")
        if (!sky.isDirectory) return
        configurable.homeField.text = sky.path
        assertTrue(configurable.homeLabel.text, configurable.homeLabel.text.contains("SkySpark ships compiled pods only"))
    }

    fun testNonHomeFolderIsRejectedAndNotStored() {
        val dir = FileUtil.createTempDirectory("notahome", null)
        configurable.homeField.text = dir.path
        assertTrue(configurable.isModified)
        try {
            configurable.apply()
            fail("apply() must throw for a folder that is not a Fantom home")
        } catch (e: ConfigurationException) {
            assertEquals(FantomConfigurable.NOT_A_HOME, e.message)
        }
        assertNull(FantomSettings.getInstance(project).homePath)
    }

    fun testRealHomeIsStoredAndLibraryAppears() {
        val home = FantomHome.detect()
        if (home == null) {
            println("FantomConfigurableTest.testRealHomeIsStoredAndLibraryAppears skipped: no Fantom home found")
            return
        }
        configurable.homeField.text = home.path
        assertTrue(configurable.isModified)
        configurable.apply()
        assertEquals(home.path, FantomSettings.getInstance(project).homePath)
        assertFalse(configurable.isModified)
        val libs = FantomLibraryRootsProvider().getAdditionalProjectLibraries(project)
        assertTrue(libs.isNotEmpty())
    }

    fun testClearingTheFieldStoresNull() {
        val home = FantomHome.detect() ?: return
        FantomSettings.getInstance(project).homePath = home.path
        configurable.reset()
        assertFalse(configurable.isModified)
        configurable.homeField.text = ""
        assertTrue(configurable.isModified)
        configurable.apply()
        assertNull(FantomSettings.getInstance(project).homePath)
    }
}
