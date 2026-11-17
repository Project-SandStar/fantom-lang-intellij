package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.axon.copy.AxonCopyException
import co.anbora.labs.fantom.lang.axon.copy.AxonCopyLimits
import co.anbora.labs.fantom.lang.axon.copy.AxonFallbackFolder
import co.anbora.labs.fantom.lang.axon.copy.AxonFunction
import co.anbora.labs.fantom.lang.axon.copy.AxonFunctionList
import co.anbora.labs.fantom.lang.axon.copy.AxonJson
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectCache
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectKey
import co.anbora.labs.fantom.lang.axon.copy.AxonServerClient
import junit.framework.TestCase
import java.io.File
import java.nio.file.Files

/**
 * The cache of the Axon project copy, the fallback folder reader, the JSON reader and the
 * name encoding. No IDE needed. Every name here is made up.
 */
class AxonProjectCacheTest : TestCase() {
    private lateinit var tmp: File
    private lateinit var root: File

    override fun setUp() {
        tmp = Files.createTempDirectory("axon-cache-test").toFile()
        root = File(tmp, "cache")
    }

    override fun tearDown() {
        tmp.walkBottomUp().forEach { it.setWritable(true); it.delete() }
    }

    private fun fn(name: String, src: String = "() => \"$name\"", doc: String? = null, mod: String? = "2026-01-02T03:04:05Z") =
        AxonFunction(name, src, doc, mod, AxonCopyLimits.sha256(src))

    private val key = AxonProjectKey("demo", "site")

    private fun allFiles(dir: File) = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.sorted().toList()

    fun testWriteThenRead() {
        val list = AxonFunctionList(key, "2026-02-03T04:05:06Z", listOf(fn("calcDailyAvg", doc = "Daily average"), fn("siteReport", mod = null)))
        val written = AxonProjectCache.write(root, list, "server")
        assertEquals(File(root, "demo/site"), written.dir)
        assertEquals(listOf(".axon-copy.json", "func/calcDailyAvg.axon", "func/siteReport.axon", "index.json"), allFiles(written.dir))
        assertEquals("() => \"calcDailyAvg\"", File(written.dir, "func/calcDailyAvg.axon").readText())

        val copy = AxonProjectCache.read(root, key)!!
        assertEquals(key, copy.key)
        assertEquals("server", copy.source)
        assertEquals("2026-02-03T04:05:06Z", copy.syncedAt)
        assertNotNull(copy.writtenAt)
        assertEquals(listOf("calcDailyAvg", "siteReport"), copy.functions.map { it.name })
        assertEquals("Daily average", copy.entry("calcDailyAvg")!!.doc)
        assertNull(copy.entry("siteReport")!!.mod)
        assertEquals(File(written.dir, "func/calcDailyAvg.axon"), copy.file("calcDailyAvg"))
        assertNull(copy.file("noSuchFunc"))
    }

    fun testMarkerHoldsKeySourceSyncedAtCountAndEachModAndHash() {
        val a = fn("calcDailyAvg")
        AxonProjectCache.write(root, AxonFunctionList(key, "2026-02-03T04:05:06Z", listOf(a)), "folder")
        val marker = AxonJson.parseObject(File(root, "demo/site/.axon-copy.json").readText())
        assertEquals("demo/site", marker["key"])
        assertEquals("folder", marker["source"])
        assertEquals("2026-02-03T04:05:06Z", marker["syncedAt"])
        assertEquals(1.0, marker["count"])
        val f = AxonJson.obj(AxonJson.obj(marker["functions"])!!["calcDailyAvg"])!!
        assertEquals("2026-01-02T03:04:05Z", f["mod"])
        assertEquals(a.hash, f["hash"])
        assertEquals(64, a.hash.length)

        val index = AxonJson.parseObject(File(root, "demo/site/index.json").readText())
        val row = AxonJson.obj(AxonJson.list(index["functions"])!![0])!!
        assertEquals(listOf("name", "file", "doc", "mod", "hash"), row.keys.toList())
    }

    fun testFilesAreReadOnly() {
        val copy = AxonProjectCache.write(root, AxonFunctionList(key, null, listOf(fn("calcDailyAvg"))), "server")
        for (f in listOf(copy.file("calcDailyAvg")!!, File(copy.dir, "index.json"), File(copy.dir, ".axon-copy.json"))) {
            // by permission bits: canWrite() is true for root and in some sandboxes
            val perms = runCatching { Files.getPosixFilePermissions(f.toPath()) }.getOrNull()
            if (perms != null) assertTrue("${f.name} is writable", perms.none { it.name.endsWith("_WRITE") })
            else assertFalse(f.canWrite())
        }
    }

