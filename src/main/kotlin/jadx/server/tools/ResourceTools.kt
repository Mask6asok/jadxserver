package jadx.server.tools

import jadx.server.engine.AndroidApplicationInfo
import jadx.server.engine.AndroidManifestInfo
import jadx.server.engine.AndroidManifestParser
import jadx.server.engine.DecompiledApk
import jadx.server.engine.ManifestComponent
import jadx.server.engine.ManifestComponentNodeMatch
import jadx.server.engine.ManifestComponentType
import jadx.server.engine.ManifestIntentData
import jadx.server.engine.ManifestIntentFilter
import jadx.server.mcp.McpToolDef
import jadx.server.mcp.ToolResult
import jadx.server.util.getString
import jadx.server.util.getBoolean
import jadx.server.util.getInt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

object ResourceTools {

    fun definitions(): List<McpToolDef> = listOf(
        McpToolDef("get_manifest", "Return AndroidManifest.xml content"),
        McpToolDef("get_manifest_summary", "Return structured AndroidManifest.xml package, version, SDK, and application summary"),
        McpToolDef("list_manifest_permissions", "List manifest uses-permission, declared permission, and uses-feature entries"),
        McpToolDef("list_manifest_components", "List Android manifest components with intent filters and key attributes")
            .param("type", "string", "Filter by component type: activity, activity_alias, service, receiver, provider, or all (default: all)", false),
        McpToolDef("search_manifest_components", "Search Android manifest component XML nodes and return each complete matching node")
            .param("query", "string", "Text or regular expression to find in component name or XML node", true)
            .param("type", "string", "Filter by component type: activity, activity_alias, service, receiver, provider, or all (default: all)", false)
            .param("regex", "boolean", "Interpret query as a regular expression (default: false)", false)
            .param("case_sensitive", "boolean", "Case-sensitive matching (default: false)", false)
            .param("limit", "number", "Maximum matches (default: 100, max: 1000)", false),
        McpToolDef("list_manifest_intent_filters", "List manifest intent filters attached to activities, services, receivers, providers, and aliases")
            .param("type", "string", "Filter by component type: activity, activity_alias, service, receiver, provider, or all (default: all)", false),
        McpToolDef("get_manifest_entrypoints", "Return launcher activities, exported components, and deep link entrypoints from AndroidManifest.xml"),
        McpToolDef("get_resource", "Return specific resource file content by path")
            .param("path", "string", "Resource path (e.g. res/values/strings.xml)", true),
        McpToolDef("list_resources", "List all resource files with types")
            .param("type", "string", "Filter by resource type (e.g. xml, png)", false),
        McpToolDef("search_resource", "Search decoded resource text, including AndroidManifest.xml and XML generated from resources.arsc")
            .param("query", "string", "Text or regular expression to find", true)
            .param("regex", "boolean", "Interpret query as a regular expression (default: false)", false)
            .param("case_sensitive", "boolean", "Case-sensitive matching (default: false)", false)
            .param("limit", "number", "Maximum matches (default: 100, max: 5000)", false)
    )

    fun getManifest(apk: DecompiledApk, args: JsonObject): ToolResult {
        val manifest = apk.getManifest()
            ?: return ToolResult.notFound("AndroidManifest.xml not found")
        return ToolResult.success {
            put("content", JsonPrimitive(manifest))
        }
    }

    fun getManifestSummary(apk: DecompiledApk, args: JsonObject): ToolResult {
        val manifest = parseManifest(apk) ?: return ToolResult.notFound("AndroidManifest.xml not found")
        return ToolResult.success {
            put("package_name", JsonPrimitive(manifest.packageName))
            put("version_name", JsonPrimitive(manifest.versionName))
            put("version_code", JsonPrimitive(manifest.versionCode))
            put("compile_sdk", JsonPrimitive(manifest.compileSdkVersion))
            put("compile_sdk_codename", JsonPrimitive(manifest.compileSdkVersionCodename))
            put("platform_build_version_code", JsonPrimitive(manifest.platformBuildVersionCode))
            put("platform_build_version_name", JsonPrimitive(manifest.platformBuildVersionName))
            put("min_sdk", JsonPrimitive(manifest.minSdk))
            put("target_sdk", JsonPrimitive(manifest.targetSdk))
            put("application", manifest.application.toJson())
        }
    }

