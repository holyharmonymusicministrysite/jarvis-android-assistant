package com.jarvis.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Small, durable task ledger for SAM's future long-running agent runtime.
 *
 * This is local task-state storage only: it does not execute commands, access
 * the network, or grant itself permissions. Work requiring sensitive actions
 * must remain approval-gated by the caller.
 */
class SamTaskLedger(context: Context) {
    enum class Status {
        QUEUED, PLANNING, RUNNING, WAITING_FOR_APPROVAL,
        VERIFYING, COMPLETED, FAILED, CANCELLED
    }

    data class Task(
        val id: String,
        val title: String,
        val instruction: String,
        val status: Status,
        val checkpoint: String,
        val attempts: Int,
        val approvalRequired: Boolean,
        val createdAt: Long,
        val updatedAt: Long,
        val lastError: String
    )

    private val preferences = context.applicationContext
        .getSharedPreferences("sam_task_ledger", Context.MODE_PRIVATE)

    @Synchronized
    fun create(title: String, instruction: String, approvalRequired: Boolean = false): Task {
        require(title.isNotBlank()) { "Task title cannot be empty." }
        require(instruction.isNotBlank()) { "Task instruction cannot be empty." }
        val now = System.currentTimeMillis()
        val task = Task(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            instruction = instruction.trim(),
            status = Status.QUEUED,
            checkpoint = "Task accepted; awaiting an execution engine.",
            attempts = 0,
            approvalRequired = approvalRequired,
            createdAt = now,
            updatedAt = now,
            lastError = ""
        )
        val all = readAll().toMutableList()
        all.add(0, task)
        persist(all)
        return task
    }

    @Synchronized
    fun get(id: String): Task? = readAll().firstOrNull { it.id == id }

    @Synchronized
    fun list(limit: Int = 50): List<Task> =
        readAll().take(limit.coerceIn(1, 200))

    /**
     * Updates a task checkpoint and status. Callers must explicitly pass
     * approvalGranted before moving an approval-gated task into RUNNING.
     */
    @Synchronized
    fun update(
        id: String,
        status: Status,
        checkpoint: String,
        error: String = "",
        approvalGranted: Boolean = false
    ): Task? {
        val all = readAll().toMutableList()
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) return null

        val old = all[index]
        require(isAllowedTransition(old.status, status)) {
            "Invalid task transition: ${old.status} -> $status"
        }
        require(!(old.approvalRequired && status == Status.RUNNING && !approvalGranted)) {
            "This task requires explicit approval before execution."
        }

        val next = old.copy(
            status = status,
            checkpoint = checkpoint,
            attempts = old.attempts + if (status == Status.RUNNING && old.status != Status.RUNNING) 1 else 0,
            updatedAt = System.currentTimeMillis(),
            lastError = error
        )
        all[index] = next
        persist(all)
        return next
    }

    private fun isAllowedTransition(from: Status, to: Status): Boolean {
        if (from == to) return true
        return when (from) {
            Status.QUEUED -> to in setOf(Status.PLANNING, Status.CANCELLED)
            Status.PLANNING -> to in setOf(Status.RUNNING, Status.WAITING_FOR_APPROVAL, Status.FAILED, Status.CANCELLED)
            Status.RUNNING -> to in setOf(Status.WAITING_FOR_APPROVAL, Status.VERIFYING, Status.FAILED, Status.CANCELLED)
            Status.WAITING_FOR_APPROVAL -> to in setOf(Status.RUNNING, Status.CANCELLED)
            Status.VERIFYING -> to in setOf(Status.COMPLETED, Status.FAILED, Status.RUNNING)
            Status.FAILED -> to in setOf(Status.QUEUED, Status.CANCELLED)
            Status.COMPLETED, Status.CANCELLED -> false
        }
    }

    private fun readAll(): List<Task> {
        val raw = preferences.getString(KEY_TASKS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                runCatching {
                    Task(
                        id = item.getString("id"),
                        title = item.getString("title"),
                        instruction = item.getString("instruction"),
                        status = Status.valueOf(item.getString("status")),
                        checkpoint = item.optString("checkpoint"),
                        attempts = item.optInt("attempts"),
                        approvalRequired = item.optBoolean("approvalRequired"),
                        createdAt = item.optLong("createdAt"),
                        updatedAt = item.optLong("updatedAt"),
                        lastError = item.optString("lastError")
                    )
                }.getOrNull()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persist(tasks: List<Task>) {
        val array = JSONArray()
        tasks.forEach { task ->
            array.put(JSONObject().apply {
                put("id", task.id)
                put("title", task.title)
                put("instruction", task.instruction)
                put("status", task.status.name)
                put("checkpoint", task.checkpoint)
                put("attempts", task.attempts)
                put("approvalRequired", task.approvalRequired)
                put("createdAt", task.createdAt)
                put("updatedAt", task.updatedAt)
                put("lastError", task.lastError)
            })
        }
        check(preferences.edit().putString(KEY_TASKS, array.toString()).commit()) {
            "Could not save SAM task state."
        }
    }

    private companion object {
        const val KEY_TASKS = "tasks_v1"
    }
}
