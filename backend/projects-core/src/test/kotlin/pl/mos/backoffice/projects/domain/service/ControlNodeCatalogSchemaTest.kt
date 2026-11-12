package pl.mos.backoffice.projects.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR 0063: the coding agent config keeps the work's keys; git, repositories, setup and Spec Kit keys are gone. */
class ControlNodeCatalogSchemaTest {

    @Test
    fun `the coding agent config schema has no git, repository, setup or phase keys`() {
        @Suppress("UNCHECKED_CAST")
        val properties = ControlNodeCatalog.codingAgentConfigSchema()["properties"] as Map<String, Any?>
        assertThat(properties.keys).contains("verifyCommand", "agentTools", "allowQuestions", "maxQuestionRounds", "codingAgent")
        assertThat(properties.keys).doesNotContain(
            "push", "branchTemplate", "mergeRequest", "mergeRequestTitleTemplate", "mergeRequestDescriptionTemplate", "mergeRequestDraft",
            "commitSubjectTemplate", "repositories", "setupCommand", "phase", "collectArtifacts",
        )
        assertThat(ControlNodeCatalog.stateFields().single { it.path == "mr.url" }.description).contains("Deliver changes")
    }
}
