package pl.mos.backoffice.projects.domain.service

import pl.mos.backoffice.projects.domain.model.CiMode
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.WorkflowDefinition
import pl.mos.backoffice.projects.domain.model.WorkflowNode

data class CiSnippet(val gitlabCi: String, val scriptUrl: String, val variables: List<String>)

/**
 * Renders a ready-to-paste `.gitlab-ci.yml` fragment for a project (docs/architecture/05-integrations.md):
 * one job per CI entrypoint (`backoffice-ci run <key> --wait`) and one signal job per `WAIT_FOR_CI`
 * signal used by the project's workflows. The script itself is downloaded from the platform.
 */
class CiSnippetRenderer {

    fun render(project: Project, workflows: List<WorkflowDefinition>, publicUrl: String): CiSnippet {
        val base = publicUrl.trimEnd('/')
        val scriptUrl = "$base/ci/backoffice-ci.sh"
        val signals = workflows.flatMap { w -> w.nodes.filterIsInstance<WorkflowNode.WaitForCi>().map { it.ci.signal } }.distinct().sorted()
        val yaml = buildString {
            appendLine("# Back Office CI integration for project ${project.key} (${project.name}).")
            appendLine("# 1. Create a CI token in Back Office (project tab CI -> CI tokens).")
            appendLine("# 2. Add it as a CI/CD variable named $TOKEN_VARIABLE: Masked, NOT Protected (protected variables")
            appendLine("#    never reach merge request pipelines of unprotected feature branches).")
            appendLine("# 3. Paste this into .gitlab-ci.yml (or include it from a shared repository).")
            appendLine()
            appendLine("variables:")
            appendLine("  $URL_VARIABLE: \"$base\"")
            appendLine()
            appendLine(".backoffice:")
            appendLine("  image: alpine:3.20")
            appendLine("  before_script:")
            appendLine("    - apk add --no-cache curl git jq")
            appendLine("    - curl -fsSL \"\$$URL_VARIABLE/ci/backoffice-ci.sh\" -o backoffice-ci.sh && chmod +x backoffice-ci.sh")
            project.ciEntrypoints.forEach { entrypoint ->
                appendLine()
                appendLine("backoffice:${entrypoint.key}:")
                appendLine(EXTENDS_BASE_JOB)
                appendLine("  stage: test")
                if (entrypoint.key == "code-review") {
                    appendLine("  rules:")
                    appendLine("    - if: \$CI_MERGE_REQUEST_IID")
                }
                appendLine(SCRIPT_KEY)
                appendLine("    - ./backoffice-ci.sh run ${entrypoint.key} --wait --diff ${if (entrypoint.uploadDiff) "auto" else "none"}")
                appendLine("  allow_failure: ${entrypoint.mode == CiMode.ADVISORY}")
            }
            signals.forEach { signal ->
                appendLine()
                appendLine("# Reports the result of your test stage to a workflow run waiting on the signal \"$signal\".")
                appendLine("backoffice:signal-$signal:")
                appendLine(EXTENDS_BASE_JOB)
                appendLine("  stage: .post")
                appendLine(SCRIPT_KEY)
                appendLine("    - ./backoffice-ci.sh signal $signal --status success")
                appendLine("  when: on_success")
                appendLine()
                appendLine("backoffice:signal-$signal-failed:")
                appendLine(EXTENDS_BASE_JOB)
                appendLine("  stage: .post")
                appendLine(SCRIPT_KEY)
                appendLine("    - ./backoffice-ci.sh signal $signal --status fail --message \"pipeline \$CI_PIPELINE_ID failed\"")
                appendLine("  when: on_failure")
            }
        }
        return CiSnippet(yaml, scriptUrl, listOf(URL_VARIABLE, TOKEN_VARIABLE))
    }

    companion object {
        const val URL_VARIABLE = "BACKOFFICE_URL"
        const val TOKEN_VARIABLE = "BACKOFFICE_CI_TOKEN"

        /** Every job extends the hidden `.backoffice` job that downloads the script. */
        private const val EXTENDS_BASE_JOB = "  extends: .backoffice"
        private const val SCRIPT_KEY = "  script:"
    }
}
