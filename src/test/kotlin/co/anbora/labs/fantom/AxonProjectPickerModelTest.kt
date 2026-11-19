package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.axon.AxonProjectPickerModel
import co.anbora.labs.fantom.ide.axon.AxonProjectPickerModel.Connection
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectInfo
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectKey
import junit.framework.TestCase

/** Grouping, counts, search, sorting and the start selection of the Axon project picker. Every name is made up. */
class AxonProjectPickerModelTest : TestCase() {

    private fun info(instance: String, project: String, n: Int = 1, sync: String? = null) =
        AxonProjectInfo(AxonProjectKey(instance, project), n, sync)

    private val list = listOf(
        info("plant-a", "bldg2", 4),
        info("demo", "site", 12, "2026-01-02T03:04:05Z"),
        info("Demo", "Alpha", 1),
        info("plant-a", "Site North"),
        info("demo", "beta.v2"),
        info("plant-a", "annex"),
    )
    private val model = AxonProjectPickerModel(list)

    private fun names(l: List<AxonProjectInfo>) = l.map { it.key.toString() }

    fun testGroupsByInstanceSortedCaseInsensitive() {
        assertEquals(listOf("Demo", "demo", "plant-a"), model.instances)
        assertEquals(listOf("annex", "bldg2", "Site North"), model.projects("plant-a", "").map { it.key.project })
        assertEquals(listOf("beta.v2", "site"), model.projects("demo", "").map { it.key.project })
    }

    fun testAllConnectionsListsEveryProjectSorted() {
        assertEquals(
            listOf("Demo/Alpha", "demo/beta.v2", "demo/site", "plant-a/annex", "plant-a/bldg2", "plant-a/Site North"),
            names(model.projects(null, "")),
        )
    }

    fun testConnectionsAndCountsWithoutSearch() {
        assertEquals(
            listOf(Connection(null, 6, 6), Connection("Demo", 1, 1), Connection("demo", 2, 2), Connection("plant-a", 3, 3)),
            model.connections(""),
        )
        assertEquals("6", AxonProjectPickerModel.connectionCount(model.connections("")[0], ""))
        assertEquals("All connections", AxonProjectPickerModel.connectionName(model.connections("")[0]))
        assertEquals("plant-a", AxonProjectPickerModel.connectionName(model.connections("")[3]))
    }

    fun testSearchOnProjectNameIsCaseInsensitiveAndHidesEmptyConnections() {
        val c = model.connections("SITE")
        assertEquals(listOf(Connection(null, 2, 6), Connection("demo", 1, 2), Connection("plant-a", 1, 3)), c)
        assertEquals("1/2", AxonProjectPickerModel.connectionCount(c[1], "SITE"))
        assertEquals(listOf("demo/site", "plant-a/Site North"), names(model.projects(null, "SITE")))
        assertEquals(listOf("plant-a/Site North"), names(model.projects("plant-a", "site")))
    }

    fun testSearchOnInstanceNameMatchesAllItsProjects() {
        val c = model.connections("plant")
        assertEquals(listOf(Connection(null, 3, 6), Connection("plant-a", 3, 3)), c)
        assertEquals(3, model.projects("plant-a", "plant").size)
    }

    fun testSearchOnInstanceSlashProjectAndTrimmedText() {
        assertEquals(listOf("demo/site"), names(model.projects(null, "  demo/si ")))
        assertEquals(listOf(Connection(null, 1, 6), Connection("demo", 1, 2)), model.connections("demo/si"))
    }

    fun testNamesWithSpacesAndDots() {
        assertEquals(listOf("plant-a/Site North"), names(model.projects(null, "e n")))
        assertEquals(listOf("demo/beta.v2"), names(model.projects(null, ".v2")))
        assertEquals("plant-a / Site North", AxonProjectPickerModel.projectName(info("plant-a", "Site North"), true))
        assertEquals("Site North", AxonProjectPickerModel.projectName(info("plant-a", "Site North"), false))
    }

    fun testNoMatch() {
        assertEquals(listOf(Connection(null, 0, 6)), model.connections("zzz"))
        assertTrue(model.projects(null, "zzz").isEmpty())
        assertTrue(model.projects("demo", "zzz").isEmpty())
        assertEquals("0/6", AxonProjectPickerModel.connectionCount(Connection(null, 0, 6), "zzz"))
    }

    fun testEmptyList() {
        val empty = AxonProjectPickerModel(emptyList())
        assertEquals(listOf(Connection(null, 0, 0)), empty.connections(""))
        assertTrue(empty.projects(null, "").isEmpty())
        assertTrue(empty.projects("demo", "").isEmpty())
        assertNull(empty.initialInstance(AxonProjectKey("demo", "site"), "demo"))
    }

    fun testUnknownConnectionHasNoProjects() {
        assertTrue(model.projects("nowhere", "").isEmpty())
    }

    fun testInitialConnectionPrefersTheActiveProject() {
        val active = AxonProjectKey("plant-a", "bldg2")
        assertEquals("plant-a", model.initialInstance(active, null))
        assertEquals("plant-a", model.initialInstance(active, "demo"))
        assertEquals("plant-a", model.initialInstance(active, AxonProjectPickerModel.ALL))
    }

    fun testInitialConnectionFallsBackToTheRememberedOneThenToAll() {
        val gone = AxonProjectKey("plant-b", "site")
        assertEquals("demo", model.initialInstance(gone, "demo"))
        assertEquals("demo", model.initialInstance(null, "demo"))
        assertNull(model.initialInstance(null, AxonProjectPickerModel.ALL))
        assertNull(model.initialInstance(null, "plant-b"))
        assertNull(model.initialInstance(null, null))
    }

    fun testRememberRoundTrip() {
        assertEquals("/", AxonProjectPickerModel.remember(null))
        assertEquals("demo", AxonProjectPickerModel.remember("demo"))
        assertNull(model.initialInstance(null, AxonProjectPickerModel.remember(null)))
        assertEquals("plant-a", model.initialInstance(null, AxonProjectPickerModel.remember("plant-a")))
    }

    fun testProjectDetail() {
        assertEquals("12 functions, synced 2026-01-02T03:04:05Z", AxonProjectPickerModel.projectDetail(info("demo", "site", 12, "2026-01-02T03:04:05Z")))
        assertEquals("1 function", AxonProjectPickerModel.projectDetail(info("demo", "site", 1)))
        assertEquals("0 functions", AxonProjectPickerModel.projectDetail(info("demo", "site", 0, " ")))
    }
}
