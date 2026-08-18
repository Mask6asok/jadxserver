package jadx.server.server

import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Coordinates analysis leases with the exclusive maintenance window used by
 * the periodic memory cleanup.  Every analysis holds the read side for its
 * complete engine lifecycle; maintenance holds the write side while detached
 * engines are closed and the JVM is prompted to collect garbage.
 */
class AnalysisActivityCoordinator {
    private val lock = ReentrantReadWriteLock(true)

    fun <T> withAnalysis(block: () -> T): T {
        val readLock = lock.readLock()
        readLock.lock()
        return try {
            block()
        } finally {
            readLock.unlock()
        }
    }

    fun tryEnterMaintenance(): MaintenanceLease? {
        val writeLock = lock.writeLock()
        if (!writeLock.tryLock()) {
            return null
        }
        return MaintenanceLease(writeLock)
    }

    fun <T> withMaintenance(block: () -> T): T {
        val writeLock = lock.writeLock()
        writeLock.lock()
        return try {
            block()
        } finally {
            writeLock.unlock()
        }
    }

    class MaintenanceLease internal constructor(
        private val lock: Lock,
    ) : AutoCloseable {
        override fun close() {
            lock.unlock()
        }
    }
}
