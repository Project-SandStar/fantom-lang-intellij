package co.anbora.labs.fantom.ide.mcp

import co.anbora.labs.fantom.lang.axon.copy.AxonCopyException
import co.anbora.labs.fantom.lang.axon.copy.AxonCopyLimits
import co.anbora.labs.fantom.lang.axon.copy.AxonFunction
import co.anbora.labs.fantom.lang.axon.copy.AxonFunctionList
import co.anbora.labs.fantom.lang.axon.copy.AxonFunctionSource
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectCopy
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectInfo
import co.anbora.labs.fantom.lang.axon.copy.AxonProjectKey
import co.anbora.labs.fantom.lang.axon.copy.AxonServerSettings
import co.anbora.labs.fantom.lang.axon.proj.AxonProjFolders
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.Callable

/**
 * `fantom_find_usages` of an Axon project function gives the count IDE Find Usages gives:
 * for a function in a proj folder and for a function of the read-only copy. Made-up names.
 */
class FantomMcpAxonUsagesTest : BasePlatformTestCase() {
    private lateinit var cache: File
    private val copy get() = AxonProjectCopy.getInstance(project)

    override fun setUp() {
        super.setUp()
        AxonServerSettings.getInstance().projFolder = ""
        AxonProjFolders.clearForTest()
        cache = FileUtil.createTempDirectory("axon-mcp-cache", null)
        copy.testCacheRoot = cache
    }

    override fun tearDown() {
        try {
            copy.dropNow()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            copy.testSource = null
            copy.testCacheRoot = null
            cache.walkTopDown().forEach { it.setWritable(true) }
            FileUtil.delete(cache)
            AxonProjFolders.clearForTest()
        } finally {
            super.tearDown()
        }
    }

    private fun usages(path: String, offset: Int): String {
        val f = ApplicationManager.getApplication().executeOnPooledThread(Callable {
            runCatching { runBlocking { FantomMcpService.usages(project, path, offset, 0, 0, 0) } }
        })
        val end = System.currentTimeMillis() + 60_000
        while (!f.isDone) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(5)
            if (System.currentTimeMillis() > end) fail("no answer")
        }
        val answer = f.get().getOrThrow()
        assertTrue(answer.toString(), answer is FantomMcpService.Answer.Ok)
        return (answer as FantomMcpService.Answer.Ok).text
    }

    private fun count(text: String): Int = text.lines().first().substringAfterLast(": ").toInt()

    private fun ideCount(target: PsiElement): Int = myFixture.findUsages(target).size

    private fun meta(instance: String, project: String) = """{"instance":"$instance","project":"$project","functionCount":1}"""

    fun testAFunctionInAProjFolder() {
        myFixture.addFileToProject("proj/demo/site/.sync-metadata.json", meta("demo", "site"))
        myFixture.addFileToProject("proj/demo/site/func/siteArea.axon", "(site) => site->area")
        val report = myFixture.addFileToProject("proj/demo/site/func/siteReport.axon", "() => readAll(site).map(siteArea)")
        myFixture.addFileToProject("proj/demo/other/.sync-metadata.json", meta("demo", "other"))
        myFixture.addFileToProject("proj/demo/other/func/siteArea.axon", "(site) => 0")
        myFixture.addFileToProject("proj/demo/other/func/otherOnly.axon", "() => siteArea(1)")

        val target = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("proj/demo/site/func/siteArea.axon")!!)!!
        val ide = ideCount(target)
        assertEquals(1, ide)
        val text = usages("proj/demo/site/func/siteReport.axon", report.text.indexOf("siteArea") + 1)
        assertEquals(text, ide, count(text))
        assertTrue(text, text.lines()[1].contains("siteReport.axon"))
    }

    fun testAFunctionOfTheCopy() {
        val key = AxonProjectKey("demo", "site")
        copy.testSource = object : AxonFunctionSource {
            override val id = "server"
            override fun listProjects(check: () -> Unit) = listOf(AxonProjectInfo(key, 2, null))
            override fun fetch(key: AxonProjectKey, sync: Boolean, check: () -> Unit): AxonFunctionList {
                if (key.project != "site") throw AxonCopyException(AxonCopyException.Kind.UNKNOWN_PROJECT, "unknown")
                return AxonFunctionList(key, null, listOf("siteArea" to "(site) => site->area", "siteReport" to "() => 1").map { (n, s) ->
                    AxonFunction(n, s, null, null, AxonCopyLimits.sha256(s))
                })
            }
        }
        copy.selectNow(key)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val one = myFixture.addFileToProject("one.axon", "() => siteArea(1)")
        myFixture.addFileToProject("two.axon", "(s) => s.siteArea()")

        val target: PsiFile = PsiManager.getInstance(project).findFile(copy.findFunctionFile("siteArea")!!)!!
        val ide = ideCount(target)
        assertEquals(2, ide)
        val text = usages("one.axon", one.text.indexOf("siteArea") + 1)
        assertEquals(text, ide, count(text))
    }
}
