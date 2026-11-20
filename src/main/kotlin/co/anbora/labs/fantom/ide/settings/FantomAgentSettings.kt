package co.anbora.labs.fantom.ide.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * "Allow AI agents to build and run tests" (Settings | Languages & Frameworks | Fantom), off
 * by default. Stored in the workspace file, not in `.idea/fantom.xml`, so a project shared in
 * git cannot arrive with the switch already on.
 */
@Service(Service.Level.PROJECT)
@State(name = "FantomAgentTools", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class FantomAgentSettings : SimplePersistentStateComponent<FantomAgentSettings.State>(State()) {

    class State : BaseState() {
        /** The MCP tools `fantom_build` and `fantom_run_test` may start processes. */
        var allowRun by property(false)
    }

    var allowAgentRun: Boolean
        get() = state.allowRun
        set(value) { state.allowRun = value }

    companion object {
        fun getInstance(project: Project): FantomAgentSettings = project.service()
    }
}
