package pl.mos.backoffice.projects.adapter.`in`.rest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pl.mos.backoffice.projects.adapter.ProjectsTestApplication
import pl.mos.backoffice.projects.adapter.ProjectsTestBeans
import pl.mos.backoffice.projects.application.port.out.PersonalIntakeRepository
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.domain.model.ConnectionId
import pl.mos.backoffice.projects.domain.model.IntakeProposal
import pl.mos.backoffice.projects.domain.model.IntakeRuleId
import pl.mos.backoffice.projects.domain.model.IssueHeadline
import pl.mos.backoffice.projects.domain.model.PersonalIntakeRule
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProposalState
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** ADR 0038 through REST and PostgreSQL: private projects for members, the personal intake tables and endpoints. */
@SpringBootTest(classes = [ProjectsTestApplication::class])
@AutoConfigureMockMvc
@Import(ProjectsTestBeans::class)
class MyWorkRestTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var projects: ProjectRepository
    @Autowired lateinit var intake: PersonalIntakeRepository

    private fun key(prefix: String) = prefix + UUID.randomUUID().toString().take(4).uppercase().replace(Regex("[^A-Z0-9]"), "X")

    @Test
    fun `a member creates a private project but not a team project`() {
        val member = "projects.create.personal"
        mvc.perform(
            post("/api/v1/projects").header("X-Test-Platform-Permissions", member).contentType(MediaType.APPLICATION_JSON)
                .content("""{"key":"${key("TEAM")}","name":"Team"}"""),
        ).andExpect(status().isForbidden)

        val k = key("MINE")
        mvc.perform(
            post("/api/v1/projects").header("X-Test-Platform-Permissions", member).contentType(MediaType.APPLICATION_JSON)
                .content("""{"key":"$k","name":"My automation","personal":true}"""),
        ).andExpect(status().isCreated).andExpect(jsonPath("$.personal").value(true))
        assertThat(projects.findByKey(k)!!.personal).isTrue()
    }

    @Test
    fun `rules and proposals persist, each issue is proposed once per rule, deleting a rule removes its proposals`() {
        val owner = UUID.randomUUID()
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val rule = intake.saveRule(
            PersonalIntakeRule(
                IntakeRuleId.new(), owner, "Olivia", "My issues", ConnectionId.new(), "assignee = currentUser()", 5, WorkflowDefinitionId.new(),
                nextPollAt = now, createdAt = now, updatedAt = now,
            ),
        )
        assertThat(intake.findRule(rule.id)).isEqualTo(rule)
        assertThat(intake.dueRules(now.plusSeconds(1)).map { it.id }).contains(rule.id)

        val first = IntakeProposal.found(rule, IssueHeadline("SHOP-1", "Fix login", "Bug", "To Do", "High"), ProposalState.NEW, now)
        assertThat(intake.insertProposalIfAbsent(first)).isTrue()
        assertThat(intake.insertProposalIfAbsent(first.copy(id = pl.mos.backoffice.projects.domain.model.IntakeProposalId.new()))).isFalse()
        assertThat(intake.insertProposalIfAbsent(IntakeProposal.found(rule, IssueHeadline("SHOP-2", null, null, null, null), ProposalState.BASELINE, now))).isTrue()
        assertThat(intake.knownKeys(rule.id)).containsExactlyInAnyOrder("SHOP-1", "SHOP-2")
        assertThat(intake.newCounts(owner)).containsEntry(rule.id, 1)
        assertThat(intake.proposalsOf(owner, ProposalState.NEW).single()).isEqualTo(first)

        val dismissed = intake.saveProposal(first.dismissed(now))
        assertThat(intake.findProposal(first.id)).isEqualTo(dismissed)
        assertThat(intake.proposalsOf(owner, ProposalState.NEW)).isEmpty()

        intake.deleteRule(rule.id)
        assertThat(intake.findRule(rule.id)).isNull()
        assertThat(intake.findProposal(first.id)).isNull()
        assertThat(projects.findById(ProjectId.new())).isNull()
    }

    @Test
    fun `the my-work endpoints need connections personal and validate the rule`() {
        mvc.perform(get("/api/v1/me/intake/rules")).andExpect(status().isForbidden)
        val personal = "connections.personal"
        mvc.perform(get("/api/v1/me/intake/rules").header("X-Test-Extra-Platform-Permissions", personal))
            .andExpect(status().isOk).andExpect(jsonPath("$.length()").value(0))
        mvc.perform(get("/api/v1/me/intake/proposals?state=DISMISSED").header("X-Test-Extra-Platform-Permissions", personal)).andExpect(status().isOk)
        mvc.perform(get("/api/v1/me/intake/proposals?state=BASELINE").header("X-Test-Extra-Platform-Permissions", personal)).andExpect(status().isBadRequest)
        mvc.perform(
            post("/api/v1/me/intake/rules").header("X-Test-Extra-Platform-Permissions", personal).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Mine","connectionId":"${UUID.randomUUID()}","jql":"assignee = currentUser()","workflowId":"${UUID.randomUUID()}"}"""),
        ).andExpect(status().isBadRequest)
    }
}
