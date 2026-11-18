package co.anbora.labs.fantom

import co.anbora.labs.fantom.lang.axon.copy.AxonCopyLimits
import co.anbora.labs.fantom.lang.axon.copy.AxonJson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A stand-in for the Axon server's IDE API, on a free port of the loopback address. It
 * follows the contract of the plugin: bearer token, project list, functions, push with a
 * conflict check. [mode] makes it misbehave. All data is made up.
 */
class FakeAxonServer(private val token: String = "test-token") : AutoCloseable {

    class Fn(var src: String, var doc: String?, var mod: String?)

    private val pool = Executors.newCachedThreadPool()
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    /** `instance/project` to its functions, by name. */
    val projects = LinkedHashMap<String, LinkedHashMap<String, Fn>>()

    /** `METHOD rawPath?rawQuery` of every request, with or without a good token. */
    val requests = CopyOnWriteArrayList<String>()
    val authHeaders = CopyOnWriteArrayList<String>()
    val bodies = CopyOnWriteArrayList<String>()

    /** ok, slow, wrongKey, garbage, cut, error500, redirect, huge. */
    @Volatile var mode = "ok"
    @Volatile var redirectTo = ""
    private var pushes = 0

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = pool
        server.createContext("/") { ex -> try { handle(ex) } catch (e: Exception) { runCatching { ex.close() } } }
        server.start()
    }

    fun put(key: String, name: String, src: String, doc: String? = null, mod: String? = "2026-01-02T03:04:05Z") {
        projects.getOrPut(key) { LinkedHashMap() }[name] = Fn(src, doc, mod)
    }

    private fun handle(ex: HttpExchange) {
        val uri = ex.requestURI
        requests += "${ex.requestMethod} ${uri.rawPath}${uri.rawQuery?.let { "?$it" } ?: ""}"
        val auth = ex.requestHeaders.getFirst("Authorization")
        authHeaders += auth.orEmpty()
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        if (ex.requestMethod == "POST") bodies += body
        if (auth != "Bearer $token") return reply(ex, 401, """{"error":"unauthorized"}""")
        when (mode) {
            "slow" -> Thread.sleep(1500)
            "garbage" -> return reply(ex, 200, "<html>not json</html>")
            "error500" -> return reply(ex, 500, """{"error":"the database is down"}""")
            "redirect" -> {
                ex.responseHeaders.add("Location", redirectTo + uri.rawPath)
                ex.sendResponseHeaders(302, -1)
                return ex.close()
            }
            "huge" -> return reply(ex, 200, "{\"pad\":\"" + "x".repeat(300_000) + "\"}")
        }
        val path = uri.rawPath
        if (path == "/api/ide/projects" && ex.requestMethod == "GET") {
            // written by hand, as the contract shows it
            val rows = projects.entries.joinToString(",") { (key, fns) ->
                val (i, p) = key.split("/", limit = 2)
                """{"key":${q(key)},"instance":${q(i)},"project":${q(p)},"functionCount":${fns.size},"lastSync":null}"""
            }
            return reply(ex, 200, """{"projects":[$rows]}""")
        }
        val m = Regex("^/api/ide/projects/([^/]+)/([^/]+)/functions(?:/([^/]+))?$").matchEntire(path)
            ?: return reply(ex, 404, """{"error":"not found"}""")
        val key = dec(m.groupValues[1]) + "/" + dec(m.groupValues[2])
        val fns = projects[key] ?: return reply(ex, 404, """{"error":"unknown project"}""")
        val name = m.groupValues[3].takeIf { it.isNotEmpty() }?.let(::dec)
        if (name == null && ex.requestMethod == "GET") {
            val sync = uri.rawQuery == "sync=true"
            val out = linkedMapOf(
                "key" to (if (mode == "wrongKey") "demo/other" else key),
                "syncedAt" to (if (sync) "2026-05-06T07:08:09Z" else null),
                "functions" to fns.map { (n, f) ->
                    linkedMapOf("name" to n, "src" to f.src, "doc" to f.doc, "mod" to f.mod, "hash" to AxonCopyLimits.sha256(f.src))
                },
            )
            val text = AxonJson.write(out, indent = false)
            return reply(ex, 200, if (mode == "cut") text.substring(0, text.length / 2) else text)
        }
        if (name != null && ex.requestMethod == "POST") {
            val o = AxonJson.parseObject(body)
            val have = fns[name]
            if (have != null && have.mod != o["expectedMod"]) {
                return reply(ex, 409, AxonJson.write(linkedMapOf("status" to "conflict", "mod" to have.mod, "hash" to AxonCopyLimits.sha256(have.src)), false))
            }
            val src = o["src"] as String
            val mod = "2026-06-0${++pushes}T00:00:00Z"
            fns[name] = Fn(src, if (o.containsKey("doc")) o["doc"] as String? else have?.doc, mod)
            return reply(ex, 200, AxonJson.write(linkedMapOf("status" to "ok", "mod" to mod, "hash" to AxonCopyLimits.sha256(src)), false))
        }
        reply(ex, 405, """{"error":"method"}""")
    }

    private fun q(s: String) = AxonJson.write(s)
    private fun dec(s: String) = URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8)

    private fun reply(ex: HttpExchange, status: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private var closed = false

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        server.stop(0)
        pool.shutdownNow()
        pool.awaitTermination(5, TimeUnit.SECONDS)
    }
}
