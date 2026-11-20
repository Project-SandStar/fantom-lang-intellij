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
 * MCP tools for the Axon projects open in the IDE (task 4.31): which project a file belongs
 * to, the functions of a project, and one function. Loaded only when the MCP Server plugin is
 * on (see `fantom-mcp.xml`). The work is in [AxonMcpService].
 *
 * Each description says when to use it rather than the Axon MCP server: these tools answer
 * from the files open in the IDE (with unsaved edits); the server answers from SkySpark.
 */
class AxonMcpToolset : McpToolset {

    @McpTool(name = "axon_project_of")
    @McpDescription(
        """
        The Axon project (connection/project) that a file open in the IDE belongs to, as the
        editor resolves its names: from its proj folder (proj/<connection>/<project>/func/),
        else the active Axon project copy, else none. A backup or version folder gives none.
        Use it before you edit or resolve Axon in the IDE. For projects that are not open in
        the IDE, or for what SkySpark holds now, use the Axon MCP server instead.
        Answers "indexing, retry" while the IDE indexes.
        """
    )
    suspend fun axon_project_of(
        @McpDescription("File or folder path, absolute or relative to the project folder")
        path: String,
    ): String = answer { AxonMcpService.projectOf(it, path) }

    @McpTool(name = "axon_functions")
    @McpDescription(
        """
        The functions of an Axon project open in the IDE: one line per function with its name,
        its file (a path the other tools take) and the first line of its doc. Pick the project
        by a file path in it, or by projectKey (connection/project), or give neither for the
        active Axon project copy. Answers from the files in the IDE, so it sees local edits
        that are not pushed. To search SkySpark itself, or a project not open in the IDE, use
        the Axon MCP server instead. Answers "indexing, retry" while the IDE indexes.
        """
    )
    suspend fun axon_functions(
        @McpDescription("A file or folder in the project; empty to use projectKey or the active copy")
        path: String = "",
        @McpDescription("connection/project, used when path is empty")
        projectKey: String = "",
        @McpDescription("Only names that contain this text, any case; empty for all")
        filter: String = "",
        @McpDescription("Most functions to return (default 50, max 500)")
        limit: Int = FantomMcpQueries.DEFAULT_LIMIT,
    ): String = answer { AxonMcpService.functions(it, path, projectKey, filter, limit) }

    @McpTool(name = "axon_function")
    @McpDescription(
        """
        One Axon function as the IDE has it: name(params), project, file, mod, doc and source
        (the open editor text, with unsaved edits). Give the path of its .axon file, or a name
        with a path in the project, a projectKey (connection/project), or neither for the
        active copy. A function of the same name in another project is never returned.
        To read what SkySpark runs now, to run or check Axon, or to commit a change, use the
        Axon MCP server instead. Answers "indexing, retry" while the IDE indexes.
        """
    )
    suspend fun axon_function(
        @McpDescription("The .axon file of the function, or any file in its project when name is given")
        path: String = "",
        @McpDescription("connection/project, used when path is empty")
        projectKey: String = "",
        @McpDescription("Function name; empty to take the function of the file at path")
        name: String = "",
    ): String = answer { AxonMcpService.function(it, path, projectKey, name) }

    /** Runs [body] on a pool thread, never on the UI thread; it takes its own read actions. */
    private suspend fun answer(body: suspend (Project) -> FantomMcpService.Answer): String {
        val project = currentCoroutineContext().project
        return when (val a = withContext(Dispatchers.IO) { body(project) }) {
            is FantomMcpService.Answer.Ok -> a.text
            is FantomMcpService.Answer.Fail -> mcpFail(a.text)
        }
    }
}