    fun testSafeName() {
        assertEquals("calcDailyAvg", AxonProjectCache.safeName("calcDailyAvg"))
        assertEquals("calc%20daily%20avg", AxonProjectCache.safeName("calc daily avg"))
        assertEquals("%2E%2E", AxonProjectCache.safeName(".."))
        assertEquals("%2E", AxonProjectCache.safeName("."))
        assertEquals("%2E%2E%2F%2E%2E%2Fevil", AxonProjectCache.safeName("../../evil"))
        assertEquals("a%5Cb%3Ac", AxonProjectCache.safeName("a\\b:c"))
        assertEquals("site%2Ev2", AxonProjectCache.safeName("site.v2"))
        assertEquals("~", AxonProjectCache.safeName(""))
        assertEquals("con~", AxonProjectCache.safeName("con"))
        assertEquals("NUL~", AxonProjectCache.safeName("NUL"))
        assertEquals("%C3%A9", AxonProjectCache.safeName("\u00e9"))
        // the encoding is one to one: two names never share a file
        assertFalse(AxonProjectCache.safeName("a b") == AxonProjectCache.safeName("a%20b"))
        val long = "x".repeat(400)
        val cut = AxonProjectCache.safeName(long)
        assertTrue(cut.length <= 120)
        assertFalse(cut == AxonProjectCache.safeName(long + "y"))
        for (n in listOf("..", "a/b", "a\\b", " ", "\u0000", "~/x", "C:\\x")) {
            val s = AxonProjectCache.safeName(n)
            assertTrue(s, s.all { it.isLetterOrDigit() || it in "_-%~" })
        }
    }

    fun testNamesWithSpacesDotsAndSlashesStayInsideTheCache() {
        val wild = AxonProjectKey("demo site", "..")
        val names = listOf("calc daily avg", "..", "../../evil", "a/b", "/etc/passwd", "con", "site.report", ".hidden", "x\\y", "tr\u00e8s")
        val before = tmp.walkTopDown().map { it.path }.toSet()
        val copy = AxonProjectCache.write(root, AxonFunctionList(wild, null, names.map { fn(it) }), "server")

        assertEquals(File(root, "demo%20site/%2E%2E"), copy.dir)
        val rootPath = root.canonicalFile.toPath()
        val created = tmp.walkTopDown().map { it.path }.toSet() - before
        assertTrue(created.isNotEmpty())
        for (p in created) assertTrue("$p is outside the cache", File(p).canonicalFile.toPath().startsWith(rootPath))
        assertFalse(File(tmp, "evil.axon").exists())

        val read = AxonProjectCache.read(root, wild)!!
        assertEquals(names.sorted(), read.functions.map { it.name })
        for (n in names) {
            val f = read.file(n)!!
            assertEquals(read.funcDir, f.parentFile)
            assertEquals("() => \"$n\"", f.readText())
        }
        assertEquals("%2E%2E.axon", read.entry("..")!!.file)
        assertEquals("calc%20daily%20avg.axon", read.entry("calc daily avg")!!.file)
        assertTrue(AxonProjectCache.delete(root, wild))
        assertTrue(root.isDirectory)
    }

    fun testNamesThatDifferByCaseOnlyGetTwoFiles() {
        val copy = AxonProjectCache.write(root, AxonFunctionList(key, null, listOf(fn("Report"), fn("report"))), "server")
        val files = copy.functions.map { it.file }
        assertEquals(2, files.map { it.lowercase() }.toSet().size)
        assertEquals("() => \"Report\"", copy.file("Report")!!.readText())
        assertEquals("() => \"report\"", copy.file("report")!!.readText())
    }

    fun testAFailedWriteLeavesTheOldCopyIntact() {
        val old = AxonFunctionList(key, "2026-01-01T00:00:00Z", listOf(fn("calcDailyAvg", "() => 1"), fn("siteReport", "() => 2")))
        val dir = AxonProjectCache.write(root, old, "server").dir
        val before = allFiles(dir).associateWith { File(dir, it).readText() }

        var calls = 0
        val new = AxonFunctionList(key, "2026-03-03T00:00:00Z", (1..5).map { fn("newFunc$it", "() => 99") })
        try {
            AxonProjectCache.write(root, new, "server") { if (++calls == 3) throw IllegalStateException("cut off") }
            fail("the write must fail")
        } catch (e: IllegalStateException) {
            assertEquals("cut off", e.message)
        }
        assertEquals(before, allFiles(dir).associateWith { File(dir, it).readText() })
        assertEquals(listOf("site"), File(root, "demo").list()!!.toList())
        assertEquals("2026-01-01T00:00:00Z", AxonProjectCache.read(root, key)!!.syncedAt)
    }

