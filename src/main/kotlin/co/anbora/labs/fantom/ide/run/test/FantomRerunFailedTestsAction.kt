package co.anbora.labs.fantom.ide.run.test

import co.anbora.labs.fantom.ide.run.FantomTestRunConfiguration
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.openapi.ui.ComponentContainer

/** Reruns the failed tests: one `fant` process with a `pod::Type.method` argument per failed test. */
class FantomRerunFailedTestsAction(
    consoleView: ComponentContainer,
    properties: FantomTestConsoleProperties,
) : AbstractRerunFailedTestsAction(consoleView) {

    init {
        init(properties)
    }

    override fun getRunProfile(environment: ExecutionEnvironment): MyRunProfile? {
        val configuration = myConsoleProperties.configuration as? FantomTestRunConfiguration ?: return null
        val specs = specsFor(getFailedTests(configuration.project))
        if (specs.isEmpty()) return null
        return object : MyRunProfile(configuration), FantomDebuggableTestProfile {
            override fun getState(executor: Executor, env: ExecutionEnvironment): RunProfileState =
                FantomTestRunState(env, configuration, specs)

            override fun debugState(environment: ExecutionEnvironment, port: Int): RunProfileState =
                FantomTestRunState(environment, configuration, specs) { configuration.buildDebugCommandLine(port, specs = it) }
        }
    }

    companion object {
        /** `pod::Type.method` for each failed leaf test, in order, without duplicates. */
        fun specsFor(failed: List<AbstractTestProxy>): List<String> =
            failed.asSequence<AbstractTestProxy>()
                .filter { it.isLeaf }
                .mapNotNull { FantomTestLocator.qnameOf(it.locationUrl) }
                .filter { FantomTestLocator.split(it)?.third != null }
                .distinct()
                .toList()
    }
}
