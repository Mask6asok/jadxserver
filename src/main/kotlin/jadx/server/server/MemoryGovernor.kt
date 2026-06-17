package jadx.server.server

data class MemorySnapshot(
    val maxBytes: Long,
    val totalBytes: Long,
    val freeBytes: Long,
) {
    val usedBytes: Long get() = totalBytes - freeBytes
    val usedRatio: Double get() = if (maxBytes <= 0L) 0.0 else usedBytes.toDouble() / maxBytes.toDouble()
    val headroomBytes: Long get() = (maxBytes - usedBytes).coerceAtLeast(0L)
}

enum class MemoryPressureLevel {
    NORMAL,
    SOFT,
    HARD,
    CRITICAL,
}

enum class MemoryDecisionType {
    ALLOW,
    REFUSE,
}

data class MemoryDecision(
    val type: MemoryDecisionType,
    val level: MemoryPressureLevel,
    val snapshot: MemorySnapshot,
    val reason: String,
)

class MemoryGovernor(
    private val snapshotProvider: () -> MemorySnapshot = {
        val runtime = Runtime.getRuntime()
        MemorySnapshot(
            maxBytes = runtime.maxMemory(),
            totalBytes = runtime.totalMemory(),
            freeBytes = runtime.freeMemory(),
        )
    },
) {
    companion object {
        const val SOFT_THRESHOLD: Double = 0.72
        const val HARD_THRESHOLD: Double = 0.85
        const val CRITICAL_THRESHOLD: Double = 0.92
        const val SPAWN_HEADROOM_BYTES: Long = 1024L * 1024L * 1024L
        const val HEAVY_OPERATION_HEADROOM_BYTES: Long = 512L * 1024L * 1024L
    }

    fun snapshot(): MemorySnapshot = snapshotProvider()

    fun allowSpawn(): MemoryDecision = decide(requiredHeadroomBytes = SPAWN_HEADROOM_BYTES, operation = "spawn")

    fun allowHeavyOperation(): MemoryDecision = decide(requiredHeadroomBytes = HEAVY_OPERATION_HEADROOM_BYTES, operation = "heavy_operation")

    private fun decide(requiredHeadroomBytes: Long, operation: String): MemoryDecision {
        val snapshot = snapshot()
        val level = pressureLevel(snapshot)
        val insufficientHeadroom = snapshot.headroomBytes < requiredHeadroomBytes
        val shouldRefuse = level == MemoryPressureLevel.CRITICAL || insufficientHeadroom
        return if (shouldRefuse) {
            MemoryDecision(
                type = MemoryDecisionType.REFUSE,
                level = level,
                snapshot = snapshot,
                reason = if (insufficientHeadroom) {
                    "$operation headroom below required threshold"
                } else {
                    "$operation refused at critical heap pressure"
                },
            )
        } else {
            MemoryDecision(
                type = MemoryDecisionType.ALLOW,
                level = level,
                snapshot = snapshot,
                reason = "$operation allowed",
            )
        }
    }

    fun pressureLevel(snapshot: MemorySnapshot = snapshot()): MemoryPressureLevel = when {
        snapshot.usedRatio >= CRITICAL_THRESHOLD -> MemoryPressureLevel.CRITICAL
        snapshot.usedRatio >= HARD_THRESHOLD -> MemoryPressureLevel.HARD
        snapshot.usedRatio >= SOFT_THRESHOLD -> MemoryPressureLevel.SOFT
        else -> MemoryPressureLevel.NORMAL
    }
}
