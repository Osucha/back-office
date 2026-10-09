package pl.mos.backoffice.projects.adapter.`in`.rest

import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pl.mos.backoffice.projects.adapter.ConcurrentRecordingAuditPort
import pl.mos.backoffice.projects.adapter.FakeModelCatalog
import pl.mos.backoffice.projects.adapter.FakeProjectAccessDirectory
import pl.mos.backoffice.projects.adapter.ProjectsTestApplication
import pl.mos.backoffice.projects.adapter.ProjectsTestBeans
import pl.mos.backoffice.projects.adapter.RecordingProjectAccessProvisioner
import pl.mos.backoffice.projects.adapter.TestAuthorizationGate
import pl.mos.backoffice.projects.adapter.out.langgraph.LangGraphWorkflowEngine
import pl.mos.backoffice.projects.domain.model.WorkflowRunId
import pl.mos.backoffice.shared.security.CurrentUser
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.security.UserId
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Project-scoped access through the REST layer: lists only show projects the caller can view, mutations
 * need their permission (403 otherwise), and a human gate's approver rule and checklist decide who may
 * approve (`canDecide` in the inbox, 409 for a refused rule, 400 for missing checklist items).
 */
@SpringBootTest(classes = [ProjectsTestApplication::class])
@AutoConfigureMockMvc
@Import(ProjectsTestBeans::class)
class AccessControlRestTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: JsonMapper
    @Autowired lateinit var catalog: FakeModelCatalog
    @Autowired lateinit var audit: ConcurrentRecordingAuditPort
    @Autowired lateinit var provisioner: RecordingProjectAccessProvisioner
    @Autowired lateinit var engine: LangGraphWorkflowEngine
    @Autowired lateinit var directory: FakeProjectAccessDirectory

    private fun key(prefix: String) = prefix + UUID.randomUUID().toString().take(4).uppercase().replace(Regex("[^A-Z0-9]"), "X")

    private fun read(body: String): Map<*, *> = json.readValue(body, Map::class.java)

    private fun createProject(key: String): Map<*, *> {
        val body = """{"key":"$key","name":"P $key","repository":{"url":"https://git.example.com/g/$key.git","defaultBranch":"main"},"defaults":{"modelProfileId":"${catalog.add()}"}}"""
        return read(mvc.perform(post("/api/v1/projects").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated).andReturn().response.contentAsString)
    }

    private fun MockHttpServletRequestBuilder.asMember(projectId: Any?, vararg permissions: String, roles: String? = null): MockHttpServletRequestBuilder {
        header("X-Test-Project-Permissions", "$projectId=${permissions.joinToString(",")}")
        roles?.let { header("X-Test-Project-Roles", it) }
        return this
    }

    @Test
    fun `a viewer of one project sees only that project and cannot change anything`() {
        val a = createProject(key("ACA"))
        val b = createProject(key("ACB"))
        assertThat(provisioner.granted.map { it.first.toString() }).contains(a["id"].toString(), b["id"].toString())
        assertThat(audit.operations()).contains("PROJECT_CREATE")

        mvc.perform(get("/api/v1/projects").asMember(a["id"], "project.view"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(a["id"].toString()))
            .andExpect(jsonPath("$[0].myPermissions[0]").value("project.view"))
        mvc.perform(get("/api/v1/projects/${b["id"]}").asMember(a["id"], "project.view")).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/projects/${a["id"]}/ci-tokens").asMember(a["id"], "project.view")).andExpect(status().isForbidden)
        mvc.perform(post("/api/v1/projects/${a["id"]}/ci-tokens").asMember(a["id"], "project.view").contentType(MediaType.APPLICATION_JSON).content("""{"name":"x"}"""))
            .andExpect(status().isForbidden)
        mvc.perform(post("/api/v1/projects/${a["id"]}/ci-tokens").asMember(a["id"], "project.ci.manage").contentType(MediaType.APPLICATION_JSON).content("""{"name":"x"}"""))
            .andExpect(status().isCreated).andExpect(jsonPath("$.createdBy.displayName").value("Mia Member"))
        mvc.perform(
            put("/api/v1/projects/${a["id"]}").asMember(a["id"], "project.view").contentType(MediaType.APPLICATION_JSON)
                .content("""{"key":"${a["key"]}","name":"renamed","repository":{"url":"https://x/y.git","defaultBranch":"main"}}"""),
        ).andExpect(status().isForbidden)
        mvc.perform(post("/api/v1/projects").asMember(a["id"], "project.view").contentType(MediaType.APPLICATION_JSON)
            .content("""{"key":"${key("NOP")}","name":"x","repository":{"url":"https://x/y.git","defaultBranch":"main"}}"""))
            .andExpect(status().isForbidden)

        val workflows = json.readValue(
            mvc.perform(get("/api/v1/projects/${a["id"]}/workflows").asMember(a["id"], "project.view")).andExpect(status().isOk).andReturn().response.contentAsString,
            List::class.java,
        ).map { it as Map<*, *> }
        val review = workflows.first { it["name"] == "CI code review" }
        mvc.perform(post("/api/v1/workflows/${review["id"]}/runs").asMember(a["id"], "project.view").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden)
        mvc.perform(post("/api/v1/workflows/${review["id"]}/duplicate").asMember(a["id"], "project.view")).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/settings/integrations").asMember(a["id"], "project.view")).andExpect(status().isForbidden)

        mvc.perform(get("/api/v1/runs").asMember(b["id"], "project.view")).andExpect(status().isOk)
        mvc.perform(get("/api/v1/approvals").asMember(b["id"], "project.view")).andExpect(status().isOk)
    }

    @Test
    fun `the gate approver rule and checklist decide who may approve, and the decision is stamped and audited`() {
        val project = createProject(key("GAT"))
        val workflow = """
            {"name":"gated","nodes":[
              {"id":"start","kind":"START","name":"Start","position":{"x":0,"y":0}},
              {"id":"gate","kind":"HUMAN_GATE","name":"Release?","position":{"x":1,"y":0},
               "gate":{"question":"Release?","approvers":{"projectRoles":["approver","owner"],"accountable":"owner","forbidSelfApproval":false},
                       "checklist":[{"id":"notes","text":"Release notes written","required":true},{"id":"demo","text":"Demo recorded","required":false}]}},
              {"id":"done","kind":"END","name":"Done","position":{"x":2,"y":0},"end":{"outcome":"SUCCESS"}}
            ],"edges":[{"id":"e1","from":"start","fromPort":"out","to":"gate"},{"id":"e2","from":"gate","fromPort":"approve","to":"done"}]}
        """.trimIndent()
        val created = read(
            mvc.perform(post("/api/v1/projects/${project["id"]}/workflows").contentType(MediaType.APPLICATION_JSON).content(workflow))
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.nodes[1].gate.approvers.accountable").value("owner"))
                .andExpect(jsonPath("$.nodes[1].gate.checklist[0].id").value("notes"))
                .andReturn().response.contentAsString,
        )
        val bad = workflow.replace("\"accountable\":\"owner\"", "\"accountable\":\"viewer\"")
        mvc.perform(post("/api/v1/workflows/validate?projectId=${project["id"]}").contentType(MediaType.APPLICATION_JSON).content(bad))
            .andExpect(status().isOk).andExpect(jsonPath("$.valid").value(false))
            .andExpect(jsonPath("$.errors[0].field").value("gate.approvers.accountable"))

        val run = read(mvc.perform(post("/api/v1/workflows/${created["id"]}/runs").contentType(MediaType.APPLICATION_JSON).content("""{"issueKey":"GAT-1"}"""))
            .andExpect(status().isAccepted).andExpect(jsonPath("$.startedBy.displayName").value("Olivia Owner")).andReturn().response.contentAsString)
        val runId = run["id"].toString()
        val approvalId = awaitApproval(runId)

        // a member with run.approve but without one of the gate roles sees the gate but cannot decide it
        mvc.perform(get("/api/v1/approvals/$approvalId").asMember(project["id"], "project.view", "run.approve", roles = "developer"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.canDecide").value(false))
            .andExpect(jsonPath("$.cannotDecideReason").value(Matchers.containsString("approver, owner")))
            .andExpect(jsonPath("$.approvers.accountable").value("owner"))
            .andExpect(jsonPath("$.checklist.length()").value(2))
            .andExpect(jsonPath("$.startedBy.displayName").value("Olivia Owner"))
        mvc.perform(post("/api/v1/approvals/$approvalId/decide").asMember(project["id"], "project.view", "run.approve", roles = "developer")
            .contentType(MediaType.APPLICATION_JSON).content("""{"option":"reject"}"""))
            .andExpect(status().isConflict)
        mvc.perform(post("/api/v1/approvals/$approvalId/decide").asMember(project["id"], "project.view", roles = "approver")
            .contentType(MediaType.APPLICATION_JSON).content("""{"option":"reject"}"""))
            .andExpect(status().isForbidden)

        // an approver must tick the required checklist items
        val approver = { b: MockHttpServletRequestBuilder -> b.asMember(project["id"], "project.view", "run.approve", roles = "approver") }
        mvc.perform(approver(get("/api/v1/approvals"))).andExpect(status().isOk).andExpect(jsonPath("$[?(@.id == '$approvalId')].canDecide").value(true))
        mvc.perform(approver(post("/api/v1/approvals/$approvalId/decide")).contentType(MediaType.APPLICATION_JSON).content("""{"option":"approve","checkedItems":["demo"]}"""))
            .andExpect(status().isBadRequest)
        mvc.perform(approver(post("/api/v1/approvals/$approvalId/decide")).contentType(MediaType.APPLICATION_JSON).content("""{"option":"approve","checkedItems":["notes"],"comment":"go"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("DECIDED"))
            .andExpect(jsonPath("$.decision.checkedItems[0]").value("notes"))
            .andExpect(jsonPath("$.decision.decidedBy.displayName").value("Mia Member"))
        engine.awaitIdle(WorkflowRunId.of(runId))

        val decided = audit.entries.filter { it.operation == "RUN_GATE_DECIDED" && it.entityId == approvalId }
        assertThat(decided.map { it.result.name }).containsExactly("DENIED", "SUCCESS")
        assertThat(decided.last().details["checkedItems"]).isEqualTo(listOf("notes"))
        assertThat(audit.entries.filter { it.operation == "RUN_START" && it.entityId == runId }).hasSize(1)
    }

    @Test
    fun `an owner assigns an open gate to a member who can approve, the assignee decides it and the change is audited`() {
        val project = createProject(key("ASG"))
        val projectId = UUID.fromString(project["id"].toString())
        fun user(id: UUID, name: String, permissions: Set<String>, roles: Set<String>) = directory.register(
            CurrentUser(PrincipalKind.USER, UserId(id), "$id@example.com", name, emptySet(), mapOf(projectId to permissions), projectRoles = mapOf(projectId to roles)),
        )
        val ann = user(UUID.randomUUID(), "Ann Approver", setOf("project.view", "run.approve"), setOf("approver"))
        val dave = user(UUID.randomUUID(), "Dave Developer", setOf("project.view", "run.approve"), setOf("developer"))
        val vic = user(UUID.randomUUID(), "Vic Viewer", setOf("project.view"), setOf("approver"))
        val mia = user(TestAuthorizationGate.member(projectId, emptySet()).userId!!.value, "Mia Member", setOf("project.view", "run.approve"), setOf("approver"))
        directory.register(TestAuthorizationGate.OWNER)
        val annId = ann.userId!!.value
        val miaId = mia.userId!!.value

        val workflow = """
            {"name":"assigned","nodes":[
              {"id":"start","kind":"START","name":"Start","position":{"x":0,"y":0}},
              {"id":"gate","kind":"HUMAN_GATE","name":"Release?","position":{"x":1,"y":0},
               "gate":{"question":"Release?","approvers":{"projectRoles":["approver","owner"],"users":["$annId"],"forbidSelfApproval":true}}},
              {"id":"done","kind":"END","name":"Done","position":{"x":2,"y":0},"end":{"outcome":"SUCCESS"}}
            ],"edges":[{"id":"e1","from":"start","fromPort":"out","to":"gate"},{"id":"e2","from":"gate","fromPort":"approve","to":"done"}]}
        """.trimIndent()
        val created = read(mvc.perform(post("/api/v1/projects/$projectId/workflows").contentType(MediaType.APPLICATION_JSON).content(workflow))
            .andExpect(status().isCreated).andReturn().response.contentAsString)
        val runId = read(mvc.perform(post("/api/v1/workflows/${created["id"]}/runs").contentType(MediaType.APPLICATION_JSON).content("""{"issueKey":"ASG-1"}"""))
            .andExpect(status().isAccepted).andReturn().response.contentAsString)["id"].toString()
        val approvalId = awaitApproval(runId)
        val asMia = { b: MockHttpServletRequestBuilder -> b.asMember(projectId, "project.view", "run.approve", roles = "approver") }
        fun assign(body: String, builder: (MockHttpServletRequestBuilder) -> MockHttpServletRequestBuilder = { it }) =
            mvc.perform(builder(put("/api/v1/approvals/$approvalId/assignees")).contentType(MediaType.APPLICATION_JSON).content(body))

        // the definition names Ann: Mia (an approver) sees the gate but cannot decide it, and cannot assign it
        mvc.perform(asMia(get("/api/v1/approvals/$approvalId"))).andExpect(status().isOk)
            .andExpect(jsonPath("$.canDecide").value(false))
            .andExpect(jsonPath("$.canAssign").value(false))
            .andExpect(jsonPath("$.assignees[0].displayName").value("Ann Approver"))
            .andExpect(jsonPath("$.assignment").doesNotExist())
        assign("""{"userIds":["$miaId"]}""", asMia).andExpect(status().isForbidden)
        mvc.perform(asMia(get("/api/v1/approvals/$approvalId/assignee-candidates"))).andExpect(status().isForbidden)

        // the owner (run.assign) is offered only members who could decide: not the developer, the viewer or the run's starter
        mvc.perform(get("/api/v1/approvals/$approvalId/assignee-candidates")).andExpect(status().isOk)
            .andExpect(jsonPath("$[*].displayName").value(Matchers.containsInAnyOrder("Ann Approver", "Mia Member")))
            .andExpect(jsonPath("$[?(@.displayName == 'Ann Approver')].assigned").value(true))
        assign("""{"userIds":["${dave.userId}"]}""").andExpect(status().isBadRequest)
        assign("""{"userIds":["${vic.userId}"]}""").andExpect(status().isBadRequest)
        assign("""{"userIds":["${TestAuthorizationGate.OWNER_ID}"]}""").andExpect(status().isBadRequest) // four-eyes: Olivia started the run
        assign("""{"userIds":["${UUID.randomUUID()}"]}""").andExpect(status().isBadRequest)
        assign("""{"comment":"no list"}""").andExpect(status().isBadRequest)

        assign("""{"userIds":["$miaId"],"comment":"Ann is on leave"}""").andExpect(status().isOk)
            .andExpect(jsonPath("$.assignees[0].userId").value(miaId.toString()))
            .andExpect(jsonPath("$.assignees[0].displayName").value("Mia Member"))
            .andExpect(jsonPath("$.assignment.assignedBy.displayName").value("Olivia Owner"))
            .andExpect(jsonPath("$.assignment.comment").value("Ann is on leave"))
            .andExpect(jsonPath("$.canAssign").value(true))
        mvc.perform(asMia(get("/api/v1/approvals"))).andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.id == '$approvalId')].canDecide").value(true))
        val assigned = audit.entries.single { it.operation == "APPROVAL_ASSIGN" && it.entityId == approvalId }
        assertThat(assigned.details["old"]).isEqualTo(listOf(annId.toString()))
        assertThat(assigned.details["new"]).isEqualTo(listOf(miaId.toString()))
        assertThat(assigned.projectId).isEqualTo(projectId)

        mvc.perform(asMia(post("/api/v1/approvals/$approvalId/decide")).contentType(MediaType.APPLICATION_JSON).content("""{"option":"approve"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.decision.decidedBy.displayName").value("Mia Member"))
        engine.awaitIdle(WorkflowRunId.of(runId))
        assign("""{"userIds":["$annId"]}""").andExpect(status().isConflict)
    }

    @Test
    fun `clearing the assignment of an open gate brings back the definition's approvers`() {
        val project = createProject(key("CLR"))
        val projectId = UUID.fromString(project["id"].toString())
        val ann = directory.register(
            CurrentUser(PrincipalKind.USER, UserId(UUID.randomUUID()), "ann@example.com", "Ann Again", emptySet(), mapOf(projectId to setOf("run.approve")), projectRoles = mapOf(projectId to setOf("approver"))),
        )
        val workflow = """
            {"name":"cleared","nodes":[
              {"id":"start","kind":"START","name":"Start","position":{"x":0,"y":0}},
              {"id":"gate","kind":"HUMAN_GATE","name":"Go?","position":{"x":1,"y":0},"gate":{"question":"Go?"}},
              {"id":"done","kind":"END","name":"Done","position":{"x":2,"y":0},"end":{"outcome":"SUCCESS"}}
            ],"edges":[{"id":"e1","from":"start","fromPort":"out","to":"gate"},{"id":"e2","from":"gate","fromPort":"approve","to":"done"}]}
        """.trimIndent()
        val created = read(mvc.perform(post("/api/v1/projects/$projectId/workflows").contentType(MediaType.APPLICATION_JSON).content(workflow))
            .andExpect(status().isCreated).andReturn().response.contentAsString)
        val runId = read(mvc.perform(post("/api/v1/workflows/${created["id"]}/runs").contentType(MediaType.APPLICATION_JSON).content("""{"issueKey":"CLR-1"}"""))
            .andExpect(status().isAccepted).andReturn().response.contentAsString)["id"].toString()
        val approvalId = awaitApproval(runId)

        mvc.perform(put("/api/v1/approvals/$approvalId/assignees").contentType(MediaType.APPLICATION_JSON).content("""{"userIds":["${ann.userId}"]}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.assignees.length()").value(1))
        // a maintainer-like member with run.assign (but no run.approve) may assign too
        mvc.perform(put("/api/v1/approvals/$approvalId/assignees").asMember(projectId, "project.view", "run.assign", roles = "maintainer")
            .contentType(MediaType.APPLICATION_JSON).content("""{"userIds":[]}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.assignees.length()").value(0))
            .andExpect(jsonPath("$.assignment").doesNotExist())
        assertThat(audit.entries.filter { it.operation == "APPROVAL_ASSIGN" && it.entityId == approvalId }.last().details["cleared"]).isEqualTo(true)
        mvc.perform(post("/api/v1/runs/$runId/cancel")).andExpect(status().is2xxSuccessful)
    }

    private fun awaitApproval(runId: String): String {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val run = read(mvc.perform(get("/api/v1/runs/$runId")).andExpect(status().isOk).andReturn().response.contentAsString)
            val waiting = run["waitingOn"] as Map<*, *>?
            if (run["status"] == "WAITING" && waiting?.get("approvalId") != null) return waiting["approvalId"].toString()
            Thread.sleep(100)
        }
        throw AssertionError("run $runId did not reach the gate")
    }
}
