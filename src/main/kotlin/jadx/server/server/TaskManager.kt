package jadx.server.server

import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class TaskStatus {
    Running,
    Completed,
    Failed
}

data class TaskInfo(
    val taskId: String,
    val toolName: String,
    var status: TaskStatus = TaskStatus.Running,
    var progress: Double = 0.0,
    var result: JsonObject? = null,
    var error: String? = null,
    val createdAt: Instant = Instant.now(),
    var completedAt: Instant? = null
)

class TaskManager {
    private val tasks = ConcurrentHashMap<String, TaskInfo>()
    private val counter = AtomicLong(0)

    fun create(toolName: String): String {
        val id = "task-%05d".format(counter.incrementAndGet())
        tasks[id] = TaskInfo(taskId = id, toolName = toolName)
        return id
    }

    fun complete(taskId: String, result: JsonObject) {
        tasks[taskId]?.let { task ->
            task.status = TaskStatus.Completed
            task.result = result
            task.progress = 100.0
            task.completedAt = Instant.now()
        }
    }

    fun fail(taskId: String, error: String) {
        tasks[taskId]?.let { task ->
            task.status = TaskStatus.Failed
            task.error = error
            task.completedAt = Instant.now()
        }
    }

    fun updateProgress(taskId: String, percent: Double) {
        tasks[taskId]?.let { task ->
            task.progress = percent.coerceIn(0.0, 100.0)
        }
    }

    fun get(taskId: String): TaskInfo? = tasks[taskId]

    fun pruneOld() {
        val cutoff = Instant.now().minusSeconds(300)
        val toRemove = tasks.entries
            .filter { (_, task) ->
                task.status != TaskStatus.Running &&
                    task.completedAt != null &&
                    task.completedAt!!.isBefore(cutoff)
            }
            .map { it.key }
        for (id in toRemove) {
            tasks.remove(id)
        }
    }

    fun taskCount(): Int = tasks.size

    fun allTasks(): Collection<TaskInfo> = tasks.values
}
