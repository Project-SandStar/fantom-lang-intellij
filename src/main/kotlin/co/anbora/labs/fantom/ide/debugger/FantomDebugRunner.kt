package co.anbora.labs.fantom.ide.debugger

import co.anbora.labs.fantom.ide.run.FantomRunConfiguration
import co.anbora.labs.fantom.ide.run.test.FantomDebuggableTestProfile
import com.intellij.debugger.impl.GenericDebuggerRunner
import com.intellij.execution.configurations.RemoteConnection
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.util.net.NetUtils

/**
 * Debug for a Fantom run or test configuration: starts `java -agentlib:jdwp=...,server=y,suspend=y
 * fanx.tools.Fan <target>` (for tests, the `bin/fant` main class and the test specs) on a free
 * local port and attaches the Java debugger to it. Test runs keep the test tree.
 */
class FantomDebugRunner : GenericDebuggerRunner() {
    override fun getRunnerId(): String = RUNNER_ID

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == DefaultDebugExecutor.EXECUTOR_ID &&
            (profile is FantomRunConfiguration || profile is FantomDebuggableTestProfile)

    override fun doExecute(state: RunProfileState, env: ExecutionEnvironment): RunContentDescriptor? {
        FileDocumentManager.getInstance().saveAllDocuments()
        val port = NetUtils.findAvailableSocketPort()
        val debugState = when (val profile = env.runProfile) {
            is FantomRunConfiguration -> profile.debugState(env, port)
            is FantomDebuggableTestProfile -> profile.debugState(env, port)
            else -> return null
        }
        val connection = RemoteConnection(true, HOST, port.toString(), false)
        return attachVirtualMachine(debugState, env, connection, true)
    }

    companion object {
        const val RUNNER_ID = "FantomDebugRunner"
        private const val HOST = "127.0.0.1"
    }
}
