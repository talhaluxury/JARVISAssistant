package com.jarvis.assistant.core.workflow

import android.content.Context
import com.jarvis.assistant.command.CommandCodec
import com.jarvis.assistant.command.JarvisCommand
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Local, user-controlled workflow store with version/change metadata. */
class WorkflowStore(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_workflows", Context.MODE_PRIVATE)
    private val key = "definitions"

    fun all(): List<Workflow> = runCatching {
        val arr = JSONArray(prefs.getString(key, "[]"))
        buildList { for (i in 0 until arr.length()) parse(arr.getJSONObject(i))?.let(::add) }
    }.getOrDefault(emptyList())

    fun save(workflow: Workflow): Workflow {
        require(workflow.name.isNotBlank() && workflow.trigger.isNotBlank())
        require(workflow.steps.isNotEmpty() && workflow.steps.size <= 50)
        require(workflow.requiresApproval || workflow.steps.none { it is JarvisCommand.Automate })
        val old = all().firstOrNull { it.id == workflow.id }
        val next = if (old == null) workflow else workflow.copy(
            version = old.version + 1,
            createdAt = old.createdAt,
            updatedAt = System.currentTimeMillis(),
            changeHistory = old.changeHistory + "v${old.version} → v${old.version + 1} updated"
        )
        persist(all().filterNot { it.id == next.id } + next)
        return next
    }

    fun createApproved(name: String, trigger: String, steps: List<JarvisCommand>): Workflow {
        val now = System.currentTimeMillis()
        return save(Workflow(UUID.randomUUID().toString(), name, trigger, steps, enabled = true, version = 1, requiresApproval = true, createdAt = now, updatedAt = now))
    }

    fun delete(id: String) = persist(all().filterNot { it.id == id })
    fun setEnabled(id: String, enabled: Boolean) { all().firstOrNull { it.id == id }?.let { save(it.copy(enabled = enabled, updatedAt = System.currentTimeMillis())) } }

    private fun persist(items: List<Workflow>) {
        val a = JSONArray()
        items.forEach { w ->
            val steps = JSONArray()
            w.steps.forEach { steps.put(CommandCodec.encode(it)) }
            val conditions = JSONArray(); w.conditions.forEach { conditions.put(it.expression) }
            val history = JSONArray(); w.changeHistory.forEach(history::put)
            a.put(JSONObject().put("id", w.id).put("name", w.name).put("trigger", w.trigger)
                .put("enabled", w.enabled).put("version", w.version).put("requiresApproval", w.requiresApproval)
                .put("createdAt", w.createdAt).put("updatedAt", w.updatedAt)
                .put("changeHistory", history).put("conditions", conditions).put("steps", steps))
        }
        prefs.edit().putString(key, a.toString()).apply()
    }

    private fun parse(o: JSONObject): Workflow? = runCatching {
        val stepsJson = o.optJSONArray("steps") ?: JSONArray()
        val steps = buildList { for (i in 0 until stepsJson.length()) CommandCodec.decode(stepsJson.getJSONObject(i))?.let(::add) }
        val conditionsJson = o.optJSONArray("conditions") ?: JSONArray()
        val conditions = buildList { for (i in 0 until conditionsJson.length()) add(WorkflowCondition(conditionsJson.getString(i))) }
        val historyJson = o.optJSONArray("changeHistory") ?: JSONArray()
        val history = buildList { for (i in 0 until historyJson.length()) add(historyJson.getString(i)) }
        Workflow(o.getString("id"), o.getString("name"), o.getString("trigger"), steps,
            o.optBoolean("enabled"), o.optInt("version", 1), o.optBoolean("requiresApproval", true),
            o.optLong("createdAt", System.currentTimeMillis()), o.optLong("updatedAt", System.currentTimeMillis()), history, conditions)
    }.getOrNull()
}
