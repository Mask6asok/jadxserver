package jadx.server.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject

sealed class ToolResult {
    data class Success(val data: JsonObject) : ToolResult()
    data class Error(val code: Int, val message: String) : ToolResult()

    companion object {
        fun success(data: JsonObject) = Success(data)
        fun success(block: JsonObjectBuilder.() -> Unit) = Success(buildJsonObject(block))
        fun error(code: Int, message: String) = Error(code, message)
        fun notFound(message: String) = Error(-32001, message)
        fun badParams(message: String) = Error(-32602, message)
        fun internal(message: String) = Error(-32603, message)
        fun poolFull(max: Int) = Error(-32003, "Worker pool at capacity ($max instances)")
    }
}