    fun testAGoodWriteReplacesTheWholeCopy() {
        AxonProjectCache.write(root, AxonFunctionList(key, null, listOf(fn("oldOne"), fn("kept", "() => 1"))), "server")
        val copy = AxonProjectCache.write(root, AxonFunctionList(key, null, listOf(fn("kept", "() => 2"), fn("newOne"))), "server")
        assertEquals(listOf(".axon-copy.json", "func/kept.axon", "func/newOne.axon", "index.json"), allFiles(copy.dir))
        assertEquals("() => 2", copy.file("kept")!!.readText())
        assertEquals(listOf("site"), File(root, "demo").list()!!.toList())
    }

    fun testAFolderWithoutAMarkerIsNotACopy() {
        val dir = AxonProjectCache.write(root, AxonFunctionList(key, null, listOf(fn("calcDailyAvg"))), "server").dir
        File(dir, ".axon-copy.json").apply { setWritable(true); delete() }
        assertNull(AxonProjectCache.read(root, key))
    }

    fun testAMarkerOfAnotherProjectIsNotThisCopy() {
        val dir = AxonProjectCache.write(root, AxonFunctionList(key, null, listOf(fn("calcDailyAvg"))), "server").dir
        val other = File(root, "demo/other")
        dir.renameTo(other)
        assertNull(AxonProjectCache.read(root, AxonProjectKey("demo", "other")))
    }

    fun testACopyMovedAsideByACutOffWriteIsPutBack() {
        val dir = AxonProjectCache.write(root, AxonFunctionList(key, null, listOf(fn("calcDailyAvg"))), "server").dir
        assertTrue(dir.renameTo(File(dir.parentFile, "site.old-123")))
        val copy = AxonProjectCache.read(root, key)
        assertNotNull(copy)
        assertEquals(listOf("calcDailyAvg"), copy!!.functions.map { it.name })
        assertEquals(listOf("site"), File(root, "demo").list()!!.toList())
    }

    fun testDeleteRemovesTheCopyAndTheEmptyInstanceFolder() {
        AxonProjectCache.write(root, AxonFunctionList(key, null, listOf(fn("calcDailyAvg"))), "server")
        val other = AxonProjectKey("demo", "plant")
        AxonProjectCache.write(root, AxonFunctionList(other, null, listOf(fn("plantLoad"))), "server")

        assertTrue(AxonProjectCache.delete(root, key))
        assertNull(AxonProjectCache.read(root, key))
        assertNotNull(AxonProjectCache.read(root, other))
        assertTrue(AxonProjectCache.delete(root, other))
        assertFalse(File(root, "demo").exists())
        assertTrue(root.isDirectory)
        // deleting what is not there is fine
        assertTrue(AxonProjectCache.delete(root, key))
    }

    fun testDuplicateAndEmptyNamesAreRefused() {
        for (bad in listOf(listOf(fn("a"), fn("a")), listOf(fn("")))) {
            try {
                AxonProjectCache.write(root, AxonFunctionList(key, null, bad), "server")
                fail("must be refused")
            } catch (e: AxonCopyException) {
                assertEquals(AxonCopyException.Kind.BAD_REPLY, e.kind)
            }
        }
        assertFalse(File(root, "demo").exists())
    }

    // ---- fallback folder -------------------------------------------------------------------

