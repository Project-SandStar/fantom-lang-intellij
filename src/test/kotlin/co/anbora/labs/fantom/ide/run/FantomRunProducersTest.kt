package co.anbora.labs.fantom.ide.run

import com.intellij.execution.RunManager
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The two run producers called one by one, and the run gutter called on each leaf.
 * The root `FantomRunConfigurationTest` covers the merged context menu.
 */
class FantomRunProducersTest : BasePlatformTestCase() {
    private val build = "using build\nclass Build : BuildPod {\n  new make() { podName = \"mypod\" }\n}\n"
    private val code = """
        class App
        {
          static Void main(Str[] args) { echo(args) }
          Void helper() {}
        }
        class AppTest : Test
        {
          Void testOne() { verify(true) }
          static Void testStatic() {}
          Void notATest() {}
        }
        class MidTest : AppTest {}
        class LeafTest : MidTest { Void testDeep() {} }
        class Nothing { Void main() {} }
    """.trimIndent() + "\n"

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("src/mypod/build.fan", build)
        val file = myFixture.addFileToProject("src/mypod/fan/App.fan", code)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
    }

    private fun at(marker: String): PsiElement {
        val offset = code.indexOf(marker)
        assertTrue(marker, offset >= 0)
        return myFixture.file.findElementAt(offset + 1)!!
    }

    private val mainProducer get() = RunConfigurationProducer.getInstance(FantomRunConfigurationProducer::class.java)
    private val testProducer get() = RunConfigurationProducer.getInstance(FantomTestRunConfigurationProducer::class.java)

    private fun mainConfig(marker: String) =
        mainProducer.createConfigurationFromContext(ConfigurationContext(at(marker)))?.configuration as FantomRunConfiguration?

    private fun testConfig(marker: String) =
        testProducer.createConfigurationFromContext(ConfigurationContext(at(marker)))?.configuration as FantomTestRunConfiguration?

    fun testMainProducerOnMainAndInClass() {
        for (marker in listOf("main(Str", "helper", "class App\n")) {
            val config = mainConfig(marker)!!
            assertEquals(marker, "mypod::App.main", config.target)
            assertEquals("App.main", config.name)
        }
    }

    fun testMainProducerSourceElementIsMain() {
        val fromContext = mainProducer.createConfigurationFromContext(ConfigurationContext(at("helper")))!!
        assertEquals("main", (fromContext.sourceElement as co.anbora.labs.fantom.lang.core.psi.FantomNamedElement).name)
    }

    /** No `static Void main`, or a test method: the main producer stays out. */
    fun testMainProducerSkips() {
        assertNull(mainConfig("Void main() {}"))
        assertNull(mainConfig("testOne"))
    }

    fun testTestProducerOnTestMethod() {
        val config = testConfig("testOne")!!
        assertEquals("mypod", config.pod)
        assertEquals("AppTest", config.testClass)
        assertEquals("testOne", config.testMethod)
        assertEquals("AppTest.testOne", config.name)
        assertEquals("mypod::AppTest.testOne", config.testSpec())
    }

    /** A static `test*` method and a plain method run the whole class. */
    fun testTestProducerOnOtherMethods() {
        for (marker in listOf("testStatic", "notATest")) {
            val config = testConfig(marker)!!
            assertEquals(marker, "", config.testMethod)
            assertEquals(marker, "mypod::AppTest", config.testSpec())
        }
    }

    /** `Test` reached through two project subclasses still counts. */
    fun testTestProducerThroughSubclasses() {
        assertEquals("mypod::LeafTest.testDeep", testConfig("testDeep")!!.testSpec())
    }

    fun testTestProducerSkipsNonTestClass() {
        assertNull(testConfig("helper"))
    }

    fun testIsConfigurationFromContext() {
        val config = testConfig("testOne")!!
        assertTrue(testProducer.isConfigurationFromContext(config, ConfigurationContext(at("testOne"))))
        assertFalse(testProducer.isConfigurationFromContext(config, ConfigurationContext(at("notATest"))))
        // The main producer never claims a test configuration, nor the reverse.
        assertFalse(mainProducer.isConfigurationFromContext(config, ConfigurationContext(at("main(Str"))))
        val main = mainConfig("main(Str")!!
        assertTrue(mainProducer.isConfigurationFromContext(main, ConfigurationContext(at("helper"))))
        assertFalse(testProducer.isConfigurationFromContext(main, ConfigurationContext(at("testOne"))))
    }

    fun testStoredConfigIsFoundAgain() {
        val settings = testProducer.createConfigurationFromContext(ConfigurationContext(at("testOne")))!!.configurationSettings
        RunManager.getInstance(project).addConfiguration(settings)
        assertSame(settings, testProducer.findExistingConfiguration(ConfigurationContext(at("testOne"))))
    }

    /** "name -> is test icon" for each leaf the run gutter marks. */
    fun testRunGutterInfo() {
        val contributor = FantomRunLineMarkerContributor()
        val marked = PsiTreeUtil.collectElements(myFixture.file) { it.firstChild == null }
            .mapNotNull { leaf -> contributor.getInfo(leaf)?.let { leaf.text to (it.icon == AllIcons.RunConfigurations.TestState.Run) } }
        assertEquals(
            listOf(
                "App" to false, "main" to false,
                "AppTest" to true, "testOne" to true,
                "MidTest" to true, "LeafTest" to true, "testDeep" to true,
            ),
            marked,
        )
        val info = contributor.getInfo(at("main(Str").let { it })!!
        assertTrue(info.actions.isNotEmpty())
    }
}