    fun listManifestPermissions(apk: DecompiledApk, args: JsonObject): ToolResult {
        val manifest = parseManifest(apk) ?: return ToolResult.notFound("AndroidManifest.xml not found")
        return ToolResult.success {
            put("uses_permissions", buildJsonArray {
                for (permission in manifest.usesPermissions) {
                    add(buildJsonObject {
                        put("name", JsonPrimitive(permission.name))
                        put("max_sdk_version", JsonPrimitive(permission.maxSdkVersion))
                    })
                }
            })
            put("declared_permissions", buildJsonArray {
                for (permission in manifest.declaredPermissions) {
                    add(buildJsonObject {
                        put("name", JsonPrimitive(permission.name))
                        put("protection_level", JsonPrimitive(permission.protectionLevel))
                        put("label", JsonPrimitive(permission.label))
                        put("description", JsonPrimitive(permission.description))
                    })
                }
            })
            put("uses_features", buildJsonArray {
                for (feature in manifest.usesFeatures) {
                    add(buildJsonObject {
                        put("name", JsonPrimitive(feature.name))
                        put("required", JsonPrimitive(feature.required))
                    })
                }
            })
        }
    }

    fun listManifestComponents(apk: DecompiledApk, args: JsonObject): ToolResult {
        val manifest = parseManifest(apk) ?: return ToolResult.notFound("AndroidManifest.xml not found")
        val type = args.getString("type") ?: "all"
        val components = manifest.components.filterByType(type)
            ?: return ToolResult.badParams("Invalid component type: $type")

        return ToolResult.success {
            put("count", JsonPrimitive(components.size))
            put("components", buildJsonArray {
                for (component in components) {
                    add(component.toJson(includeIntentFilters = true))
                }
            })
        }
    }

