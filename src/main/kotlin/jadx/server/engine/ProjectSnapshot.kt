package jadx.server.engine

import kotlinx.serialization.Serializable

/** Immutable, serialization-safe view of an analysed project. */
@Serializable
data class ProjectSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val fileHash: String,
    val apkSha256: String,
    val metadata: SnapshotMetadata,
    val classSummaries: List<SnapshotClassSummary>,
    /** Detailed metadata is populated on demand for classes an agent visits. */
    val classDetails: Map<String, SnapshotClassDetail> = emptyMap(),
    val manifest: String? = null,
    /** String surrogate for the source directory; never a live JADX object. */
    val sourceDir: String? = null,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

@Serializable
data class SnapshotMetadata(
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
    val receivers: List<String>,
)

@Serializable
data class SnapshotClassSummary(
    val name: String,
    val `package`: String,
    val isPublic: Boolean,
    val isAbstract: Boolean,
    val isInterface: Boolean,
    val isInner: Boolean,
    val superClass: String?,
    val methodCount: Int,
    val fieldCount: Int,
    val innerClassCount: Int,
)

/** Mutable-JADX-free representation of the fields returned by class_info. */
@Serializable
data class SnapshotClassDetail(
    val name: String,
    val packageName: String,
    val superclass: String?,
    val interfaces: List<String>,
    val isAbstract: Boolean,
    val isInterface: Boolean,
    val isEnum: Boolean,
    val accessFlags: String,
    val methods: List<SnapshotMethodSummary>,
    val fields: List<SnapshotFieldSummary>,
    val innerClasses: List<String>,
)

@Serializable
data class SnapshotMethodSummary(
    val name: String,
    val signature: String,
    val returnType: String,
    val parameters: String,
    val paramCount: Int,
    val isPublic: Boolean,
    val isStatic: Boolean,
)

@Serializable
data class SnapshotFieldSummary(
    val name: String,
    val type: String,
    val isStatic: Boolean,
    val isFinal: Boolean,
    val accessFlags: String,
)
