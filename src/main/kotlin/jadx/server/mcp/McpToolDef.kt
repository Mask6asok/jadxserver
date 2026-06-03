package jadx.server.mcp

/**
 * Tool schema definition - mirrors IDA-rust's ToolSchema pattern.
 */
data class McpToolDef(
    val name: String,
    val description: String,
    val params: List<ToolParam> = emptyList()
) {
    fun param(name: String, type: String, description: String, required: Boolean = false): McpToolDef {
        return copy(params = params + ToolParam(name, type, description, required))
    }
}

data class ToolParam(
    val name: String,
    val type: String,  // "string" | "boolean" | "number" | "array"
    val description: String,
    val required: Boolean
)
