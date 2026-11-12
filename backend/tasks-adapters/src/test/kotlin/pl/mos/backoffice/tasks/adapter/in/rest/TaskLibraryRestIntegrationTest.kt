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
        assertThat(mvc.get().uri("/api/v1/rules")).hasStatusOk().bodyJson()
            .extractingPath("$[*].key").asArray().contains(*BuiltInLibrary.ruleKeys.toTypedArray())
        assertThat(mvc.get().uri("/api/v1/task-templates")).hasStatusOk().bodyJson()
            .extractingPath("$[*].key").asArray().contains(*BuiltInLibrary.templateKeys.toTypedArray())
            .doesNotContainAnyElementsOf(BuiltInLibrary.formerActionTemplateKeys)
        assertThat(mvc.get().uri("/api/v1/agents")).hasStatusOk().bodyJson()
            .extractingPath("$[*].key").asArray().contains(*BuiltInLibrary.agentKeys.toTypedArray())
        assertThat(mvc.get().uri("/api/v1/skills")).hasStatusOk().bodyJson()
            .extractingPath("$[*].key").asArray().contains(*BuiltInLibrary.skillKeys.toTypedArray())
        assertThat(mvc.get().uri("/api/v1/skills")).hasStatusOk().bodyJson()
            .extractingPath("$[*].key").asArray().doesNotContainAnyElementsOf(BuiltInLibrary.ruleKeys)
    }

    @Test
    fun `rules support create, read, update, list and delete with a 409 while a template lists them`() {
        val projectId = UUID.randomUUID()
        val scope = """{"type":"PROJECT","projectId":"$projectId"}"""
        val created = post("/api/v1/rules", """{"name":"Zasada $suffix","description":"d","content":"Always write tests.","scope":$scope,"automatic":true,"tags":["q"]}""").exchange()
        assertThat(created).hasStatus(HttpStatus.CREATED)
        val id = idOf(created.response.contentAsString)
        val key = json.readTree(created.response.contentAsString).get("key").asString()
        assertThat(key).describedAs("derived from the name").isEqualTo("zasada-$suffix")
        assertThat(created).bodyJson().satisfies(
            { assertThat(it).extractingPath("$.builtIn").isEqualTo(false) },
            { assertThat(it).extractingPath("$.automatic").isEqualTo(true) },
            { assertThat(it).extractingPath("$.scope.type").isEqualTo("PROJECT") },
            { assertThat(it).extractingPath("$.updatedBy.displayName").isEqualTo("Alice Test") },
        )
        assertThat(post("/api/v1/rules", """{"key":"$key","name":"Dup","content":"x","scope":$scope}""")).hasStatus(HttpStatus.CONFLICT)
        assertThat(post("/api/v1/rules", """{"key":"Bad Key","name":"x","content":"x","scope":$scope}""")).hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(post("/api/v1/rules", """{"name":"x","content":"","scope":$scope}""")).hasStatus(HttpStatus.BAD_REQUEST)

        assertThat(put("/api/v1/rules/$id", """{"name":"Renamed","content":"Always write tests first.","scope":$scope,"automatic":true}""")).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.key").isEqualTo(key) },
            { assertThat(it).extractingPath("$.name").isEqualTo("Renamed") },
        )
        assertThat(mvc.get().uri("/api/v1/rules/$id")).hasStatusOk().bodyJson().extractingPath("$.content").isEqualTo("Always write tests first.")
        assertThat(mvc.get().uri("/api/v1/rules").param("projectId", projectId.toString())).hasStatusOk().bodyJson()
            .extractingPath("$[*].key").asArray().contains(key, BuiltInLibrary.AGENT_GROUND_RULES)
        assertThat(mvc.get().uri("/api/v1/rules")).hasStatusOk().bodyJson().extractingPath("$[*].key").asArray().doesNotContain(key)

        // the automatic project rule reaches every step of the project, first, and not other projects
        assertThat(mvc.get().uri("/api/v1/task-templates/resolve/${BuiltInLibrary.CODE_REVIEW}").param("projectId", projectId.toString())).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.rules[*].key").asArray().containsExactly(key, BuiltInLibrary.AGENT_GROUND_RULES, BuiltInLibrary.ASK_DONT_GUESS) },
            { assertThat(it).extractingPath("$.rules[0].automatic").isEqualTo(true) },
            { assertThat(it).extractingPath("$.category").isEqualTo("REVIEW") },
        )
        assertThat(mvc.get().uri("/api/v1/task-templates/resolve/${BuiltInLibrary.CODE_REVIEW}")).hasStatusOk().bodyJson()
            .extractingPath("$.rules[*].key").asArray().doesNotContain(key)

        // ADR 0057: rules belong to agents; an agent lists only rules visible in its scope
        val agent = post("/api/v1/agents", """{"name":"Uses $suffix","scope":$scope,"rules":["$key"],"instructions":"Be careful.","mcpServers":[{"name":"docs","url":"https://mcp.example.com/docs"}]}""").exchange()
        assertThat(agent).hasStatus(HttpStatus.CREATED)
        assertThat(agent).bodyJson().satisfies(
            { assertThat(it).extractingPath("$.rules").asArray().containsExactly(key) },
            { assertThat(it).extractingPath("$.instructions").isEqualTo("Be careful.") },
            { assertThat(it).extractingPath("$.mcpServers[0].url").isEqualTo("https://mcp.example.com/docs") },
        )
        assertThat(post("/api/v1/agents", """{"name":"Ghost $suffix","scope":{"type":"GLOBAL"},"rules":["$key"]}""")).describedAs("a project rule is not visible to a global agent")
            .hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(post("/api/v1/agents", """{"name":"Bad mcp $suffix","scope":$scope,"mcpServers":[{"name":"x","url":"ftp://x"}]}""")).hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(mvc.delete().uri("/api/v1/rules/$id")).hasStatus(HttpStatus.CONFLICT)

        assertThat(mvc.delete().uri("/api/v1/agents/${idOf(agent.response.contentAsString)}")).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(mvc.delete().uri("/api/v1/rules/$id")).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(mvc.get().uri("/api/v1/rules/$id")).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(audit.entries.filter { it.entityId == id.toString() }.map { it.operation }).containsExactly("RULE_CREATE", "RULE_UPDATE", "RULE_DELETE")
    }

    @Test
    fun `a template created without a key gets one from its name and keeps it on update`() {
        val body = """{"name":"Analiza zgłoszenia $suffix","scope":{"type":"GLOBAL"},"category":"ANALYSIS"}"""
        val created = post("/api/v1/task-templates", body).exchange()
        assertThat(created).hasStatus(HttpStatus.CREATED)
        assertThat(created).bodyJson().satisfies(
            { assertThat(it).extractingPath("$.key").isEqualTo("analiza-zgloszenia-$suffix") },
            { assertThat(it).extractingPath("$.category").isEqualTo("ANALYSIS") },
        )
        val second = post("/api/v1/task-templates", body).exchange()
        assertThat(second).hasStatus(HttpStatus.CREATED)
        assertThat(second).bodyJson().extractingPath("$.key").isEqualTo("analiza-zgloszenia-$suffix-2")

        val id = idOf(created.response.contentAsString)
        assertThat(put("/api/v1/task-templates/$id", """{"name":"Other name","scope":{"type":"GLOBAL"}}""")).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.key").isEqualTo("analiza-zgloszenia-$suffix") },
            { assertThat(it).extractingPath("$.version").isEqualTo(2) },
        )
        assertThat(mvc.delete().uri("/api/v1/task-templates/$id")).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(mvc.delete().uri("/api/v1/task-templates/${idOf(second.response.contentAsString)}")).hasStatus(HttpStatus.NO_CONTENT)
    }

    @Test
    fun `task templates support CRUD with validation, version bump and built-in protection`() {
        val key = "tpl-$suffix"
        val created = post(
            "/api/v1/task-templates",
            templateBody(key, extra = ""","extendsKey":"${BuiltInLibrary.BASE_AGENT}","agentKey":"ignored","modelProfileId":"${UUID.randomUUID()}","instructions":"more","instructionsMode":"APPEND","config":{"depth":{"max":3},"push":true},"parameters":[{"name":"depth","label":"Depth","type":"NUMBER","required":true,"default":2}],"parameterValues":{"depth":3},"tags":["t"]"""),
        ).exchange()
        assertThat(created).hasStatus(HttpStatus.CREATED)
        val id = idOf(created.response.contentAsString)
        assertThat(created).bodyJson().satisfies(
            { assertThat(it).extractingPath("$.version").isEqualTo(1) },
            { assertThat(it).extractingPath("$").asMap().describedAs("ADR 0061: templates name no agent and no model").doesNotContainKeys("agentKey", "modelProfileId") },
            { assertThat(it).extractingPath("$.needsCodingAgent").isEqualTo(true) },
            { assertThat(it).extractingPath("$.extendsKey").isEqualTo(BuiltInLibrary.BASE_AGENT) },
            { assertThat(it).extractingPath("$.scope.type").isEqualTo("GLOBAL") },
            { assertThat(it).extractingPath("$.config.depth.max").isEqualTo(3) },
            { assertThat(it).extractingPath("$.parameters[0].type").isEqualTo("NUMBER") },
            { assertThat(it).extractingPath("$.parameterValues.depth").isEqualTo(3) },
            { assertThat(it).extractingPath("$.instructions").isEqualTo("more") },
            { assertThat(it).extractingPath("$.instructionsMode").isEqualTo("APPEND") },
        )
        assertThat(post("/api/v1/task-templates", templateBody("badparam-$suffix", extra = ""","parameterValues":{"ghost":1}"""))).hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(post("/api/v1/task-templates", templateBody("badname-$suffix", extra = ""","parameters":[{"name":"1x","label":"x","type":"TEXT","required":false}]"""))).hasStatus(HttpStatus.BAD_REQUEST)

        assertThat(post("/api/v1/task-templates", templateBody(key))).hasStatus(HttpStatus.CONFLICT)
        assertThat(post("/api/v1/task-templates", templateBody("orphan-$suffix", extra = ""","extendsKey":"ghost-$suffix""""))).hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(post("/api/v1/task-templates", templateBody("schema-$suffix", extra = ""","outputSchema":"{oops""""))).hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(post("/api/v1/task-templates", templateBody("noproj-$suffix", scope = """{"type":"PROJECT"}""", extra = """"""))).hasStatus(HttpStatus.BAD_REQUEST)

        assertThat(put("/api/v1/task-templates/$id", templateBody(key, extra = ""","description":"updated""""))).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.version").isEqualTo(2) },
            { assertThat(it).extractingPath("$.description").isEqualTo("updated") },
            { assertThat(it).extractingPath("$.needsCodingAgent").isEqualTo(false) },
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
            { assertThat(it).extractingPath("$.lineage").asArray().containsExactly(BuiltInLibrary.IMPLEMENT_CLAUDE_CODE, BuiltInLibrary.IMPLEMENT_OPENCODE) },
            { assertThat(it).extractingPath("$.agent.key").describedAs("ADR 0061: no step agent, coding work").isEqualTo("opencode") },
            { assertThat(it).extractingPath("$.agent.harness").isEqualTo("OPENCODE") },
            { assertThat(it).extractingPath("$.config").asMap().doesNotContainKey("agent") },
            { assertThat(it).extractingPath("$.config.push").isEqualTo(true) },
            { assertThat(it).extractingPath("$.config.mergeRequestTitleTemplate").isEqualTo("{{issue.key}}: {{issue.summary}}") },
            { assertThat(it).extractingPath("$.skills[*].key").asArray().containsExactly(BuiltInLibrary.SPEC_WRITING, BuiltInLibrary.DEFINITION_OF_READY_DONE, BuiltInLibrary.BUILD_GUARDRAILS) },
            { assertThat(it).extractingPath("$.agent.skills").asArray().contains(BuiltInLibrary.SPEC_WRITING) },
            { assertThat(it).extractingPath("$.rules[*].key").asArray().containsExactly(BuiltInLibrary.AGENT_GROUND_RULES, BuiltInLibrary.ASK_DONT_GUESS) },
            { assertThat(it).extractingPath("$.category").isEqualTo("IMPLEMENTATION") },
            { assertThat(it).extractingPath("$.produces").asArray().containsExactly("diff", "mr") },
            { assertThat(it).extractingPath("$.ports").asArray().containsExactly("success", "failure") },
            { assertThat(it).extractingPath("$.abstract").isEqualTo(false) },
            { assertThat(it).extractingPath("$.check.ok").isEqualTo(true) },
            { assertThat(it).extractingPath("$.instructions").asString().contains("verifyCommand").doesNotContain("{{runInputText}}").doesNotContain("project.constitution") },
            { assertThat(it).extractingPath("$.parameters[*].name").asArray().containsExactly("input") },
            { assertThat(it).extractingPath("$.runInputs").asArray().describedAs("ADR 0062: nothing bound START").isEmpty() },
        )
        assertThat(mvc.get().uri("/api/v1/task-templates/resolve/${BuiltInLibrary.BASE_AGENT}")).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.abstract").isEqualTo(true) },
            { assertThat(it).extractingPath("$.lineage").asArray().containsExactly(BuiltInLibrary.BASE_AGENT) },
        )
        assertThat(mvc.get().uri("/api/v1/task-templates/resolve/nope-$suffix")).hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `resolve with a step's overrides returns what the step gets`() {
        val overrides = """{"agentKey":"opencode","promptReplace":"Only analyse, change nothing.","config":{"push":false},"addSkills":["ignored"]}"""
        assertThat(
            mvc.post().uri("/api/v1/task-templates/resolve/${BuiltInLibrary.AGENT_TASK}").contentType(MediaType.APPLICATION_JSON).content(overrides),
        ).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.instructions").asString().describedAs("legacy promptReplace reads as the replacement").isEqualTo("Only analyse, change nothing.") },
            { assertThat(it).extractingPath("$.agent.key").isEqualTo("opencode") },
            { assertThat(it).extractingPath("$.agent.harness").isEqualTo("OPENCODE") },
            { assertThat(it).extractingPath("$.config.push").isEqualTo(false) },
            { assertThat(it).extractingPath("$.check.ok").isEqualTo(true) },
        )
        assertThat(
            mvc.post().uri("/api/v1/task-templates/resolve/nope-$suffix").contentType(MediaType.APPLICATION_JSON).content("{}"),
        ).hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `ADR 0062 - a step that binds parameters START gets them as run start fields, legacy run inputs are ignored`() {
        val key = "inputs-$suffix"
        val created = post(
            "/api/v1/task-templates",
            templateBody(
                key,
                extra = ""","extendsKey":"${BuiltInLibrary.BASE_AGENT}","runInputs":[{"name":"ignored","label":"Ignored"}],""" +
                    """"parameters":[{"name":"audience","label":"Audience","type":"CHOICE","required":true,"options":["devs","ops"],"help":"who reads it"}]""",
            ),
        ).exchange()
        assertThat(created).hasStatus(HttpStatus.CREATED)
        val id = idOf(created.response.contentAsString)
        assertThat(created).bodyJson().extractingPath("$").asMap().doesNotContainKeys("runInputs", "prompt")

        val bindings = """{"parameterBindings":{"audience":{"source":"START"},"input":{"source":"START"}}}"""
        assertThat(mvc.post().uri("/api/v1/task-templates/resolve/$key").contentType(MediaType.APPLICATION_JSON).content(bindings)).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.runInputs[*].name").asArray().containsExactly("input", "audience") },
            { assertThat(it).extractingPath("$.runInputs[1].type").isEqualTo("TEXT") },
            { assertThat(it).extractingPath("$.runInputs[1].required").isEqualTo(true) },
            { assertThat(it).extractingPath("$.runInputs[1].help").isEqualTo("who reads it One of: devs, ops.") },
            { assertThat(it).extractingPath("$.check.ok").isEqualTo(true) },
        )
        assertThat(mvc.get().uri("/api/v1/task-templates/resolve/$key")).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.runInputs").asArray().isEmpty() },
            { assertThat(it).extractingPath("$.check.ok").describedAs("the required parameter has no value without a binding").isEqualTo(false) },
        )

        assertThat(mvc.delete().uri("/api/v1/task-templates/$id")).hasStatus(HttpStatus.NO_CONTENT)
    }

    @Test
    fun `a project template shadows the built-in one for that project only and gets its project agent's skills and rules`() {
        val projectId = UUID.randomUUID()
        val skill = post("/api/v1/skills", """{"key":"mine-$suffix","name":"Mine","content":"# mine","scope":{"type":"PROJECT","projectId":"$projectId"},"tags":["p"]}""").exchange()
        assertThat(skill).hasStatus(HttpStatus.CREATED)
        val agent = post(
            "/api/v1/agents",
            """{"key":"strict-$suffix","name":"Strict","scope":{"type":"PROJECT","projectId":"$projectId"},"skills":["mine-$suffix"],""" +
                """"rules":["${BuiltInLibrary.AGENT_GROUND_RULES}","${BuiltInLibrary.ASK_DONT_GUESS}","${BuiltInLibrary.KOTLIN_HEXAGONAL}"]}""",
        ).exchange()
        assertThat(agent).hasStatus(HttpStatus.CREATED)
        val shadow = post(
            "/api/v1/task-templates",
            templateBody(
                BuiltInLibrary.CODE_REVIEW,
                scope = """{"type":"PROJECT","projectId":"$projectId"}""",
                extra = ""","extendsKey":"${BuiltInLibrary.BASE_AGENT}","category":"REVIEW","consumes":["diff"],"produces":["review"],"instructions":"Review strictly","config":{"maxChunkLines":500}""",
            ),
        ).exchange()
        assertThat(shadow).hasStatus(HttpStatus.CREATED)

        assertThat(post("/api/v1/task-templates/resolve/${BuiltInLibrary.CODE_REVIEW}?projectId=$projectId", """{"agentKey":"strict-$suffix"}""")).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.name").isEqualTo("Name ${BuiltInLibrary.CODE_REVIEW}") },
            { assertThat(it).extractingPath("$.lineage").asArray().containsExactly(BuiltInLibrary.CODE_REVIEW, BuiltInLibrary.BASE_AGENT) },
            { assertThat(it).extractingPath("$.instructions").isEqualTo("Review strictly") },
            { assertThat(it).extractingPath("$.skills[*].key").asArray().containsExactly("mine-$suffix") },
            { assertThat(it).extractingPath("$.rules[*].key").asArray().containsExactly(BuiltInLibrary.AGENT_GROUND_RULES, BuiltInLibrary.ASK_DONT_GUESS, BuiltInLibrary.KOTLIN_HEXAGONAL) },
            { assertThat(it).extractingPath("$.config.maxChunkLines").isEqualTo(500) },
            { assertThat(it).extractingPath("$.check.ok").isEqualTo(true) },
        )
        assertThat(post("/api/v1/task-templates/resolve/${BuiltInLibrary.CODE_REVIEW}", """{"agentKey":"${BuiltInLibrary.REVIEWER}"}""")).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.name").isEqualTo("Code review") },
            { assertThat(it).extractingPath("$.check.warnings").asArray().describedAs("the reviewer has the skill the review requires").isEmpty() },
            { assertThat(it).extractingPath("$.skills[*].key").asArray().containsExactly(BuiltInLibrary.SECURITY_REVIEW_CHECKLIST, BuiltInLibrary.DEFINITION_OF_READY_DONE) },
            { assertThat(it).extractingPath("$.agent.key").isEqualTo(BuiltInLibrary.REVIEWER) },
        )
        assertThat(mvc.get().uri("/api/v1/task-templates").param("projectId", projectId.toString())).hasStatusOk().bodyJson()
            .extractingPath("$[?(@.key == '${BuiltInLibrary.CODE_REVIEW}')].scope.type").asArray().containsExactlyInAnyOrder("GLOBAL", "PROJECT")
        assertThat(mvc.get().uri("/api/v1/skills").param("projectId", projectId.toString())).hasStatusOk().bodyJson()
            .extractingPath("$[*].key").asArray().contains("mine-$suffix")
        assertThat(mvc.get().uri("/api/v1/skills")).hasStatusOk().bodyJson().extractingPath("$[*].key").asArray().doesNotContain("mine-$suffix")

        val skillId = idOf(skill.response.contentAsString)
        assertThat(mvc.delete().uri("/api/v1/skills/$skillId")).hasStatus(HttpStatus.CONFLICT)
        assertThat(mvc.delete().uri("/api/v1/task-templates/${idOf(shadow.response.contentAsString)}")).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(mvc.delete().uri("/api/v1/agents/${idOf(agent.response.contentAsString)}")).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(mvc.delete().uri("/api/v1/skills/$skillId")).hasStatus(HttpStatus.NO_CONTENT)
    }

    @Test
    fun `ADR 0060 - agent access, a project copy of a global agent, required skills as warnings, read-only on a pushing step`() {
        val projectId = UUID.randomUUID()
        val project = """{"type":"PROJECT","projectId":"$projectId"}"""
        val skill = "needed-$suffix"
        assertThat(post("/api/v1/skills", """{"key":"$skill","name":"Needed","content":"# needed","scope":{"type":"GLOBAL"}}""")).hasStatus(HttpStatus.CREATED)

        // a global agent with PATHS access; invalid access is a 400
        val docs = post("/api/v1/agents", """{"key":"docs-$suffix","name":"Docs","scope":{"type":"GLOBAL"},"harness":"CLAUDE_CODE","access":{"mode":"PATHS","paths":["docs/**"]}}""").exchange()
        assertThat(docs).hasStatus(HttpStatus.CREATED).bodyJson().satisfies(
            { assertThat(it).extractingPath("$.access.mode").isEqualTo("PATHS") },
            { assertThat(it).extractingPath("$.access.paths[0]").isEqualTo("docs/**") },
        )
        assertThat(post("/api/v1/agents", """{"name":"Bad","scope":{"type":"GLOBAL"},"access":{"mode":"PATHS","paths":["../x"]}}""")).hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(post("/api/v1/agents", """{"name":"Empty","scope":{"type":"GLOBAL"},"access":{"mode":"PATHS"}}""")).hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(mvc.get().uri("/api/v1/agents/${idOf(docs.response.contentAsString)}")).hasStatusOk().bodyJson().extractingPath("$.access.mode").isEqualTo("PATHS")

        // import into a project: the same key in project scope, read only there; the project sees both
        val copy = post("/api/v1/agents", """{"key":"docs-$suffix","name":"Docs (project)","scope":$project,"harness":"CLAUDE_CODE","access":{"mode":"READ_ONLY"}}""").exchange()
        assertThat(copy).hasStatus(HttpStatus.CREATED).bodyJson().extractingPath("$.access.mode").isEqualTo("READ_ONLY")
        assertThat(mvc.get().uri("/api/v1/agents").param("projectId", projectId.toString())).hasStatusOk().bodyJson()
            .extractingPath("$[?(@.key == 'docs-$suffix')].scope.type").asArray().containsExactlyInAnyOrder("GLOBAL", "PROJECT")
        val plain = post("/api/v1/agents", """{"name":"Plain $suffix","scope":{"type":"GLOBAL"}}""").exchange()
        assertThat(plain).bodyJson().extractingPath("$.access.mode").isEqualTo("FULL")

        // a template that requires a skill the agent lacks: a warning, still executable
        val needs = post("/api/v1/task-templates", templateBody("needs-$suffix", extra = ""","requiresSkills":["$skill"],"config":{"push":false}""")).exchange()
        assertThat(needs).hasStatus(HttpStatus.CREATED).bodyJson().extractingPath("$.requiresSkills[0]").isEqualTo(skill)
        assertThat(post("/api/v1/task-templates/resolve/needs-$suffix", """{"agentKey":"docs-$suffix"}""")).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.check.ok").isEqualTo(true) },
            { assertThat(it).extractingPath("$.check.warnings[0]").asString().contains("lacks skill $skill") },
        )
        assertThat(post("/api/v1/task-templates", templateBody("unknown-skill-$suffix", extra = ""","requiresSkills":["nope-$suffix"]"""))).hasStatus(HttpStatus.BAD_REQUEST)

        // the project's read-only copy on a step that pushes is a problem
        assertThat(
            post("/api/v1/task-templates/resolve/needs-$suffix?projectId=$projectId", """{"agentKey":"docs-$suffix","config":{"push":true}}"""),
        ).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.check.ok").isEqualTo(false) },
            { assertThat(it).extractingPath("$.check.problems[0]").asString().contains("READ_ONLY") },
        )
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
        val created = post("/api/v1/task-templates", templateBody(key, extra = """""")).exchange()
        assertThat(created).hasStatus(HttpStatus.CREATED)
        assertThat(created).bodyJson().satisfies(
            { assertThat(it).extractingPath("$.updatedBy.displayName").isEqualTo("Alice Test") },
            { assertThat(it).extractingPath("$.updatedBy.kind").isEqualTo("USER") },
        )
        val id = idOf(created.response.contentAsString)
        assertThat(put("/api/v1/task-templates/$id", templateBody(key, extra = ""","instructions":"abc""""))).hasStatusOk()
        assertThat(mvc.delete().uri("/api/v1/task-templates/$id")).hasStatus(HttpStatus.NO_CONTENT)

        val mine = audit.entries.filter { it.entityId == id.toString() }
        assertThat(mine.map { it.operation }).containsExactly("TEMPLATE_CREATE", "TEMPLATE_UPDATE", "TEMPLATE_DELETE")
        assertThat(mine[1].details["instructionsLength"]).isEqualTo(mapOf("old" to 0, "new" to 3))
    }

    @Test
    fun `callers without the library permissions get 403 and change nothing`() {
        val previous = gate.user
        try {
            gate.user = FakeAuthorizationGate.user(platform = setOf("library.view"))
            assertThat(mvc.get().uri("/api/v1/task-templates")).hasStatusOk()
            assertThat(post("/api/v1/task-templates", templateBody("denied-$suffix", extra = """"""))).hasStatus(HttpStatus.FORBIDDEN)
            assertThat(post("/api/v1/rules", """{"key":"denied-$suffix","name":"x","content":"x","scope":{"type":"GLOBAL"}}""")).hasStatus(HttpStatus.FORBIDDEN)
            assertThat(post("/api/v1/skills", """{"key":"denied-$suffix","name":"x","content":"x","scope":{"type":"GLOBAL"}}""")).hasStatus(HttpStatus.FORBIDDEN)
            assertThat(post("/api/v1/agents", """{"key":"denied-$suffix","name":"x","scope":{"type":"GLOBAL"}}""")).hasStatus(HttpStatus.FORBIDDEN)

            gate.user = FakeAuthorizationGate.user()
            assertThat(mvc.get().uri("/api/v1/task-templates")).hasStatus(HttpStatus.FORBIDDEN)
            assertThat(mvc.get().uri("/api/v1/rules")).hasStatus(HttpStatus.FORBIDDEN)
            assertThat(mvc.get().uri("/api/v1/skills")).hasStatus(HttpStatus.FORBIDDEN)
        } finally {
            gate.user = previous
        }
        assertThat(mvc.get().uri("/api/v1/task-templates")).hasStatusOk().bodyJson().extractingPath("$[*].key").asArray().doesNotContain("denied-$suffix")
    }

    @Test
    fun `agents support create, read, update, list and delete, and a step names them`() {
        val projectId = UUID.randomUUID()
        val scope = """{"type":"PROJECT","projectId":"$projectId"}"""
        val model = UUID.randomUUID()
        val created = post("/api/v1/agents", """{"name":"Tani agent $suffix","description":"d","scope":$scope,"harness":"OPENCODE","modelProfileId":"$model","tags":["cheap"]}""").exchange()
        assertThat(created).hasStatus(HttpStatus.CREATED)
        val id = idOf(created.response.contentAsString)
        val key = json.readTree(created.response.contentAsString).get("key").asString()
        assertThat(key).isEqualTo("tani-agent-$suffix")
        assertThat(created).bodyJson().satisfies(
            { assertThat(it).extractingPath("$.harness").isEqualTo("OPENCODE") },
            { assertThat(it).extractingPath("$.modelProfileId").isEqualTo(model.toString()) },
            { assertThat(it).extractingPath("$.builtIn").isEqualTo(false) },
            { assertThat(it).extractingPath("$.updatedBy.displayName").isEqualTo("Alice Test") },
        )
        assertThat(post("/api/v1/agents", """{"key":"$key","name":"Dup","scope":$scope}""")).hasStatus(HttpStatus.CONFLICT)
        assertThat(post("/api/v1/agents", """{"key":"Bad Key","name":"x","scope":$scope}""")).hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(post("/api/v1/agents", """{"name":"","scope":$scope}""")).hasStatus(HttpStatus.BAD_REQUEST)

        assertThat(put("/api/v1/agents/$id", """{"name":"Renamed","scope":$scope,"harness":"CLAUDE_CODE"}""")).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.key").isEqualTo(key) },
            { assertThat(it).extractingPath("$.harness").isEqualTo("CLAUDE_CODE") },
        )
        assertThat(mvc.get().uri("/api/v1/agents/$id")).hasStatusOk().bodyJson().extractingPath("$.name").isEqualTo("Renamed")
        assertThat(mvc.get().uri("/api/v1/agents").param("projectId", projectId.toString())).hasStatusOk().bodyJson()
            .extractingPath("$[*].key").asArray().contains(key, "model-answer")
        assertThat(mvc.get().uri("/api/v1/agents")).hasStatusOk().bodyJson().extractingPath("$[*].key").asArray().doesNotContain(key)

        val template = post("/api/v1/task-templates", templateBody("agent-user-$suffix", scope = scope, extra = "")).exchange()
        assertThat(template).hasStatus(HttpStatus.CREATED)
        assertThat(post("/api/v1/task-templates/resolve/agent-user-$suffix?projectId=$projectId", """{"agentKey":"$key"}""")).hasStatusOk().bodyJson().satisfies(
            { assertThat(it).extractingPath("$.agent.key").isEqualTo(key) },
            { assertThat(it).extractingPath("$.agent.harness").isEqualTo("CLAUDE_CODE") },
        )
        assertThat(mvc.delete().uri("/api/v1/task-templates/${idOf(template.response.contentAsString)}")).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(mvc.delete().uri("/api/v1/agents/$id")).describedAs("ADR 0061: templates name no agent").hasStatus(HttpStatus.NO_CONTENT)
        assertThat(mvc.get().uri("/api/v1/agents/$id")).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(audit.entries.filter { it.entityId == id.toString() }.map { it.operation }).containsExactly("AGENT_CREATE", "AGENT_UPDATE", "AGENT_DELETE")
    }

    @Test
    fun `the module contributes its permissions to the catalogue`() {
        assertThat(contributions.flatMap { it.permissions() }.map { it.key })
            .contains("library.view", "library.manage", "library.project.manage")
    }
}
