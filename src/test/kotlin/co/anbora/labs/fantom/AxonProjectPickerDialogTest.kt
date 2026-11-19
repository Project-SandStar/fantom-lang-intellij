package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.axon.AxonProjectPickerDialog
import co.anbora.labs.fantom.ide.axon.AxonProjectPickerModel
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectInfo
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectKey
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectSettings
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer

/**
 * The Axon project picker builds in headless mode, and the search narrows both panes. The
 * dialog is never shown, so focus, real key presses and double-clicks are not tested here.
 * Every name is made up.
 */
class AxonProjectPickerDialogTest : BasePlatformTestCase() {

    private val list = listOf(
        AxonProjectInfo(AxonProjectKey("demo", "site"), 12, "2026-01-02T03:04:05Z"),
        AxonProjectInfo(AxonProjectKey("demo", "bldg2"), 3, null),
        AxonProjectInfo(AxonProjectKey("plant-a", "site"), 5, null),
        AxonProjectInfo(AxonProjectKey("plant-a", "annex"), 1, null),
    )

    private fun <T> withDialog(active: AxonProjectKey?, remembered: String?, body: (AxonProjectPickerDialog) -> T): T {
        val dialog = AxonProjectPickerDialog(project, AxonProjectPickerModel(list), active, remembered)
        try {
            return body(dialog)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    private fun AxonProjectPickerDialog.connections() =
        (0 until connectionList.model.size).map { connectionList.model.getElementAt(it).instance }

    private fun AxonProjectPickerDialog.projects() =
        (0 until projectList.model.size).map { projectList.model.getElementAt(it).key.toString() }

    fun testTheActiveProjectIsPreselected() = withDialog(AxonProjectKey("plant-a", "site"), "demo") { d ->
        assertEquals(listOf(null, "demo", "plant-a"), d.connections())
        assertEquals("plant-a", d.connectionList.selectedValue.instance)
        assertEquals(listOf("plant-a/annex", "plant-a/site"), d.projects())
        assertEquals("plant-a/site", d.projectList.selectedValue.key.toString())
        assertTrue(d.isOKActionEnabled)
    }

    fun testTypingNarrowsBothPanes() = withDialog(null, null) { d ->
        assertNull(d.connectionList.selectedValue.instance)
        assertEquals(4, d.projects().size)

        d.searchField.text = "annex"
        assertEquals(listOf(null, "plant-a"), d.connections())
        assertEquals(listOf("plant-a/annex"), d.projects())
        assertEquals("plant-a/annex", d.projectList.selectedValue.key.toString())

        d.searchField.text = "zzz"
        assertEquals(listOf<String?>(null), d.connections())
        assertTrue(d.projects().isEmpty())
        assertFalse(d.isOKActionEnabled)

        d.searchField.text = ""
        assertEquals(listOf(null, "demo", "plant-a"), d.connections())
        assertTrue(d.isOKActionEnabled)
    }

    fun testASearchKeepsTheUserConnectionWhenItStillShows() = withDialog(null, "demo") { d ->
        assertEquals("demo", d.connectionList.selectedValue.instance)
        d.searchField.text = "annex"
        // demo has no match: the left pane falls back to "All connections"
        assertNull(d.connectionList.selectedValue.instance)
        assertEquals("demo", d.rememberValue)
        d.searchField.text = "site"
        assertEquals("demo", d.connectionList.selectedValue.instance)
        assertEquals(listOf("demo/site"), d.projects())
    }

    fun testSelectingAConnectionShowsItsProjectsAndIsRemembered() = withDialog(null, null) { d ->
        assertEquals(AxonProjectPickerModel.ALL, d.rememberValue)
        d.connectionList.selectedIndex = 1
        assertEquals("demo", d.rememberValue)
        assertEquals(listOf("demo/bldg2", "demo/site"), d.projects())
    }

    fun testUpDownAndPick() = withDialog(null, "demo") { d ->
        assertEquals("demo/bldg2", d.projectList.selectedValue.key.toString())
        d.moveSelection(1)
        assertEquals("demo/site", d.projectList.selectedValue.key.toString())
        d.moveSelection(1)
        assertEquals("demo/site", d.projectList.selectedValue.key.toString())
        d.moveSelection(-1)
        assertEquals("demo/bldg2", d.projectList.selectedValue.key.toString())
        d.pick()
        assertEquals(AxonProjectKey("demo", "bldg2"), d.chosen?.key)
    }

    fun testPickWithNoSelectionDoesNothing() = withDialog(null, null) { d ->
        d.searchField.text = "zzz"
        d.pick()
        assertNull(d.chosen)
    }

    fun testTheLastConnectionIsInTheWorkspaceState() {
        val settings = AxonProjectSettings.getInstance(project)
        val old = settings.pickerConnection
        try {
            settings.pickerConnection = AxonProjectPickerModel.ALL
            val xml = JDOMUtil.write(XmlSerializer.serialize(settings.state))
            assertTrue(xml, xml.contains("pickerConnection"))
            val back = XmlSerializer.deserialize(XmlSerializer.serialize(settings.state), AxonProjectSettings.State::class.java)
            assertEquals("/", back.pickerConnection)
            settings.pickerConnection = "plant-a"
            assertEquals("plant-a", settings.pickerConnection)
            settings.pickerConnection = ""
            assertNull(settings.pickerConnection)
        } finally {
            settings.pickerConnection = old
        }
    }
}
