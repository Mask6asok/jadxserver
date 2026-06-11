package jadx.server.project

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class JadxProjectCodeData(
    val comments: List<JsonObject> = emptyList(),
    val renames: List<JsonObject> = emptyList()
)

@Serializable
data class JadxProjectTabView(
    val x: Int = 0,
    val y: Int = 0
)

@Serializable
data class JadxProjectOpenTab(
    val type: String = "class",
    val tabPath: String = "",
    val subPath: String = "java",
    val caret: Int = 0,
    val view: JadxProjectTabView = JadxProjectTabView(),
    val active: Boolean = false,
    val pinned: Boolean = false,
    val bookmarked: Boolean = false,
    val hidden: Boolean = false,
    val previewTab: Boolean = false
)

@Serializable
data class JadxProjectFile(
    val projectVersion: Int = 2,
    val files: List<String> = emptyList(),
    val treeExpansionsV2: List<String> = emptyList(),
    val codeData: JadxProjectCodeData = JadxProjectCodeData(),
    val openTabs: List<JadxProjectOpenTab> = emptyList(),
    val mappingsPath: String? = null,
    val cacheDir: String? = null,
    val enableLiveReload: Boolean = false,
    val searchHistory: List<String> = emptyList(),
    val searchResourcesFilter: String = "\$TEXT",
    val searchResourcesSizeLimit: Int = 0,
    val pluginOptions: Map<String, String> = emptyMap()
)
