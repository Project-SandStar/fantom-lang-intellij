package co.anbora.labs.fantom.lang.axon.copy

import java.security.MessageDigest

/** A SkySpark project on an instance: the key `<instance>/<project>`. */
data class AxonProjectKey(val instance: String, val project: String) {

    override fun toString(): String = "$instance/$project"

    companion object {
        /**
         * The key in `<instance>/<project>` text, or null. The split is at the first
         * slash: an instance name holds none, a project name may.
         */
        fun parse(text: String?): AxonProjectKey? {
            val t = text ?: return null
            val i = t.indexOf('/')
            if (i <= 0 || i == t.length - 1) return null
            return AxonProjectKey(t.substring(0, i), t.substring(i + 1))
        }
    }
}

/** One row of the project list. */
data class AxonProjectInfo(val key: AxonProjectKey, val functionCount: Int, val lastSync: String?)

/** One Axon function as a source hands it over. [hash] is the sha256 of [src], in hex. */
data class AxonFunction(val name: String, val src: String, val doc: String?, val mod: String?, val hash: String)

/** The functions of one project, and when the source last read them from SkySpark. */
data class AxonFunctionList(val key: AxonProjectKey, val syncedAt: String?, val functions: List<AxonFunction>)

/** One function of the local copy: no source text, [file] is the name of its file in `func/`. */
data class AxonFunctionEntry(val name: String, val file: String, val doc: String?, val mod: String?, val hash: String)

/** Why a fetch, a list or a push did not work. [message] is fit to show to the developer. */
class AxonCopyException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind {
        /** No server URL and no fallback folder. */
        NOT_CONFIGURED,
        /** 401: no token, or a wrong one. */
        UNAUTHORIZED,
        /** HTTP 403: the key works, but its user is read-only and cannot commit. */
        READ_ONLY,
        /** 404, or a fallback folder without that project. */
        UNKNOWN_PROJECT,
        TIMEOUT,
        /** The reply or the folder is over the size cap. */
        TOO_LARGE,
        /** Not the JSON of the contract, or the reply of another project. */
        BAD_REPLY,
        /** Another HTTP status, or the connection failed. */
        IO,
    }
}

/** Where functions come from: the Axon server, or a local `proj` folder. */
interface AxonFunctionSource {
    /** `server` or `folder`: written to the marker of the copy. */
    val id: String

    /** The projects on offer. Throws [AxonCopyException]. */
    fun listProjects(check: () -> Unit = {}): List<AxonProjectInfo>

    /**
     * The functions of [key]. With [sync] the source reads them from SkySpark first.
     * [check] is called often; it throws to cancel. Throws [AxonCopyException].
     */
    fun fetch(key: AxonProjectKey, sync: Boolean, check: () -> Unit = {}): AxonFunctionList
}

/** Limits and hashing shared by the sources and the cache. */
object AxonCopyLimits {
    /** The largest reply, and the largest total of the source files of one project. */
    const val MAX_BYTES: Long = 64L * 1024 * 1024
    const val MAX_FUNCTIONS: Int = 20_000
    const val MAX_PROJECTS: Int = 5_000

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private val HEX64 = Regex("[0-9a-f]{64}")

    /** [hash] when it is a full sha256, else the sha256 of [src]. */
    fun hashOf(src: String, hash: String?): String = hash?.lowercase()?.takeIf { HEX64.matches(it) } ?: sha256(src)

    /**
     * Checks a list before it is written: at most [MAX_FUNCTIONS], no empty name and
     * no name twice. Throws [AxonCopyException] of kind BAD_REPLY or TOO_LARGE.
     */
    fun validate(list: AxonFunctionList) {
        if (list.functions.size > MAX_FUNCTIONS)
            throw AxonCopyException(AxonCopyException.Kind.TOO_LARGE, "The project has more than $MAX_FUNCTIONS functions.")
        val seen = HashSet<String>()
        var bytes = 0L
        for (f in list.functions) {
            if (f.name.isEmpty()) throw AxonCopyException(AxonCopyException.Kind.BAD_REPLY, "A function has no name.")
            if (!seen.add(f.name)) throw AxonCopyException(AxonCopyException.Kind.BAD_REPLY, "A function is listed twice.")
            bytes += f.src.length
            if (bytes > MAX_BYTES) throw AxonCopyException(AxonCopyException.Kind.TOO_LARGE, "The functions are larger than ${MAX_BYTES / (1024 * 1024)} MB.")
        }
    }
}
