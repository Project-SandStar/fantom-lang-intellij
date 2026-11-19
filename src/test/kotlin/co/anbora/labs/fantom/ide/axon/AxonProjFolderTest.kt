package co.anbora.labs.fantom.ide.axon

import co.anbora.labs.fantom.lang.axon.copy.AxonCopyException
import co.anbora.labs.fantom.lang.axon.copy.AxonCopyLimits
import co.anbora.labs.fantom.lang.axon.copy.AxonFunction
import co.anbora.labs.fantom.lang.axon.copy.AxonFunctionList
import co.anbora.labs.fantom.lang.axon.copy.AxonFunctionSource
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectCopy
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectInfo
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectKey
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectSettings
import co.anbora.labs.fantom.lang.axon.copy.AxonServerSettings
import co.anbora.labs.fantom.lang.axon.proj.AxonProjFolders
import co.anbora.labs.fantom.lang.axon.project.AxonProjectFunction
import co.anbora.labs.fantom.lang.axon.project.AxonProjectFunctions
import co.anbora.labs.fantom.lang.axon.psi.AxonNameUse
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Task 4.38: an Axon file in a `proj` folder (`proj/<connection>/<project>/func/<name>.axon`)
 * sees the functions of its own project only. Every name here is made up.
 */
class AxonProjFolderTest : BasePlatformTestCase() {

    private val folders get() = AxonProjFolders.getInstance()
    private val server get() = AxonServerSettings.getInstance()

    override fun setUp() {
        super.setUp()
        server.projFolder = ""
        AxonProjFolders.clearForTest()
    }

    override fun tearDown() {
        try {
            server.projFolder = ""
            AxonProjFolders.clearForTest()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        } finally {
            super.tearDown()
        }
    }

    private fun meta(instance: String, project: String) = """{"instance":"$instance","project":"$project","functionCount":1}"""

    /** A made-up proj folder: demo/site and demo/other, each with a `siteArea`; plant-a/bldg2. */
    private fun layout(root: String = "proj") {
        myFixture.addFileToProject("$root/demo/site/.sync-metadata.json", meta("demo", "site"))
        myFixture.addFileToProject("$root/demo/site/func/siteArea.axon", "(site) => site->area")
        myFixture.addFileToProject("$root/demo/site/func/siteArea.trio", "name: siteArea\nfunc\ndoc:\n  Area of a site.\n  In square metres.\nsrc: (site) => site->area\n")
        myFixture.addFileToProject("$root/demo/site/func/siteReport.axon", "() => readAll(site).map(siteArea)")
        myFixture.addFileToProject("$root/demo/other/.sync-metadata.json", meta("demo", "other"))
        myFixture.addFileToProject("$root/demo/other/func/siteArea.axon", "(site) => 0")
        myFixture.addFileToProject("$root/demo/other/func/otherOnly.axon", "() => siteArea(1)")
        myFixture.addFileToProject("$root/plant-a/bldg2/.sync-metadata.json", meta("plant-a", "bldg2"))
        myFixture.addFileToProject("$root/plant-a/bldg2/func/load.axon", "() => 42")
        // copies, never real code
        myFixture.addFileToProject("$root/demo/backup/site/.sync-metadata.json", meta("demo", "site"))
        myFixture.addFileToProject("$root/demo/backup/site/func/old.axon", "() => siteArea(1)")
        myFixture.addFileToProject("$root/demo/backup/site/func/siteArea.axon", "(site) => -1")
        myFixture.addFileToProject("$root/demo/site/func/.versions/siteArea.axon", "(site) => -2")
        myFixture.addFileToProject("$root/demo/site/func/.versions/siteReport.axon", "() => siteArea(2)")
        myFixture.addFileToProject("$root/demo/site/.backups/func/siteReport.axon", "() => siteArea(3)")
        myFixture.addFileToProject("$root/demo/.sidecar-x/site/func/siteReport.axon", "() => siteArea(4)")
    }

    private fun file(path: String): VirtualFile = myFixture.findFileInTempDir(path) ?: error("no $path")

