package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.run.FantomCommandLine
import co.anbora.labs.fantom.ide.run.FantomRunConfiguration
import co.anbora.labs.fantom.ide.run.FantomRunConfigurationType
import co.anbora.labs.fantom.ide.run.FantomTestRunConfiguration
import co.anbora.labs.fantom.lang.library.FantomHome
import co.anbora.labs.fantom.lang.library.FantomPodResolver
import co.anbora.labs.fantom.lang.library.FantomSettings
import com.intellij.execution.ExecutionException
import com.intellij.execution.RunManager
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Run configurations from context, their command lines, and the run gutter arrows.
 * Command-line checks need a Fantom home on this machine and skip without one.
 */
class FantomRunConfigurationTest : BasePlatformTestCase() {
    private val build = "using build\nclass Build : BuildPod {\n  new make() { podName = \"mypod\" }\n}\n"
    private val foo = "class Foo {\n  static Void ma<caret>in(Str[] args) { echo(args) }\n}\n"
    private val fooTest = "class FooTest : Test {\n  Void testBar() { verify(true) }\n  Void helper() {}\n}\n"

    override fun tearDown() {
        try {
            FantomSettings.getInstance(project).homePath = null
        } finally {
            super.tearDown()
        }
    }

    private fun pod(): PsiFile = myFixture.addFileToProject("src/mypod/build.fan", build)

    private fun configureInPod(path: String, text: String): PsiFile {
        pod()
        val file = myFixture.addFileToProject(path, text)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return file
    }

    private fun configFromCaret(): RunConfiguration? {
        val element = myFixture.file.findElementAt(myFixture.caretOffset) ?: return null
        val context = ConfigurationContext(element)
        val fromContext = context.configurationsFromContext?.firstOrNull() ?: return null
        return fromContext.configurationSettings.configuration
    }

    fun testPodNameFromBuildFan() {
        val file = configureInPod("src/mypod/fan/Foo.fan", foo)
        assertEquals("mypod", FantomPodResolver.podNameFor(file.virtualFile, project))
    }

    fun testPodNameFromFanFolderWithoutBuildFan() {
        val file = myFixture.addFileToProject("src/other/fan/Foo.fan", foo)
        assertEquals("other", FantomPodResolver.podNameFor(file.virtualFile, project))
    }

    fun testScriptHasNoPod() {
        val file = myFixture.addFileToProject("scripts/Hello.fan", foo)
        assertNull(FantomPodResolver.podNameFor(file.virtualFile, project))
    }

    fun testMainProducesFantomConfig() {
        configureInPod("src/mypod/fan/Foo.fan", foo)
        val config = configFromCaret()
        assertTrue("expected a Fantom config, got $config", config is FantomRunConfiguration)
        config as FantomRunConfiguration
        assertEquals("Foo.main", config.name)
        assertEquals("mypod::Foo.main", config.target)
        assertTrue(config.target.contains("::Foo.main"))
        assertSame(FantomRunConfigurationType.instance.mainFactory, config.factory)
    }

    fun testStandaloneScriptProducesScriptPathTarget() {
        myFixture.configureByText("Hello.fan", foo)
        val config = configFromCaret()
        assertTrue("expected a Fantom config, got $config", config is FantomRunConfiguration)
        config as FantomRunConfiguration
        assertEquals("Foo.main", config.name)
        assertEquals(myFixture.file.virtualFile.path, config.target)
    }

    fun testTestClassProducesFantomTestConfig() {
        configureInPod("src/mypod/test/FooTest.fan", fooTest.replace("class FooTest", "class Foo<caret>Test"))
        val config = configFromCaret()
        assertTrue("expected a Fantom Test config, got $config", config is FantomTestRunConfiguration)
        config as FantomTestRunConfiguration
        assertEquals("FooTest", config.name)
        assertEquals("mypod::FooTest", config.testSpec())
        assertSame(FantomRunConfigurationType.instance.testFactory, config.factory)
    }

    fun testTestMethodProducesMethodConfig() {
        configureInPod("src/mypod/test/FooTest.fan", fooTest.replace("testBar()", "test<caret>Bar()"))
        val config = configFromCaret()
        assertTrue("expected a Fantom Test config, got $config", config is FantomTestRunConfiguration)
        config as FantomTestRunConfiguration
        assertEquals("FooTest.testBar", config.name)
        assertEquals("mypod::FooTest.testBar", config.testSpec())
    }

    fun testNonTestMethodInTestClassRunsWholeClass() {
        configureInPod("src/mypod/test/FooTest.fan", fooTest.replace("helper()", "hel<caret>per()"))
        val config = configFromCaret() as FantomTestRunConfiguration
        assertEquals("mypod::FooTest", config.testSpec())
    }

    fun testSameContextIsNotDuplicated() {
        configureInPod("src/mypod/fan/Foo.fan", foo)
        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val first = ConfigurationContext(element).configurationsFromContext!!.first()
        RunManager.getInstance(project).addConfiguration(first.configurationSettings)
        val again = ConfigurationContext(element).findExisting()
        assertNotNull("second lookup must find the stored config", again)
        assertSame(first.configurationSettings.configuration, again!!.configuration)
    }

