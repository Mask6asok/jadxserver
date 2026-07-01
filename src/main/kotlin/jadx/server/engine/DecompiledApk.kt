package jadx.server.engine

import jadx.api.JavaClass
import jadx.api.JavaField
import jadx.api.JavaMethod
import jadx.api.JavaNode
import jadx.api.JadxDecompiler
import jadx.api.ResourceFile
import jadx.api.ResourceType
import jadx.api.ICodeInfo
import jadx.api.utils.CodeUtils
import jadx.core.dex.nodes.ProcessState
import jadx.core.utils.tasks.TaskExecutor
import jadx.core.xmlgen.ResContainer
import jadx.server.config.XrefMode
import jadx.server.server.ProjectCacheLayout
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Comparator
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DecompiledApk internal constructor(
    private val decompiler: JadxDecompiler,
    private val classIndex: List<ClassIndexEntry>,
    private val exactNameIndex: Map<String, Int>,
    val resources: List<ResourceFile>,
    val metadata: ApkMetadata,
    val sourceDir: Path? = null,
    val xrefMode: XrefMode = XrefMode.JADX,
    private val ownedCacheDir: Path? = null
) : Closeable {
    private val logger = LoggerFactory.getLogger(DecompiledApk::class.java)

    var lastAccess: Instant = Instant.now()
        private set

    private val resolvedClassCache = object : LinkedHashMap<String, JavaClass>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JavaClass>?): Boolean = size > RESOLVED_CLASS_CACHE_CAPACITY
    }
    @Volatile
    private var fullCodeCacheWarm = false

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
        return resolveClass(className)?.code
    }

    fun getClassInfo(className: String): ClassInfo? {
        touch()
        val cls = resolveClass(className) ?: return null
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
            classIndex.filter { it.name.contains(filter, ignoreCase = true) }
        } else {
            classIndex
        }
        return filtered
            .drop(offset)
            .take(count)
            .map { it.toSummary() }
    }

    fun findClass(filter: String, limit: Int = 100): List<ClassSummary> {
        touch()
        return classIndex.asSequence()
            .filter { it.name.contains(filter, ignoreCase = true) || it.simpleName.contains(filter, ignoreCase = true) }
            .take(limit)
            .map { it.toSummary() }
            .toList()
    }

    fun getMethodCode(className: String, methodName: String, signature: String? = null): String? {
        touch()
        val cls = resolveClass(className) ?: return null
        val method = findMethod(cls, methodName, signature) ?: return null
        return method.codeStr
    }

    fun getSmali(className: String, methodName: String? = null, signature: String? = null): String? {
        touch()
        val cls = resolveClass(className) ?: return null
        val smali = cls.smali
        if (methodName == null) return smali

        val method = findMethod(cls, methodName, signature) ?: return null
        return extractSmaliMethod(smali, method.methodNode.methodInfo.shortId)
    }

    fun listMethods(className: String): List<MethodInfo>? {
        touch()
        val cls = resolveClass(className) ?: return null
        return cls.methods.map { toMethodInfo(it) }
    }

    fun searchCode(query: String, limit: Int = 100): List<SearchMatch> {
        touch()
        val regex = query.toRegexOrNull()
        if (sourceDir != null) {
            warmupCodeCache()
            return searchCachedCode(limit) { line ->
                matchesSearchQuery(line, query, regex)
            }
        }
        return runFullCodeScan {
            val results = mutableListOf<SearchMatch>()
            for ((processed, className) in classNames().withIndex()) {
                if (results.size >= limit) break
                guardFullScanMemory(processed)
                val code = getClassCode(className) ?: continue
                for ((index, line) in code.lineSequence().withIndex()) {
                    if (results.size >= limit) break
                    if (matchesSearchQuery(line, query, regex)) {
                        results.add(SearchMatch(className, index + 1, line.trim()))
                    }
                }
            }
            results
        }
    }

    fun searchString(query: String, limit: Int = 100): List<SearchMatch> {
        touch()
        val regex = query.toRegexOrNull()
        if (sourceDir != null) {
            warmupCodeCache()
            val literalPattern = Regex("\"([^\"]*)\"")
            return searchCachedCode(limit) { line ->
                matchesStringLiteralQuery(line, query, regex, literalPattern)
            }
        }
        return runFullCodeScan {
            val literalPattern = Regex("\"([^\"]*)\"")
            val results = mutableListOf<SearchMatch>()
            for ((processed, className) in classNames().withIndex()) {
                if (results.size >= limit) break
                guardFullScanMemory(processed)
                val code = getClassCode(className) ?: continue
                for ((index, line) in code.lineSequence().withIndex()) {
                    if (results.size >= limit) break
                    if (matchesStringLiteralQuery(line, query, regex, literalPattern)) {
                        results.add(SearchMatch(className, index + 1, line.trim()))
                    }
                }
            }
            results
        }
    }

    fun warmupCodeCache(): CodeCacheWarmup {
        touch()
        val classes = decompiler.classes
            .asSequence()
            .filter { !it.isInner && !it.isNoCode }
            .toList()
        if (classes.isEmpty()) {
            fullCodeCacheWarm = true
            return CodeCacheWarmup(totalClasses = 0, generatedClasses = 0, cachedClasses = 0, failedClasses = 0)
        }

        val codeCache = decompiler.args.codeCache
        if (fullCodeCacheWarm) {
            val cachedCount = classes.count { cls ->
                runCatching { codeCache.contains(cls.rawName) }.getOrDefault(false)
            }
            if (cachedCount == classes.size) {
                return CodeCacheWarmup(
                    totalClasses = classes.size,
                    generatedClasses = 0,
                    cachedClasses = cachedCount,
                    failedClasses = 0
                )
            }
            fullCodeCacheWarm = false
        }

        val generatedCount = AtomicInteger(0)
        val cachedCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        val batches = try {
            decompiler.decompileScheduler.buildBatches(classes)
        } catch (e: Exception) {
            logger.warn("Failed to build JADX decompile batches, falling back to one linear batch: {}", e.message)
            listOf(classes)
        }
        val jobs = batches.map { batch ->
            Runnable {
                for (cls in batch) {
                    try {
                        if (codeCache.contains(cls.rawName)) {
                            cachedCount.incrementAndGet()
                        } else {
                            cls.decompile()
                            generatedCount.incrementAndGet()
                        }
                    } catch (e: OutOfMemoryError) {
                        throw e
                    } catch (e: Throwable) {
                        failedCount.incrementAndGet()
                        logger.debug("Failed to warm source cache for {}", cls.fullName, e)
                    }
                }
            }
        }

        val executor = TaskExecutor()
        executor.setThreadsCount(resolveWorkerThreads())
        executor.addParallelTasks(jobs)
        try {
            executor.execute()
            executor.awaitTermination()
        } finally {
            unloadClasses()
            System.gc()
        }

        fullCodeCacheWarm = failedCount.get() == 0
        return CodeCacheWarmup(
            totalClasses = classes.size,
            generatedClasses = generatedCount.get(),
            cachedClasses = cachedCount.get(),
            failedClasses = failedCount.get()
        )
    }

    fun getClassXrefs(className: String, limit: Int = 100, mode: String? = null): List<XrefMatch> {
        touch()
        val resolvedMode = resolveMode(mode)
        if (resolvedMode == XrefMode.JADX) {
            return getClassXrefsJadx(className, limit)
        }
        val targetCls = resolveClass(className) ?: return emptyList()
        val simpleName = targetCls.name
        val results = mutableListOf<XrefMatch>()

        return runFullCodeScan {
            for ((processed, otherName) in classNames().withIndex()) {
                if (otherName == className) continue
                if (results.size >= limit) break
                guardFullScanMemory(processed)
                val code = getClassCode(otherName) ?: continue
                for ((index, line) in code.lineSequence().withIndex()) {
                    if (results.size >= limit) break
                    if (line.contains(simpleName) || line.contains(className)) {
                        results.add(XrefMatch(otherName, "", index + 1))
                    }
                }
            }
            results
        }
    }

    private fun getClassXrefsJadx(className: String, limit: Int): List<XrefMatch> {
        val cls = resolveClass(className) ?: return emptyList()
        return runLocatedUsageScan {
            val results = mutableListOf<XrefMatch>()
            val seen = linkedSetOf<String>()
            appendLocatedUsage(results, seen, cls, safeUseIn(cls), limit)
            for (mth in cls.methods) {
                if (results.size >= limit) break
                if (mth.isConstructor) {
                    appendLocatedUsage(results, seen, mth, safeUseIn(mth), limit)
                }
            }
            results
        }
    }

    fun getMethodXrefs(className: String, methodName: String, direction: String = "both", limit: Int = 100, mode: String? = null): List<XrefMatch> {
        touch()
        val resolvedMode = resolveMode(mode)
        if (resolvedMode == XrefMode.JADX) {
            return getMethodXrefsJadx(className, methodName, direction, limit)
        }
        return runFullCodeScan {
            val results = mutableListOf<XrefMatch>()
            val fullRef = "$className.$methodName"

            if (direction == "from" || direction == "both") {
                val cls = resolveClass(className) ?: return@runFullCodeScan emptyList()
                val code = getClassCode(className) ?: return@runFullCodeScan emptyList()
                for (m in cls.methods) {
                    if (results.size >= limit) break
                    for ((idx, line) in code.lineSequence().withIndex()) {
                        if (results.size >= limit) break
                        if (idx > 0 && line.contains(methodName)) {
                            results.add(XrefMatch(className, m.name, idx + 1))
                        }
                    }
                }
            }

            if (direction == "to" || direction == "both") {
                for ((processed, otherName) in classNames().withIndex()) {
                    if (results.size >= limit) break
                    if (otherName == className) continue
                    guardFullScanMemory(processed)
                    val code = getClassCode(otherName) ?: continue
                    for ((index, line) in code.lineSequence().withIndex()) {
                        if (results.size >= limit) break
                        if (line.contains(fullRef) || line.contains(methodName)) {
                            results.add(XrefMatch(otherName, "", index + 1))
                        }
                    }
                }
            }
            results
        }
    }

    private fun getMethodXrefsJadx(className: String, methodName: String, direction: String, limit: Int): List<XrefMatch> {
        val cls = resolveClass(className) ?: return emptyList()
        return runLocatedUsageScan {
            val results = mutableListOf<XrefMatch>()
            val seen = linkedSetOf<String>()

            if (direction == "to" || direction == "both") {
                val methods = cls.methods.filter { it.name == methodName }
                    .flatMap { methodWithOverrides(it) }
                    .distinctBy { it.fullName }
                for (m in methods) {
                    if (results.size >= limit) break
                    appendLocatedUsage(results, seen, m, safeUseIn(m), limit)
                }
            }

            if (direction == "from" || direction == "both") {
                val methods = cls.methods.filter { it.name == methodName }
                for (m in methods) {
                    if (results.size >= limit) break
                    val used = try { m.getUsed() } catch (_: Exception) { emptySet() }
                    for (callee in used) {
                        if (results.size >= limit) break
                        if (callee !is JavaMethod) continue
                        appendLocatedCalleeUsage(results, seen, caller = m, callee = callee, limit = limit)
                    }
                }
            }

            results
        }
    }

    fun getManifest(): String? {
        touch()
        val manifestRes = resources.find { it.type == ResourceType.MANIFEST } ?: return null
        return extractResourceText(manifestRes)
    }

    fun getResource(path: String): String? {
        touch()
        val normalizedPath = path.removePrefix("/")
        val res = resources.find {
            it.originalName.removePrefix("/") == normalizedPath || it.deobfName.removePrefix("/") == normalizedPath
        }
        if (res != null) return extractResourceText(res)

        for (resource in resources) {
            if (resource.type != ResourceType.ARSC) continue
            val container = try { resource.loadContent() } catch (_: Exception) { null } ?: continue
            findContainerText(container, normalizedPath)?.let { return it }
        }
        return null
    }

    fun listResources(): List<ResourceInfo> {
        touch()
        return resources.map { ResourceInfo(it.originalName, it.type.name, it.originalName) }
    }

    fun searchResources(
        query: String,
        limit: Int = 100,
        regex: Regex? = null,
        caseSensitive: Boolean = false
    ): List<ResourceSearchMatch> {
        touch()
        val results = mutableListOf<ResourceSearchMatch>()
        for (resource in resources) {
            if (results.size >= limit) break
            val container = try { resource.loadContent() } catch (_: Exception) { null } ?: continue
            searchResourceContainer(
                container = container,
                resourceType = resource.type.name,
                query = query,
                regex = regex,
                caseSensitive = caseSensitive,
                limit = limit,
                results = results
            )
        }
        return results
    }

    fun resolveClass(className: String): JavaClass? {
        touch()
        val classSlot = exactNameIndex[className] ?: return null
        synchronized(resolvedClassCache) {
            resolvedClassCache[className]?.let { return it }
        }
        val indexedName = classIndex[classSlot].name
        val resolved = decompiler.searchJavaClassByAliasFullName(indexedName)
            ?: decompiler.searchJavaClassByOrigFullName(indexedName)
            ?: return null
        synchronized(resolvedClassCache) {
            resolvedClassCache[className] = resolved
        }
        return resolved
    }

    internal fun classCount(): Int = classIndex.size

    internal fun hasExactClassName(className: String): Boolean = exactNameIndex.containsKey(className)

    internal fun resolvedCacheKeysForTest(): List<String> = synchronized(resolvedClassCache) { resolvedClassCache.keys.toList() }

    internal fun unloadClasses(): UnloadSummary {
        touch()
        clearResolvedClassCache()
        var generatedAndUnloadedCount = 0
        var notLoadedCount = 0
        val classes = decompiler.root.getClasses()
        for (cls in classes) {
            val state = cls.state
            cls.unload()
            if (state == ProcessState.PROCESS_COMPLETE) {
                cls.state = ProcessState.GENERATED_AND_UNLOADED
                generatedAndUnloadedCount++
            } else {
                cls.state = ProcessState.NOT_LOADED
                notLoadedCount++
            }
        }
        clearResolvedClassCache()
        logger.debug(
            "Unloaded class internals for {} classes (generated={}, reset={})",
            classes.size,
            generatedAndUnloadedCount,
            notLoadedCount
        )
        return UnloadSummary(classes.size, generatedAndUnloadedCount, notLoadedCount)
    }

    override fun close() {
        try {
            decompiler.close()
        } finally {
            ownedCacheDir?.let { deleteRecursivelyIfExists(it) }
        }
    }

    private fun clearResolvedClassCache() {
        synchronized(resolvedClassCache) {
            resolvedClassCache.clear()
        }
    }

    private fun deleteRecursivelyIfExists(path: Path) {
        if (!Files.exists(path)) {
            return
        }
        try {
            Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        } catch (e: Exception) {
            logger.warn("Failed to delete owned cache directory {}: {}", path, e.message)
        }
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

    // --- Private helpers ---

    private fun classNames(): Sequence<String> = classIndex.asSequence().map { it.name }

    private fun sourceClasses(): List<SourceClass> {
        val dir = sourceDir ?: return emptyList()
        val layout = ProjectCacheLayout(dir.parent)
        return decompiler.classes
            .asSequence()
            .filter { !it.isInner && !it.isNoCode }
            .map { SourceClass(it.fullName, layout.sourceFileFor(it.rawName)) }
            .toList()
    }

    private fun searchCachedCode(limit: Int, lineMatches: (String) -> Boolean): List<SearchMatch> {
        val classes = sourceClasses()
        if (classes.isEmpty()) {
            return emptyList()
        }
        val results = Collections.synchronizedList(mutableListOf<SearchMatch>())
        val nextIndex = AtomicInteger(0)
        val done = AtomicBoolean(false)
        val workerCount = resolveWorkerThreads().coerceAtMost(classes.size)
        val executor = Executors.newFixedThreadPool(workerCount) { runnable ->
            Thread(runnable, "jadx-search-worker").apply { isDaemon = true }
        }
        repeat(workerCount) {
            executor.execute {
                while (!done.get()) {
                    val index = nextIndex.getAndIncrement()
                    if (index >= classes.size) {
                        return@execute
                    }
                    scanSourceFile(classes[index], limit, lineMatches, results, done)
                }
            }
        }
        executor.shutdown()
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
        return results.take(limit)
    }

    private fun scanSourceFile(
        source: SourceClass,
        limit: Int,
        lineMatches: (String) -> Boolean,
        results: MutableList<SearchMatch>,
        done: AtomicBoolean
    ) {
        if (!Files.exists(source.file)) {
            return
        }
        try {
            Files.newBufferedReader(source.file).use { reader ->
                var lineNo = 0
                while (!done.get()) {
                    val line = reader.readLine() ?: break
                    lineNo++
                    if (!lineMatches(line)) {
                        continue
                    }
                    val reachedLimit = synchronized(results) {
                        if (results.size >= limit) {
                            true
                        } else {
                            results.add(SearchMatch(source.className, lineNo, line.trim()))
                            results.size >= limit
                        }
                    }
                    if (reachedLimit) {
                        done.set(true)
                        return
                    }
                }
            }
        } catch (e: Exception) {
            logger.debug("Failed to scan cached source {}", source.file, e)
        }
    }

    private fun resolveWorkerThreads(): Int {
        val configured = runCatching { decompiler.args.threadsCount }.getOrDefault(0)
        if (configured > 0) {
            return configured
        }
        return maxOf(2, Runtime.getRuntime().availableProcessors() / 2).coerceAtMost(8)
    }

    private fun String.toRegexOrNull(): Regex? = runCatching {
        Regex(this, RegexOption.IGNORE_CASE)
    }.getOrNull()

    private fun matchesSearchQuery(line: String, query: String, regex: Regex?): Boolean {
        if (regex != null) {
            return regex.containsMatchIn(line)
        }
        return line.contains(query, ignoreCase = true)
    }

    private fun matchesStringLiteralQuery(line: String, query: String, regex: Regex?, literalPattern: Regex): Boolean {
        for (match in literalPattern.findAll(line)) {
            val literal = match.groupValues[1]
            if (regex != null) {
                if (regex.containsMatchIn(literal)) return true
            } else if (literal.contains(query, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun <T> runFullCodeScan(block: () -> T): T {
        return try {
            block()
        } finally {
            unloadClasses()
            System.gc()
        }
    }

    private fun <T> runLocatedUsageScan(block: () -> T): T {
        return try {
            block()
        } finally {
            unloadClasses()
            System.gc()
        }
    }

    private fun appendLocatedUsage(
        results: MutableList<XrefMatch>,
        seen: MutableSet<String>,
        searchNode: JavaNode,
        useNodes: List<JavaNode>,
        limit: Int
    ) {
        val useClasses = useNodes
            .mapNotNull { runCatching { it.topParentClass }.getOrNull() }
            .distinctBy { it.fullName }
        for (topUseClass in useClasses) {
            if (results.size >= limit) break
            val matches = locateUsePlaces(
                searchNode = searchNode,
                topUseClass = topUseClass,
                resultClassName = null,
                resultMethodName = null,
            )
            if (matches.isEmpty()) {
                val fallback = fallbackXrefForUseNode(topUseClass)
                appendUnique(results, seen, fallback, limit)
                continue
            }
            for (match in matches) {
                appendUnique(results, seen, match, limit)
                if (results.size >= limit) break
            }
        }
    }

    private fun appendLocatedCalleeUsage(
        results: MutableList<XrefMatch>,
        seen: MutableSet<String>,
        caller: JavaMethod,
        callee: JavaMethod,
        limit: Int
    ) {
        val topUseClass = caller.topParentClass
        val matches = locateUsePlaces(
            searchNode = callee,
            topUseClass = topUseClass,
            resultClassName = callee.declaringClass.fullName,
            resultMethodName = callee.name,
        ).filter { it.sourceMethodName.isBlank() || it.sourceMethodName == caller.name }
        if (matches.isEmpty()) {
            appendUnique(
                results,
                seen,
                XrefMatch(
                    className = callee.declaringClass.fullName,
                    methodName = callee.name,
                    line = 0,
                    sourceClassName = caller.declaringClass.fullName,
                    sourceMethodName = caller.name,
                ),
                limit
            )
            return
        }
        for (match in matches) {
            appendUnique(results, seen, match, limit)
            if (results.size >= limit) break
        }
    }

    private fun locateUsePlaces(
        searchNode: JavaNode,
        topUseClass: JavaClass,
        resultClassName: String?,
        resultMethodName: String?,
    ): List<XrefMatch> {
        return try {
            val codeInfo = topUseClass.codeInfo
            val code = codeInfo.codeStr
            val positions = topUseClass.getUsePlacesFor(codeInfo, searchNode)
            positions.mapNotNull { pos ->
                val content = CodeUtils.getLineForPos(code, pos).trim()
                if (content.startsWith("import ")) {
                    return@mapNotNull null
                }
                val enclosingNode = resolveEnclosingNode(codeInfo, pos)
                val sourceMethodName = (enclosingNode as? JavaMethod)?.name.orEmpty()
                XrefMatch(
                    className = resultClassName ?: topUseClass.fullName,
                    methodName = resultMethodName ?: sourceMethodName,
                    line = lineNumberForPos(code, pos),
                    sourceClassName = topUseClass.fullName,
                    sourceMethodName = sourceMethodName,
                    content = content,
                )
            }
        } catch (e: Exception) {
            logger.debug("Failed to locate xref usage for {} in {}", searchNode.fullName, topUseClass.fullName, e)
            emptyList()
        }
    }

    private fun fallbackXrefForUseNode(topUseClass: JavaClass): XrefMatch {
        return XrefMatch(
            className = topUseClass.fullName,
            methodName = "",
            line = 0,
            sourceClassName = topUseClass.fullName,
            sourceMethodName = "",
            content = "",
        )
    }

    private fun appendUnique(results: MutableList<XrefMatch>, seen: MutableSet<String>, match: XrefMatch, limit: Int) {
        if (results.size >= limit) {
            return
        }
        val key = listOf(match.className, match.methodName, match.line, match.sourceClassName, match.sourceMethodName).joinToString("|")
        if (seen.add(key)) {
            results.add(match)
        }
    }

    private fun methodWithOverrides(method: JavaMethod): List<JavaMethod> {
        val related = try { method.overrideRelatedMethods } catch (_: Exception) { emptyList() }
        return if (related.isEmpty()) listOf(method) else related
    }

    private fun safeUseIn(node: JavaNode): List<JavaNode> {
        return try { node.useIn } catch (_: Exception) { emptyList() }
    }

    private fun resolveEnclosingNode(codeInfo: ICodeInfo, pos: Int): JavaNode? {
        return try { decompiler.getEnclosingNode(codeInfo, pos) } catch (_: Exception) { null }
    }

    private fun lineNumberForPos(code: String, pos: Int): Int {
        val end = pos.coerceIn(0, code.length)
        var line = 1
        for (i in 0 until end) {
            if (code[i] == '\n') {
                line++
            }
        }
        return line
    }

    private fun guardFullScanMemory(processedClassCount: Int) {
        if (processedClassCount == 0 || processedClassCount % FULL_SCAN_UNLOAD_BATCH != 0) {
            return
        }
        unloadClasses()
        if (isFreeMemoryAvailable()) {
            return
        }
        System.gc()
        if (!isFreeMemoryAvailable()) {
            throw IllegalStateException("Full code scan stopped because free heap memory is below the safe threshold")
        }
    }

    private fun isFreeMemoryAvailable(): Boolean {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val totalFree = runtime.freeMemory() + (maxMemory - runtime.totalMemory())
        val minFree = (maxMemory * MIN_FREE_MEMORY_RATIO).toLong().coerceAtMost(MAX_MIN_FREE_MEMORY_BYTES)
        return totalFree > minFree
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

    private fun extractSmaliMethod(smali: String, shortId: String): String? {
        val lines = smali.lines()
        val start = lines.indexOfFirst { line ->
            val declaration = line.trim()
            declaration.startsWith(".method ") && declaration.substringAfter(".method ").endsWith(shortId)
        }
        if (start == -1) return null

        val end = (start + 1 until lines.size).firstOrNull { lines[it].trim() == ".end method" }
            ?: (lines.size - 1)
        return lines.subList(start, end + 1).joinToString("\n")
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

    private fun findContainerText(container: ResContainer, path: String): String? {
        if (container.name.removePrefix("/") == path) {
            return when (container.dataType) {
                ResContainer.DataType.TEXT, ResContainer.DataType.RES_TABLE -> container.text.codeStr
                else -> null
            }
        }
        for (subFile in container.subFiles) {
            findContainerText(subFile, path)?.let { return it }
        }
        return null
    }

    private fun searchResourceContainer(
        container: ResContainer,
        resourceType: String,
        query: String,
        regex: Regex?,
        caseSensitive: Boolean,
        limit: Int,
        results: MutableList<ResourceSearchMatch>
    ) {
        if (results.size >= limit) return
        if (container.dataType == ResContainer.DataType.TEXT || container.dataType == ResContainer.DataType.RES_TABLE) {
            val content = try { container.text.codeStr } catch (_: Exception) { "" }
            for ((lineIndex, line) in content.lineSequence().withIndex()) {
                if (results.size >= limit) return
                val columns = if (regex != null) {
                    regex.findAll(line).map { it.range.first + 1 }
                } else {
                    literalMatchColumns(line, query, caseSensitive)
                }
                for (column in columns) {
                    results.add(ResourceSearchMatch(container.name, resourceType, lineIndex + 1, column, line.trim()))
                    if (results.size >= limit) return
                }
            }
        }
        for (subFile in container.subFiles) {
            searchResourceContainer(subFile, ResourceType.XML.name, query, regex, caseSensitive, limit, results)
            if (results.size >= limit) return
        }
    }

    private fun literalMatchColumns(line: String, query: String, caseSensitive: Boolean): Sequence<Int> = sequence {
        if (query.isEmpty()) return@sequence
        var start = 0
        while (start <= line.length - query.length) {
            val index = line.indexOf(query, start, ignoreCase = !caseSensitive)
            if (index == -1) break
            yield(index + 1)
            start = index + query.length
        }
    }

    private companion object {
        const val RESOLVED_CLASS_CACHE_CAPACITY = 128
        const val FULL_SCAN_UNLOAD_BATCH = 32
        const val MIN_FREE_MEMORY_RATIO = 0.2
        const val MAX_MIN_FREE_MEMORY_BYTES = 512L * 1024L * 1024L
    }

    private data class SourceClass(
        val className: String,
        val file: Path
    )
}

internal data class ClassIndexEntry(
    val name: String,
    val simpleName: String,
    val `package`: String,
    val isPublic: Boolean,
    val isAbstract: Boolean,
    val isInterface: Boolean,
    val isInner: Boolean,
    val superClass: String?,
    val methodCount: Int,
    val fieldCount: Int,
    val innerClassCount: Int
) {
    fun toSummary(): ClassSummary = ClassSummary(
        name = name,
        `package` = `package`,
        isPublic = isPublic,
        isAbstract = isAbstract,
        isInterface = isInterface,
        isInner = isInner,
        superClass = superClass,
        methodCount = methodCount,
        fieldCount = fieldCount,
        innerClassCount = innerClassCount
    )
}

internal fun JavaClass.toIndexEntry(): ClassIndexEntry {
    val access = accessInfo
    return ClassIndexEntry(
        name = fullName,
        simpleName = name,
        `package` = getPackage() ?: "",
        isPublic = access.isPublic,
        isAbstract = access.isAbstract,
        isInterface = access.isInterface,
        isInner = isInner,
        superClass = classNode.superClass?.toString(),
        methodCount = methods.size,
        fieldCount = fields.size,
        innerClassCount = innerClasses.size
    )
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

data class CodeCacheWarmup(
    val totalClasses: Int,
    val generatedClasses: Int,
    val cachedClasses: Int,
    val failedClasses: Int
)

@Serializable
data class XrefMatch(
    val className: String,
    val methodName: String,
    val line: Int,
    val sourceClassName: String = "",
    val sourceMethodName: String = "",
    val content: String = "",
)

data class ResourceInfo(
    val name: String,
    val type: String,
    val path: String
)

data class ResourceSearchMatch(
    val path: String,
    val type: String,
    val line: Int,
    val column: Int,
    val content: String
)

data class UnloadSummary(
    val totalClasses: Int,
    val generatedAndUnloadedCount: Int,
    val notLoadedCount: Int
)