    /** Opens [path] with [text] (a caret marks the name) and resolves the name at the caret. */
    private fun resolveIn(path: String, text: String): PsiElement? {
        val vf = file(path)
        val offset = text.indexOf("<caret>")
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)!!.setText(text.replace("<caret>", ""))
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        myFixture.openFileInEditor(vf)
        myFixture.editor.caretModel.moveToOffset(offset)
        val psi = myFixture.file
        assertEmpty("parse errors", PsiTreeUtil.findChildrenOfType(psi, PsiErrorElement::class.java))
        val use = PsiTreeUtil.getParentOfType(psi.findElementAt(offset), AxonNameUse::class.java, false)!!
        return use.reference!!.resolve()
    }

    private fun pathOf(e: PsiElement?): String? = (e as? PsiFile)?.virtualFile?.path?.substringAfter("/proj/")

    // ---- gate 1: two projects with a function of the same name ----------------------------------

    fun testACallResolvesInItsOwnProjectOnly() {
        layout()
        assertEquals("demo/site/func/siteArea.axon", pathOf(resolveIn("proj/demo/site/func/siteReport.axon", "() => <caret>siteArea(1)")))
        assertEquals("demo/other/func/siteArea.axon", pathOf(resolveIn("proj/demo/other/func/otherOnly.axon", "() => <caret>siteArea(1)")))
        assertNull("no load in demo/site", resolveIn("proj/demo/site/func/siteReport.axon", "() => <caret>load()"))
        assertEquals("plant-a/bldg2/func/load.axon", pathOf(resolveIn("proj/plant-a/bldg2/func/load.axon", "() => <caret>load()")))
    }

    fun testItWorksTheSameWithTheGlobalFolderSet() {
        layout()
        server.projFolder = file("proj").path
        assertEquals("demo/site/func/siteArea.axon", pathOf(resolveIn("proj/demo/site/func/siteReport.axon", "() => <caret>siteArea(1)")))
        assertEquals("demo/other/func/siteArea.axon", pathOf(resolveIn("proj/demo/other/func/otherOnly.axon", "() => <caret>siteArea(1)")))
    }

    fun testUnderTheGlobalFolderTheProjectComesFromThePathEvenWithoutMetadata() {
        myFixture.addFileToProject("proj/lab/bldg2/func/a.axon", "() => b()")
        myFixture.addFileToProject("proj/lab/bldg2/func/b.axon", "() => 1")
        val a = file("proj/lab/bldg2/func/a.axon")
        assertNull("no metadata, no global folder: not a project", folders.projectOf(a))
        server.projFolder = file("proj").path + "/"
        val found = folders.projectOf(a)!!
        assertEquals(AxonProjectKey("lab", "bldg2"), found.key)
        assertEquals(file("proj/lab/bldg2"), found.folder)
        assertEquals("lab/bldg2/func/b.axon", pathOf(resolveIn("proj/lab/bldg2/func/a.axon", "() => <caret>b()")))
        // the connection and the global folder itself are no project
        assertNull(folders.projectOf(file("proj/lab")))
        assertNull(folders.projectOf(file("proj")))
    }

    fun testTheKeyComesFromTheMetadataNotTheFolderNames() {
        myFixture.addFileToProject("proj/conn one/site copy/.sync-metadata.json", meta("demo", "site"))
        myFixture.addFileToProject("proj/conn one/site copy/func/a.axon", "() => 1")
        assertEquals(AxonProjectKey("demo", "site"), folders.projectOf(file("proj/conn one/site copy/func/a.axon"))!!.key)
        // a metadata file without the names: the folder names
        myFixture.addFileToProject("proj/lab/bldg2/.sync-metadata.json", "{}")
        myFixture.addFileToProject("proj/lab/bldg2/func/a.axon", "() => 1")
        assertEquals(AxonProjectKey("lab", "bldg2"), folders.projectOf(file("proj/lab/bldg2/func/a.axon"))!!.key)
        assertEquals(AxonProjectKey("lab", "bldg2"), folders.projectOfNow(file("proj/lab/bldg2"))!!.key)
    }

    // ---- gate 2: backup and version folders --------------------------------------------------------

    fun testBackupAndVersionFilesResolveNothingAndHaveNoProject() {
        layout()
        for (global in listOf("", file("proj").path)) {
            server.projFolder = global
            for (path in listOf(
                "proj/demo/backup/site/func/old.axon",
                "proj/demo/site/func/.versions/siteReport.axon",
                "proj/demo/site/.backups/func/siteReport.axon",
                "proj/demo/.sidecar-x/site/func/siteReport.axon",
            )) {
                assertNull("$path [$global]", folders.projectOf(file(path)))
                assertEquals("$path [$global]", AxonProjFolders.Place.Copy, folders.placeOf(file(path)))
                assertNull("$path [$global]", resolveIn(path, "() => <caret>siteArea(1)"))
                assertEquals("Axon: no project", AxonProjectWidgets.text(project))
            }
        }
    }

    // ---- gate 3: a file outside any project folder -------------------------------------------------

    fun testAFileOutsideBehavesAsBefore() {
        layout()
        myFixture.addFileToProject("scripts/a.axon", "() => siteArea(1)")
        val a = file("scripts/a.axon")
        assertEquals(AxonProjFolders.Place.Outside, folders.placeOf(a))
        assertNull(resolveIn("scripts/a.axon", "() => <caret>siteArea(1)"))
        assertEquals("Axon: none", AxonProjectWidgets.text(project))
    }

    /** An in-memory copy source: no network. */
    private class MemSource(val data: Map<AxonProjectKey, List<AxonFunction>>) : AxonFunctionSource {
        override val id = "server"
        override fun listProjects(check: () -> Unit) = data.map { AxonProjectInfo(it.key, it.value.size, null) }
        override fun fetch(key: AxonProjectKey, sync: Boolean, check: () -> Unit): AxonFunctionList =
            AxonFunctionList(key, null, data[key] ?: throw AxonCopyException(AxonCopyException.Kind.UNKNOWN_PROJECT, "unknown"))
    }

    /** With a project copy chosen: a file outside uses the copy, a file in a project folder never does. */
    fun testTheCopyServesFilesOutsideOnly() {
        layout()
        myFixture.addFileToProject("scripts/a.axon", "() => copyOnly()")
        val copy = AxonProjectCopy.getInstance(project)
        val cache = FileUtil.createTempDirectory("axon-proj-cache", null)
        val key = AxonProjectKey("lab", "copy")
        fun fn(name: String, src: String) = AxonFunction(name, src, null, null, AxonCopyLimits.sha256(src))
        try {
            copy.testCacheRoot = cache
            copy.testSource = MemSource(mapOf(key to listOf(fn("copyOnly", "() => 1"), fn("siteArea", "(site) => 7"))))
            copy.selectNow(key)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val outside = resolveIn("scripts/a.axon", "() => <caret>copyOnly()") as? PsiFile
            assertEquals("copyOnly.axon", outside?.virtualFile?.name)
            assertFalse(outside!!.virtualFile.path, outside.virtualFile.path.contains("/proj/"))
            assertEquals("Axon: lab/copy", AxonProjectWidgets.text(project))
            assertNull("the copy is not used in a project folder", resolveIn("proj/demo/site/func/siteReport.axon", "() => <caret>copyOnly()"))
            assertEquals("demo/site/func/siteArea.axon", pathOf(resolveIn("proj/demo/site/func/siteReport.axon", "() => <caret>siteArea(1)")))
            assertNull("nor in a backup folder", resolveIn("proj/demo/backup/site/func/old.axon", "() => <caret>copyOnly()"))
        } finally {
            copy.dropNow()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            copy.testSource = null
            copy.testCacheRoot = null
            cache.walkTopDown().forEach { it.setWritable(true) }
            FileUtil.delete(cache)
        }
    }

    // ---- completion, doc, find usages, status bar ---------------------------------------------------

    fun testCompletionListsTheFunctionsOfTheSameProject() {
        layout()
        resolveIn("proj/demo/other/func/otherOnly.axon", "() => <caret>siteArea(1)")
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.textLength)
        myFixture.type("\n")
        myFixture.completeBasic()
        val names = myFixture.lookupElementStrings.orEmpty()
        assertTrue(names.toString(), names.containsAll(listOf("siteArea", "otherOnly")))
        assertFalse(names.toString(), names.contains("siteReport") || names.contains("load"))
    }

    fun testQuickDocShowsTheDocOfTheTrioRecord() {
        layout()
        val target = resolveIn("proj/demo/site/func/siteReport.axon", "() => <caret>siteArea(1)")
        val doc = AxonDocumentationProvider().generateDoc(target, null)!!
        assertTrue(doc, doc.contains("siteArea(site)") && doc.contains("demo/site") && doc.contains("Area of a site."))
        val other = AxonDocumentationProvider().generateDoc(resolveIn("proj/demo/other/func/otherOnly.axon", "() => <caret>siteArea(1)"), null)!!
        assertFalse(other, other.contains("Area of a site."))
    }

    /** A doc asked while the IDE indexes is null, and is read again once indexing ends. */
    fun testADocAskedInDumbModeIsReadAgainAfterIndexing() {
        layout()
        val psi = PsiManager.getInstance(project).findFile(file("proj/demo/site/func/siteReport.axon"))!!
        val set = AxonProjectFunctions.of(psi)!!
        val fn = set.function("siteArea")!!
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertNull(fn.doc)
        }
        assertEquals("Area of a site.\nIn square metres.", fn.doc)
    }

    /** The cache rule itself: a null is kept only when the source said it was final. */
    fun testTheDocCacheKeepsOnlyAFinalAnswer() {
        var ready = false
        var calls = 0
        val fn = AxonProjectFunction("siteArea", { calls++; if (ready) "Area." else null }, { ready })
        assertNull(fn.doc)
        assertNull(fn.doc)
        assertEquals(2, calls)
        ready = true
        assertEquals("Area.", fn.doc)
        assertEquals("Area.", fn.doc)
        assertEquals(3, calls)
        // a final null is kept
        var n = 0
        val none = AxonProjectFunction("x", { n++; null }, { true })
        assertNull(none.doc)
        assertNull(none.doc)
        assertEquals(1, n)
    }

    fun testFindUsagesStaysInTheProject() {
        layout()
        val target = PsiManager.getInstance(project).findFile(file("proj/demo/site/func/siteArea.axon"))!!
        val usages = ReferencesSearch.search(target, GlobalSearchScope.projectScope(project)).findAll()
            .map { it.element.containingFile.virtualFile.path.substringAfter("/proj/") }.sorted()
        assertEquals(listOf("demo/site/func/siteReport.axon"), usages.filter { it.endsWith(".axon") })
        assertFalse(usages.toString(), usages.any { it.startsWith("demo/other") || it.contains("backup") })
        // the use scope itself: the project folder, not the IDE project
        val scope = PsiSearchHelper.getInstance(project).getUseScope(target)
        assertTrue(scope.toString(), scope is GlobalSearchScope)
        scope as GlobalSearchScope
        assertTrue(scope.contains(file("proj/demo/site/func/siteReport.axon")))
        assertTrue(scope.contains(file("proj/demo/site/func/siteArea.trio")))
        assertFalse("another project", scope.contains(file("proj/demo/other/func/otherOnly.axon")))
        assertFalse("another connection", scope.contains(file("proj/plant-a/bldg2/func/load.axon")))
        // a function in a backup folder is searched in its own file only
        val old = PsiManager.getInstance(project).findFile(file("proj/demo/backup/site/func/siteArea.axon"))!!
        val oldScope = PsiSearchHelper.getInstance(project).getUseScope(old) as GlobalSearchScope
        assertTrue(oldScope.contains(file("proj/demo/backup/site/func/siteArea.axon")))
        assertFalse(oldScope.contains(file("proj/demo/backup/site/func/old.axon")))
    }

    /**
     * Rename of a function file in a project folder: the calls in the same project get the new
     * function name (never `name.axon`); a project with a function of the same name, and the
     * backup and version copies, keep theirs.
     */
    fun testRenameOfAFunctionFileStaysInItsProject() {
        layout()
        myFixture.addFileToProject("proj/demo/site/func/siteTotal.axon", "() => siteArea(1) + siteArea(2)")
        val target = PsiManager.getInstance(project).findFile(file("proj/demo/site/func/siteArea.axon"))!!
        val others = listOf(
            "proj/demo/other/func/otherOnly.axon",
            "proj/demo/other/func/siteArea.axon",
            "proj/demo/backup/site/func/old.axon",
            "proj/demo/site/func/.versions/siteReport.axon",
            "proj/demo/site/.backups/func/siteReport.axon",
        ).associateWith { String(file(it).contentsToByteArray()) }

        myFixture.renameElement(target, "siteSize.axon")

        assertNotNull(myFixture.findFileInTempDir("proj/demo/site/func/siteSize.axon"))
        val text = { path: String -> com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file(path))!!.text }
        assertEquals("() => readAll(site).map(siteSize)", text("proj/demo/site/func/siteReport.axon"))
        assertEquals("() => siteSize(1) + siteSize(2)", text("proj/demo/site/func/siteTotal.axon"))
        for ((path, before) in others) assertEquals(path, before, text(path))
        assertNotNull("the other project keeps its function", myFixture.findFileInTempDir("proj/demo/other/func/siteArea.axon"))
        val all = listOf("proj/demo/site/func/siteReport.axon", "proj/demo/site/func/siteTotal.axon") + others.keys
        assertFalse(all.any { text(it).contains(".axon") })
    }

    /** The `src` of a trio record in a project folder is Axon of that project too. */
    fun testTrioSrcInAProjectFolderResolvesInThatProject() {
        layout()
        myFixture.addFileToProject("proj/demo/other/func/calc.trio", "name: calc\nsrc: (x) => siteArea(x)\nfunc\n")
        val trio = PsiManager.getInstance(project).findFile(file("proj/demo/other/func/calc.trio"))!!
        val leaf = InjectedLanguageManager.getInstance(project).findInjectedElementAt(trio, trio.text.indexOf("siteArea") + 1)
        assertNotNull("no Axon in the src tag", leaf)
        val use = PsiTreeUtil.getParentOfType(leaf, AxonNameUse::class.java, false)!!
        assertEquals("demo/other/func/siteArea.axon", pathOf(use.reference!!.resolve()))
    }

    /** The status bar reads only Axon and trio files: other files never walk up their folders. */
    fun testTheStatusBarSkipsOtherFiles() {
        layout()
        myFixture.addFileToProject("proj/demo/backup/notes.txt", "made up")
        myFixture.openFileInEditor(file("proj/demo/backup/notes.txt"))
        assertNull(AxonProjectWidgets.editorPlace(project))
        assertEquals("Axon: none", AxonProjectWidgets.text(project))
        myFixture.openFileInEditor(file("proj/demo/site/func/siteArea.trio"))
        assertEquals("Axon: demo/site", AxonProjectWidgets.text(project))
    }

    fun testTheStatusBarShowsTheProjectOfTheEditorFile() {
        layout()
        myFixture.openFileInEditor(file("proj/plant-a/bldg2/func/load.axon"))
        assertEquals("Axon: plant-a/bldg2", AxonProjectWidgets.text(project))
        assertTrue(AxonProjectWidgets.tooltip(project).contains("plant-a/bldg2"))
        myFixture.openFileInEditor(file("proj/demo/site/func/siteArea.axon"))
        assertEquals("Axon: demo/site", AxonProjectWidgets.text(project))
        server.projFolder = file("proj").path
        assertTrue("a global proj folder shows the entry", AxonProjectWidgetFactory().isAvailable(project))
    }

    // ---- the cache follows the disk ----------------------------------------------------------------

    fun testANewFunctionAndANewMetadataAreSeen() {
        layout()
        assertNull(resolveIn("proj/demo/site/func/siteReport.axon", "() => <caret>siteNew()"))
        myFixture.addFileToProject("proj/demo/site/func/siteNew.axon", "() => 1")
        assertEquals("demo/site/func/siteNew.axon", pathOf(resolveIn("proj/demo/site/func/siteReport.axon", "() => <caret>siteNew()")))
        myFixture.addFileToProject("proj/lab/bldg9/func/x.axon", "() => 1")
        assertNull(folders.projectOf(file("proj/lab/bldg9/func/x.axon")))
        myFixture.addFileToProject("proj/lab/bldg9/.sync-metadata.json", meta("lab", "bldg9"))
        assertEquals(AxonProjectKey("lab", "bldg9"), folders.projectOf(file("proj/lab/bldg9/func/x.axon"))!!.key)
    }

    // ---- the IDE project is one connection or one project --------------------------------------------

    fun testWorksWhenTheOpenFolderIsOneProject() {
        // the light project root is the project folder itself: detect by the metadata
        myFixture.addFileToProject(".sync-metadata.json", meta("demo", "site"))
        myFixture.addFileToProject("func/a.axon", "() => b()")
        myFixture.addFileToProject("func/b.axon", "() => 1")
        assertEquals(AxonProjectKey("demo", "site"), folders.projectOf(file("func/a.axon"))!!.key)
        val b = resolveIn("func/a.axon", "() => <caret>b()")
        assertEquals(file("func/b.axon"), (b as PsiFile).virtualFile)
    }

    // ---- gate 4: speed -------------------------------------------------------------------------------

    fun testOneHundredProjectsOfFiftyFunctionsStayFast() {
        for (p in 0 until 100) {
            val dir = "proj/c${p % 12}/p$p"
            myFixture.addFileToProject("$dir/.sync-metadata.json", meta("c${p % 12}", "p$p"))
            for (f in 0 until 50) myFixture.addFileToProject("$dir/func/fn$f.axon", "() => fn${(f + 1) % 50}()")
        }
        AxonProjFolders.clearForTest()
        val start = System.nanoTime()
        val found = resolveIn("proj/c7/p55/func/fn3.axon", "() => <caret>fn4()")
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals("c7/p55/func/fn4.axon", pathOf(found))
        assertTrue("open and resolve took $ms ms", ms < 200)
        val files = (0 until 100).map { file("proj/c${it % 12}/p$it/func/fn1.axon") }
        AxonProjFolders.clearForTest()
        val start2 = System.nanoTime()
        for (f in files) assertEquals(f.parent.parent, folders.projectOf(f)!!.folder)
        val ms2 = (System.nanoTime() - start2) / 1_000_000
        assertTrue("100 first project lookups took $ms2 ms", ms2 < 500)
    }

    // ---- the setting -------------------------------------------------------------------------------

    fun testTheOldFallbackFolderMovesToTheGlobalFieldOnce() {
        val settings = AxonProjectSettings.getInstance(project)
        // as the platform reads an old workspace file: through loadState
        val old = AxonProjectSettings.State().apply { fallbackPath = "/made/up/proj"; instance = "demo"; this.project = "site" }
        settings.loadState(old)
        assertEquals("/made/up/proj", server.projFolder)
        assertNull(settings.state.fallbackPath)
        assertEquals(AxonProjectKey("demo", "site"), settings.activeKey)
        settings.activeKey = null
        settings.setOldFallbackPathForTest("/made/up/proj")
        server.projFolder = ""
        settings.migrateFallbackPath()
        assertEquals("/made/up/proj", server.projFolder)
        assertNull(settings.state.fallbackPath)
        // a global value that is set already wins; the old one is still dropped
        settings.setOldFallbackPathForTest("/made/up/other")
        settings.migrateFallbackPath()
        assertEquals("/made/up/proj", server.projFolder)
        assertNull(settings.state.fallbackPath)
    }

    fun testTheCountOfConnectionsAndProjects() {
        val root = FileUtil.createTempDirectory("proj", null)
        try {
            for (p in listOf("demo/site", "demo/other", "plant-a/bldg2", "demo/backup/site", "demo/.versions/site")) {
                File(root, p).mkdirs()
                File(root, "$p/.sync-metadata.json").writeText("{}")
            }
            File(root, "empty/notAProject").mkdirs()
            assertEquals(AxonProjFolders.Companion.Count(2, 3), AxonProjFolders.count(root))
            assertNull(AxonProjFolders.count(File(root, "missing")))
        } finally {
            FileUtil.delete(root)
        }
    }

    fun testNormalize() {
        val home = System.getProperty("user.home").replace('\\', '/')
        assertEquals("$home/Code/proj", AxonProjFolders.normalize(" ~/Code/proj/ "))
        assertEquals("/a/b", AxonProjFolders.normalize("/a/b"))
        assertEquals("C:/a/b", AxonProjFolders.normalize("C:\\a\\b\\"))
    }
}
