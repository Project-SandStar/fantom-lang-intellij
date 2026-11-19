package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.axon.AxonProjectPickerDialog
import co.anbora.labs.fantom.ide.axon.AxonProjectPickerModel
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectInfo
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectKey
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectSettings
import com.intellij.openapi.components.State
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent

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
        // the fallback is not a choice of the user: nothing is to be stored
        assertNull(d.rememberValue)
        d.searchField.text = "site"
        assertEquals("demo", d.connectionList.selectedValue.instance)
        assertEquals(listOf("demo/site"), d.projects())
        assertNull(d.rememberValue)
    }

    fun testSelectingAConnectionShowsItsProjectsAndIsRemembered() = withDialog(null, null) { d ->
        // opening (and closing) the window stores nothing
        assertNull(d.rememberValue)
        d.connectionList.selectedIndex = 1
        assertEquals("demo", d.rememberValue)
        assertEquals(listOf("demo/bldg2", "demo/site"), d.projects())
        d.connectionList.selectedIndex = 0
        assertEquals(AxonProjectPickerModel.ALL, d.rememberValue)
    }

    fun testAClickOnTheRowASearchSelectedIsAChoice() = withDialog(null, "demo") { d ->
        d.searchField.text = "annex"
        assertNull(d.connectionList.selectedValue.instance)
        // the user clicks "All connections", which the search already selected: no selection event
        d.connectionList.mouseListeners.forEach {
            it.mouseReleased(MouseEvent(d.connectionList, MouseEvent.MOUSE_RELEASED, 0L, 0, 1, 1, 1, false))
        }
        assertEquals(AxonProjectPickerModel.ALL, d.rememberValue)
        d.searchField.text = ""
        // the search no longer takes the user back to "demo"
        assertNull(d.connectionList.selectedValue.instance)
        assertEquals(4, d.projects().size)
    }

    fun testOpeningOnTheActiveProjectDoesNotOverwriteTheRememberedConnection() = withDialog(AxonProjectKey("plant-a", "site"), "demo") { d ->
        assertEquals("plant-a", d.connectionList.selectedValue.instance)
        assertNull(d.rememberValue)
    }

    private fun AxonProjectPickerDialog.press(keyCode: Int): KeyEvent {
        val field = searchField.textEditor
        val e = KeyEvent(field, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, keyCode, KeyEvent.CHAR_UNDEFINED)
        for (l in field.keyListeners) l.keyPressed(e)
        return e
    }

    fun testDownUpAndEnterInTheSearchFieldDriveTheProjectList() = withDialog(null, "demo") { d ->
        assertEquals("demo/bldg2", d.projectList.selectedValue.key.toString())
        assertTrue(d.press(KeyEvent.VK_DOWN).isConsumed)
        assertEquals("demo/site", d.projectList.selectedValue.key.toString())
        assertTrue(d.press(KeyEvent.VK_UP).isConsumed)
        assertEquals("demo/bldg2", d.projectList.selectedValue.key.toString())
        assertNull(d.chosen)
        assertTrue(d.press(KeyEvent.VK_ENTER).isConsumed)
        assertEquals(AxonProjectKey("demo", "bldg2"), d.chosen?.key)
    }

    fun testOtherKeysInTheSearchFieldAreLeftAlone() = withDialog(null, null) { d ->
        assertFalse(d.press(KeyEvent.VK_A).isConsumed)
        assertFalse(d.press(KeyEvent.VK_ESCAPE).isConsumed)
        assertNull(d.chosen)
    }

    /** The platform search field binds Escape to "clear" when this is true; then Escape would not close the window. */
    fun testEscapeIsNotTakenBySearchFieldAndFocusStartsThere() = withDialog(null, null) { d ->
        assertFalse(d.searchField.clearsTextOnEscape)
        assertSame(d.searchField.textEditor, d.preferredFocusedComponent)
    }

    fun testFiveHundredProjectsFilterInTheDialog() {
        val big = (0 until 500).map { AxonProjectInfo(AxonProjectKey("plant-${it % 5}", "bldg$it"), it, null) }
        val dialog = AxonProjectPickerDialog(project, AxonProjectPickerModel(big), AxonProjectKey("plant-4", "bldg499"), null)
        try {
            assertEquals("plant-4", dialog.connectionList.selectedValue.instance)
            assertEquals("plant-4/bldg499", dialog.projectList.selectedValue.key.toString())
            val start = System.nanoTime()
            for (q in listOf("b", "bl", "bld", "bldg", "bldg4", "bldg49", "bldg499")) dialog.searchField.text = q
            val ms = (System.nanoTime() - start) / 1_000_000
            assertEquals(listOf("plant-4/bldg499"), dialog.projects())
            assertEquals("plant-4/bldg499", dialog.projectList.selectedValue.key.toString())
            assertTrue("typing took $ms ms", ms < 3000)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
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
        // the state is stored in the workspace file only, never in a shared settings file
        val state = AxonProjectSettings::class.java.getAnnotation(State::class.java)
        assertEquals(listOf(StoragePathMacros.WORKSPACE_FILE), state.storages.map { it.value })
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
