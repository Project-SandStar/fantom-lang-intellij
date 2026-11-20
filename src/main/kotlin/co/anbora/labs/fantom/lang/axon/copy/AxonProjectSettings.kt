package co.anbora.labs.fantom.lang.axon.copy

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Axon project of this IDE project. It holds customer names (instance, project), so it
 * is in the workspace file, which is never shared, and not in `fantom.xml`.
 */
@Service(Service.Level.PROJECT)
@State(name = "FantomAxonProject", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class AxonProjectSettings : SimplePersistentStateComponent<AxonProjectSettings.State>(State()) {

    class State : BaseState() {
        var instance by string()
        var project by string()
        /** The old per-project "Fallback folder" (before 4.38). Read once, moved to [AxonServerSettings.projFolder]. */
        var fallbackPath by string()
        var pickerConnection by string()
    }

    override fun loadState(state: State) {
        super.loadState(state)
        migrateFallbackPath()
    }

    /**
     * Moves the old per-project fallback folder into the global proj folder when that one is
     * empty, then forgets it. Runs when the workspace file is read.
     */
    internal fun migrateFallbackPath() {
        val old = state.fallbackPath?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val server = AxonServerSettings.getInstance()
        if (server.projFolder.isEmpty()) server.projFolder = old
        state.fallbackPath = null
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

    /** For tests of the move: sets the old per-project folder as an old workspace file would hold it. */
    @TestOnly
    fun setOldFallbackPathForTest(path: String?) { state.fallbackPath = path }

    companion object {
        fun getInstance(project: Project): AxonProjectSettings = project.service()
    }
}

/**
 * The Axon server of this machine: its URL and the global `proj` folder, for every IDE
 * project. A host name or a path is as private as an instance name, so the file is
 * application level (never in a project folder) and is left out of settings sync. The
 * token is not here: see [AxonServerToken].
 */
@Service(Service.Level.APP)
@State(name = "FantomAxonServer", storages = [Storage("fantom-axon.xml", roamingType = RoamingType.DISABLED)])
class AxonServerSettings : SimplePersistentStateComponent<AxonServerSettings.State>(State()) {

    class State : BaseState() {
        var url by string()
        var projFolder by string()
    }

    /** The base URL, without a slash at the end; blank when no server is set. */
    var url: String
        get() = state.url.orEmpty()
        set(value) { state.url = value.trim().trimEnd('/').takeIf { it.isNotEmpty() } }

    /**
     * The global `proj` folder of the Axon server (`proj/<connection>/<project>/func/`), as
     * typed; blank when not set. Files under it map to their project by path, and the
     * project copy reads it when no server URL is set.
     */
    var projFolder: String
        get() = state.projFolder.orEmpty()
        set(value) { state.projFolder = value.trim().takeIf { it.isNotEmpty() } }

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

    /** What the last [get] or [set] saw: true when a token is stored, null before the first. */
    @Volatile private var stored: Boolean? = null
    /** Whether the last [get] or [set] found the token in memory only; null with no token. */
    @Volatile private var memoryOnly: Boolean? = null
    private val warming = AtomicBoolean()

    fun get(): String? {
        val t = PasswordSafe.instance.getPassword(attributes())?.takeIf { it.isNotEmpty() }
        stored = t != null
        memoryOnly = t?.let { onlyInMemory(it) }
        return t
    }

    /** Stores [token]; null or blank removes it. */
    fun set(token: String?) {
        val t = token?.trim()?.takeIf { it.isNotEmpty() }
        PasswordSafe.instance.setPassword(attributes(), t)
        stored = t != null
        memoryOnly = t?.let { onlyInMemory(it) }
    }

    /**
     * True when the password store keeps the token in memory only, so it is lost when the
     * IDE closes. This happens on a host with no keychain, for example the remote host of
     * a Gateway session, unless KeePass is set. Null when no token is stored or nothing was
     * read yet. Memory only: safe on any thread.
     */
    fun memoryOnlyHint(): Boolean? = memoryOnly

    /** The test password store never keeps a key in memory only: tests set the hint here. */
    @TestOnly
    internal fun setMemoryOnlyHintForTest(value: Boolean?) { memoryOnly = value }

    private fun onlyInMemory(token: String): Boolean = runCatching {
        PasswordSafe.instance.isPasswordStoredOnlyInMemory(attributes(), Credentials(null, token))
    }.getOrDefault(false)

    /**
     * Whether a token is stored, as the last read saw it; null when nothing was read yet.
     * Memory only, so an action's update can call it. When it is null, a read starts on
     * a pooled thread, once.
     */
    fun storedHint(): Boolean? {
        stored?.let { return it }
        if (warming.compareAndSet(false, true)) {
            ApplicationManager.getApplication().executeOnPooledThread { runCatching { get() } }
        }
        return null
    }
}
