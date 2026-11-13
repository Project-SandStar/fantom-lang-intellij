package co.anbora.labs.fantom

import co.anbora.labs.fantom.ide.run.FantomRunConfigurationType
import co.anbora.labs.fantom.ide.run.FantomTestRunConfiguration
import co.anbora.labs.fantom.ide.run.test.FantOutputParser
import co.anbora.labs.fantom.ide.run.test.FantomRerunFailedTestsAction
import co.anbora.labs.fantom.ide.run.test.FantomTestConsoleProperties
import co.anbora.labs.fantom.ide.run.test.FantomTestEventsConverter
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.testframework.sm.runner.GeneralToSMTRunnerEventsConvertor
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * `fant` output, captured from Fantom 1.0.84 and 1.0.79 runs of a two-test pod
 * (`testA` passes, `testB` fails `verifyEq(1, 2)`), fed through the converter into a test tree.
 */
class FantomTestEventsConverterTest : BasePlatformTestCase() {

    private fun fixture(name: String): String = File("src/test/testData/fant/$name").readText()

    private fun checkFooTest(root: SMTestProxy, pod: String = "footest") {
        val suites = root.children
        assertEquals(listOf("FooTest"), suites.map { it.name })
        val suite = suites.single()
        assertEquals("fantom://$pod::FooTest", suite.locationUrl)
        val tests = suite.children
        assertEquals(listOf("testA", "testB"), tests.map { it.name })
        val (a, b) = tests
        assertTrue(a.isPassed)
        assertFalse(a.isDefect)
        assertTrue(b.isDefect)
        assertEquals("fantom://$pod::FooTest.testA", a.locationUrl)
        assertEquals("fantom://$pod::FooTest.testB", b.locationUrl)
        assertEquals("sys::TestErr: Test failed: 1 [sys::Int] != 2 [sys::Int]", b.errorMessage)
        assertTrue(b.stacktrace, b.stacktrace!!.contains("$pod::FooTest.testB (FooTest.fan:1)"))
        assertFalse(b.stacktrace!!.contains("Time:"))
        assertEquals(1, root.allTests.count { it.isLeaf && it.isDefect })
        assertEquals(1, root.allTests.count { it.isLeaf && it.isPassed })
    }

    fun testFant1084() {
        // clock: start A = 0, pass A = 7, start B = 10, TEST FAILED = 25
        val root = run(project, testRootDisposable, fixture("fant-1.0.84-pod.txt"), clock = listOf(0L, 7L, 10L, 25L))
        checkFooTest(root)
        val (a, b) = root.children.single().children
        assertEquals(7L, a.duration)
        assertEquals(15L, b.duration)
    }

    fun testFant1079() {
        val root = run(project, testRootDisposable, fixture("fant-1.0.79-pod.txt"), clock = listOf(100L, 103L, 110L, 111L))
        checkFooTest(root)
        val (a, b) = root.children.single().children
        assertEquals(3L, a.duration)
        assertEquals(1L, b.duration)
    }

    fun testChunksSplitMidLine() {
        val text = fixture("fant-1.0.84-pod.txt")
        for (size in listOf(1, 7, 64)) {
            checkFooTest(run(project, testRootDisposable, text, chunk = size))
        }
    }

    fun testRerunOutputHasOneSuitePerClass() {
        checkFooTest(run(project, testRootDisposable, fixture("fant-1.0.84-rerun.txt")))
        checkFooTest(run(project, testRootDisposable, fixture("fant-1.0.79-rerun.txt")))
    }

    fun testRerunSpecsAreTheFailedMethods() {
        val root = run(project, testRootDisposable, fixture("fant-1.0.84-pod.txt"))
        val failed = root.allTests.filter { it.isDefect }
        assertEquals(listOf("footest::FooTest.testB"), FantomRerunFailedTestsAction.specsFor(failed))
    }

    fun testProcessEndClosesOpenTest() {
        val text = "-- Run: footest::FooTest.testA\nsome output\n"
        val root = run(project, testRootDisposable, text)
        val a = root.children.single().children.single()
        assertEquals("testA", a.name)
        assertTrue(a.isDefect)
    }

    fun testCannotMakeTest() {
        val text = "\n-- Run:  footest::FooTest.testA...\n\nERROR: Cannot make test footest::FooTest\n\nTime: 3ms\n"
        val a = run(project, testRootDisposable, text).children.single().children.single()
        assertTrue(a.isDefect)
        assertEquals("ERROR: Cannot make test footest::FooTest", a.errorMessage)
    }

    fun testParserLines() {
        assertEquals("footest::FooTest.testA", FantOutputParser.run.matchEntire("-- Run: footest::FooTest.testA")!!.groupValues[1])
        assertEquals("footest::FooTest.testA", FantOutputParser.run.matchEntire("-- Run:  footest::FooTest.testA...")!!.groupValues[1])
        assertNotNull(FantOutputParser.pass.matchEntire("   Pass: footest::FooTest.testA [1]"))
        assertEquals("a|'b|[c|]|n||", FantOutputParser.escape("a'b[c]\n|"))
    }

    companion object {
        /** Runs [text] through a [FantomTestEventsConverter] into a fresh test tree and returns its root. */
        fun run(
            project: Project,
            disposable: com.intellij.openapi.Disposable,
            text: String,
            chunk: Int = Int.MAX_VALUE,
            clock: List<Long> = emptyList(),
        ): SMTestProxy.SMRootTestProxy {
            val config = FantomRunConfigurationType.instance.testFactory.createTemplateConfiguration(project) as FantomTestRunConfiguration
            config.pod = "footest"
            val properties = FantomTestConsoleProperties(config, DefaultRunExecutor.getRunExecutorInstance())
            Disposer.register(disposable, properties)
            val times = clock.iterator()
            var last = 0L
            val converter = FantomTestEventsConverter(FantomTestConsoleProperties.FRAMEWORK, properties) {
                if (times.hasNext()) last = times.next()
                last
            }
            Disposer.register(disposable, converter)
            val root = SMTestProxy.SMRootTestProxy()
            val processor = GeneralToSMTRunnerEventsConvertor(project, root, FantomTestConsoleProperties.FRAMEWORK)
            Disposer.register(disposable, processor)
            converter.setProcessor(processor)
            converter.startTesting()
            var i = 0
            while (i < text.length) {
                val end = if (chunk >= text.length - i) text.length else i + chunk
                converter.process(text.substring(i, end), ProcessOutputTypes.STDOUT)
                i = end
            }
            converter.flushBufferOnProcessTermination(1)
            converter.finishTesting()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            return root
        }
    }
}
