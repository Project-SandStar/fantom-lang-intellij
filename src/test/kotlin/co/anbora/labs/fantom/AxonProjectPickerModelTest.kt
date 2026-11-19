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

    fun testSearchOnTheShownInstanceSpaceSlashProjectText() {
        // under "All connections" a row reads "demo / site"; typing what is shown finds it
        assertEquals(listOf("demo/site"), names(model.projects(null, "demo / si")))
        assertEquals(listOf(Connection(null, 1, 6), Connection("demo", 1, 2)), model.connections("demo / si"))
    }

    fun testAProjectNamedLikeAnotherConnection() {
        val m = AxonProjectPickerModel(listOf(info("demo", "plant-a"), info("demo", "site"), info("plant-a", "annex")))
        // both the project "plant-a" and every project of the connection "plant-a" match
        assertEquals(listOf("demo/plant-a", "plant-a/annex"), names(m.projects(null, "plant-a")))
        assertEquals(listOf(Connection(null, 2, 3), Connection("demo", 1, 2), Connection("plant-a", 1, 1)), m.connections("plant-a"))
        assertEquals(listOf("demo/plant-a"), names(m.projects("demo", "plant-a")))
        assertEquals(listOf("plant-a/annex"), names(m.projects("plant-a", "plant-a")))
    }

    fun testUnicodeNames() {
        val m = AxonProjectPickerModel(listOf(info("démo", "Bâtiment Est"), info("demo", "Gebäude"), info("plant-a", "site")))
        assertEquals(listOf("démo/Bâtiment Est"), names(m.projects(null, "BÂTIMENT")))
        assertEquals(listOf("demo/Gebäude"), names(m.projects(null, "GEBÄUDE")))
        assertEquals(listOf(Connection(null, 1, 3), Connection("démo", 1, 1)), m.connections("dém"))
        assertEquals("démo", m.initialInstance(AxonProjectKey("démo", "Bâtiment Est"), null))
    }

    fun testFiveHundredProjectsFilterAtEveryKeystroke() {
        val big = (0 until 500).map { info("plant-${it % 7}", "bldg$it", it) }
        val m = AxonProjectPickerModel(big)
        assertEquals(500, m.projects(null, "").size)
        assertEquals(8, m.connections("").size)
        val typed = "plant-3/bldg24"
        val start = System.nanoTime()
        for (i in 1..typed.length) {
            val q = typed.substring(0, i)
            m.connections(q)
            m.projects(null, q)
            m.projects("plant-3", q)
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        // bldg24 is on plant-3 (24 % 7 == 3); bldg240..249 are spread over the connections
        assertEquals(listOf("plant-3/bldg24", "plant-3/bldg241", "plant-3/bldg248"), names(m.projects(null, typed)))
        assertEquals(listOf(Connection(null, 3, 500), Connection("plant-3", 3, 71)), m.connections(typed))
        // a generous bound: the real cost is well under a millisecond per keystroke
        assertTrue("filtering took $ms ms", ms < 2000)
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
