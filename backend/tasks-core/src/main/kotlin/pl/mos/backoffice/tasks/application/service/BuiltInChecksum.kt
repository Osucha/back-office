package pl.mos.backoffice.tasks.application.service

import pl.mos.backoffice.shared.runner.AgentAccess
import pl.mos.backoffice.shared.runner.AgentSession
import pl.mos.backoffice.tasks.domain.model.Agent
import pl.mos.backoffice.tasks.domain.model.Rule
import pl.mos.backoffice.tasks.domain.model.Skill
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import java.security.MessageDigest

/**
 * Content checksums of library entries over every editable field, so the seed can tell whether a stored built-in
 * already matches the shipped definition. Identity, scope, version and audit fields are left out.
 *
 * The canonical form survives a database round trip: map keys are sorted (jsonb reorders them), `null` values in
 * config maps are dropped (the JSON mapper does not write them), whole numbers compare equal whatever their type,
 * and empty instructions or an empty schema equal missing ones. Without that a template whose shipped config holds
 * `verifyCommand: null` would look changed on every start.
 */
object BuiltInChecksum {

    fun of(t: TaskTemplate): String = sha256(
        canonical(
            mapOf(
                "name" to t.name, "description" to t.description, "abstract" to t.abstract, "extendsKey" to t.extendsKey,
                "category" to t.category?.name,
                "instructions" to t.instructions, "instructionsMode" to t.instructionsMode.name,
                "config" to t.config, "outputSchema" to t.outputSchema,
                "confidenceField" to t.confidenceField, "produces" to t.produces, "consumes" to t.consumes, "tags" to t.tags,
                "parameters" to t.parameters.map { mapOf("name" to it.name, "label" to it.label, "type" to it.type.name, "required" to it.required, "default" to it.default, "options" to it.options, "help" to it.help) },
                "parameterValues" to t.parameterValues,
                // ADR 0060: only when set, so the checksums of templates without it stay
                "requiresSkills" to t.requiresSkills.takeIf { it.isNotEmpty() },
            ),
        ),
    )

    fun of(s: Skill): String = sha256(canonical(mapOf("name" to s.name, "description" to s.description, "content" to s.content, "tags" to s.tags)))

    fun of(r: Rule): String = sha256(
        canonical(mapOf("name" to r.name, "description" to r.description, "content" to r.content, "automatic" to r.automatic, "tags" to r.tags)),
    )

    fun of(a: Agent): String = sha256(
        canonical(
            mapOf(
                "name" to a.name, "description" to a.description, "harness" to a.harness?.name, "modelProfileId" to a.modelProfileId?.value?.toString(), "tags" to a.tags,
                "instructions" to a.instructions, "rules" to a.rules, "skills" to a.skills, "mcpServers" to a.mcpServers.map { mapOf("name" to it.name, "url" to it.url) },
                // ADR 0060: only when not FULL, so the checksums of other agents stay
                "access" to a.access.takeUnless { it == AgentAccess.FULL }?.let { mapOf("mode" to it.mode.name, "paths" to it.paths, "directory" to it.directory.name.takeIf { _ -> it.separate }) },
                // ADR 0062: only when not the default
                "session" to a.session.name.takeUnless { a.session == AgentSession.PER_STEP },
            ),
        ),
    )

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
