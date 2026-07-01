package jadx.server.engine

import jadx.api.JadxArgs
import jadx.api.JavaClass
import jadx.api.JadxDecompiler
import jadx.api.ResourceType
import jadx.api.usage.impl.EmptyUsageInfoCache
import jadx.core.dex.nodes.ProcessState
import jadx.server.config.XrefMode
import jadx.server.mcp.McpToolDef
import jadx.server.server.CacheState
import jadx.server.server.ProjectCacheLayout
import jadx.server.util.HashUtil
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator

class JadxEngine : DecompilerEngine {
    private val logger = LoggerFactory.getLogger(JadxEngine::class.java)

    override val name: String = "jadx"

    override fun toolSchemas(): List<McpToolDef> = listOf(
        McpToolDef("list_classes", "List classes in the APK with optional filter and pagination")
            .param("filter", "string", "Substring filter for class names")
            .param("offset", "number", "Pagination offset")
            .param("count", "number", "Number of results to return"),

        McpToolDef("get_class_code", "Get decompiled Java source code for a class")
            .param("class_name", "string", "Fully qualified class name", required = true),

        McpToolDef("get_class_info", "Get detailed metadata for a class including methods, fields, and hierarchy")
            .param("class_name", "string", "Fully qualified class name", required = true),

        McpToolDef("get_method_code", "Get decompiled source code for a specific method")
            .param("class_name", "string", "Fully qualified class name", required = true)
            .param("method_name", "string", "Method name", required = true)
            .param("signature", "string", "Method signature for disambiguation"),

        McpToolDef("list_methods", "List all methods in a class with signatures and access modifiers")
            .param("class_name", "string", "Fully qualified class name", required = true),

        McpToolDef("search_code", "Search decompiled code for a text pattern across all classes")
            .param("query", "string", "Search query string", required = true)
            .param("limit", "number", "Maximum number of results"),

        McpToolDef("search_string", "Search for string literals in decompiled code")
            .param("query", "string", "String or regex to search for within string literals", required = true)
            .param("limit", "number", "Maximum number of results"),

        McpToolDef("class_xrefs", "Find cross-references for a class (who uses it and what it uses)")
            .param("class_name", "string", "Fully qualified class name", required = true)
            .param("mode", "string", "Xref mode: 'text' for string search, 'jadx' for bytecode API (default: server config)")
            .param("limit", "number", "Maximum results"),

        McpToolDef("method_xrefs", "Find cross-references for a method (who calls it)")
            .param("class_name", "string", "Fully qualified class name", required = true)
            .param("method_name", "string", "Method name", required = true)
            .param("direction", "string", "'callers' or 'callees' (default: both)")
            .param("mode", "string", "Xref mode: 'text' for string search, 'jadx' for bytecode API (default: server config)")
            .param("limit", "number", "Maximum results per direction"),

        McpToolDef("get_manifest", "Get the decoded AndroidManifest.xml content"),

        McpToolDef("get_manifest_summary", "Return structured AndroidManifest.xml package, version, SDK, and application summary"),

        McpToolDef("list_manifest_permissions", "List manifest uses-permission, declared permission, and uses-feature entries"),

        McpToolDef("list_manifest_components", "List Android manifest components with intent filters and key attributes")
            .param("type", "string", "Filter by component type: activity, activity_alias, service, receiver, provider, or all"),

        McpToolDef("search_manifest_components", "Search Android manifest component XML nodes and return each complete matching node")
            .param("query", "string", "Text or regular expression to find in component name or XML node", required = true)
            .param("type", "string", "Filter by component type: activity, activity_alias, service, receiver, provider, or all")
            .param("regex", "boolean", "Interpret query as a regular expression")
            .param("case_sensitive", "boolean", "Use case-sensitive matching")
            .param("limit", "number", "Maximum number of matches"),

        McpToolDef("list_manifest_intent_filters", "List manifest intent filters attached to activities, services, receivers, providers, and aliases")
            .param("type", "string", "Filter by component type: activity, activity_alias, service, receiver, provider, or all"),

        McpToolDef("get_manifest_entrypoints", "Return launcher activities, exported components, and deep link entrypoints from AndroidManifest.xml"),

        McpToolDef("get_resource", "Get content of a specific resource file by path")
            .param("path", "string", "Resource file path (e.g. res/values/strings.xml)", required = true),

        McpToolDef("list_resources", "List all resource files in the APK"),

        McpToolDef("search_resource", "Search decoded resource text, including AndroidManifest.xml and XML generated from resources.arsc")
            .param("query", "string", "Text or regular expression to find", required = true)
            .param("regex", "boolean", "Interpret query as a regular expression")
            .param("case_sensitive", "boolean", "Use case-sensitive matching")
            .param("limit", "number", "Maximum number of matches"),

        McpToolDef("get_smali", "Get smali (Dalvik bytecode) representation of a class")
            .param("class_name", "string", "Fully qualified class name", required = true)
            .param("method_name", "string", "Method name; omit to return the complete class")
            .param("signature", "string", "Method signature for overload disambiguation"),

        McpToolDef("get_apk_metadata", "Get APK metadata including package name, version, permissions, and components"),

        McpToolDef("get_field_info", "Get detailed information about fields in a class")
            .param("class_name", "string", "Fully qualified class name", required = true),

        McpToolDef("health", "Check the health status of the decompiler instance")
    )

