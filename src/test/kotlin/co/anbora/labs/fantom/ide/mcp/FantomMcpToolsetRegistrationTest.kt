package co.anbora.labs.fantom.ide.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** fantom-mcp.xml must put the toolset on the MCP Server extension point, with its tools. */
class FantomMcpToolsetRegistrationTest : BasePlatformTestCase() {

    fun testToolsetIsRegistered() {
        val ours = McpToolset.EP.extensionList.filterIsInstance<FantomMcpToolset>()
        assertEquals("FantomMcpToolset must be registered once", 1, ours.size)
    }

    fun testToolNames() {
        val names = FantomMcpToolset::class.java.declaredMethods
            .filter { it.isAnnotationPresent(McpTool::class.java) }
            .map { it.name }
            .sorted()
        assertEquals(listOf("fantom_build", "fantom_diagnostics", "fantom_find_usages", "fantom_pod_deps", "fantom_resolve", "fantom_run_test", "fantom_type_of"), names)
    }
}
