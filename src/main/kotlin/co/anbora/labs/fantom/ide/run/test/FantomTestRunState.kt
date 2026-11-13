package co.anbora.labs.fantom.ide.run.test

import co.anbora.labs.fantom.ide.run.FantomTestRunConfiguration
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.autotest.ToggleAutoTestAction
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView

/**
 * Runs `fant <specs>` and shows the results in the test tree.
 * Stderr is merged into stdout: Fantom 1.0.79 and older print the failure trace on stderr,
 * and a merged stream keeps it next to its `TEST FAILED` line.
 */
class FantomTestRunState(
    environment: ExecutionEnvironment,
    private val configuration: FantomTestRunConfiguration,
    private val specs: List<String>,
    /** Builds the process for [specs]; the Debug runner passes a JDWP command line here. */
    private val commandLine: (List<String>) -> GeneralCommandLine = { configuration.buildCommandLine(specs = it) },
) : CommandLineState(environment) {

    @Throws(ExecutionException::class)
    override fun startProcess(): ProcessHandler {
        val cmd = commandLine(specs).withRedirectErrorStream(true)
        val handler = KillableColoredProcessHandler(cmd)
        ProcessTerminatedListener.attach(handler)
        return handler
    }

    @Throws(ExecutionException::class)
    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val handler = startProcess()
        val properties = FantomTestConsoleProperties(configuration, executor)
        val console = SMTestRunnerConnectionUtil.createAndAttachConsole(FantomTestConsoleProperties.FRAMEWORK, handler, properties)
        val result = DefaultExecutionResult(console, handler, *createActions(console, handler, executor))
        val rerun = properties.createRerunFailedTestsAction(console)
        if (console is SMTRunnerConsoleView) rerun.setModelProvider { console.resultsViewer }
        result.setRestartActions(rerun, ToggleAutoTestAction())
        return result
    }
}
