package jadx.server.util

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

fun json(block: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)

fun JsonObject.getString(key: String): String? =
    this[key]?.jsonPrimitive?.content

fun JsonObject.getInt(key: String, default: Int = 0): Int =
    this[key]?.jsonPrimitive?.intOrNull ?: default

fun JsonObject.getBoolean(key: String, default: Boolean = false): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull ?: default

fun JsonObjectBuilder.putString(key: String, value: String?) {
    if (value != null) put(key, JsonPrimitive(value))
}

fun JsonObjectBuilder.putInt(key: String, value: Int?) {
    if (value != null) put(key, JsonPrimitive(value))
}

fun JsonObjectBuilder.putBoolean(key: String, value: Boolean?) {
    if (value != null) put(key, JsonPrimitive(value))
}

fun JsonObjectBuilder.putLong(key: String, value: Long?) {
    if (value != null) put(key, JsonPrimitive(value))
}
