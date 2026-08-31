package jadx.server.engine

import jadx.api.ICodeInfo
import jadx.api.impl.AnnotatedCodeInfo
import jadx.api.metadata.ICodeAnnotation
import jadx.api.metadata.ICodeNodeRef
import jadx.api.metadata.annotations.NodeDeclareRef
import jadx.api.metadata.annotations.NodeEnd
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.FieldNode
import jadx.core.dex.nodes.MethodNode
import jadx.core.dex.nodes.RootNode
import jadx.server.server.ProjectCacheLayout
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
import java.security.MessageDigest

/**
 * Small, first-party persistence layer for the part of JADX code metadata
 * needed by xref location and enclosing-method resolution.
 *
 * The normal source cache intentionally stores plain text.  Once a class is
 * unloaded, however, returning [jadx.api.impl.SimpleCodeInfo] loses the
 * declaration/use annotations that [jadx.api.JavaClass.getUsePlacesFor]
 * requires.  This sidecar stores only stable node identities (raw class name
 * plus method/field short id) and positions; it never serializes live JADX
 * objects.  The current [RootNode] is bound after decompiler load and is used
 * to rehydrate those identities lazily on cache reads.
 */
internal class ProjectDiskCodeMetadata(
    private val layout: ProjectCacheLayout,
) {
    @Volatile
    private var root: RootNode? = null

    fun bind(root: RootNode) {
        this.root = root
    }

    fun isBound(): Boolean = root != null

    fun fileFor(className: String): Path = layout.codeMetadataFileFor(className)

    /**
     * Persist metadata for [codeInfo].  Unsupported annotations are omitted
     * deliberately: xref/enclosing-node logic only needs class/method/field
     * node refs, declaration refs, and END markers.  If no supported metadata
     * exists, a zero-entry sidecar is still written.  It acts as a completion
     * marker for source-only/error results and prevents endless regeneration;
     * a legacy source with no sidecar is handled as a cache miss by the
     * xref-enabled code cache.
     */
    fun write(file: Path, code: String, codeInfo: ICodeInfo) {
        val entries = collectEntries(codeInfo)
        val lines = codeInfo.getCodeMetadata().getLineMapping()
            .entries
            .sortedBy { it.key }
        Files.createDirectories(file.parent)
        val temp = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
        try {
            DataOutputStream(BufferedOutputStream(Files.newOutputStream(temp, WRITE, CREATE, TRUNCATE_EXISTING))).use { out ->
                out.write(MAGIC)
                out.writeInt(FORMAT_VERSION)
                val codeBytes = code.toByteArray(StandardCharsets.UTF_8)
                out.writeLong(codeBytes.size.toLong())
                out.write(codeDigest(codeBytes))

                out.writeInt(lines.size)
                for ((decompiledLine, sourceLine) in lines) {
                    out.writeInt(decompiledLine)
                    out.writeInt(sourceLine)
                }

                out.writeInt(entries.size)
                for (entry in entries) {
                    out.writeInt(entry.position)
                    out.writeByte(entry.tag)
                    when (entry.tag) {
                        TAG_CLASS -> out.writeUTF(entry.className!!)
                        TAG_METHOD -> {
                            out.writeUTF(entry.className!!)
                            out.writeUTF(entry.shortId!!)
                        }
                        TAG_FIELD -> {
                            out.writeUTF(entry.className!!)
                            out.writeUTF(entry.shortId!!)
                        }
                        TAG_DECLARATION -> {
                            out.writeByte(entry.nodeTag)
                            when (entry.nodeTag) {
                                TAG_CLASS -> out.writeUTF(entry.className!!)
                                TAG_METHOD, TAG_FIELD -> {
                                    out.writeUTF(entry.className!!)
                                    out.writeUTF(entry.shortId!!)
                                }
                                else -> throw IOException("Unexpected declaration node tag: ${entry.nodeTag}")
                            }
                            out.writeInt(entry.defPosition)
                        }
                        TAG_END -> Unit
                        else -> throw IOException("Unexpected metadata tag: ${entry.tag}")
                    }
                }
            }
            moveAtomically(temp, file)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /**
     * Read and rebind a sidecar to the currently loaded root.  Any stale,
     * corrupt, incompatible, or unresolvable sidecar returns null; callers
     * then safely fall back to [jadx.api.impl.SimpleCodeInfo].
     */
    fun read(file: Path, code: String): ICodeInfo? {
        val currentRoot = root ?: return null
        if (!Files.isRegularFile(file)) return null
        return try {
            DataInputStream(BufferedInputStream(Files.newInputStream(file))).use { input ->
                val magic = ByteArray(MAGIC.size)
                input.readFully(magic)
                if (!magic.contentEquals(MAGIC)) {
                    throw IOException("Unexpected code metadata header")
                }
                val version = input.readInt()
                if (version != FORMAT_VERSION) {
                    throw IOException("Unexpected code metadata version: $version")
                }
                val codeBytes = code.toByteArray(StandardCharsets.UTF_8)
                val byteLength = input.readLong()
                if (byteLength != codeBytes.size.toLong()) {
                    throw IOException("Code metadata/source length mismatch")
                }
                val expectedDigest = ByteArray(DIGEST_SIZE)
                input.readFully(expectedDigest)
                if (!expectedDigest.contentEquals(codeDigest(codeBytes))) {
                    throw IOException("Code metadata/source digest mismatch")
                }

                val lines = readLines(input)
                val annotations = readAnnotations(input, currentRoot, code.length)
                AnnotatedCodeInfo(code, lines, annotations)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Validate only the sidecar header/version/source fingerprint. This is
     * used by warmup admission and deliberately does not resolve node refs.
     */
    fun isCompatible(file: Path, code: String): Boolean {
        if (!Files.isRegularFile(file)) return false
        return try {
            DataInputStream(BufferedInputStream(Files.newInputStream(file))).use { input ->
                val magic = ByteArray(MAGIC.size)
                input.readFully(magic)
                if (!magic.contentEquals(MAGIC) || input.readInt() != FORMAT_VERSION) {
                    return false
                }
                val codeBytes = code.toByteArray(StandardCharsets.UTF_8)
                if (input.readLong() != codeBytes.size.toLong()) return false
                val digest = ByteArray(DIGEST_SIZE)
                input.readFully(digest)
                digest.contentEquals(codeDigest(codeBytes))
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun collectEntries(codeInfo: ICodeInfo): List<StoredEntry> {
        val raw = codeInfo.getCodeMetadata().getAsMap().entries
        return raw.mapNotNull { (position, annotation) ->
            when (annotation) {
                is NodeDeclareRef -> {
                    val node = encodeNode(annotation.getNode()) ?: return@mapNotNull null
                    StoredEntry(
                        position = position,
                        tag = TAG_DECLARATION,
                        nodeTag = node.tag,
                        className = node.className,
                        shortId = node.shortId,
                        defPosition = annotation.getDefPos(),
                    )
                }
                is ClassNode -> StoredEntry(position, TAG_CLASS, className = annotation.rawName)
                is MethodNode -> StoredEntry(
                    position,
                    TAG_METHOD,
                    className = annotation.parentClass.rawName,
                    shortId = annotation.methodInfo.shortId,
                )
                is FieldNode -> StoredEntry(
                    position,
                    TAG_FIELD,
                    className = annotation.fieldInfo.declClass.rawName,
                    shortId = annotation.fieldInfo.shortId,
                )
                is NodeEnd -> StoredEntry(position, TAG_END)
                else -> null
            }
        }.sortedWith(compareBy<StoredEntry> { it.position }.thenBy { it.tag }.thenBy { it.nodeTag })
    }

    private fun encodeNode(node: ICodeNodeRef): NodeIdentity? = when (node) {
        is ClassNode -> NodeIdentity(TAG_CLASS, node.rawName, null)
        is MethodNode -> NodeIdentity(TAG_METHOD, node.parentClass.rawName, node.methodInfo.shortId)
        is FieldNode -> NodeIdentity(TAG_FIELD, node.fieldInfo.declClass.rawName, node.fieldInfo.shortId)
        else -> null
    }

    private fun readLines(input: DataInputStream): Map<Int, Int> {
        val count = readCount(input, "line mapping")
        if (count == 0) return emptyMap()
        val lines = LinkedHashMap<Int, Int>(count)
        repeat(count) {
            val decompiledLine = input.readInt()
            val sourceLine = input.readInt()
            if (decompiledLine < 0 || sourceLine < 0) {
                throw IOException("Negative line mapping")
            }
            lines[decompiledLine] = sourceLine
        }
        return lines
    }

    private fun readAnnotations(
        input: DataInputStream,
        root: RootNode,
        codeLength: Int,
    ): Map<Int, ICodeAnnotation> {
        val count = readCount(input, "annotation")
        if (count == 0) return emptyMap()
        val annotations = LinkedHashMap<Int, ICodeAnnotation>(count)
        repeat(count) {
            val position = input.readInt()
            if (position < 0 || position > codeLength) {
                throw IOException("Invalid code metadata position: $position")
            }
            val tag = input.readUnsignedByte()
            val annotation = when (tag) {
                TAG_CLASS -> resolveClass(root, input.readUTF())
                TAG_METHOD -> resolveMethod(root, input.readUTF(), input.readUTF())
                TAG_FIELD -> resolveField(root, input.readUTF(), input.readUTF())
                TAG_DECLARATION -> {
                    val node = when (input.readUnsignedByte()) {
                        TAG_CLASS -> resolveClass(root, input.readUTF())
                        TAG_METHOD -> resolveMethod(root, input.readUTF(), input.readUTF())
                        TAG_FIELD -> resolveField(root, input.readUTF(), input.readUTF())
                        else -> throw IOException("Unknown declaration node tag")
                    }
                    val defPosition = input.readInt()
                    if (defPosition < 0 || defPosition > codeLength) {
                        throw IOException("Invalid declaration position: $defPosition")
                    }
                    NodeDeclareRef(node).also {
                        it.setDefPos(defPosition)
                        node.setDefPosition(defPosition)
                    }
                }
                TAG_END -> NodeEnd.VALUE
                else -> throw IOException("Unknown code metadata tag: $tag")
            }
            annotations[position] = annotation
        }
        return annotations
    }

    private fun resolveClass(root: RootNode, rawName: String): ClassNode =
        root.resolveRawClass(rawName) ?: throw IOException("Class not found: $rawName")

    private fun resolveMethod(root: RootNode, className: String, shortId: String): MethodNode =
        root.resolveDirectMethod(className, shortId)

    private fun resolveField(root: RootNode, className: String, shortId: String): FieldNode {
        val cls = resolveClass(root, className)
        return cls.searchFieldByShortId(shortId)
            ?: throw IOException("Field not found: $className.$shortId")
    }

    private fun readCount(input: DataInputStream, kind: String): Int {
        val count = input.readInt()
        if (count < 0 || count > MAX_ENTRIES) {
            throw IOException("Invalid $kind count: $count")
        }
        return count
    }

    private fun moveAtomically(temp: Path, target: Path) {
        try {
            Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, target, REPLACE_EXISTING)
        }
    }

    private fun codeDigest(codeBytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(codeBytes)

    private data class NodeIdentity(
        val tag: Int,
        val className: String,
        val shortId: String?,
    )

    private data class StoredEntry(
        val position: Int,
        val tag: Int,
        val nodeTag: Int = 0,
        val className: String? = null,
        val shortId: String? = null,
        val defPosition: Int = 0,
    )

    private companion object {
        private const val FORMAT_VERSION = 1
        private const val MAX_ENTRIES = 1_000_000
        private const val DIGEST_SIZE = 32
        private const val TAG_CLASS = 1
        private const val TAG_METHOD = 2
        private const val TAG_FIELD = 3
        private const val TAG_DECLARATION = 4
        private const val TAG_END = 5
        private val MAGIC = "jadx-server-md".toByteArray(StandardCharsets.US_ASCII)
    }
}
