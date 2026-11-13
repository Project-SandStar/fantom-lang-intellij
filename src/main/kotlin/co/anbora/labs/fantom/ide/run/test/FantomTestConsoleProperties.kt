package co.anbora.labs.fantom.ide.run.test

import co.anbora.labs.fantom.ide.run.FantomTestRunConfiguration
import com.intellij.execution.Executor
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.ui.ConsoleView

/** Test console settings for `fant` runs: the converter, the locator and rerun of failed tests. */
class FantomTestConsoleProperties(
    val configuration: FantomTestRunConfiguration,
    executor: Executor,
) : SMTRunnerConsoleProperties(configuration, FRAMEWORK, executor), SMCustomMessagesParsing {

    init {
        isIdBasedTestTree = false
        setIfUndefined(TestConsoleProperties.HIDE_PASSED_TESTS, false)
    }

    override fun createTestEventsConverter(
        testFrameworkName: String,
        consoleProperties: TestConsoleProperties,
    ): OutputToGeneralTestEventsConverter = FantomTestEventsConverter(testFrameworkName, consoleProperties)

    override fun getTestLocator(): SMTestLocator = FantomTestLocator

    override fun createRerunFailedTestsAction(consoleView: ConsoleView): AbstractRerunFailedTestsAction =
        FantomRerunFailedTestsAction(consoleView, this)

    companion object {
        const val FRAMEWORK = "Fantom"
    }
}
