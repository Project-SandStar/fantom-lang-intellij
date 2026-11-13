package co.anbora.labs.fantom.ide.run.test

import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment

/**
 * A Fantom test profile the Debug runner can start: the Test configuration itself and the
 * "Rerun Failed Tests" profile. [debugState] runs `fant` under a JDWP agent that waits on `port`.
 */
interface FantomDebuggableTestProfile {
    fun debugState(environment: ExecutionEnvironment, port: Int): RunProfileState
}