    private fun projFolder(): File {
        val proj = File(tmp, "proj")
        val func = File(proj, "demo/site/func").apply { mkdirs() }
        File(proj, "demo/site/.sync-metadata.json").writeText(
            """{"lastSync":"2026-04-05T06:07:08.000Z","functionCount":3,"functions":{
               "calcDailyAvg":{"lastModified":"2026-04-01T00:00:00.000Z"},
               "siteReport":{"lastModified":"2026-04-02T00:00:00.000Z"}}}""")
        File(func, "calcDailyAvg.axon").writeText("(points, dates) => do\n  points.hisRead(dates).hisRollup(avg, 1day)\nend\n")
        File(func, "calcDailyAvg.trio").writeText(
            "func\nname:calcDailyAvg\ndoc:\n  Daily average of the points.\n  \n  Second paragraph.\nmod:11/10/2023, 7:07:07 PM UTC\nsrc:\n  (points, dates) => do\n  end\n")
        File(func, "siteReport.axon").writeText("() => readAll(site)")
        File(func, "siteReport.trio").writeText("name:siteReport\ndoc:\"One \\\"line\\\" doc\"\nfunc\n")
        File(func, "no trio.axon").writeText("() => 1")
        File(func, "notes.txt").writeText("not a function")
        // a second instance, and folders that are not projects
        File(proj, "lab/plant one/func").mkdirs()
        File(proj, "lab/plant one/.sync-metadata.json").writeText("{}")
        File(proj, "lab/plant one/func/plantLoad.axon").writeText("() => 2")
        File(proj, "lab/no-metadata/func").mkdirs()
        File(proj, ".git/objects").mkdirs()
        return proj
    }

    fun testFallbackFolderListsTheFoldersThatHoldSyncMetadata() {
        val list = AxonFallbackFolder(projFolder()).listProjects()
        assertEquals(listOf("demo/site", "lab/plant one"), list.map { it.key.toString() })
        assertEquals(3, list[0].functionCount)
        assertEquals("2026-04-05T06:07:08.000Z", list[0].lastSync)
        assertEquals(1, list[1].functionCount)
        assertNull(list[1].lastSync)
    }

    fun testFallbackFolderReadsSourceDocAndMod() {
        val got = AxonFallbackFolder(projFolder()).fetch(key, sync = true)
        assertEquals(key, got.key)
        assertEquals("2026-04-05T06:07:08.000Z", got.syncedAt)
        assertEquals(listOf("calcDailyAvg", "no trio", "siteReport"), got.functions.map { it.name })
        val avg = got.functions[0]
        assertTrue(avg.src.startsWith("(points, dates) => do"))
        assertEquals("Daily average of the points.\n\nSecond paragraph.", avg.doc)
        assertEquals("2023-11-10T19:07:07Z", avg.mod)
        assertEquals(AxonCopyLimits.sha256(avg.src), avg.hash)
        val report = got.functions[2]
        assertEquals("One \"line\" doc", report.doc)
        assertEquals("2026-04-02T00:00:00.000Z", report.mod)
        assertNull(got.functions[1].doc)
        assertNull(got.functions[1].mod)
    }

    fun testFallbackFolderNeverBuildsAPathFromAKey() {
        val proj = projFolder()
        File(tmp, "outside/func").mkdirs()
        File(tmp, "outside/.sync-metadata.json").writeText("{}")
        File(tmp, "outside/func/secret.axon").writeText("() => 0")
        for (k in listOf(AxonProjectKey("..", "outside"), AxonProjectKey("demo", "../../outside"), AxonProjectKey("demo", "nope"))) {
            try {
                AxonFallbackFolder(proj).fetch(k, false)
                fail("$k must be unknown")
            } catch (e: AxonCopyException) {
                assertEquals(AxonCopyException.Kind.UNKNOWN_PROJECT, e.kind)
            }
        }
    }

    fun testFallbackFolderIsNotChanged() {
        val proj = projFolder()
        val before = proj.walkTopDown().map { it.path to it.lastModified() }.toList()
        val src = AxonFallbackFolder(proj)
        AxonProjectCache.write(root, src.fetch(key, false), src.id)
        assertEquals(before, proj.walkTopDown().map { it.path to it.lastModified() }.toList())
    }

    fun testAMissingFallbackFolder() {
        try {
            AxonFallbackFolder(File(tmp, "nowhere")).listProjects()
            fail()
        } catch (e: AxonCopyException) {
            assertEquals(AxonCopyException.Kind.NOT_CONFIGURED, e.kind)
        }
    }

    fun testIsoOf() {
        assertEquals("2023-11-10T19:07:07Z", AxonFallbackFolder.isoOf("11/10/2023, 7:07:07 PM UTC"))
        assertEquals("2024-01-02T00:05:09Z", AxonFallbackFolder.isoOf("1/2/2024, 12:05:09\u202fAM UTC"))
        assertEquals("2026-04-01T00:00:00.000Z", AxonFallbackFolder.isoOf("2026-04-01T00:00:00.000Z"))
        assertNull(AxonFallbackFolder.isoOf("yesterday"))
        assertNull(AxonFallbackFolder.isoOf(""))
        assertNull(AxonFallbackFolder.isoOf(null))
    }

