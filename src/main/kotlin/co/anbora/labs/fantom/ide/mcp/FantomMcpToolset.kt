package co.anbora.labs.fantom.ide.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * MCP tools for AI agents: diagnostics, resolve, usages, types and pod dependencies of Fantom
 * and Axon code, with the answers the IDE gives; and, when the user turns it on, build and test. Loaded only when the MCP Server plugin is on (see `fantom-mcp.xml`).
 * The work is in [FantomMcpService]; this class only maps MCP calls to it.
 */
class FantomMcpToolset : McpToolset {

    @McpTool(name = "fantom_diagnostics")
    @McpDescription(
        """
        Problems the IDE shows for a Fantom (.fan) or Axon (.axon) file in the project:
        parse errors, enabled inspections, and compiler messages when a fresh compile exists.
        One line per problem: SEVERITY line:column message [source].
        Answers "indexing, retry" while the IDE indexes.
        """
    )
    suspend fun fantom_diagnostics(
        @McpDescription("File path, absolute or relative to the project folder")
        path: String,
        @McpDescription("Most problems to return (default 50, max 500)")
        limit: Int = FantomMcpQueries.DEFAULT_LIMIT,
    ): String = answer { FantomMcpService.diagnostics(it, path, limit) }

    @McpTool(name = "fantom_resolve")
    @McpDescription(
        """
        Where a Fantom or Axon name is defined, as Go to Declaration finds it.
        Give path with offset (0-based) or line and column (1-based), or give only a
        qualified Fantom name: pod::Type.slot, Type.slot or Type.
        One line per target: kind name, then path:line:column and the source line.
        Answers "indexing, retry" while the IDE indexes.
        """
    )
    suspend fun fantom_resolve(
        @McpDescription("File path, absolute or relative to the project folder; empty with qualifiedName")
        path: String = "",
        @McpDescription("0-based character offset in the file; -1 to use line and column")
        offset: Int = -1,
        @McpDescription("1-based line, used when offset is -1")
        line: Int = 0,
        @McpDescription("1-based column, used when offset is -1")
        column: Int = 1,
        @McpDescription("Qualified Fantom name, used when path is empty")
        qualifiedName: String = "",
        @McpDescription("Most targets to return (default 50, max 500)")
        limit: Int = FantomMcpQueries.DEFAULT_LIMIT,
    ): String = answer { FantomMcpService.resolve(it, path, offset, line, column, qualifiedName, limit) }

    @McpTool(name = "fantom_find_usages")
    @McpDescription(
        """
        Find Usages for a Fantom or Axon declaration, in project files only.
        Point at the declaration or at a use of it: path with offset (0-based) or line and
        column (1-based). An Axon name is searched in its own file.
        One line per usage: path:line:column and the source line.
        Answers "indexing, retry" while the IDE indexes.
        """
    )
    suspend fun fantom_find_usages(
        @McpDescription("File path, absolute or relative to the project folder")
        path: String,
        @McpDescription("0-based character offset in the file; -1 to use line and column")
        offset: Int = -1,
        @McpDescription("1-based line, used when offset is -1")
        line: Int = 0,
        @McpDescription("1-based column, used when offset is -1")
        column: Int = 1,
        @McpDescription("Most usages to return (default 50, max 500)")
        limit: Int = FantomMcpQueries.DEFAULT_LIMIT,
    ): String = answer { FantomMcpService.usages(it, path, offset, line, column, limit) }

    @McpTool(name = "fantom_type_of")
    @McpDescription(
        """
        The inferred type at a place in a Fantom (.fan) file, as Go to Type Declaration finds it:
        the expression there, else the declaration named or used there. Give path with offset
        (0-based) or line and column (1-based). Or give only qualifiedName (pod::Type.slot or
        Type.slot) for the declared type of a slot. First line is the type, e.g. Str[] or Int?.
        """
    )
    suspend fun fantom_type_of(
        @McpDescription("File path, absolute or relative to the project folder; empty with qualifiedName")
        path: String = "",
        @McpDescription("0-based character offset in the file; -1 to use line and column")
        offset: Int = -1,
        @McpDescription("1-based line, used when offset is -1")
        line: Int = 0,
        @McpDescription("1-based column, used when offset is -1")
        column: Int = 1,
        @McpDescription("Qualified slot name, used when path is empty")
        qualifiedName: String = "",
        @McpDescription("Most slots to return (default 50, max 500)")
        limit: Int = FantomMcpQueries.DEFAULT_LIMIT,
    ): String = answer { FantomMcpService.typeOf(it, path, offset, line, column, qualifiedName, limit) }

    @McpTool(name = "fantom_pod_deps")
    @McpDescription(
        """
        The depends of a project pod from its build.fan, each with the source the plugin picked
        for the current build target: this project, an install's sources, a tag or working copy
        under the source roots, a stub, or not found. Give pod, or path of a file in the pod.
        """
    )
    suspend fun fantom_pod_deps(
        @McpDescription("Pod name, as in podName of its build.fan")
        pod: String = "",
        @McpDescription("A file or folder in the pod, used when pod is empty")
        path: String = "",
    ): String = answer { FantomMcpService.podDeps(it, pod, path) }

    @McpTool(name = "fantom_build")
    @McpDescription(
        """
        Builds a project pod with the default build target, as Build Pod does, and returns pass or
        fail and every compiler message: SEVERITY path:line:column message. Saves open editors
        first. The pod goes to a private work folder, never into an install. Off until the user
        turns on "Allow AI agents to build and run tests" in the Fantom settings.
        """
    )
    suspend fun fantom_build(
        @McpDescription("Pod name, as in podName of its build.fan")
        pod: String = "",
        @McpDescription("A file or folder in the pod, used when pod is empty")
        path: String = "",
        @McpDescription("Most messages to return (default 50, max 500)")
        limit: Int = FantomMcpQueries.DEFAULT_LIMIT,
    ): String = answer { FantomMcpService.build(it, pod, path, limit) }

    @McpTool(name = "fantom_run_test")
    @McpDescription(
        """
        Builds a project pod, then runs fant on it, on one test type, or on one test method.
        One line per test: PASS pod::Type.method, or FAIL pod::Type.method path:line message.
        A failed build returns its compiler messages instead. Stops after 5 minutes. Off until
        the user turns on "Allow AI agents to build and run tests" in the Fantom settings.
        """
    )
    suspend fun fantom_run_test(
        @McpDescription("Pod name")
        pod: String,
        @McpDescription("Test type name, empty for every test of the pod")
        type: String = "",
        @McpDescription("Test method name, empty for every method of the type")
        method: String = "",
        @McpDescription("Most tests to return (default 50, max 500)")
        limit: Int = FantomMcpQueries.DEFAULT_LIMIT,
    ): String = answer { FantomMcpService.runTest(it, pod, type, method, limit) }

    /** Runs [body] on a pool thread, never on the UI thread; it takes its own read actions. */
    private suspend fun answer(body: suspend (Project) -> FantomMcpService.Answer): String {
        val project = currentCoroutineContext().project
        return when (val a = withContext(Dispatchers.IO) { body(project) }) {
            is FantomMcpService.Answer.Ok -> a.text
            is FantomMcpService.Answer.Fail -> mcpFail(a.text)
        }
    }
}