    override fun open(file: Path, options: EngineOptions): EngineInstance {
        val ownedCacheDir = if (options.sourceDir == null) {
            Files.createTempDirectory("jadx-server-cache-")
        } else {
            null
        }
        var decompiler: JadxDecompiler? = null
        try {
            val sourceDir = options.sourceDir ?: ownedCacheDir!!.resolve("code")
            val projectCacheLayout = sourceDir.parent.let(::ProjectCacheLayout)
            val expectedCacheState = if (options.xrefMode == XrefMode.JADX) {
                buildExpectedCacheState(file, options)
            } else {
                null
            }
            val args = JadxArgs().apply {
                inputFiles = (options.inputFiles ?: listOf(file)).map { it.toFile() }.toMutableList()
                threadsCount = resolveThreads(options.threads)
                isDeobfuscationOn = options.deobfuscate
                isSkipResources = options.skipResources
                codeCache = ProjectDiskCodeCache(sourceDir)
                usageInfoCache = when {
                    options.xrefMode != XrefMode.JADX -> EmptyUsageInfoCache()
                    expectedCacheState != null -> ProjectDiskUsageInfoCache(projectCacheLayout, expectedCacheState)
                    else -> EmptyUsageInfoCache()
                }
                outDir = sourceDir.toFile()
                if (options.classFilter != null) {
                    val filterPattern = options.classFilter
                    classFilter = java.util.function.Predicate { it.contains(filterPattern) }
                }
                if (options.pluginOptions.isNotEmpty()) {
                    pluginOptions.putAll(options.pluginOptions)
                }
            }

            decompiler = JadxDecompiler(args)
            decompiler.load()

            val classes = decompiler.classes
            val resourceList = decompiler.resources
            val apkMetadata = extractMetadata(resourceList, classes)
            val classIndex = classes.map { it.toIndexEntry() }
            val exactNameIndex = classIndex.mapIndexed { index, entry -> entry.name to index }.toMap()

            val srcOutDir = sourceDir.resolve("sources").toFile()
            srcOutDir.mkdirs()
            args.outDirSrc = srcOutDir

            val apk = DecompiledApk(
                decompiler,
                classIndex,
                exactNameIndex,
                resourceList,
                apkMetadata,
                sourceDir,
                options.xrefMode,
                ownedCacheDir
            )

            return EngineInstance(
                engineName = name,
                fileHash = file.fileName.toString(),
                state = apk
            )
        } catch (failure: Throwable) {
            try {
                decompiler?.close()
            } catch (cleanupError: Throwable) {
                if (failure !is OutOfMemoryError) {
                    logger.warn("Failed to close decompiler after open failure: {}", cleanupError.message)
                }
            }
            if (ownedCacheDir != null) {
                try {
                    deleteRecursivelyIfExists(ownedCacheDir)
                } catch (cleanupError: Throwable) {
                    if (failure !is OutOfMemoryError) {
                        logger.warn("Failed to delete temporary cache directory {} after open failure: {}", ownedCacheDir, cleanupError.message)
                    }
                }
            }
            if (failure is OutOfMemoryError) {
                System.gc()
            }
            throw failure
        }
    }

