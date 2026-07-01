package jadx.server

import jadx.server.config.ServerConfig
import jadx.server.engine.AndroidManifestParser
import jadx.server.engine.DecompiledApk
import jadx.server.engine.EngineOptions
import jadx.server.engine.ManifestComponent
import jadx.server.engine.ManifestComponentType
import jadx.server.mcp.ToolResult
import jadx.server.server.AcquireResult
import jadx.server.server.ServerState
import jadx.server.tools.ToolRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ManifestToolsApkIntegrationTest {
    private lateinit var tempDir: Path
    private lateinit var state: ServerState
    private lateinit var registry: ToolRegistry
    private lateinit var apkHash: String

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("jadx-test-manifest-tools")
        state = ServerState(ServerConfig(uploadDir = tempDir))
        registry = ToolRegistry.build(state)

        val apkFile = Path.of(System.getProperty("user.dir"))
            .resolve("test/apps/com.huawei.notepad.apk")
        assertTrue(Files.exists(apkFile), "No test APK found at: $apkFile")

        val entry = state.fileIndex.add(apkFile, tempDir)
        apkHash = entry.hash
    }

    @AfterTest
    fun tearDown() {
        state.shutdown()
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun manifestToolsMatchDecodedApkManifest() = withApk { apk ->
        val rawManifest = apk.toolSuccess("get_manifest")["content"].string()
        val manifest = AndroidManifestParser.parse(rawManifest)

        assertEquals(apk.metadata.packageName, manifest.packageName)
        assertEquals(apk.metadata.versionName, manifest.versionName)
        assertEquals(apk.metadata.versionCode, manifest.versionCode)
        assertEquals(apk.metadata.minSdk, manifest.minSdk)
        assertEquals(apk.metadata.targetSdk, manifest.targetSdk)
        assertEquals(apk.metadata.permissions.toSet(), manifest.usesPermissions.map { it.name }.toSet())
        assertEquals(apk.metadata.activities.toSet(), manifest.components.namesOf(ManifestComponentType.ACTIVITY))
        assertEquals(apk.metadata.services.toSet(), manifest.components.namesOf(ManifestComponentType.SERVICE))
        assertEquals(apk.metadata.receivers.toSet(), manifest.components.namesOf(ManifestComponentType.RECEIVER))

        val summary = apk.toolSuccess("get_manifest_summary")
        assertEquals(manifest.packageName, summary["package_name"].string())
        assertEquals(manifest.versionName, summary["version_name"].nullableString())
        assertEquals(manifest.versionCode, summary["version_code"].nullableInt())
        assertEquals(manifest.compileSdkVersion, summary["compile_sdk"].nullableInt())
        assertEquals(manifest.minSdk, summary["min_sdk"].nullableInt())
        assertEquals(manifest.targetSdk, summary["target_sdk"].nullableInt())

        val application = summary["application"] as JsonObject
        assertEquals(manifest.application?.name, application["name"].nullableString())
        assertEquals(manifest.application?.label, application["label"].nullableString())
        assertEquals(manifest.application?.theme, application["theme"].nullableString())
        assertEquals(manifest.application?.allowBackup, application["allow_backup"].nullableBoolean())

        val permissions = apk.toolSuccess("list_manifest_permissions")
        val usesPermissions = permissions["uses_permissions"].array()
        val declaredPermissions = permissions["declared_permissions"].array()
        val usesFeatures = permissions["uses_features"].array()
        assertEquals(manifest.usesPermissions.size, usesPermissions.size)
        assertEquals(manifest.declaredPermissions.size, declaredPermissions.size)
        assertEquals(manifest.usesFeatures.size, usesFeatures.size)
        assertEquals(manifest.usesPermissions.map { it.name }.toSet(), usesPermissions.names())
        assertEquals(manifest.declaredPermissions.map { it.name }.toSet(), declaredPermissions.names())
        assertEquals(manifest.usesFeatures.map { it.name }.toSet(), usesFeatures.names())

        val components = apk.toolSuccess("list_manifest_components")["components"].array()
        assertEquals(manifest.components.size, components.size)
        assertEquals(manifest.components.map { it.name }.toSet(), components.names())
        assertEquals(manifest.components.countByType(), components.countByType())

        for (type in ManifestComponentType.entries) {
            val apiType = type.apiName()
            val filtered = apk.toolSuccess("list_manifest_components", buildJsonObject {
                put("type", JsonPrimitive(apiType))
            })
            val filteredComponents = filtered["components"].array()
            val expected = manifest.components.filter { it.type == type }
            assertEquals(expected.size, filtered["count"].int())
            assertEquals(expected.map { it.name }.toSet(), filteredComponents.names())
        }

        val expectedService = assertNotNull(manifest.components.firstOrNull { it.type == ManifestComponentType.SERVICE })
        val componentSearch = apk.toolSuccess("search_manifest_components", buildJsonObject {
            put("query", JsonPrimitive(expectedService.name))
            put("type", JsonPrimitive("service"))
        })
        val componentMatches = componentSearch["matches"].array()
        assertEquals(1, componentSearch["match_count"].int())
        val serviceNode = componentMatches.first() as JsonObject
        assertEquals("service", serviceNode["type"].string())
        assertEquals(expectedService.name, serviceNode["name"].string())
        assertTrue(serviceNode["xml"].string().startsWith("<service"))
        assertTrue(serviceNode["xml"].string().contains(expectedService.name))

        val intentFilters = apk.toolSuccess("list_manifest_intent_filters")
        val expectedFilterCount = manifest.components.sumOf { it.intentFilters.size }
        assertEquals(expectedFilterCount, intentFilters["count"].int())
        assertEquals(expectedFilterCount, intentFilters["filters"].array().size)

        val entrypoints = apk.toolSuccess("get_manifest_entrypoints")
        assertEquals(
            manifest.components.launcherActivities().map { it.name }.toSet(),
            entrypoints["launcher_activities"].array().names()
        )
        assertEquals(
            manifest.components.exportedComponents(ManifestComponentType.ACTIVITY).map { it.name }.toSet(),
            entrypoints["exported_activities"].array().names()
        )
        assertEquals(
            manifest.components.exportedComponents(ManifestComponentType.SERVICE).map { it.name }.toSet(),
            entrypoints["exported_services"].array().names()
        )
        assertEquals(
            manifest.components.exportedComponents(ManifestComponentType.RECEIVER).map { it.name }.toSet(),
            entrypoints["exported_receivers"].array().names()
        )
        assertEquals(
            manifest.components.exportedComponents(ManifestComponentType.PROVIDER).map { it.name }.toSet(),
            entrypoints["exported_providers"].array().names()
        )
        assertEquals(manifest.components.deepLinkCount(), entrypoints["deeplinks"].array().size)
    }

    private fun withApk(test: (DecompiledApk) -> Unit) {
        val entry = state.fileIndex.resolve(apkHash)!!
        val engineOptions = EngineOptions(xrefMode = state.config.xrefMode)
        val acquireResult = state.enginePool.acquire("manifest-test", apkHash, engineOptions)
        val instance = when (acquireResult) {
            is AcquireResult.Found -> acquireResult.instance
            is AcquireResult.NeedSpawn -> {
                val inst = state.engine.open(Path.of(entry.path), engineOptions)
                state.enginePool.insert("manifest-test", apkHash, inst)
                inst
            }
            else -> throw AssertionError("Unexpected acquire result: $acquireResult")
        }
        try {
            test(instance.state as DecompiledApk)
        } finally {
            state.enginePool.release(instance)
        }
    }

    private fun DecompiledApk.toolSuccess(name: String, args: JsonObject = buildJsonObject {}): JsonObject {
        val result = registry.executeAnalysis(name, this, args)
        assertTrue(result is ToolResult.Success, "Expected $name to succeed, got $result")
        return result.data
    }

    private fun List<ManifestComponent>.namesOf(type: ManifestComponentType): Set<String> {
        return filter { it.type == type }.map { it.name }.toSet()
    }

    private fun List<ManifestComponent>.countByType(): Map<String, Int> {
        return groupingBy { it.type.apiName() }.eachCount()
    }

    private fun List<ManifestComponent>.launcherActivities(): List<ManifestComponent> {
        return filter { component ->
            component.type == ManifestComponentType.ACTIVITY && component.intentFilters.any { filter ->
                "android.intent.action.MAIN" in filter.actions &&
                    "android.intent.category.LAUNCHER" in filter.categories
            }
        }
    }

    private fun List<ManifestComponent>.exportedComponents(type: ManifestComponentType): List<ManifestComponent> {
        return filter { it.type == type && it.exported == true }
    }

    private fun List<ManifestComponent>.deepLinkCount(): Int {
        return sumOf { component ->
            component.intentFilters.count { filter ->
                filter.categories.contains("android.intent.category.BROWSABLE") ||
                    filter.data.any { it.scheme != null || it.host != null }
            }
        }
    }

    private fun JsonArray.names(): Set<String> {
        return mapNotNull { ((it as JsonObject)["name"] as? JsonPrimitive)?.contentOrNull }.toSet()
    }

    private fun JsonArray.countByType(): Map<String, Int> {
        return map { ((it as JsonObject)["type"] as JsonPrimitive).content }
            .groupingBy { it }
            .eachCount()
    }

    private fun ManifestComponentType.apiName(): String = name.lowercase()

    private fun kotlinx.serialization.json.JsonElement?.array(): JsonArray {
        return assertNotNull(this as? JsonArray)
    }

    private fun kotlinx.serialization.json.JsonElement?.string(): String {
        return assertNotNull(this as? JsonPrimitive).content
    }

    private fun kotlinx.serialization.json.JsonElement?.nullableString(): String? {
        return (this as? JsonPrimitive)?.contentOrNull
    }

    private fun kotlinx.serialization.json.JsonElement?.int(): Int {
        return assertNotNull(this as? JsonPrimitive).content.toInt()
    }

    private fun kotlinx.serialization.json.JsonElement?.nullableInt(): Int? {
        return (this as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    }

    private fun kotlinx.serialization.json.JsonElement?.nullableBoolean(): Boolean? {
        return (this as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
    }
}
