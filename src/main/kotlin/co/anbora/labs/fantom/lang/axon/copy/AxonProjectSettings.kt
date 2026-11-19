package co.anbora.labs.fantom.lang.axon.copy

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * The Axon project of this IDE project, and the local `proj` folder to read when no
 * server is set. Both hold customer names (instance, project, a path), so they are in
 * the workspace file, which is never shared, and not in `fantom.xml`.
 */
@Service(Service.Level.PROJECT)
@State(name = "FantomAxonProject", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class AxonProjectSettings : SimplePersistentStateComponent<AxonProjectSettings.State>(State()) {

    class State : BaseState() {
        var instance by string()
        var project by string()
        var fallbackPath by string()
        var pickerConnection by string()
    }

    /**
     * The connection last selected in the project picker, as the picker stores it: an
     * instance name, or a marker for "All connections"; null when none was selected yet.
     */
    var pickerConnection: String?
        get() = state.pickerConnection?.takeIf { it.isNotEmpty() }
        set(value) { state.pickerConnection = value?.takeIf { it.isNotEmpty() } }

    /** The chosen project, or null for none. */
    var activeKey: AxonProjectKey?
        get() {
            val i = state.instance?.takeIf { it.isNotEmpty() } ?: return null
            val p = state.project?.takeIf { it.isNotEmpty() } ?: return null
            return AxonProjectKey(i, p)
        }
        set(value) {
            state.instance = value?.instance
            state.project = value?.project
        }

    /** The `proj` folder of a local Axon server, or blank. */
    var fallbackPath: String
        get() = state.fallbackPath.orEmpty()
        set(value) { state.fallbackPath = value.trim().takeIf { it.isNotEmpty() } }

    companion object {
        fun getInstance(project: Project): AxonProjectSettings = project.service()
    }
}

/**
 * The Axon server of this machine: its URL, for every IDE project. A host name is as
 * private as an instance name, so the file is application level (never in a project
 * folder) and is left out of settings sync. The token is not here: see [AxonServerToken].
 */
@Service(Service.Level.APP)
@State(name = "FantomAxonServer", storages = [Storage("fantom-axon.xml", roamingType = RoamingType.DISABLED)])
class AxonServerSettings : SimplePersistentStateComponent<AxonServerSettings.State>(State()) {

    class State : BaseState() {
        var url by string()
    }

    /** The base URL, without a slash at the end; blank when no server is set. */
    var url: String
        get() = state.url.orEmpty()
        set(value) { state.url = value.trim().trimEnd('/').takeIf { it.isNotEmpty() } }

    companion object {
        fun getInstance(): AxonServerSettings = service()
    }
}

/**
 * The bearer token of the Axon server, in the IDE's password store (the system keychain,
 * or KeePass). It is never written to a settings file. The password store can be slow
 * and can ask the user: call these from a background thread.
 */
object AxonServerToken {

    private fun attributes() = CredentialAttributes(generateServiceName("Fantom", "Axon server token"))

    fun get(): String? = PasswordSafe.instance.getPassword(attributes())?.takeIf { it.isNotEmpty() }

    /** Stores [token]; null or blank removes it. */
    fun set(token: String?) {
        PasswordSafe.instance.setPassword(attributes(), token?.trim()?.takeIf { it.isNotEmpty() })
    }
}