    private fun buildExpectedCacheState(file: Path, options: EngineOptions): CacheState {
        val inputPath = (options.inputFiles ?: listOf(file)).firstOrNull() ?: file
        return CacheState(
            jadxVersion = JadxDecompiler.getVersion(),
            serverBuildVersion = JadxEngine::class.java.`package`?.implementationVersion ?: "dev",
            inputHash = HashUtil.md5(inputPath),
            pluginOptionsFingerprint = pluginOptionsFingerprint(options.pluginOptions),
            deobfuscationFlags = options.deobfuscate,
            classFilter = options.classFilter,
            xrefMode = options.xrefMode.name,
        )
    }

    private fun resolveThreads(configuredThreads: Int): Int {
        if (configuredThreads > 0) {
            return configuredThreads
        }
        return maxOf(2, Runtime.getRuntime().availableProcessors() / 2).coerceAtMost(8)
    }

    private fun pluginOptionsFingerprint(pluginOptions: Map<String, String>): String {
        val canonical = pluginOptions.entries
            .sortedBy { it.key }
            .joinToString("\n") { (key, value) -> "$key=$value" }
        return MessageDigest.getInstance("MD5")
            .digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    override fun close(instance: EngineInstance) {
        val apk = instance.state as? DecompiledApk
        apk?.close()
        // Prompt GC after closing engine instance — jadx produces massive
        // temporary objects during decompilation that G1GC may not reclaim promptly.
        System.gc()
    }

    override fun unload(instance: EngineInstance): Boolean {
        val apk = instance.state as? DecompiledApk ?: return false
        val summary = apk.unloadClasses()
        logger.debug(
            "Unloaded {} classes for instance {} (generated={}, reset={})",
            summary.totalClasses,
            instance.instanceId,
            summary.generatedAndUnloadedCount,
            summary.notLoadedCount
        )
        return true
    }

    override fun health(instance: EngineInstance): InstanceHealth {
        val apk = instance.state as? DecompiledApk ?: return InstanceHealth.DEAD
        return try {
            apk.classCount()
            InstanceHealth.HEALTHY
        } catch (_: Exception) {
            InstanceHealth.DEAD
        }
    }

    private fun extractMetadata(resources: List<jadx.api.ResourceFile>, classes: List<JavaClass>): ApkMetadata {
        val manifestRes = resources.find { it.type == ResourceType.MANIFEST }
        val manifestText = try {
            manifestRes?.loadContent()?.text?.codeStr
        } catch (_: Exception) {
            null
        }

        val classCount = classes.size
        val methodCount = classes.sumOf { it.methods.size }

        if (manifestText == null) {
            return ApkMetadata("", null, null, classCount, methodCount, null, null, emptyList(), emptyList(), emptyList(), emptyList())
        }

        val packageName = extractAttr(manifestText, "package")
        val versionName = extractAttr(manifestText, "android:versionName")
        val versionCode = extractAttr(manifestText, "android:versionCode")?.toIntOrNull()
        val minSdk = extractAndroidAttrValue(manifestText, "uses-sdk", "android:minSdkVersion")?.toIntOrNull()
        val targetSdk = extractAndroidAttrValue(manifestText, "uses-sdk", "android:targetSdkVersion")?.toIntOrNull()
        val permissions = extractAndroidNames(manifestText, "uses-permission")
        val activities = extractAndroidNames(manifestText, "activity")
        val services = extractAndroidNames(manifestText, "service")
        val receivers = extractAndroidNames(manifestText, "receiver")

        return ApkMetadata(
            packageName = packageName ?: "",
            versionName = versionName,
            versionCode = versionCode,
            classCount = classCount,
            methodCount = methodCount,
            minSdk = minSdk,
            targetSdk = targetSdk,
            permissions = permissions,
            activities = activities,
            services = services,
            receivers = receivers
        )
    }

    private fun extractAttr(xml: String, attrName: String): String? {
        val regex = Regex("""$attrName\s*=\s*"([^"]*)"""")
        return regex.find(xml)?.groupValues?.get(1)
    }

    private fun extractAndroidNames(xml: String, tagName: String): List<String> {
        val regex = Regex("""<$tagName[^>]*android:name\s*=\s*"([^"]*)"""")
        return regex.findAll(xml).map { it.groupValues[1] }.toList()
    }

    private fun extractAndroidAttrValue(xml: String, tagName: String, attrName: String): String? {
        val regex = Regex("""<$tagName[^>]*$attrName\s*=\s*"([^"]*)"""")
        return regex.find(xml)?.groupValues?.get(1)
    }

    private fun deleteRecursivelyIfExists(path: Path) {
        if (!Files.exists(path)) {
            return
        }
        Files.walk(path)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }
}
