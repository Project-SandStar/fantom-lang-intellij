package co.anbora.labs.fantom.lang.library

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Per-project Fantom settings, stored in `.idea/fantom.xml`.
 *
 * [homePath] is the Fantom installation the project compiles against. When it is
 * blank or not a valid home, [effectiveHome] falls back to [FantomHome.detect].
 */
@Service(Service.Level.PROJECT)
@State(name = "FantomSettings", storages = [Storage("fantom.xml")])
class FantomSettings : SimplePersistentStateComponent<FantomSettings.State>(State()) {

    class State : BaseState() {
        var homePath by string()
    }

    var homePath: String?
        get() = state.homePath
        set(value) { state.homePath = value?.takeIf { it.isNotBlank() } }

    /** The home to use: the configured one if valid, else the detected one, else null. */
    fun effectiveHome(): File? {
        val configured = homePath?.let(::File)
        if (configured != null && FantomHome.isHome(configured)) return configured
        return FantomHome.detect()
    }

    companion object {
        fun getInstance(project: Project): FantomSettings = project.service()
    }
}
