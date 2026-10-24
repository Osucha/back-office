package pl.mos.backoffice.projects.adapter.`in`.rest

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflowLibrary
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflows
import pl.mos.backoffice.projects.application.port.`in`.StartRun
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.shared.tasks.RunInputField
import pl.mos.backoffice.shared.tasks.RunInputType
import java.util.UUID

/** `GET /workflows/{workflowId}/run-inputs` (ADR 0052): the `RunInputField` list of openapi.yaml. */
class WorkflowRunInputsControllerTest {

    private val workflows = Mockito.mock(ManageWorkflows::class.java)
    private val mvc = MockMvcBuilders.standaloneSetup(
        WorkflowController(workflows, Mockito.mock(StartRun::class.java), RestMapper(), Mockito.mock(ManageWorkflowLibrary::class.java)),
    ).build()

    @Test
    fun `returns the effective run inputs with name, label, type, required and help`() {
        val id = UUID.randomUUID()
        Mockito.`when`(workflows.runInputs(WorkflowDefinitionId(id))).thenReturn(
            listOf(
                RunInputField("issueKey", "Jira issue", RunInputType.ISSUE_KEY, required = true, help = "e.g. ABC-1"),
                RunInputField("input", "Input material", RunInputType.MULTILINE),
            ),
        )

        mvc.perform(get("/api/v1/workflows/$id/run-inputs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].name").value("issueKey"))
            .andExpect(jsonPath("$[0].label").value("Jira issue"))
            .andExpect(jsonPath("$[0].type").value("ISSUE_KEY"))
            .andExpect(jsonPath("$[0].required").value(true))
            .andExpect(jsonPath("$[0].help").value("e.g. ABC-1"))
            .andExpect(jsonPath("$[1].type").value("MULTILINE"))
            .andExpect(jsonPath("$[1].required").value(false))
    }
}