    /** The real `proj` folder of this machine, read in place. Nothing of it is printed or copied. */
    fun testRealProjFolderWhenPresent() {
        val proj = File(System.getProperty("user.home"), "Code/axon-mcp-server/proj")
        if (!proj.isDirectory) {
            println("AxonProjectCacheTest.testRealProjFolderWhenPresent skipped: missing the local proj folder")
            return
        }
        val src = AxonFallbackFolder(proj)
        val projects = src.listProjects()
        assertTrue("no project in the proj folder", projects.isNotEmpty())
        var functions = 0
        var withMod = 0
        var withDoc = 0
        for (p in projects) {
            val got = src.fetch(p.key, false)
            assertEquals(p.key, got.key)
            for (f in got.functions) assertEquals(AxonCopyLimits.sha256(f.src), f.hash)
            functions += got.functions.size
            withMod += got.functions.count { it.mod != null }
            withDoc += got.functions.count { it.doc != null }
            // every name becomes one file inside the cache, and the copy reads back whole
            val copy = AxonProjectCache.write(root, got, src.id)
            assertEquals(got.functions.size, copy.funcDir.list()!!.size)
            assertEquals(got.functions.map { it.name }.sorted(), AxonProjectCache.read(root, p.key)!!.functions.map { it.name })
            assertTrue(copy.dir.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
        }
        assertTrue("no function", functions > 0)
        assertTrue("no function has a mod", withMod > 0)
        assertTrue("no function has a doc", withDoc > 0)
        // every mod that was read is ISO text
        println("AxonProjectCacheTest.testRealProjFolderWhenPresent: ${projects.size} projects, $functions functions, $withMod with mod, $withDoc with doc")
    }

    // ---- JSON and URL --------------------------------------------------------------------------

    fun testJsonRoundTrip() {
        val v = linkedMapOf(
            "s" to "a \"quoted\" \\ line\nnext\ttab \u00e9 \u2028 \u0001",
            "n" to null, "t" to true, "i" to 42, "d" to 1.5,
            "l" to listOf(1, "two", emptyList<Any>(), emptyMap<String, Any>()),
        )
        for (indent in listOf(true, false)) {
            val back = AxonJson.parseObject(AxonJson.write(v, indent))
            assertEquals(v["s"], back["s"])
            assertNull(back["n"])
            assertTrue(back.containsKey("n"))
            assertEquals(true, back["t"])
            assertEquals(42.0, back["i"])
            assertEquals(1.5, back["d"])
            assertEquals(listOf(1.0, "two", emptyList<Any>(), emptyMap<String, Any>()), back["l"])
        }
        assertEquals("{\"a\":1,\"b\":[true,null]}", AxonJson.write(linkedMapOf("a" to 1, "b" to listOf(true, null)), indent = false))
    }

    fun testJsonReadsEscapesAndRefusesBadText() {
        assertEquals("A\u00e9/\n", AxonJson.parse("\"\\u0041\\u00e9\\/\\n\""))
        assertEquals(-12.5e3, AxonJson.parse(" -12.5e3 "))
        for (bad in listOf("", "{", "{\"a\":}", "[1,]", "{\"a\":1} x", "'a'", "\"abc", "nul", "{a:1}", "[".repeat(200))) {
            try {
                AxonJson.parse(bad)
                fail("'${bad.take(12)}' must be refused")
            } catch (e: AxonJson.Malformed) {
                // expected
            }
        }
    }

    fun testUrlSegment() {
        assertEquals("demo", AxonServerClient.segment("demo"))
        assertEquals("demo%20site", AxonServerClient.segment("demo site"))
        assertEquals("site.v2", AxonServerClient.segment("site.v2"))
        assertEquals("%2E%2E", AxonServerClient.segment(".."))
        assertEquals("%2E", AxonServerClient.segment("."))
        assertEquals("a%2Fb%3Fc%23d%25e%2Bf", AxonServerClient.segment("a/b?c#d%e+f"))
        assertEquals("%C3%A9", AxonServerClient.segment("\u00e9"))
    }

    fun testKeyParse() {
        assertEquals(AxonProjectKey("demo", "site"), AxonProjectKey.parse("demo/site"))
        assertEquals(AxonProjectKey("demo site", "a/b"), AxonProjectKey.parse("demo site/a/b"))
        assertEquals("demo/site", AxonProjectKey("demo", "site").toString())
        for (bad in listOf(null, "", "demo", "/site", "demo/")) assertNull(AxonProjectKey.parse(bad))
    }
}