    fun testCommandLines() {
        val home = FantomHome.detect()
        if (home == null) {
            println("FantomRunConfigurationTest.testCommandLines skipped: no Fantom home found")
            return
        }
        val type = FantomRunConfigurationType.instance
        val run = FantomRunConfiguration(project, type.mainFactory, "Foo.main").apply {
            target = "mypod::Foo.main"
            programArgs = "a \"b c\""
            envs["FOO"] = "1"
        }
        val runCmd = run.buildCommandLine(home)
        assertEquals(File(home, "bin/fan").path, runCmd.exePath)
        assertEquals(listOf("mypod::Foo.main", "a", "b c"), runCmd.parametersList.list)
        assertEquals("1", runCmd.environment["FOO"])
        assertTrue(runCmd.environment["PATH"]!!.startsWith(File(home, "bin").path))

        val test = FantomTestRunConfiguration(project, type.testFactory, "FooTest.testBar").apply {
            pod = "mypod"; testClass = "FooTest"; testMethod = "testBar"
            workingDir = home.path
        }
        val testCmd = test.buildCommandLine(home)
        assertEquals(File(home, "bin/fant").path, testCmd.exePath)
        assertEquals(listOf("mypod::FooTest.testBar"), testCmd.parametersList.list)
        assertEquals(home.path, testCmd.workDirectory?.path)
    }

    fun testNoHomeGivesClearError() {
        val type = FantomRunConfigurationType.instance
        val run = FantomRunConfiguration(project, type.mainFactory, "x").apply { target = "mypod::Foo.main" }
        try {
            run.buildCommandLine(null)
            fail("expected ExecutionException")
        } catch (e: ExecutionException) {
            assertEquals(FantomCommandLine.NO_HOME, e.message)
        }
        val test = FantomTestRunConfiguration(project, type.testFactory, "x").apply { pod = "mypod" }
        try {
            test.buildCommandLine(null)
            fail("expected ExecutionException")
        } catch (e: ExecutionException) {
            assertTrue(e.message!!.contains("Settings | Languages & Frameworks | Fantom"))
        }
    }

    fun testStateRoundTrip() {
        val type = FantomRunConfigurationType.instance
        val run = FantomRunConfiguration(project, type.mainFactory, "x").apply {
            target = "mypod::Foo.main"; programArgs = "-v"; workingDir = "/tmp"; envs["A"] = "b"
        }
        val element = org.jdom.Element("configuration")
        run.writeExternal(element)
        val back = FantomRunConfiguration(project, type.mainFactory, "y")
        back.readExternal(element)
        assertEquals("mypod::Foo.main", back.target)
        assertEquals("-v", back.programArgs)
        assertEquals("/tmp", back.workingDir)
        assertEquals(mapOf("A" to "b"), back.envs)
    }

    @Suppress("UNCHECKED_CAST")
    fun testSettingsEditorsRoundTrip() {
        val type = FantomRunConfigurationType.instance
        val run = FantomRunConfiguration(project, type.mainFactory, "x").apply {
            target = "mypod::Foo.main"; programArgs = "-v"; workingDir = "/tmp"; envs["A"] = "b"
        }
        val runEditor = run.configurationEditor as com.intellij.openapi.options.SettingsEditor<FantomRunConfiguration>
        try {
            runEditor.component
            runEditor.resetFrom(run)
            val copy = FantomRunConfiguration(project, type.mainFactory, "y")
            runEditor.applyTo(copy)
            assertEquals("mypod::Foo.main", copy.target)
            assertEquals("-v", copy.programArgs)
            assertEquals("/tmp", copy.workingDir)
            assertEquals(mapOf("A" to "b"), copy.envs)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(runEditor)
        }

        val test = FantomTestRunConfiguration(project, type.testFactory, "x").apply {
            pod = "mypod"; testClass = "FooTest"; testMethod = "testBar"
        }
        val testEditor = test.configurationEditor as com.intellij.openapi.options.SettingsEditor<FantomTestRunConfiguration>
        try {
            testEditor.component
            testEditor.resetFrom(test)
            val copy = FantomTestRunConfiguration(project, type.testFactory, "y")
            testEditor.applyTo(copy)
            assertEquals("mypod::FooTest.testBar", copy.testSpec())
        } finally {
            com.intellij.openapi.util.Disposer.dispose(testEditor)
        }
    }

    fun testGuttersOnMainAndTestMethod() {
        pod()
        val file = myFixture.addFileToProject(
            "src/mypod/fan/All.fan",
            "class Foo {\n  static Void main() {}\n  Void other() {}\n}\n" +
                "class FooTest : Test {\n  Void testBar() {}\n  Void setup() {}\n}\n",
        )
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.doHighlighting()
        val runIcons = setOf(AllIcons.RunConfigurations.TestState.Run, AllIcons.Actions.Execute)
        val all = myFixture.findAllGutters().filter { it.icon in runIcons }
        assertEquals("arrows on Foo, main, FooTest, testBar; got ${all.map { it.tooltipText }}", 4, all.size)

        val text = myFixture.editor.document.text
        fun arrowsAt(snippet: String): List<javax.swing.Icon> {
            myFixture.editor.caretModel.moveToOffset(text.indexOf(snippet) + 1)
            return myFixture.findGuttersAtCaret().map { it.icon }.filter { it in runIcons }
        }
        assertEquals(listOf(AllIcons.Actions.Execute), arrowsAt("static Void main"))
        assertEquals(listOf(AllIcons.RunConfigurations.TestState.Run), arrowsAt("Void testBar"))
        assertEmpty(arrowsAt("Void other"))
        assertEmpty(arrowsAt("Void setup"))
    }
}
