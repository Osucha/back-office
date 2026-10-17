package pl.mos.backoffice.runnercontroller.sandbox

import tools.jackson.databind.JsonNode

/**
 * ADR 0044: the persistent workspace of a run that a job names in `spec.workspace.name` (signed with the spec). The
 * backend mounts it at `/workspace` as a Docker volume or a PersistentVolumeClaim of that name, labelled so the
 * controller can list what it holds and delete what Back Office released (`POST /runner/v2/workspaces/sync`).
 */
object Workspaces {
    /** `bo-ws-<run id hex>-<key>`, as Back Office builds it; anything else is refused (it becomes a volume name). */
    val NAME = Regex("^bo-ws-[0-9a-f]{32}-[a-z][a-z0-9-]{0,19}$")

    /** Label (value `true`) of every workspace volume / PVC this controller creates. */
    const val LABEL = "backoffice.io/workspace"

    /** The workspace name of [spec], or null when it names none or an invalid one. */
    fun nameOf(spec: JsonNode?): String? {
        val name = spec?.get("workspace")?.get("name")?.takeIf { it.isString }?.asString() ?: return null
        return name.takeIf { NAME.matches(it) }
    }

    /** Whether [spec] names a workspace at all (valid or not). */
    fun requested(spec: JsonNode?): Boolean = spec?.get("workspace")?.isObject == true
}
