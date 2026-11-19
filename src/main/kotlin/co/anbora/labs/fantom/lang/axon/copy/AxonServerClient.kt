package co.anbora.labs.fantom.lang.axon.copy

import co.anbora.labs.fantom.lang.axon.copy.AxonCopyException.Kind
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.util.io.HttpRequests
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException

/** The reply to a push: stored, or refused because the function changed on the server. */
data class AxonPushResult(val ok: Boolean, val mod: String?, val hash: String?) {
    val conflict: Boolean get() = !ok
}

/**
 * The HTTP client of the Axon server's IDE API:
 *
 * - `GET /api/ide/projects`
 * - `GET /api/ide/projects/{instance}/{project}/functions?sync=true|false`
 * - `POST /api/ide/projects/{instance}/{project}/functions/{name}`
 *
 * Every request carries `Authorization: Bearer <token>`. Redirects are not followed, so
 * the token goes to the configured host only. Replies are read up to [maxBytes]. The
 * calls block: they throw when made on the UI thread or in a read action.
 */
class AxonServerClient(
    baseUrl: String,
    private val token: String?,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 120_000,
    private val maxBytes: Long = AxonCopyLimits.MAX_BYTES,
) : AxonFunctionSource {

    private val base = baseUrl.trim().trimEnd('/')

    override val id: String get() = "server"

    override fun listProjects(check: () -> Unit): List<AxonProjectInfo> {
        val reply = call("/api/ide/projects", null, check)
        if (reply.status != 200) throw failure(reply, null)
        val rows = AxonJson.list(json(reply)["projects"]) ?: throw bad("the reply has no project list")
        if (rows.size > AxonCopyLimits.MAX_PROJECTS) throw AxonCopyException(Kind.TOO_LARGE, "The server lists more than ${AxonCopyLimits.MAX_PROJECTS} projects.")
        return rows.map { row ->
            val o = AxonJson.obj(row) ?: throw bad("a project is not an object")
            val instance = AxonJson.str(o["instance"])?.takeIf { it.isNotEmpty() } ?: throw bad("a project has no instance")
            val project = AxonJson.str(o["project"])?.takeIf { it.isNotEmpty() } ?: throw bad("a project has no name")
            AxonProjectInfo(AxonProjectKey(instance, project), (o["functionCount"] as? Number)?.toInt() ?: 0, AxonJson.str(o["lastSync"]))
        }
    }

    /**
     * Checks the URL and the token with one `GET /api/ide/projects`. Returns the number of
     * projects the token can see. Throws [AxonCopyException] with a reason a person can act
     * on: no server at the URL, a refused token, a timeout.
     */
    fun checkConnection(check: () -> Unit = {}): Int = listProjects(check).size

    override fun fetch(key: AxonProjectKey, sync: Boolean, check: () -> Unit): AxonFunctionList {
        val reply = call(projectPath(key) + "/functions?sync=$sync", null, check)
        if (reply.status != 200) throw failure(reply, key)
        val o = json(reply)
        // The server must name the project it answered for. A reply for another project is never written.
        if (AxonJson.str(o["key"]) != key.toString()) throw bad("the reply is for another project")
        val rows = AxonJson.list(o["functions"]) ?: throw bad("the reply has no function list")
        if (rows.size > AxonCopyLimits.MAX_FUNCTIONS) throw AxonCopyException(Kind.TOO_LARGE, "The project has more than ${AxonCopyLimits.MAX_FUNCTIONS} functions.")
        val functions = rows.map { row ->
            val f = AxonJson.obj(row) ?: throw bad("a function is not an object")
            val name = AxonJson.str(f["name"])?.takeIf { it.isNotEmpty() } ?: throw bad("a function has no name")
            val src = AxonJson.str(f["src"]) ?: throw bad("a function has no source")
            AxonFunction(name, src, AxonJson.str(f["doc"]), AxonJson.str(f["mod"]), AxonCopyLimits.hashOf(src, AxonJson.str(f["hash"])))
        }
        return AxonFunctionList(key, AxonJson.str(o["syncedAt"]), functions).also(AxonCopyLimits::validate)
    }

    /**
     * Stores [src] as function [name] of [key]. [expectedMod] is the `mod` the caller last
     * saw; when the function has changed on the server since, nothing is stored and the
     * result is a conflict with the server's `mod` and `hash`. With [doc] null the stored
     * doc is kept. Throws [AxonCopyException] for every other failure.
     */
    fun pushFunction(key: AxonProjectKey, name: String, src: String, doc: String?, expectedMod: String?, check: () -> Unit = {}): AxonPushResult {
        if (name.isEmpty()) throw AxonCopyException(Kind.BAD_REPLY, "The function has no name.")
        val body = LinkedHashMap<String, Any?>()
        body["src"] = src
        if (doc != null) body["doc"] = doc
        body["expectedMod"] = expectedMod
        val reply = call(projectPath(key) + "/functions/" + segment(name), AxonJson.write(body, indent = false), check)
        if (reply.status != 200 && reply.status != 409) throw failure(reply, key)
        val o = json(reply)
        val status = AxonJson.str(o["status"])
        val ok = reply.status == 200 && status == "ok"
        if (!ok && !(reply.status == 409 && status == "conflict")) throw bad("the reply has no known status")
        return AxonPushResult(ok, AxonJson.str(o["mod"]), AxonJson.str(o["hash"]))
    }

    private fun projectPath(key: AxonProjectKey) = "/api/ide/projects/" + segment(key.instance) + "/" + segment(key.project)

    private class Reply(val status: Int, val body: String)

    private fun json(reply: Reply): Map<String, Any?> =
        try { AxonJson.parseObject(reply.body) } catch (e: AxonJson.Malformed) { throw bad("the reply is not JSON") }

    private fun bad(what: String) = AxonCopyException(Kind.BAD_REPLY, "The Axon server sent a reply the plugin cannot use: $what.")

    private fun failure(reply: Reply, key: AxonProjectKey?): AxonCopyException {
        val said = runCatching { AxonJson.str(AxonJson.parseObject(reply.body)["error"]) }.getOrNull()
            ?.take(200)?.let { " The server said: $it" }.orEmpty()
        return when (reply.status) {
            401, 403 -> AxonCopyException(Kind.UNAUTHORIZED, "The Axon server refused the token (HTTP ${reply.status}).")
            404 -> if (key != null) AxonCopyException(Kind.UNKNOWN_PROJECT, "The Axon server does not know the project $key.")
            else AxonCopyException(Kind.IO, "The Axon server has no IDE API at this URL (HTTP 404).")
            in 300..399 -> AxonCopyException(Kind.IO, "The Axon server sent a redirect (HTTP ${reply.status}). Redirects are not followed; set the final URL.")
            else -> AxonCopyException(Kind.IO, "The Axon server replied HTTP ${reply.status}.$said")
        }
    }

    private fun call(path: String, body: String?, check: () -> Unit): Reply {
        val app = ApplicationManager.getApplication()
        if (app != null && (app.isDispatchThread || app.isReadAccessAllowed))
            throw IllegalStateException("No network on the UI thread or in a read action")
        if (!(base.startsWith("http://") || base.startsWith("https://")))
            throw AxonCopyException(Kind.NOT_CONFIGURED, "The Axon server URL must start with http:// or https://.")
        val bearer = token?.trim()?.takeIf { it.isNotEmpty() }
        if (bearer != null && bearer.any { it < ' ' || it.code == 127 })
            throw AxonCopyException(Kind.UNAUTHORIZED, "The Axon server token holds a character that cannot be sent.")
        val url = base + path
        val builder = if (body == null) HttpRequests.request(url) else HttpRequests.post(url, HttpRequests.JSON_CONTENT_TYPE)
        try {
            check()
            return builder
                .connectTimeout(connectTimeoutMs)
                .readTimeout(readTimeoutMs)
                .followRedirects(false)
                .throwStatusCodeException(false)
                .isReadResponseOnError(true)
                .accept("application/json")
                .tuner { c -> if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer") }
                .connect { request ->
                    if (body != null) request.write(body)
                    val connection = request.connection as HttpURLConnection
                    val status = connection.responseCode
                    check()
                    val text = when {
                        // 3xx without a body, 204: nothing to read
                        status in 300..399 || status == 204 -> ""
                        // the body of an error is on the error stream; the input stream throws
                        status >= 400 -> connection.errorStream?.use { readCapped(it, check) }.orEmpty()
                        else -> readCapped(request.inputStream, check)
                    }
                    Reply(status, text)
                }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: AxonCopyException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw AxonCopyException(Kind.TIMEOUT, "The Axon server did not answer in time.", e)
        } catch (e: IOException) {
            throw AxonCopyException(Kind.IO, "Cannot reach the Axon server: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private fun readCapped(input: InputStream, check: () -> Unit): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            check()
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw AxonCopyException(Kind.TOO_LARGE, "The reply of the Axon server is larger than ${maxBytes / (1024 * 1024)} MB.")
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8)
    }

    companion object {
        /**
         * [text] as one URL path segment: every byte outside `A-Z a-z 0-9 - _ ~ .` is
         * percent-encoded (a space is `%20`, a slash `%2F`), and a segment of dots only
         * (`.`, `..`) has its dots encoded too, so that no client or proxy takes it for a
         * path step.
         */
        fun segment(text: String): String {
            val dotsOnly = text.isNotEmpty() && text.all { it == '.' }
            val sb = StringBuilder()
            for (b in text.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt().toChar()
                val plain = b >= 0 && (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '~' || (c == '.' && !dotsOnly))
                if (plain) sb.append(c) else sb.append('%').append("%02X".format(b.toInt() and 0xff))
            }
            return sb.toString()
        }
    }
}
