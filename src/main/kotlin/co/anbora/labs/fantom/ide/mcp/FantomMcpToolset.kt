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
 * MCP tools for AI agents: diagnostics, resolve and usages of Fantom and Axon code, with the
 * answers the IDE gives. Loaded only when the MCP Server plugin is on (see `fantom-mcp.xml`).
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

    /** Runs [body] on a pool thread, never on the UI thread; it takes its own read actions. */
    private suspend fun answer(body: (Project) -> FantomMcpService.Answer): String {
        val project = currentCoroutineContext().project
        return when (val a = withContext(Dispatchers.IO) { body(project) }) {
            is FantomMcpService.Answer.Ok -> a.text
            is FantomMcpService.Answer.Fail -> mcpFail(a.text)
        }
    }
}
