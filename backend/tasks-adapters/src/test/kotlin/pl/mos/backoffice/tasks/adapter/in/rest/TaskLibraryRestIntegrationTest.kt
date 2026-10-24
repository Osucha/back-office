package pl.mos.backoffice.tasks.adapter.`in`.rest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import pl.mos.backoffice.shared.security.PermissionCatalogContribution
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import pl.mos.backoffice.tasks.PostgresTestSupport
import pl.mos.backoffice.tasks.application.port.`in`.SeedTaskLibrary
import pl.mos.backoffice.tasks.application.service.BuiltInLibrary
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Boots with the seed on (default) so the built-in library is present for the resolve tests. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestSupport::class)
class TaskLibraryRestIntegrationTest {

    @Autowired lateinit var mvc: MockMvcTester
    @Autowired lateinit var json: JsonMapper
    @Autowired lateinit var seed: SeedTaskLibrary
    @Autowired lateinit var gate: FakeAuthorizationGate
    @Autowired lateinit var audit: RecordingAuditPort
    @Autowired lateinit var contributions: List<PermissionCatalogContribution>

    private val suffix = UUID.randomUUID().toString().substring(0, 8)

    private fun post(uri: String, body: String) = mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body)
    private fun put(uri: String, body: String) = mvc.put().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body)
    private fun idOf(body: String): UUID = UUID.fromString(json.readTree(body).get("id").asString())

    private fun templateBody(key: String, scope: String = """{"type":"GLOBAL"}""", extra: String = "") = """
        {"key":"$key","name":"Name $key","description":"d","scope":$scope$extra}
    """.trimIndent()

    @Test
    fun `the startup seed is idempotent and exposes the built-in library`() {
        assertThat(seed.seed().total).isZero()
        assertThat(mvc.get().uri("/api/v1/task-interfaces")).hasStatusOk().bodyJson()
            .extractingPath("$[*].key").asArray().contains(*BuiltInLibrary.interfaceKeys.toTypedArray())
        assertThat(mvc.get().uri("/api/v1/task-templates")).hasStatusOk().bodyJson()
            .extractingPath("$[*].key").asArray().contains(*BuiltInLibrary.templateKeys.toTypedArray())
        assertThat(mvc.get().uri("/api/v1/skills")).hasStatusOk().bodyJson()
            .extractingPath("$[*].key").asArray().contains(*BuiltInLibrary.skillKeys.toTypedArray())
        assertThat(mvc.get().uri("/api/v1/task-interfaces/${BuiltInLibrary.IMPLEMENTER}")).hasStatusOk().bodyJson()
            .extractingPath("$.implementedBy").asArray().containsExactly(BuiltInLibrary.IMPLEMENT_OPENCODE)
    }

    @Test
    fun `task interfaces support create, read, update, list and delete with a 409 while implemented`() {
        val key = "iface-$suffix"
        val body = """{"key":"$key","name":"Iface","description":"d","inputs":[{"name":"diff","kind":"ARTIFACT"}],"outputs":[{"name":"review","kind":"ARTIFACT","required":true,"description":"out"}],"ports":["success","failure","changes"]}"""
        val created = post("/api/v1/task-interfaces", body).exchange()
        assertThat(created).hasStatus(HttpStatus.CREATED)
        assertThat(created).bodyJson().satisfies(
            { assertThat(it).extractingPath("$.key").isEqualTo(key) },
            { assertThat(it).extractingPath("$.builtIn").isEqualTo(false) },
            { assertThat(it).extractingPath("$.implementedBy").asArray().isEmpty() },
            { assertThat(it).extractingPath("$.ports").asArray().containsExactly("success", "failure", "changes") },
        )
        assertThat(post("/api/v1/task-interfaces", body)).hasStatus(HttpStatus.CONFLICT)
        assertThat(post("/api/v1/task-interfaces", """{"key":"Bad Key","name":"x"}""")).hasStatus(HttpStatus.BAD_REQUEST)

        assertThat(put("/api/v1/task-interfaces/$key", """{"key":"$key","name":"Renamed","inputs":[],"outputs":[]}""")).hasStatusOk()
            .bodyJson().extractingPath("$.name").isEqualTo("Renamed")
        assertThat(mvc.get().uri("/api/v1/task-interfaces").exchange()).bodyJson().extractingPath("$[*].key").asArray().contains(key)

        val template = post("/api/v1/task-templates", templateBody("impl-$suffix", extra = ""","executor":"LLM","implements":["$key"]""")).exchange()
        assertThat(template).hasStatus(HttpStatus.CREATED)
        assertThat(mvc.get().uri("/api/v1/task-interfaces/$key")).hasStatusOk().bodyJson().extractingPath("$.implementedBy").asArray().containsExactly("impl-$suffix")
        assertThat(mvc.delete().uri("/api/v1/task-interfaces/$key")).hasStatus(HttpStatus.CONFLICT)

        assertThat(mvc.delete().uri("/api/v1/task-templates/${idOf(template.response.contentAsString)}")).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(mvc.delete().uri("/api/v1/task-interfaces/$key")).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(mvc.get().uri("/api/v1/task-interfaces/$key")).hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `task templates support CRUD with validation, version bump and built-in protection`() {
        val key = "tpl-$suffix"
        val created = post(
            "/api/v1/task-templates",
            templateBody(key, extra = ""","extendsKey":"${BuiltInLibrary.BASE_AGENT}","executor":"SPEC_KIT","prompt":{"system":"more","mode":"APPEND"},"config":{"depth":{"max":3}},"skills":["${BuiltInLibrary.SPEC_WRITING}"],"tags":["t"]"""),
        ).exchange()
        assertThat(created).hasStatus(HttpStatus.CREATED)
        val id = idOf(created.response.contentAsString)
        assertThat(created).bodyJson().satisfies(
            { assertThat(it).extractingPath("$.version").isEqualTo(1) },
            { assertThat(it).extractingPath("$.executor").isEqualTo("SPEC_KIT") },
            { assertThat(it).extractingPath("$.extendsKey").isEqualTo(BuiltInLibrary.BASE_AGENT) },
            { assertThat(it).extractingPath("$.scope.type").isEqualTo("GLOBAL") },
            { assertThat(it).extractingPath("$.config.depth.max").isEqualTo(3) },
        )

        assertThat(post("/api/v1/task-templates", templateBody(key))).hasStatus(HttpStatus.CONFLICT)
        assertThat(post("/api/v1/task-templates", templateBody("bare-$suffix"))).hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(post("/api/v1/task-templates", templateBody("orphan-$suffix", extra = ""","executor":"LLM","extendsKey":"ghost-$suffix""""))).hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(post("/api/v1/task-templates", templateBody("schema-$suffix", extra = ""","executor":"LLM","outputSchema":"{oops""""))).hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(post("/api/v1/task-templates", templateBody("noproj-$suffix", scope = """{"type":"PROJECT"}""", extra = ""","executor":"LLM""""))).hasStatus(HttpStatus.BAD_REQUEST)

        assertThat(put("/api/v1/task-templates/$id", templateBody(key, extra = ""","executor":"LLM","description":"updated""""))).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.version").isEqualTo(2) },
            { assertThat(it).extractingPath("$.executor").isEqualTo("LLM") },
            { assertThat(it).extractingPath("$.description").isEqualTo("updated") },
        )
        assertThat(mvc.get().uri("/api/v1/task-templates/$id")).hasStatusOk().bodyJson().extractingPath("$.version").isEqualTo(2)
        assertThat(mvc.get().uri("/api/v1/task-templates").param("includeAbstract", "false")).hasStatusOk().bodyJson()
            .extractingPath("$[*].key").asArray().contains(key).doesNotContain(BuiltInLibrary.BASE_AGENT)

        val builtIn = json.readTree(mvc.get().uri("/api/v1/task-templates").exchange().response.contentAsString)
            .first { it.get("key").asString() == BuiltInLibrary.CODE_REVIEW }
        assertThat(builtIn.get("builtIn").asBoolean()).isTrue()
        assertThat(mvc.delete().uri("/api/v1/task-templates/${builtIn.get("id").asString()}")).hasStatus(HttpStatus.CONFLICT)

        assertThat(mvc.delete().uri("/api/v1/task-templates/$id")).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(mvc.get().uri("/api/v1/task-templates/$id")).hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `resolve returns the merged built-in implementer with lineage, config and skills`() {
        assertThat(mvc.get().uri("/api/v1/task-templates/resolve/${BuiltInLibrary.IMPLEMENT_CLAUDE_CODE}")).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.lineage").asArray().containsExactly(BuiltInLibrary.IMPLEMENT_CLAUDE_CODE, BuiltInLibrary.IMPLEMENT_OPENCODE, BuiltInLibrary.BASE_CODING_AGENT, BuiltInLibrary.BASE_AGENT) },
            { assertThat(it).extractingPath("$.executor").isEqualTo("IMPLEMENT") },
            { assertThat(it).extractingPath("$.config.agent").isEqualTo("CLAUDE_CODE") },
            { assertThat(it).extractingPath("$.config.push").isEqualTo(true) },
            { assertThat(it).extractingPath("$.config.mergeRequestTitleTemplate").isEqualTo("{{issue.key}}: {{issue.summary}}") },
            { assertThat(it).extractingPath("$.skills[*].key").asArray().containsExactly(BuiltInLibrary.AGENT_GROUND_RULES, BuiltInLibrary.ASK_DONT_GUESS) },
            { assertThat(it).extractingPath("$.interfaces").asArray().containsExactly(BuiltInLibrary.IMPLEMENTER) },
            { assertThat(it).extractingPath("$.produces").asArray().containsExactly("diff", "mr") },
            { assertThat(it).extractingPath("$.ports").asArray().containsExactly("success", "failure") },
            { assertThat(it).extractingPath("$.abstract").isEqualTo(false) },
            { assertThat(it).extractingPath("$.interfaceCheck.ok").isEqualTo(true) },
            { assertThat(it).extractingPath("$.prompt.system").asString().contains("{{project.constitution}}") },
        )
        assertThat(mvc.get().uri("/api/v1/task-templates/resolve/${BuiltInLibrary.BASE_AGENT}")).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.abstract").isEqualTo(true) },
            { assertThat(it).extractingPath("$.lineage").asArray().containsExactly(BuiltInLibrary.BASE_AGENT) },
        )
        assertThat(mvc.get().uri("/api/v1/task-templates/resolve/nope-$suffix")).hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `run inputs round-trip through create, update and read and are inherited in the resolved view`() {
        val key = "inputs-$suffix"
        val created = post(
            "/api/v1/task-templates",
            templateBody(key, extra = ""","extendsKey":"${BuiltInLibrary.BASE_AGENT}","executor":"LLM","runInputs":[{"name":"audience","label":"Audience","type":"TEXT","required":true,"help":"who reads it"}]"""),
        ).exchange()
        assertThat(created).hasStatus(HttpStatus.CREATED)
        val id = idOf(created.response.contentAsString)
        assertThat(created).bodyJson().extractingPath("$.runInputs[0].name").isEqualTo("audience")

        assertThat(put("/api/v1/task-templates/$id", templateBody(key, extra = ""","extendsKey":"${BuiltInLibrary.BASE_AGENT}","executor":"LLM","runInputs":[{"name":"audience","label":"Readers","type":"MULTILINE","required":false}]""")))
            .hasStatusOk().bodyJson().extractingPath("$.runInputs[0].label").isEqualTo("Readers")
        assertThat(mvc.get().uri("/api/v1/task-templates/$id")).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.runInputs[0].type").isEqualTo("MULTILINE") },
            { assertThat(it).extractingPath("$.runInputs[0].required").isEqualTo(false) },
        )
        assertThat(mvc.get().uri("/api/v1/task-templates/resolve/$key")).hasStatusOk().bodyJson()
            .extractingPath("$.runInputs[*].name").asArray().containsExactly("input", "audience")

        assertThat(mvc.delete().uri("/api/v1/task-templates/$id")).hasStatus(HttpStatus.NO_CONTENT)
    }

    @Test
    fun `a project template shadows the built-in one for that project only and merges the skills`() {
        val projectId = UUID.randomUUID()
        val skill = post("/api/v1/skills", """{"key":"mine-$suffix","name":"Mine","content":"# mine","scope":{"type":"PROJECT","projectId":"$projectId"},"tags":["p"]}""").exchange()
        assertThat(skill).hasStatus(HttpStatus.CREATED)
        val shadow = post(
            "/api/v1/task-templates",
            templateBody(
                BuiltInLibrary.CODE_REVIEW,
                scope = """{"type":"PROJECT","projectId":"$projectId"}""",
                extra = ""","extendsKey":"${BuiltInLibrary.BASE_AGENT}","executor":"LLM","implements":["${BuiltInLibrary.REVIEWER}"],"consumes":["diff"],"produces":["review"],"prompt":{"user":"Review strictly"},"skills":["mine-$suffix","${BuiltInLibrary.KOTLIN_HEXAGONAL}"],"config":{"maxChunkLines":500}""",
            ),
        ).exchange()
        assertThat(shadow).hasStatus(HttpStatus.CREATED)

        assertThat(mvc.get().uri("/api/v1/task-templates/resolve/${BuiltInLibrary.CODE_REVIEW}").param("projectId", projectId.toString())).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.name").isEqualTo("Name ${BuiltInLibrary.CODE_REVIEW}") },
            { assertThat(it).extractingPath("$.lineage").asArray().containsExactly(BuiltInLibrary.CODE_REVIEW, BuiltInLibrary.BASE_AGENT) },
            { assertThat(it).extractingPath("$.prompt.user").isEqualTo("Review strictly") },
            { assertThat(it).extractingPath("$.skills[*].key").asArray().containsExactly(BuiltInLibrary.AGENT_GROUND_RULES, BuiltInLibrary.ASK_DONT_GUESS, "mine-$suffix", BuiltInLibrary.KOTLIN_HEXAGONAL) },
            { assertThat(it).extractingPath("$.config.maxChunkLines").isEqualTo(500) },
            { assertThat(it).extractingPath("$.interfaceCheck.ok").isEqualTo(true) },
        )
        assertThat(mvc.get().uri("/api/v1/task-templates/resolve/${BuiltInLibrary.CODE_REVIEW}")).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.name").isEqualTo("Code review") },
            { assertThat(it).extractingPath("$.skills[*].key").asArray().containsExactly(BuiltInLibrary.AGENT_GROUND_RULES, BuiltInLibrary.ASK_DONT_GUESS, BuiltInLibrary.SECURITY_REVIEW_CHECKLIST) },
        )
        assertThat(mvc.get().uri("/api/v1/task-templates").param("projectId", projectId.toString())).hasStatusOk().bodyJson()
            .extractingPath("$[?(@.key == '${BuiltInLibrary.CODE_REVIEW}')].scope.type").asArray().containsExactlyInAnyOrder("GLOBAL", "PROJECT")
        assertThat(mvc.get().uri("/api/v1/skills").param("projectId", projectId.toString())).hasStatusOk().bodyJson()
            .extractingPath("$[*].key").asArray().contains("mine-$suffix")
        assertThat(mvc.get().uri("/api/v1/skills")).hasStatusOk().bodyJson().extractingPath("$[*].key").asArray().doesNotContain("mine-$suffix")

        val skillId = idOf(skill.response.contentAsString)
        assertThat(mvc.delete().uri("/api/v1/skills/$skillId")).hasStatus(HttpStatus.CONFLICT)
        assertThat(mvc.delete().uri("/api/v1/task-templates/${idOf(shadow.response.contentAsString)}")).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(mvc.delete().uri("/api/v1/skills/$skillId")).hasStatus(HttpStatus.NO_CONTENT)
    }

    @Test
    fun `skills support CRUD`() {
        val key = "skill-$suffix"
        val created = post("/api/v1/skills", """{"key":"$key","name":"Skill","description":"d","content":"---\nname: $key\n---\n# rules","scope":{"type":"GLOBAL"},"tags":["a"]}""").exchange()
        assertThat(created).hasStatus(HttpStatus.CREATED)
        val id = idOf(created.response.contentAsString)
        assertThat(created).bodyJson().extractingPath("$.builtIn").isEqualTo(false)

        assertThat(post("/api/v1/skills", """{"key":"$key","name":"Dup","content":"x","scope":{"type":"GLOBAL"}}""")).hasStatus(HttpStatus.CONFLICT)
        assertThat(post("/api/v1/skills", """{"key":"$key","name":"","content":"x","scope":{"type":"GLOBAL"}}""")).hasStatus(HttpStatus.BAD_REQUEST)

        assertThat(put("/api/v1/skills/$id", """{"key":"$key","name":"Skill v2","content":"# v2","scope":{"type":"GLOBAL"}}""")).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.name").isEqualTo("Skill v2") },
            { assertThat(it).extractingPath("$.content").isEqualTo("# v2") },
        )
        assertThat(mvc.get().uri("/api/v1/skills/$id")).hasStatusOk().bodyJson().extractingPath("$.key").isEqualTo(key)
        assertThat(mvc.delete().uri("/api/v1/skills/$id")).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(mvc.get().uri("/api/v1/skills/$id")).hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `mutations are audited in the same call and stamp updatedBy`() {
        val key = "audited-$suffix"
        val created = post("/api/v1/task-templates", templateBody(key, extra = ""","executor":"LLM"""")).exchange()
        assertThat(created).hasStatus(HttpStatus.CREATED)
        assertThat(created).bodyJson().satisfies(
            { assertThat(it).extractingPath("$.updatedBy.displayName").isEqualTo("Alice Test") },
            { assertThat(it).extractingPath("$.updatedBy.kind").isEqualTo("USER") },
        )
        val id = idOf(created.response.contentAsString)
        assertThat(put("/api/v1/task-templates/$id", templateBody(key, extra = ""","executor":"LLM","prompt":{"system":"abc"}"""))).hasStatusOk()
        assertThat(mvc.delete().uri("/api/v1/task-templates/$id")).hasStatus(HttpStatus.NO_CONTENT)

        val mine = audit.entries.filter { it.entityId == id.toString() }
        assertThat(mine.map { it.operation }).containsExactly("TEMPLATE_CREATE", "TEMPLATE_UPDATE", "TEMPLATE_DELETE")
        assertThat(mine[1].details["systemPromptLength"]).isEqualTo(mapOf("old" to 0, "new" to 3))
    }

    @Test
    fun `callers without the library permissions get 403 and change nothing`() {
        val previous = gate.user
        try {
            gate.user = FakeAuthorizationGate.user(platform = setOf("library.view"))
            assertThat(mvc.get().uri("/api/v1/task-templates")).hasStatusOk()
            assertThat(post("/api/v1/task-templates", templateBody("denied-$suffix", extra = ""","executor":"LLM""""))).hasStatus(HttpStatus.FORBIDDEN)
            assertThat(post("/api/v1/task-interfaces", """{"key":"denied-$suffix","name":"x"}""")).hasStatus(HttpStatus.FORBIDDEN)
            assertThat(post("/api/v1/skills", """{"key":"denied-$suffix","name":"x","content":"x","scope":{"type":"GLOBAL"}}""")).hasStatus(HttpStatus.FORBIDDEN)

            gate.user = FakeAuthorizationGate.user()
            assertThat(mvc.get().uri("/api/v1/task-templates")).hasStatus(HttpStatus.FORBIDDEN)
            assertThat(mvc.get().uri("/api/v1/task-interfaces")).hasStatus(HttpStatus.FORBIDDEN)
            assertThat(mvc.get().uri("/api/v1/skills")).hasStatus(HttpStatus.FORBIDDEN)
        } finally {
            gate.user = previous
        }
        assertThat(mvc.get().uri("/api/v1/task-templates")).hasStatusOk().bodyJson().extractingPath("$[*].key").asArray().doesNotContain("denied-$suffix")
    }

    @Test
    fun `the module contributes its permissions to the catalogue`() {
        assertThat(contributions.flatMap { it.permissions() }.map { it.key })
            .contains("library.view", "library.manage", "library.project.manage")
    }
}
