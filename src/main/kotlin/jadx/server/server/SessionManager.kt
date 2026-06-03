package jadx.server.server

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class Session(
    val sessionId: String,
    val createdAt: Instant = Instant.now(),
    var lastActive: Instant = Instant.now(),
    val instanceIds: MutableList<String> = mutableListOf(),
    var defaultInstanceId: String? = null
)

class SessionManager {
    private val sessions = ConcurrentHashMap<String, Session>()

    fun getOrCreate(sessionId: String): Session {
        return sessions.computeIfAbsent(sessionId) { Session(it) }.also {
            it.lastActive = Instant.now()
        }
    }

    fun get(sessionId: String): Session? = sessions[sessionId]

    fun addInstance(sessionId: String, instanceId: String) {
        val s = getOrCreate(sessionId)
        if (instanceId !in s.instanceIds) {
            s.instanceIds.add(instanceId)
        }
        if (s.defaultInstanceId == null) {
            s.defaultInstanceId = instanceId
        }
    }

    fun removeInstance(sessionId: String, instanceId: String) {
        sessions[sessionId]?.let { s ->
            s.instanceIds.remove(instanceId)
            if (s.defaultInstanceId == instanceId) {
                s.defaultInstanceId = s.instanceIds.firstOrNull()
            }
        }
    }

    fun removeSession(sessionId: String): Session? = sessions.remove(sessionId)

    fun sessionCount(): Int = sessions.size

    fun allSessions(): Collection<Session> = sessions.values
}
