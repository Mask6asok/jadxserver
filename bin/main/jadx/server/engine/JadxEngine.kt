package jadx.server.engine

import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.ResourceType
import jadx.api.impl.NoOpCodeCache
import jadx.api.usage.impl.EmptyUsageInfoCache
import jadx.server.mcp.McpToolDef
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

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
            .param("query", "string", "String to search for within string literals", required = true)
            .param("limit", "number", "Maximum number of results"),

        McpToolDef("class_xrefs", "Find cross-references for a class (who uses it and what it uses)")
            .param("class_name", "string", "Fully qualified class name", required = true),

        McpToolDef("method_xrefs", "Find cross-references for a method (who calls it)")
            .param("class_name", "string", "Fully qualified class name", required = true)
            .param("method_name", "string", "Method name", required = true),

        McpToolDef("get_manifest", "Get the decoded AndroidManifest.xml content"),

        McpToolDef("get_resource", "Get content of a specific resource file by path")
            .param("path", "string", "Resource file path (e.g. res/values/strings.xml)", required = true),

        McpToolDef("list_resources", "List all resource files in the APK"),

        McpToolDef("get_smali", "Get smali (Dalvik bytecode) representation of a class")
            .param("class_name", "string", "Fully qualified class name", required = true),

        McpToolDef("get_apk_metadata", "Get APK metadata including package name, version, permissions, and components"),

        McpToolDef("get_field_info", "Get detailed information about fields in a class")
            .param("class_name", "string", "Fully qualified class name", required = true),

        McpToolDef("health", "Check the health status of the decompiler instance")
    )

    override fun open(file: Path, options: EngineOptions): EngineInstance {
        val args = JadxArgs().apply {
            inputFiles = mutableListOf(file.toFile())
            threadsCount = options.threads
            isDeobfuscationOn = options.deobfuscate
            isSkipResources = options.skipResources
            codeCache = NoOpCodeCache.INSTANCE
            usageInfoCache = EmptyUsageInfoCache()
            if (options.classFilter != null) {
                val filterPattern = options.classFilter
                classFilter = java.util.function.Predicate { it.contains(filterPattern) }
            }
        }

        val decompiler = JadxDecompiler(args)
        decompiler.load()

        val classMap = decompiler.classes.associateBy { it.fullName }
        val resourceList = decompiler.resources
        val apkMetadata = extractMetadata(resourceList, classMap)

        val sourceDir = options.sourceDir
        if (sourceDir != null) {
            val srcOutDir = sourceDir.resolve("sources").toFile()
            if (!srcOutDir.exists() || srcOutDir.listFiles()?.isEmpty() != false) {
                srcOutDir.mkdirs()
                args.outDirSrc = srcOutDir
                try {
                    logger.info("Saving decompiled sources to: {}", srcOutDir.absolutePath)
                    decompiler.saveSources()
                    logger.info("Sources saved successfully")
                } catch (e: Exception) {
                    logger.warn("Failed to save sources to disk, will use in-memory fallback: {}", e.message)
                }
            } else {
                logger.info("Source cache already exists at {}, skipping saveSources", srcOutDir.absolutePath)
                args.outDirSrc = srcOutDir
            }
        }

        val apk = DecompiledApk(decompiler, classMap, resourceList, apkMetadata, sourceDir)

        return EngineInstance(
            engineName = name,
            fileHash = file.fileName.toString(),
            state = apk
        )
    }

    override fun close(instance: EngineInstance) {
        val apk = instance.state as? DecompiledApk
        apk?.close()
    }

    override fun health(instance: EngineInstance): InstanceHealth {
        val apk = instance.state as? DecompiledApk ?: return InstanceHealth.DEAD
        return try {
            apk.classes.size
            InstanceHealth.HEALTHY
        } catch (_: Exception) {
            InstanceHealth.DEAD
        }
    }

    private fun extractMetadata(resources: List<jadx.api.ResourceFile>, classMap: Map<String, jadx.api.JavaClass>): ApkMetadata {
        val manifestRes = resources.find { it.type == ResourceType.MANIFEST }
        val manifestText = try {
            manifestRes?.loadContent()?.text?.codeStr
        } catch (_: Exception) {
            null
        }

        val classCount = classMap.size
        val methodCount = classMap.values.sumOf { it.methods.size }

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
}
