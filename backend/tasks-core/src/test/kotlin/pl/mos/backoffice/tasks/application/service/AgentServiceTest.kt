package pl.mos.backoffice.tasks.application.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import pl.mos.backoffice.tasks.application.TasksPermissions
import pl.mos.backoffice.tasks.domain.model.Agent
import pl.mos.backoffice.tasks.domain.model.AgentId
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import pl.mos.backoffice.tasks.support.Library
import java.util.UUID

/** ADR 0056: agents are library entries with the access rules, key derivation and audit of rules. */
class AgentServiceTest {

    private val lib = Library(builtInAgents = false)
    private val other: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val curator = FakeAuthorizationGate.permitting(
        TasksPermissions.LIBRARY_VIEW, TasksPermissions.LIBRARY_MANAGE, TasksPermissions.LIBRARY_PROJECT_MANAGE,
        projects = setOf(lib.projectId, other),
    )
    private val audit = RecordingAuditPort()

    private fun agents(gate: FakeAuthorizationGate = curator) = AgentService(lib.agents, lib.templates, lib.clock, gate, audit, lib.lookup)
    private fun templates() = TaskTemplateService(lib.templates, lib.lookup, lib.clock, curator, audit)

    @Test
    fun `agents are created with a derived key, unique per scope, listed by name and updated keeping the key`() {
        val model = ModelProfileId.new()
        val cheap = agents().create(Agent.Content(name = "Tani koder", harness = AgentHarness.OPENCODE, modelProfileId = model, tags = listOf("x", "x")))
        assertThat(cheap.key).isEqualTo("tani-koder")
        assertThat(cheap.tags).containsExactly("x")
        assertThat(cheap.builtIn).isFalse()
        assertThat(cheap.updatedBy!!.displayName).isEqualTo("Alice Test")
        assertThat(cheap.snapshot.modelAnswer).isFalse()
        assertThat(agents().create(Agent.Content(name = "Tani koder")).key).describedAs("unique across scopes").isEqualTo("tani-koder-2")
        assertThat(agents().create(Agent.Content(name = "1st")).key).isEqualTo("agent-1st")
        assertThatThrownBy { agents().create(Agent.Content(key = "tani-koder", name = "Again")) }
            .isInstanceOf(InvalidStateException::class.java).hasMessageContaining("already exists")
        val mine = agents().create(Agent.Content(key = "tani-koder", name = "Answer", scope = Scope.Project(lib.projectId)))
        assertThat(mine.harness).describedAs("no harness = model answer").isNull()

        assertThat(agents().list().map { it.key }).containsExactly("agent-1st", "tani-koder", "tani-koder-2")
        assertThat(agents().list(lib.projectId).map { it.id }).first().isEqualTo(agents().list().first().id)
        assertThat(agents().list(lib.projectId)).contains(mine)
        assertThat(agents().get(cheap.id)).isEqualTo(cheap)

        val updated = agents().update(cheap.id, Agent.Content(name = "Claude", harness = AgentHarness.CLAUDE_CODE))
        assertThat(updated.key).isEqualTo("tani-koder")
        assertThat(updated.harness).isEqualTo(AgentHarness.CLAUDE_CODE)
        assertThat(updated.modelProfileId).isNull()
        assertThat(audit.entries.last().operation).isEqualTo("AGENT_UPDATE")
        assertThat(audit.entries.last().details["harness"]).isEqualTo(mapOf("old" to "OPENCODE", "new" to "CLAUDE_CODE"))
        assertThatThrownBy { agents().update(cheap.id, Agent.Content(key = "agent-1st", name = "Clash")) }.isInstanceOf(InvalidStateException::class.java)
        assertThatThrownBy { agents().create(Agent.Content(key = "Bad Key", name = "x")) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { agents().get(AgentId.new()) }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `an agent a template names cannot be deleted, renamed or moved`() {
        val agent = agents().create(Agent.Content(key = "coder", name = "Coder", harness = AgentHarness.OPENCODE))
        val template = templates().create(TaskTemplate.Content(key = "work", name = "Work", agentKey = "coder"))

        assertThatThrownBy { agents().delete(agent.id) }.isInstanceOf(InvalidStateException::class.java).hasMessageContaining("used by templates")
        assertThatThrownBy { agents().update(agent.id, Agent.Content(key = "renamed", name = "Coder")) }.isInstanceOf(InvalidStateException::class.java)
        assertThatThrownBy { agents().update(agent.id, Agent.Content(name = "Coder", scope = Scope.Project(lib.projectId))) }.isInstanceOf(InvalidStateException::class.java)
        assertThat(agents().update(agent.id, Agent.Content(name = "Coder 2")).name).isEqualTo("Coder 2")

        templates().delete(template.id)
        agents().delete(agent.id)
        assertThat(lib.agents.rows).isEmpty()
        assertThat(audit.operations()).containsExactly("AGENT_CREATE", "TEMPLATE_CREATE", "AGENT_UPDATE", "TEMPLATE_DELETE", "AGENT_DELETE")
    }

    @Test
    fun `a project agent is in use only by templates that see it`() {
        val project = agents().create(Agent.Content(key = "coder", name = "Coder", scope = Scope.Project(lib.projectId)))
        agents().create(Agent.Content(key = "coder", name = "Coder", scope = Scope.Project(other)))
        lib.template("work", agentKey = "coder", scope = Scope.Project(other))

        agents().delete(project.id)
        assertThat(lib.agents.findByKeyAndScope("coder", Scope.Project(lib.projectId))).isNull()
    }

    @Test
    fun `global agents need library manage to change and library view to read, project agents the project permissions`() {
        val reader = FakeAuthorizationGate.permitting(TasksPermissions.LIBRARY_VIEW)
        val created = agents().create(Agent.Content(name = "Global"))

        assertThat(agents(reader).list().map { it.key }).containsExactly("global")
        assertThat(agents(reader).get(created.id).key).isEqualTo("global")
        assertThatThrownBy { agents(reader).create(Agent.Content(name = "No")) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { agents(reader).update(created.id, Agent.Content(name = "No")) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { agents(reader).delete(created.id) }.isInstanceOf(InsufficientPermissionException::class.java)

        val editor = FakeAuthorizationGate.permitting(TasksPermissions.LIBRARY_PROJECT_MANAGE, projects = setOf(lib.projectId))
        val mine = agents(editor).create(Agent.Content(name = "Mine", scope = Scope.Project(lib.projectId)))
        assertThat(editor.checked).containsExactly("library.project.manage@${lib.projectId}")
        assertThat(agents(editor).list(lib.projectId).map { it.key }).containsExactly("mine")
        assertThatThrownBy { agents(editor).create(Agent.Content(name = "Elsewhere", scope = Scope.Project(other))) }
            .isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { agents(FakeAuthorizationGate.denyAll()).get(mine.id) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThat(audit.entries.map { it.entityType }.distinct()).containsExactly("Agent")
        assertThat(audit.entries.first().details).containsEntry("key", "global").containsEntry("scope", "GLOBAL")
    }

    @Test
    fun `ADR 0057 - an agent's rules and skills must be visible in its scope, and the environment is audited`() {
        lib.rule("ground")
        lib.skill("fmt")
        lib.skill("mine", scope = pl.mos.backoffice.tasks.domain.model.Scope.Project(lib.projectId))

        assertThatThrownBy { agents().create(Agent.Content(name = "Bad", rules = listOf("ground", "ghost"), skills = listOf("mine"))) }
            .isInstanceOf(pl.mos.backoffice.shared.ValidationException::class.java)
            .satisfies({ e ->
                assertThat((e as pl.mos.backoffice.shared.ValidationException).errors.map { it.field to it.message }).containsExactly(
                    "rules[1]" to "rule ghost does not exist in scope GLOBAL",
                    "skills[0]" to "skill mine does not exist in scope GLOBAL",
                )
            })

        val project = pl.mos.backoffice.tasks.domain.model.Scope.Project(lib.projectId)
        val agent = agents().create(
            Agent.Content(
                name = "Env", scope = project, instructions = "Be precise.", rules = listOf("ground"), skills = listOf("mine", "fmt"),
                mcpServers = listOf(pl.mos.backoffice.shared.runner.AgentMcpServer("docs", "https://mcp.example.com")),
            ),
        )
        assertThat(agent.snapshot.skills).containsExactly("mine", "fmt")
        assertThat(audit.entries.last().details).containsEntry("rules", listOf("ground")).containsEntry("instructionsLength", 11)
            .containsEntry("mcpServers", listOf("docs=https://mcp.example.com"))

        agents().update(agent.id, agent.content.copy(instructions = "Be very precise.", skills = listOf("fmt")))
        val diff = audit.entries.last().details
        assertThat(diff["skills"]).isEqualTo(mapOf("old" to listOf("mine", "fmt"), "new" to listOf("fmt")))
        assertThat(diff["instructionsChanged"]).isEqualTo(mapOf("old" to false, "new" to true))
    }

}