    fun searchManifestComponents(apk: DecompiledApk, args: JsonObject): ToolResult {
        val manifestXml = apk.getManifest() ?: return ToolResult.notFound("AndroidManifest.xml not found")
        val query = args.getString("query")
            ?: return ToolResult.badParams("Missing required parameter: query")
        if (query.isEmpty()) return ToolResult.badParams("Parameter 'query' must not be empty")

        val type = args.getString("type") ?: "all"
        val useRegex = args.getBoolean("regex", false)
        val caseSensitive = args.getBoolean("case_sensitive", false)
        val limit = args.getInt("limit", 100).coerceIn(1, 1000)
        val regex = if (useRegex) {
            try {
                Regex(query, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
            } catch (e: IllegalArgumentException) {
                return ToolResult.badParams("Invalid regular expression: ${e.message}")
            }
        } else {
            null
        }
        val matches = try {
            AndroidManifestParser.searchComponents(
                manifestXml,
                query,
                type = type,
                regex = regex,
                caseSensitive = caseSensitive,
                limit = limit
            )
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse AndroidManifest.xml: ${e.message}", e)
        } ?: return ToolResult.badParams("Invalid component type: $type")

        return ToolResult.success {
            put("query", JsonPrimitive(query))
            put("type", JsonPrimitive(type))
            put("match_count", JsonPrimitive(matches.size))
            put("matches", buildJsonArray {
                for (match in matches) add(match.toJson())
            })
        }
    }

    fun listManifestIntentFilters(apk: DecompiledApk, args: JsonObject): ToolResult {
        val manifest = parseManifest(apk) ?: return ToolResult.notFound("AndroidManifest.xml not found")
        val type = args.getString("type") ?: "all"
        val components = manifest.components.filterByType(type)
            ?: return ToolResult.badParams("Invalid component type: $type")

        return ToolResult.success {
            put("count", JsonPrimitive(components.sumOf { it.intentFilters.size }))
            put("filters", buildJsonArray {
                for (component in components) {
                    for (filter in component.intentFilters) {
                        add(buildJsonObject {
                            put("component_type", JsonPrimitive(component.type.apiName()))
                            put("component_name", JsonPrimitive(component.name))
                            put("exported", JsonPrimitive(component.exported))
                            put("filter", filter.toJson())
                        })
                    }
                }
            })
        }
    }

    fun getManifestEntrypoints(apk: DecompiledApk, args: JsonObject): ToolResult {
        val manifest = parseManifest(apk) ?: return ToolResult.notFound("AndroidManifest.xml not found")
        val launcherActivities = manifest.components.filter { component ->
            component.type == ManifestComponentType.ACTIVITY && component.intentFilters.any { filter ->
                "android.intent.action.MAIN" in filter.actions &&
                    "android.intent.category.LAUNCHER" in filter.categories
            }
        }
        val exported = manifest.components.filter { it.exported == true }
        val deepLinks = manifest.components.flatMap { component ->
            component.intentFilters
                .filter { it.categories.contains("android.intent.category.BROWSABLE") || it.data.any { data -> data.scheme != null || data.host != null } }
                .map { filter -> component to filter }
        }

        return ToolResult.success {
            put("launcher_activities", buildJsonArray {
                for (activity in launcherActivities) add(activity.toJson(includeIntentFilters = true))
            })
            put("exported_activities", exportedComponents(exported, ManifestComponentType.ACTIVITY))
            put("exported_activity_aliases", exportedComponents(exported, ManifestComponentType.ACTIVITY_ALIAS))
            put("exported_services", exportedComponents(exported, ManifestComponentType.SERVICE))
            put("exported_receivers", exportedComponents(exported, ManifestComponentType.RECEIVER))
            put("exported_providers", exportedComponents(exported, ManifestComponentType.PROVIDER))
            put("deeplinks", buildJsonArray {
                for ((component, filter) in deepLinks) {
                    add(buildJsonObject {
                        put("component_type", JsonPrimitive(component.type.apiName()))
                        put("component_name", JsonPrimitive(component.name))
                        put("exported", JsonPrimitive(component.exported))
                        put("filter", filter.toJson())
                    })
                }
            })
        }
    }

    fun getResource(apk: DecompiledApk, args: JsonObject): ToolResult {
        val path = args.getString("path")
            ?: return ToolResult.badParams("Missing required parameter: path")

        val content = apk.getResource(path)
            ?: return ToolResult.notFound("Resource not found: $path")

        return ToolResult.success {
            put("path", JsonPrimitive(path))
            put("content", JsonPrimitive(content))
        }
    }

    fun listResources(apk: DecompiledApk, args: JsonObject): ToolResult {
        val typeFilter = args.getString("type")
        val resources = apk.listResources()
        val filtered = if (typeFilter != null) {
            resources.filter { it.type.equals(typeFilter, ignoreCase = true) }
        } else {
            resources
        }

        return ToolResult.success {
            put("count", JsonPrimitive(filtered.size))
            put("resources", buildJsonArray {
                for (r in filtered) {
                    add(buildJsonObject {
                        put("name", JsonPrimitive(r.name))
                        put("type", JsonPrimitive(r.type))
                        put("path", JsonPrimitive(r.path))
                    })
                }
            })
        }
    }

    fun searchResource(apk: DecompiledApk, args: JsonObject): ToolResult {
        val query = args.getString("query")
            ?: return ToolResult.badParams("Missing required parameter: query")
        if (query.isEmpty()) return ToolResult.badParams("Parameter 'query' must not be empty")

        val useRegex = args.getBoolean("regex", false)
        val caseSensitive = args.getBoolean("case_sensitive", false)
        val limit = args.getInt("limit", 100).coerceIn(1, 5000)
        val regex = if (useRegex) {
            try {
                Regex(query, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
            } catch (e: IllegalArgumentException) {
                return ToolResult.badParams("Invalid regular expression: ${e.message}")
            }
        } else {
            null
        }

        val matches = apk.searchResources(query, limit, regex, caseSensitive)
        return ToolResult.success {
            put("query", JsonPrimitive(query))
            put("match_count", JsonPrimitive(matches.size))
            put("matches", buildJsonArray {
                for (match in matches) {
                    add(buildJsonObject {
                        put("path", JsonPrimitive(match.path))
                        put("type", JsonPrimitive(match.type))
                        put("line", JsonPrimitive(match.line))
                        put("column", JsonPrimitive(match.column))
                        put("content", JsonPrimitive(match.content))
                    })
                }
            })
        }
    }

    private fun parseManifest(apk: DecompiledApk): AndroidManifestInfo? {
        val manifest = apk.getManifest() ?: return null
        return try {
            AndroidManifestParser.parse(manifest)
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse AndroidManifest.xml: ${e.message}", e)
        }
    }

    private fun AndroidApplicationInfo?.toJson(): JsonObject {
        val app = this
        return buildJsonObject {
            if (app == null) return@buildJsonObject
            put("name", JsonPrimitive(app.name))
            put("label", JsonPrimitive(app.label))
            put("icon", JsonPrimitive(app.icon))
            put("theme", JsonPrimitive(app.theme))
            put("debuggable", JsonPrimitive(app.debuggable))
            put("allow_backup", JsonPrimitive(app.allowBackup))
            put("large_heap", JsonPrimitive(app.largeHeap))
            put("supports_rtl", JsonPrimitive(app.supportsRtl))
            put("extract_native_libs", JsonPrimitive(app.extractNativeLibs))
            put("resizeable_activity", JsonPrimitive(app.resizeableActivity))
            put("network_security_config", JsonPrimitive(app.networkSecurityConfig))
            put("app_component_factory", JsonPrimitive(app.appComponentFactory))
            put("request_legacy_external_storage", JsonPrimitive(app.requestLegacyExternalStorage))
        }
    }

    private fun List<ManifestComponent>.filterByType(type: String): List<ManifestComponent>? {
        if (type == "all") return this
        val componentType = when (type.lowercase()) {
            "activity" -> ManifestComponentType.ACTIVITY
            "activity_alias", "activity-alias" -> ManifestComponentType.ACTIVITY_ALIAS
            "service" -> ManifestComponentType.SERVICE
            "receiver" -> ManifestComponentType.RECEIVER
            "provider" -> ManifestComponentType.PROVIDER
            else -> return null
        }
        return filter { it.type == componentType }
    }

    private fun exportedComponents(
        components: List<ManifestComponent>,
        type: ManifestComponentType
    ) = buildJsonArray {
        for (component in components.filter { it.type == type }) {
            add(component.toJson(includeIntentFilters = true))
        }
    }

    private fun ManifestComponent.toJson(includeIntentFilters: Boolean): JsonObject {
        val component = this
        return buildJsonObject {
            put("type", JsonPrimitive(component.type.apiName()))
            put("name", JsonPrimitive(component.name))
            put("target_activity", JsonPrimitive(component.targetActivity))
            put("label", JsonPrimitive(component.label))
            put("icon", JsonPrimitive(component.icon))
            put("theme", JsonPrimitive(component.theme))
            put("exported", JsonPrimitive(component.exported))
            put("enabled", JsonPrimitive(component.enabled))
            put("permission", JsonPrimitive(component.permission))
            put("read_permission", JsonPrimitive(component.readPermission))
            put("write_permission", JsonPrimitive(component.writePermission))
            put("process", JsonPrimitive(component.process))
            put("authorities", JsonPrimitive(component.authorities))
            put("grant_uri_permissions", JsonPrimitive(component.grantUriPermissions))
            put("launch_mode", JsonPrimitive(component.launchMode))
            put("task_affinity", JsonPrimitive(component.taskAffinity))
            put("screen_orientation", JsonPrimitive(component.screenOrientation))
            put("config_changes", JsonPrimitive(component.configChanges))
            put("direct_boot_aware", JsonPrimitive(component.directBootAware))
            if (includeIntentFilters) {
                put("intent_filters", buildJsonArray {
                    component.intentFilters.forEach { add(it.toJson()) }
                })
            } else {
                put("intent_filter_count", JsonPrimitive(component.intentFilters.size))
            }
            put("meta_data", buildJsonArray {
                for (metaData in component.metaData) {
                    add(buildJsonObject {
                        put("name", JsonPrimitive(metaData.name))
                        put("value", JsonPrimitive(metaData.value))
                        put("resource", JsonPrimitive(metaData.resource))
                    })
                }
            })
        }
    }

    private fun ManifestComponentNodeMatch.toJson(): JsonObject {
        val match = this
        return buildJsonObject {
            put("type", JsonPrimitive(match.type.apiName()))
            put("name", JsonPrimitive(match.name))
            put("xml", JsonPrimitive(match.xml))
        }
    }

    private fun ManifestIntentFilter.toJson(): JsonObject {
        val filter = this
        return buildJsonObject {
            put("auto_verify", JsonPrimitive(filter.autoVerify))
            put("actions", buildJsonArray { filter.actions.forEach { add(JsonPrimitive(it)) } })
            put("categories", buildJsonArray { filter.categories.forEach { add(JsonPrimitive(it)) } })
            put("data", buildJsonArray { filter.data.forEach { add(it.toJson()) } })
        }
    }

    private fun ManifestIntentData.toJson(): JsonObject {
        val data = this
        return buildJsonObject {
            put("scheme", JsonPrimitive(data.scheme))
            put("host", JsonPrimitive(data.host))
            put("port", JsonPrimitive(data.port))
            put("path", JsonPrimitive(data.path))
            put("path_prefix", JsonPrimitive(data.pathPrefix))
            put("path_pattern", JsonPrimitive(data.pathPattern))
            put("mime_type", JsonPrimitive(data.mimeType))
        }
    }

    private fun ManifestComponentType.apiName(): String = name.lowercase()
}
