package pl.mos.backoffice.projects.adapter.out.langgraph

import org.bsc.langgraph4j.state.AgentState
import org.bsc.langgraph4j.state.Channel
import org.bsc.langgraph4j.state.Channels
import pl.mos.backoffice.projects.application.port.out.RunStateKeys
import java.util.function.Supplier

/**
 * Routing state of a workflow graph (docs/architecture/03-pipelines-and-agents.md). Only what edges and
 * executors need lives here; the domain `WorkflowRun` is the source of truth. Values are plain JDK types
 * so the `ObjectStreamStateSerializer` can checkpoint them. Keys are [RunStateKeys].
 */
class WorkflowState(data: Map<String, Any?>) : AgentState(data) {

    fun string(key: String): String? = value<Any>(key).map { it.toString() }.orElse(null)?.takeIf { it.isNotBlank() }

    fun double(key: String): Double? = value<Any>(key).map { (it as? Number)?.toDouble() ?: it.toString().toDoubleOrNull() }.orElse(null)

    @Suppress("UNCHECKED_CAST")
    fun map(key: String): Map<String, Any?>? = value<Any>(key).map { it as? Map<String, Any?> }.orElse(null)

    fun visits(nodeId: String): Int = (map(RunStateKeys.LOOP_COUNTS)?.get(nodeId) as? Number)?.toInt() ?: 0

    companion object {
        const val NODE_FAILED = "sys:failed"
        const val NODE_CANCELLED = "sys:cancelled"

        val SCHEMA: Map<String, Channel<*>> = mapOf(
            RunStateKeys.FEEDBACK to Channels.appender<Any>(Supplier { ArrayList<Any>() }),
        )

        /** Converts Kotlin/immutable collections to serialisable JDK collections; drops nothing. */
        fun normalise(value: Any?): Any? = when (value) {
            null -> null
            is String, is Boolean -> value
            is Int, is Long, is Double -> value
            is Number -> value.toDouble()
            is Enum<*> -> value.name
            is Map<*, *> -> HashMap<String, Any?>().also { m -> value.forEach { (k, v) -> m[k.toString()] = normalise(v) } }
            is Collection<*> -> ArrayList<Any?>().also { l -> value.forEach { l.add(normalise(it)) } }
            is Array<*> -> ArrayList<Any?>().also { l -> value.forEach { l.add(normalise(it)) } }
            else -> value.toString()
        }

        fun normaliseUpdates(updates: Map<String, Any?>): Map<String, Any?> = HashMap<String, Any?>().also { m ->
            updates.forEach { (k, v) -> m[k] = normalise(v) }
        }
    }
}
