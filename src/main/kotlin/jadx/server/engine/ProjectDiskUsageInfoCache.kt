package jadx.server.engine

import jadx.api.plugins.input.data.IMethodRef
import jadx.api.usage.IUsageInfoCache
import jadx.api.usage.IUsageInfoData
import jadx.api.usage.IUsageInfoVisitor
import jadx.api.usage.impl.InMemoryUsageInfoCache
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.FieldNode
import jadx.core.dex.nodes.MethodNode
import jadx.core.dex.nodes.RootNode
import jadx.core.utils.Utils
import jadx.core.utils.exceptions.JadxRuntimeException
import jadx.server.server.CacheState
import jadx.server.server.ProjectCacheLayout
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.time.Instant

class ProjectDiskUsageInfoCache(
    private val layout: ProjectCacheLayout,
    private val expectedState: CacheState,
) : IUsageInfoCache {

    private val memCache = InMemoryUsageInfoCache()
    private val usageFile: Path = layout.usageDir.resolve(USAGE_FILE_NAME)

    @Volatile
    private var rawUsageData: RawUsageData? = null

    private val loadLock = Any()

    override fun get(root: RootNode): IUsageInfoData? {
        memCache.get(root)?.let { return it }
        if (!layout.isValid(expectedState) || !Files.isRegularFile(usageFile)) {
            return null
        }
        synchronized(loadLock) {
            memCache.get(root)?.let { return it }
            val rawData = rawUsageData ?: loadUsageData(usageFile)?.also { rawUsageData = it } ?: return null
            return UsageData(root, rawData).also { memCache.set(root, it) }
        }
    }

    override fun set(root: RootNode, data: IUsageInfoData) {
        memCache.set(root, data)
        val serialized = try {
            serializeUsageData(data)
        } catch (e: Exception) {
            LOG.warn("Failed to serialize usage cache for {}", usageFile, e)
            return
        }
        try {
            Files.createDirectories(layout.usageDir)
            val tempFile = Files.createTempFile(layout.usageDir, "$USAGE_FILE_NAME-", ".tmp")
            try {
                Files.newOutputStream(tempFile, WRITE, CREATE, TRUNCATE_EXISTING).use { output ->
                    output.write(serialized)
                }
                moveAtomically(tempFile, usageFile)
            } finally {
                Files.deleteIfExists(tempFile)
            }
            rawUsageData = DataInputStream(BufferedInputStream(serialized.inputStream())).use { input ->
                deserializeUsageData(input)
            }
            layout.writeState(
                expectedState.copy(
                    lastWrittenAt = Instant.now().toString(),
                    usageFileBytes = Files.size(usageFile),
                )
            )
        } catch (e: Exception) {
            LOG.warn("Failed to persist usage cache to {}", usageFile, e)
        }
    }

    override fun close() {
        rawUsageData = null
        memCache.close()
    }

    private fun loadUsageData(path: Path): RawUsageData? {
        return try {
            DataInputStream(BufferedInputStream(Files.newInputStream(path))).use(::deserializeUsageData)
        } catch (e: Exception) {
            Files.deleteIfExists(path)
            LOG.warn("Failed to load usage cache from {}", path, e)
            null
        }
    }

    private fun moveAtomically(tempFile: Path, targetFile: Path) {
        try {
            Files.move(tempFile, targetFile, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tempFile, targetFile, REPLACE_EXISTING)
        }
    }

    private fun serializeUsageData(data: IUsageInfoData): ByteArray {
        val usageData = RawUsageData()
        data.visitUsageData(CollectUsageData(usageData))
        val buffer = java.io.ByteArrayOutputStream()
        DataOutputStream(BufferedOutputStream(buffer)).use { out ->
            out.write(USAGE_HEADER)
            out.writeInt(USAGE_DATA_VERSION)
            writeData(out, usageData)
        }
        return buffer.toByteArray()
    }

    private fun deserializeUsageData(input: DataInputStream): RawUsageData {
        val header = ByteArray(USAGE_HEADER.size)
        input.readFully(header)
        if (!header.contentEquals(USAGE_HEADER)) {
            throw IOException("Unexpected usage cache header")
        }
        val dataVersion = input.readInt()
        if (dataVersion != USAGE_DATA_VERSION) {
            throw IOException("Unexpected usage cache version: $dataVersion")
        }
        return readData(input)
    }

    private class UsageData(
        private val root: RootNode,
        private val rawUsageData: RawUsageData,
    ) : IUsageInfoData {
        override fun apply() {
            val clsMap = rawUsageData.clsMap
            for (cls in root.classes) {
                val clsUsageData = clsMap[cls.rawName] ?: continue
                applyForClass(clsUsageData, cls)
            }
        }

        override fun applyForClass(cls: ClassNode) {
            val clsUsageData = rawUsageData.clsMap[cls.rawName] ?: return
            applyForClass(clsUsageData, cls)
        }

        private fun applyForClass(clsUsageData: ClsUsageData, cls: ClassNode) {
            cls.setDependencies(resolveClsList(clsUsageData.clsDeps))
            cls.setUseIn(resolveClsList(clsUsageData.clsUsage))
            cls.setUseInMth(resolveMthList(clsUsageData.clsUseInMth))

            for (mth in cls.methods) {
                val mthUsageData = clsUsageData.mthUsage[mth.methodInfo.shortId] ?: continue
                mth.setUseIn(resolveMthList(mthUsageData.usage))
                mth.setUsed(resolveMthList(mthUsageData.uses))
                mth.setUnresolvedUsed(mthUsageData.unresolvedUsage)
                mth.setCallsSelf(mthUsageData.callsSelf)
            }
            for (fld in cls.fields) {
                val fldUsageData = clsUsageData.fldUsage[fld.fieldInfo.shortId] ?: continue
                fld.setUseIn(resolveMthList(fldUsageData.usage))
            }
        }

        override fun visitUsageData(visitor: IUsageInfoVisitor) {
            throw JadxRuntimeException("Not implemented")
        }

        private fun resolveClsList(clsList: List<String>): List<ClassNode> = clsList.mapNotNull(root::resolveRawClass)

        private fun resolveMthList(mthRefList: List<MthRef>): List<MethodNode> =
            Utils.collectionMap(mthRefList, { ref -> root.resolveDirectMethod(ref.cls, ref.shortId) })
    }

    private class CollectUsageData(private val data: RawUsageData) : IUsageInfoVisitor {
        override fun visitClassDeps(cls: ClassNode, deps: List<ClassNode>) {
            data.getClassData(cls).clsDeps = clsNodesRef(deps)
        }

        override fun visitClassUsage(cls: ClassNode, usage: List<ClassNode>) {
            data.getClassData(cls).clsUsage = clsNodesRef(usage)
        }

        override fun visitClassUseInMethods(cls: ClassNode, methods: List<MethodNode>) {
            data.getClassData(cls).clsUseInMth = mthNodesRef(methods)
        }

        override fun visitFieldsUsage(fld: FieldNode, methods: List<MethodNode>) {
            data.getFieldData(fld).usage = mthNodesRef(methods)
        }

        override fun visitMethodsUsage(mth: MethodNode, methods: List<MethodNode>) {
            data.getMethodData(mth).usage = mthNodesRef(methods)
        }

        override fun visitMethodsUses(mth: MethodNode, methods: List<MethodNode>) {
            data.getMethodData(mth).uses = mthNodesRef(methods)
        }

        override fun visitUnresolvedMethodsUsage(mth: MethodNode, methods: List<IMethodRef>) {
            data.getMethodData(mth).unresolvedUsage = methods
        }

        override fun visitIsSelfCall(mth: MethodNode, isSelfCall: Boolean) {
            data.getMethodData(mth).callsSelf = isSelfCall
        }

        override fun visitComplete() {
            data.collectClassesWithoutData()
        }

        private fun clsNodesRef(usage: List<ClassNode>): List<String> = Utils.collectionMap(usage, ClassNode::getRawName)

        private fun mthNodesRef(methods: List<MethodNode>): List<MthRef> = Utils.collectionMap(methods) { m -> data.getMethodData(m).mthRef }
    }

    private class RawUsageData {
        val clsMap: MutableMap<String, ClsUsageData> = HashMap()
        var classesWithoutData: List<String> = emptyList()

        fun getClassData(cls: ClassNode): ClsUsageData = getClassData(cls.rawName)

        fun getClassData(clsRawName: String): ClsUsageData = clsMap.computeIfAbsent(clsRawName, ::ClsUsageData)

        fun getMethodData(mth: MethodNode): MthUsageData {
            val parentClass = mth.parentClass
            val shortId = mth.methodInfo.shortId
            return getClassData(parentClass).mthUsage.computeIfAbsent(shortId) {
                MthUsageData(MthRef(parentClass.rawName, shortId))
            }
        }

        fun getFieldData(fld: FieldNode): FldUsageData {
            val parentClass = fld.parentClass
            val shortId = fld.fieldInfo.shortId
            return getClassData(parentClass).fldUsage.computeIfAbsent(shortId) {
                FldUsageData(FldRef(parentClass.rawName, shortId))
            }
        }

        fun collectClassesWithoutData() {
            val allClasses = HashSet<String>(clsMap.size * 2)
            for (usageData in clsMap.values) {
                allClasses.addAll(usageData.clsDeps)
                allClasses.addAll(usageData.clsUsage)
            }
            allClasses.removeAll(clsMap.keys)
            classesWithoutData = allClasses.sorted()
        }
    }

    private class ClsUsageData(val rawName: String) {
        var clsDeps: List<String> = emptyList()
        var clsUsage: List<String> = emptyList()
        var clsUseInMth: List<MthRef> = emptyList()
        val fldUsage: MutableMap<String, FldUsageData> = HashMap()
        val mthUsage: MutableMap<String, MthUsageData> = HashMap()
    }

    private class MthUsageData(val mthRef: MthRef) {
        var usage: List<MthRef> = emptyList()
        var uses: List<MthRef> = emptyList()
        var unresolvedUsage: List<IMethodRef> = emptyList()
        var callsSelf: Boolean = false
    }

    private class FldUsageData(val fldRef: FldRef) {
        var usage: List<MthRef> = emptyList()
    }

    private class MthRef(val cls: String, val shortId: String)

    private class FldRef(val cls: String, val shortId: String)

    private class CachedMethodRef(
        private var parentClassType: String,
        private var name: String,
        private var returnType: String,
        private var argTypes: List<String>,
    ) : IMethodRef {
        override fun getUniqId(): Int = 0

        override fun load() = Unit

        override fun getParentClassType(): String = parentClassType

        override fun getName(): String = name

        override fun getReturnType(): String = returnType

        override fun getArgTypes(): List<String> = argTypes
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(ProjectDiskUsageInfoCache::class.java)
        private const val USAGE_DATA_VERSION = 2
        private const val USAGE_FILE_NAME = "usage"
        private val USAGE_HEADER = "jadx.usage".toByteArray(StandardCharsets.US_ASCII)

        private fun readData(input: DataInputStream): RawUsageData {
            val data = RawUsageData()
            val clsCount = readUVInt(input)
            val clsWithoutDataCount = readUVInt(input)

            val clsNames = arrayOfNulls<String>(clsCount + clsWithoutDataCount)
            val classes = arrayOfNulls<ClsUsageData>(clsCount)
            var idx = 0
            repeat(clsCount) { i ->
                val clsRawName = input.readUTF()
                classes[i] = data.getClassData(clsRawName)
                clsNames[idx++] = clsRawName
            }
            repeat(clsWithoutDataCount) {
                clsNames[idx++] = input.readUTF()
            }

            val mthCount = readUVInt(input)
            val methods = arrayOfNulls<MthRef>(mthCount)
            repeat(mthCount) { i ->
                val clsId = readUVInt(input)
                val mthShortId = input.readUTF()
                val cls = requireNotNull(classes[clsId])
                val mthRef = MthRef(cls.rawName, mthShortId)
                cls.mthUsage[mthShortId] = MthUsageData(mthRef)
                methods[i] = mthRef
            }

            val unresolvedMethodCount = readUVInt(input)
            val unresolvedMethods = arrayOfNulls<IMethodRef>(unresolvedMethodCount)
            repeat(unresolvedMethodCount) { i ->
                val name = input.readUTF()
                val parentClassType = input.readUTF()
                val returnType = input.readUTF()
                val argCount = input.readInt()
                val args = ArrayList<String>(argCount)
                repeat(argCount) { args.add(input.readUTF()) }
                unresolvedMethods[i] = CachedMethodRef(parentClassType, name, returnType, args)
            }

            repeat(clsCount) { i ->
                val cls = data.getClassData(requireNotNull(clsNames[i]))
                cls.clsDeps = readClsList(input, clsNames)
                cls.clsUsage = readClsList(input, clsNames)
                cls.clsUseInMth = readMthList(input, methods)

                repeat(readUVInt(input)) {
                    val mthRef = requireNotNull(methods[readUVInt(input)])
                    val mthUsageData = cls.mthUsage.getValue(mthRef.shortId)
                    mthUsageData.usage = readMthList(input, methods)
                    mthUsageData.uses = readMthList(input, methods)
                    mthUsageData.unresolvedUsage = readUnresolvedMthList(input, unresolvedMethods)
                    mthUsageData.callsSelf = input.readBoolean()
                }

                repeat(readUVInt(input)) {
                    val fldShortId = input.readUTF()
                    cls.fldUsage.computeIfAbsent(fldShortId) { FldUsageData(FldRef(cls.rawName, fldShortId)) }
                        .usage = readMthList(input, methods)
                }
            }
            data.classesWithoutData = clsNames.drop(clsCount).filterNotNull()
            return data
        }

        private fun writeData(out: DataOutputStream, usageData: RawUsageData) {
            val clsMap = HashMap<String, Int>()
            val mthMap = HashMap<MthRef, Int>()
            val unresolvedMthMap = LinkedHashMap<IMethodRef, Int>()
            val clsDataMap = usageData.clsMap
            val classes = clsDataMap.keys.sorted()
            val classesWithoutData = usageData.classesWithoutData

            writeUVInt(out, classes.size)
            writeUVInt(out, classesWithoutData.size)
            var clsIdx = 0
            for (cls in classes) {
                out.writeUTF(cls)
                clsMap[cls] = clsIdx++
            }
            for (cls in classesWithoutData) {
                out.writeUTF(cls)
                clsMap[cls] = clsIdx++
            }

            val methods = clsDataMap.values.flatMap { it.mthUsage.values }.map { it.mthRef }
            writeUVInt(out, methods.size)
            methods.forEachIndexed { index, mth ->
                writeUVInt(out, clsMap.getValue(mth.cls))
                out.writeUTF(mth.shortId)
                mthMap[mth] = index
            }

            clsDataMap.values.asSequence()
                .flatMap { it.mthUsage.values.asSequence() }
                .flatMap { it.unresolvedUsage.asSequence() }
                .filterNotNull()
                .forEach { unresolvedMthMap.putIfAbsent(it, unresolvedMthMap.size) }
            writeUVInt(out, unresolvedMthMap.size)
            for (uMth in unresolvedMthMap.keys) {
                out.writeUTF(uMth.name ?: "")
                out.writeUTF(uMth.parentClassType ?: "")
                out.writeUTF(uMth.returnType ?: "")
                val argTypes = uMth.argTypes ?: emptyList()
                out.writeInt(argTypes.size)
                argTypes.forEach(out::writeUTF)
            }

            for (cls in classes) {
                val clsData = clsDataMap.getValue(cls)
                writeClsList(out, clsMap, clsData.clsDeps)
                writeClsList(out, clsMap, clsData.clsUsage)
                writeMthList(out, mthMap, clsData.clsUseInMth)

                writeUVInt(out, clsData.mthUsage.size)
                for (mthData in clsData.mthUsage.values) {
                    writeUVInt(out, mthMap.getValue(mthData.mthRef))
                    writeMthList(out, mthMap, mthData.usage)
                    writeMthList(out, mthMap, mthData.uses)
                    writeUnresolvedMthList(out, unresolvedMthMap, mthData.unresolvedUsage)
                    out.writeBoolean(mthData.callsSelf)
                }

                writeUVInt(out, clsData.fldUsage.size)
                for (fldData in clsData.fldUsage.values) {
                    out.writeUTF(fldData.fldRef.shortId)
                    writeMthList(out, mthMap, fldData.usage)
                }
            }
        }

        private fun readClsList(input: DataInputStream, classes: Array<String?>): List<String> {
            val count = readUVInt(input)
            if (count == 0) return emptyList()
            return List(count) { requireNotNull(classes[readUVInt(input)]) }
        }

        private fun writeClsList(out: DataOutputStream, clsMap: Map<String, Int>, clsList: List<String>) {
            if (clsList.isEmpty()) {
                writeUVInt(out, 0)
                return
            }
            writeUVInt(out, clsList.size)
            clsList.forEach { cls -> writeUVInt(out, clsMap[cls] ?: throw JadxRuntimeException("Unknown class in usage: $cls")) }
        }

        private fun readMthList(input: DataInputStream, methods: Array<MthRef?>): List<MthRef> {
            val count = readUVInt(input)
            if (count == 0) return emptyList()
            return List(count) { requireNotNull(methods[readUVInt(input)]) }
        }

        private fun writeMthList(out: DataOutputStream, mthMap: Map<MthRef, Int>, mthList: List<MthRef>) {
            if (mthList.isEmpty()) {
                writeUVInt(out, 0)
                return
            }
            writeUVInt(out, mthList.size)
            mthList.forEach { writeUVInt(out, mthMap.getValue(it)) }
        }

        private fun readUnresolvedMthList(input: DataInputStream, methods: Array<IMethodRef?>): List<IMethodRef> {
            val count = readUVInt(input)
            if (count == 0) return emptyList()
            return List(count) { requireNotNull(methods[readUVInt(input)]) }
        }

        private fun writeUnresolvedMthList(out: DataOutputStream, mthMap: Map<IMethodRef, Int>, mthList: List<IMethodRef>) {
            if (mthList.isEmpty()) {
                writeUVInt(out, 0)
                return
            }
            writeUVInt(out, mthList.size)
            mthList.forEach { writeUVInt(out, mthMap.getValue(it)) }
        }

        private fun writeUVInt(out: DataOutputStream, value: Int) {
            require(value >= 0) { "Expect value >= 0, got: $value" }
            var current = value
            while (true) {
                val next = current ushr 7
                if (next == 0) {
                    out.writeByte(current and 0x7f)
                    return
                }
                out.writeByte((current and 0x7f) or 0x80)
                current = next
            }
        }

        private fun readUVInt(input: DataInputStream): Int {
            var result = 0
            var shift = 0
            while (true) {
                val value = input.readByte().toInt()
                result = result or ((value and 0x7f) shl shift)
                shift += 7
                if ((value and 0x80) != 0x80) {
                    return result
                }
            }
        }
    }
}
