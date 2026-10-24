package pl.mos.backoffice.tasks.application.service

import pl.mos.backoffice.tasks.domain.model.Skill
import pl.mos.backoffice.tasks.domain.model.Slot
import pl.mos.backoffice.tasks.domain.model.TaskInterface
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import java.security.MessageDigest

/**
 * Content checksums of library entries over every editable field, so the seed can tell whether a stored built-in
 * already matches the shipped definition. Identity, scope, version and audit fields are left out.
 *
 * The canonical form survives a database round trip: map keys are sorted (jsonb reorders them), `null` values in
 * config maps are dropped (the JSON mapper does not write them), whole numbers compare equal whatever their type,
 * and an empty prompt or schema equals a missing one. Without that a template whose shipped config holds
 * `verifyCommand: null` would look changed on every start.
 */
object BuiltInChecksum {

    fun of(t: TaskTemplate): String = sha256(
        canonical(
            mapOf(
                "name" to t.name, "description" to t.description, "abstract" to t.abstract, "extendsKey" to t.extendsKey,
                "implements" to t.implements, "executor" to t.executor?.name, "modelProfileId" to t.modelProfileId?.value?.toString(),
                "promptSystem" to t.prompt.system, "promptUser" to t.prompt.user, "promptMode" to t.prompt.mode.name,
                "skills" to t.skills, "removeSkills" to t.removeSkills, "config" to t.config, "outputSchema" to t.outputSchema,
                "confidenceField" to t.confidenceField, "produces" to t.produces, "consumes" to t.consumes, "tags" to t.tags,
                "runInputs" to t.runInputs.map { mapOf("name" to it.name, "label" to it.label, "type" to it.type.name, "required" to it.required, "help" to it.help) },
            ),
        ),
    )

    fun of(s: Skill): String = sha256(canonical(mapOf("name" to s.name, "description" to s.description, "content" to s.content, "tags" to s.tags)))

    fun of(i: TaskInterface): String = sha256(
        canonical(
            mapOf(
                "name" to i.name, "description" to i.description, "inputs" to i.inputs.map(::slot), "outputs" to i.outputs.map(::slot),
                "ports" to i.ports,
            ),
        ),
    )

    private fun slot(s: Slot): Map<String, Any?> = mapOf("name" to s.name, "kind" to s.kind.name, "required" to s.required, "description" to s.description)

    /** Deterministic text of a JSON-like value: sorted keys, no null or blank-string map entries, normalised numbers. */
    fun canonical(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean -> value.toString()
        is Number -> number(value)
        is Enum<*> -> quote(value.name)
        is Map<*, *> -> value.entries
            .filter { (_, v) -> v != null && !(v is String && v.isEmpty()) }
            .map { (k, v) -> k.toString() to v }
            .sortedBy { it.first }
            .joinToString(",", "{", "}") { (k, v) -> quote(k) + ":" + canonical(v) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { canonical(it) }
        is Array<*> -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> quote(value.toString())
    }

    private fun number(n: Number): String {
        val d = n.toDouble()
        return if (d == Math.floor(d) && !d.isInfinite() && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()
    }

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
