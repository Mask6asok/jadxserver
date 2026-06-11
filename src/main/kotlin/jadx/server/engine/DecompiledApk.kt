package jadx.server.engine

import jadx.api.JavaClass
import jadx.api.JavaField
import jadx.api.JavaMethod
import jadx.api.JadxDecompiler
import jadx.api.ResourceFile
import jadx.api.ResourceType
import jadx.core.xmlgen.ResContainer
import jadx.server.config.XrefMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class DecompiledApk(
    private val decompiler: JadxDecompiler,
    val classes: Map<String, JavaClass>,
    val resources: List<ResourceFile>,
    val metadata: ApkMetadata,
    val sourceDir: Path? = null,
    val xrefMode: XrefMode = XrefMode.JADX
) : Closeable {
    private val logger = LoggerFactory.getLogger(DecompiledApk::class.java)

    var lastAccess: Instant = Instant.now()
        private set

    private fun touch() { lastAccess = Instant.now() }

    private fun resolveMode(requestedMode: String?): XrefMode {
        if (requestedMode.equals("jadx", ignoreCase = true)) return XrefMode.JADX
        if (requestedMode.equals("text", ignoreCase = true)) return XrefMode.TEXT
        return xrefMode
    }

    fun getClassCode(className: String): String? {
        touch()
        val diskCode = readFromDisk(className)
        if (diskCode != null) return diskCode
        return classes[className]?.code
    }

    fun getClassInfo(className: String): ClassInfo? {
        touch()
        val cls = classes[className] ?: return null
        val access = cls.accessInfo
        val classNode = cls.classNode
        val superClassName = classNode.superClass?.toString()
        val interfaceNames = classNode.interfaces.map { it.toString() }
        val methods = cls.methods.map { toMethodInfo(it) }
        val fields = cls.fields.map { toFieldInfo(it) }
        val innerClasses = cls.innerClasses.map { it.fullName }
        return ClassInfo(
            name = cls.fullName,
            `package` = cls.getPackage() ?: "",
            isPublic = access.isPublic,
            isAbstract = access.isAbstract,
            isInterface = access.isInterface,
            isEnum = access.isEnum,
            superclass = superClassName,
            interfaces = interfaceNames,
            accessFlags = buildAccessFlagsString(access),
            methods = methods,
            fields = fields,
            innerClasses = innerClasses
        )
    }

    fun listClasses(filter: String? = null, offset: Int = 0, count: Int = 100): List<ClassSummary> {
        touch()
        val filtered = if (filter != null) {
            classes.values.filter { it.fullName.contains(filter, ignoreCase = true) }
        } else {
            classes.values.toList()
        }
        return filtered
            .drop(offset)
            .take(count)
            .map { cls -> toClassSummary(cls) }
    }

    fun findClass(filter: String, limit: Int = 100): List<ClassSummary> {
        touch()
        return classes.values
            .filter { it.fullName.contains(filter, ignoreCase = true) || it.name.contains(filter, ignoreCase = true) }
            .take(limit)
            .map { cls -> toClassSummary(cls) }
    }

    fun getMethodCode(className: String, methodName: String, signature: String? = null): String? {
        touch()
        val cls = classes[className] ?: return null
        val method = findMethod(cls, methodName, signature) ?: return null
        return method.codeStr
    }

    fun listMethods(className: String): List<MethodInfo>? {
        touch()
        val cls = classes[className] ?: return null
        return cls.methods.map { toMethodInfo(it) }
    }

    fun searchCode(query: String, limit: Int = 100): List<SearchMatch> {
        touch()
        val results = mutableListOf<SearchMatch>()
        for ((className, _) in classes) {
            if (results.size >= limit) break
            val code = getClassCode(className) ?: continue
            code.lines().forEachIndexed { index, line ->
                if (results.size >= limit) return@forEachIndexed
                if (line.contains(query, ignoreCase = true)) {
                    results.add(SearchMatch(className, index + 1, line.trim()))
                }
            }
        }
        return results
    }

    fun searchString(query: String, limit: Int = 100): List<SearchMatch> {
        touch()
        val pattern = Regex("\"[^\"]*${Regex.escape(query)}[^\"]*\"")
        val results = mutableListOf<SearchMatch>()
        for ((className, _) in classes) {
            if (results.size >= limit) break
            val code = getClassCode(className) ?: continue
            code.lines().forEachIndexed { index, line ->
                if (results.size >= limit) return@forEachIndexed
                if (pattern.containsMatchIn(line)) {
                    results.add(SearchMatch(className, index + 1, line.trim()))
                }
            }
        }
        return results
    }

    fun getClassXrefs(className: String, limit: Int = 100, mode: String? = null): List<XrefMatch> {
        touch()
        val resolvedMode = resolveMode(mode)
        if (resolvedMode == XrefMode.JADX) {
            return getClassXrefsJadx(className, limit)
        }
        val targetCls = classes[className] ?: return emptyList()
        val simpleName = targetCls.name
        val results = mutableListOf<XrefMatch>()

        for ((otherName, otherCls) in classes) {
            if (otherName == className) continue
            if (results.size >= limit) break
            val code = getClassCode(otherName) ?: continue
            code.lines().forEachIndexed { index, line ->
                if (results.size >= limit) return@forEachIndexed
                if (line.contains(simpleName) || line.contains(className)) {
                    results.add(XrefMatch(otherName, "", index + 1))
                }
            }
        }
        return results
    }

    private fun getClassXrefsJadx(className: String, limit: Int): List<XrefMatch> {
        readXrefCache("class", className, limit)?.let { return it }
        val cls = classes[className] ?: return emptyList()
        val useIn = try { cls.getUseIn() } catch (_: Exception) { return emptyList() }
        val results = useIn.take(limit).mapNotNull { node ->
            when (node) {
                is JavaClass -> XrefMatch(node.fullName, "", 0)
                is JavaMethod -> XrefMatch(node.declaringClass.fullName, node.name, 0)
                else -> null
            }
        }
        writeXrefCache("class", className, results)
        return results
    }

    fun getMethodXrefs(className: String, methodName: String, direction: String = "both", limit: Int = 100, mode: String? = null): List<XrefMatch> {
        touch()
        val resolvedMode = resolveMode(mode)
        if (resolvedMode == XrefMode.JADX) {
            return getMethodXrefsJadx(className, methodName, direction, limit)
        }
        val results = mutableListOf<XrefMatch>()
        val fullRef = "$className.$methodName"

        if (direction == "from" || direction == "both") {
            val cls = classes[className] ?: return emptyList()
            for (m in cls.methods) {
                if (results.size >= limit) break
                val code = getClassCode(className) ?: continue
                for ((idx, line) in code.lines().withIndex()) {
                    if (results.size >= limit) break
                    if (idx > 0 && line.contains(methodName)) {
                        results.add(XrefMatch(className, m.name, idx + 1))
                    }
                }
            }
        }

        if (direction == "to" || direction == "both") {
            for ((otherName, _) in classes) {
                if (results.size >= limit) break
                if (otherName == className) continue
                val code = getClassCode(otherName) ?: continue
                code.lines().forEachIndexed { index, line ->
                    if (results.size >= limit) return@forEachIndexed
                    if (line.contains(fullRef) || line.contains(methodName)) {
                        results.add(XrefMatch(otherName, "", index + 1))
                    }
                }
            }
        }
        return results
    }

    private fun getMethodXrefsJadx(className: String, methodName: String, direction: String, limit: Int): List<XrefMatch> {
        val cacheKey = "$className#$methodName#$direction"
        readXrefCache("method", cacheKey, limit)?.let { return it }
        val cls = classes[className] ?: return emptyList()
        val results = mutableListOf<XrefMatch>()

        if (direction == "to" || direction == "both") {
            val methods = cls.methods.filter { it.name == methodName }
            for (m in methods) {
                if (results.size >= limit) break
                val useIn = try { m.getUseIn() } catch (_: Exception) { emptyList() }
                for (caller in useIn) {
                    if (results.size >= limit) break
                    when (caller) {
                        is JavaMethod -> results.add(XrefMatch(caller.declaringClass.fullName, caller.name, 0))
                        is JavaClass -> results.add(XrefMatch(caller.fullName, "", 0))
                        else -> {}
                    }
                }
            }
        }

        if (direction == "from" || direction == "both") {
            val methods = cls.methods.filter { it.name == methodName }
            for (m in methods) {
                if (results.size >= limit) break
                val used = try { m.getUsed() } catch (_: Exception) { emptySet() }
                for (callee in used) {
                    if (results.size >= limit) break
                    results.add(XrefMatch(callee.declaringClass.fullName, callee.name, 0))
                }
            }
        }

        writeXrefCache("method", cacheKey, results)
        return results
    }

    fun getManifest(): String? {
        touch()
        val manifestRes = resources.find { it.type == ResourceType.MANIFEST } ?: return null
        return extractResourceText(manifestRes)
    }

    fun getResource(path: String): String? {
        touch()
        val res = resources.find {
            it.originalName == path || it.deobfName == path
        } ?: return null
        return extractResourceText(res)
    }

    fun listResources(): List<ResourceInfo> {
        touch()
        return resources.map { ResourceInfo(it.originalName, it.type.name, it.originalName) }
    }

    override fun close() {
        decompiler.close()
    }

    // --- Disk-based source cache helpers ---

    private fun readFromDisk(className: String): String? {
        val dir = sourceDir ?: return null
        val srcDir = dir.resolve("sources")
        val javaFile = srcDir.resolve(className.replace('.', '/') + ".java")
        if (!Files.exists(javaFile)) return null
        return try {
            Files.readString(javaFile)
        } catch (_: Exception) {
            null
        }
    }

    private fun readXrefCache(kind: String, key: String, limit: Int): List<XrefMatch>? {
        val cacheFile = xrefCacheFile(kind, key) ?: return null
        if (!Files.exists(cacheFile)) return null
        return try {
            val data = xrefJson.decodeFromString<XrefCacheFile>(Files.readString(cacheFile))
            data.results.take(limit)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeXrefCache(kind: String, key: String, results: List<XrefMatch>) {
        val cacheFile = xrefCacheFile(kind, key) ?: return
        try {
            Files.createDirectories(cacheFile.parent)
            Files.writeString(cacheFile, xrefJson.encodeToString(XrefCacheFile(results)))
        } catch (_: Exception) {
        }
    }

    private fun xrefCacheFile(kind: String, key: String): Path? {
        val dir = sourceDir ?: return null
        val safeName = buildString {
            key.forEach { ch ->
                append(if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-') ch else '_')
            }
        }
        return dir.resolve("usage").resolve(kind).resolve("$safeName.json")
    }

    // --- Private helpers ---

    private fun toClassSummary(cls: JavaClass): ClassSummary {
        val access = cls.accessInfo
        return ClassSummary(
            name = cls.fullName,
            `package` = cls.getPackage() ?: "",
            isPublic = access.isPublic,
            isAbstract = access.isAbstract,
            isInterface = access.isInterface,
            isInner = cls.isInner,
            superClass = cls.classNode.superClass?.toString(),
            methodCount = cls.methods.size,
            fieldCount = cls.fields.size,
            innerClassCount = cls.innerClasses.size
        )
    }

    private fun findMethod(cls: JavaClass, methodName: String, signature: String?): JavaMethod? {
        val methods = cls.methods
        if (signature != null) {
            return methods.find { m ->
                m.name == methodName && buildSignature(m) == signature
            }
        }
        return methods.find { it.name == methodName }
    }

    private fun buildSignature(method: JavaMethod): String {
        val params = method.arguments.joinToString(", ") { it.toString() }
        return "${method.returnType} ${method.name}($params)"
    }

    private fun toMethodInfo(m: JavaMethod): MethodInfo {
        val access = m.accessFlags
        val paramList = m.arguments
        val params = paramList.joinToString(", ") { it.toString() }
        val sig = "${m.returnType} ${m.name}($params)"
        return MethodInfo(
            name = m.name,
            signature = sig,
            returnType = m.returnType.toString(),
            parameters = params,
            paramCount = paramList.size,
            isPublic = access.isPublic,
            isStatic = access.isStatic
        )
    }

    private fun toFieldInfo(f: JavaField): FieldInfo {
        val access = f.accessFlags
        return FieldInfo(
            name = f.name,
            type = f.type.toString(),
            isStatic = access.isStatic,
            isFinal = access.isFinal,
            accessFlags = buildAccessFlagsString(access)
        )
    }

    private fun buildAccessFlagsString(access: jadx.core.dex.info.AccessInfo): String {
        val flags = mutableListOf<String>()
        if (access.isPublic) flags.add("public")
        if (access.isProtected) flags.add("protected")
        if (access.isPrivate) flags.add("private")
        if (access.isStatic) flags.add("static")
        if (access.isAbstract) flags.add("abstract")
        if (access.isFinal) flags.add("final")
        if (access.isInterface) flags.add("interface")
        if (access.isEnum) flags.add("enum")
        return flags.joinToString(" ")
    }

    private fun extractResourceText(res: ResourceFile): String? {
        return try {
            val container = res.loadContent() ?: return null
            extractContainerText(container)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractContainerText(container: ResContainer): String? {
        return when (container.dataType) {
            ResContainer.DataType.TEXT -> container.text.codeStr
            ResContainer.DataType.RES_TABLE -> {
                val subTexts = container.subFiles.mapNotNull { extractContainerText(it) }
                if (subTexts.isEmpty()) {
                    try { container.text.codeStr } catch (_: Exception) { null }
                } else {
                    subTexts.joinToString("\n")
                }
            }
            else -> null
        }
    }
}

data class ApkMetadata(
    val packageName: String,
    val versionName: String?,
    val versionCode: Int?,
    val classCount: Int,
    val methodCount: Int,
    val minSdk: Int?,
    val targetSdk: Int?,
    val permissions: List<String>,
    val activities: List<String>,
    val services: List<String>,
    val receivers: List<String>
)

data class ClassSummary(
    val name: String,
    val `package`: String,
    val isPublic: Boolean,
    val isAbstract: Boolean,
    val isInterface: Boolean,
    val isInner: Boolean,
    val superClass: String?,
    val methodCount: Int,
    val fieldCount: Int,
    val innerClassCount: Int
)

data class ClassInfo(
    val name: String,
    val `package`: String,
    val isPublic: Boolean,
    val isAbstract: Boolean,
    val isInterface: Boolean,
    val isEnum: Boolean,
    val superclass: String?,
    val interfaces: List<String>,
    val accessFlags: String,
    val methods: List<MethodInfo>,
    val fields: List<FieldInfo>,
    val innerClasses: List<String>
)

data class MethodInfo(
    val name: String,
    val signature: String,
    val returnType: String,
    val parameters: String,
    val paramCount: Int,
    val isPublic: Boolean,
    val isStatic: Boolean
)

data class FieldInfo(
    val name: String,
    val type: String,
    val isStatic: Boolean,
    val isFinal: Boolean,
    val accessFlags: String
)

data class SearchMatch(
    val className: String,
    val line: Int,
    val content: String
)

@Serializable
data class XrefMatch(
    val className: String,
    val methodName: String,
    val line: Int
)

data class ResourceInfo(
    val name: String,
    val type: String,
    val path: String
)

@Serializable
private data class XrefCacheFile(
    val results: List<XrefMatch>
)

private val xrefJson = Json {
    prettyPrint = false
    ignoreUnknownKeys = true
}
