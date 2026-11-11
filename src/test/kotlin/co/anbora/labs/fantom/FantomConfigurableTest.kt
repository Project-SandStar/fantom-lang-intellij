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
            FantomSettings.getInstance(project).homePath = null
        } finally {
            super.tearDown()
        }
    }

    fun testEmptyFieldIsNotModified() {
        assertFalse(configurable.isModified)
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
