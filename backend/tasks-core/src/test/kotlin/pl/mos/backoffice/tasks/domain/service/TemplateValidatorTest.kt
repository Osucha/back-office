package pl.mos.backoffice.tasks.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.tasks.RunInputField
import pl.mos.backoffice.shared.tasks.RunInputType
import pl.mos.backoffice.shared.tasks.TemplateParameter
import pl.mos.backoffice.shared.tasks.TemplateParameterType
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import pl.mos.backoffice.tasks.support.Library

class TemplateValidatorTest {

    private val lib = Library()
    private val validator = TemplateValidator(lib.lookup)

    private fun candidate(
        key: String = "candidate",
        extendsKey: String? = null,
        abstract: Boolean = false,
        agentKey: String? = null,
        parameters: List<TemplateParameter> = emptyList(),
        parameterValues: Map<String, Any?> = emptyMap(),
        outputSchema: String? = null,
        confidenceField: String? = null,
        scope: Scope = Scope.Global,
        runInputs: List<RunInputField> = emptyList(),
    ) = TaskTemplate.create(
        TaskTemplate.Content(
            key = key, name = key, extendsKey = extendsKey, abstract = abstract, agentKey = agentKey,
            parameters = parameters, parameterValues = parameterValues, outputSchema = outputSchema, confidenceField = confidenceField, scope = scope, runInputs = runInputs,
        ),
        lib.now,
    )

    private fun fields(t: TaskTemplate) = validator.problems(t).map { it.field }

    @Test
    fun `a well formed concrete template passes`() {
        validator.validate(candidate(parameters = listOf(TemplateParameter("pp", "P", default = "x")), outputSchema = """{"type":"object"}""", confidenceField = "confidence"))
    }

    @Test
    fun `the aggregate itself rejects malformed keys`() {
        assertThatThrownBy { candidate(key = "Bad Key") }.isInstanceOf(ValidationException::class.java).hasMessageContaining("key")
        assertThatThrownBy { candidate(key = "a") }.describedAs("one character is too short").isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { candidate(key = "1ab") }.describedAs("must start with a letter").isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `parent must exist and cannot be the template itself`() {
        assertThat(fields(candidate(extendsKey = "candidate"))).containsExactly("extendsKey")
        assertThat(validator.problems(candidate(extendsKey = "ghost")).single().message).contains("ghost does not exist")
        lib.template("parent", abstract = true)
        assertThat(fields(candidate(extendsKey = "parent"))).isEmpty()
    }

    @Test
    fun `parent lookup honours project shadowing`() {
        lib.template("parent", abstract = true, scope = Scope.Project(lib.projectId))
        assertThat(fields(candidate(extendsKey = "parent", scope = Scope.Project(lib.projectId)))).isEmpty()
        assertThat(fields(candidate(extendsKey = "parent"))).containsExactly("extendsKey")
    }

    @Test
    fun `a template needs no agent, but a named agent must exist where the template is visible`() {
        assertThat(fields(candidate())).describedAs("no agent anywhere = model answer").isEmpty()
        assertThat(fields(candidate(agentKey = "opencode"))).isEmpty()
        assertThat(validator.problems(candidate(agentKey = "ghost")).map { it.field to it.message }).containsExactly("agentKey" to "agent ghost does not exist")

        lib.agent("mine", AgentHarness.CLAUDE_CODE, scope = Scope.Project(lib.projectId))
        assertThat(fields(candidate(agentKey = "mine"))).describedAs("a project agent is not visible globally").containsExactly("agentKey")
        assertThat(fields(candidate(agentKey = "mine", scope = Scope.Project(lib.projectId)))).isEmpty()
    }

    @Test
    fun `output schema must be well formed JSON and confidence field needs a schema`() {
        assertThat(validator.problems(candidate(outputSchema = """{"type": }""")).single().message).contains("not well-formed JSON")
        assertThat(fields(candidate(confidenceField = "confidence"))).containsExactly("confidenceField")

        lib.template("root", abstract = true, outputSchema = "{}")
        assertThat(fields(candidate(extendsKey = "root", confidenceField = "confidence"))).isEmpty()
    }

    @Test
    fun `ADR 0058 - parameter declarations are unique and consistent`() {
        val problems = validator.problems(
            candidate(
                parameters = listOf(
                    TemplateParameter("aa", "A"),
                    TemplateParameter("aa", "A again"),
                    TemplateParameter("pick", "Pick", TemplateParameterType.CHOICE),
                    TemplateParameter("dup", "Dup", TemplateParameterType.CHOICE, options = listOf("x", "x")),
                    TemplateParameter("num", "Num", TemplateParameterType.NUMBER, default = "ten"),
                    TemplateParameter("txt", "Txt", options = listOf("x")),
                ),
            ),
        )
        assertThat(problems.map { it.field to it.message }).containsExactly(
            "parameters" to "parameter names must be unique; aa is declared more than once",
            "parameters" to "parameter pick: a CHOICE needs non-blank options",
            "parameters" to "parameter dup: options must be distinct",
            "parameters" to "parameter num: default \"ten\" is not a number",
            "parameters" to "parameter txt: options are only for CHOICE parameters",
        )
    }

    @Test
    fun `ADR 0058 - parameter values name parameters of the lineage and fit their type`() {
        lib.template("root", abstract = true, parameters = listOf(TemplateParameter("depth", "Depth", TemplateParameterType.NUMBER)))

        assertThat(fields(candidate(extendsKey = "root", parameterValues = mapOf("depth" to 3)))).isEmpty()
        val problems = validator.problems(candidate(extendsKey = "root", parameterValues = mapOf("depth" to "deep", "ghost" to 1)))
        assertThat(problems.map { it.field to it.message }).containsExactly(
            "parameterValues" to "parameter depth: \"deep\" is not a number",
            "parameterValues" to "parameter ghost is not declared by this template or a template it extends",
        )
        assertThat(fields(candidate(parameters = listOf(TemplateParameter("own", "Own", TemplateParameterType.BOOLEAN)), parameterValues = mapOf("own" to true))))
            .describedAs("its own declaration counts").isEmpty()
    }

    @Test
    fun `validate collects every problem into one exception`() {
        assertThatThrownBy { validator.validate(candidate(extendsKey = "ghost", agentKey = "nobody", confidenceField = "cc", parameterValues = mapOf("i" to 1))) }
            .isInstanceOf(ValidationException::class.java)
            .satisfies({ e -> assertThat((e as ValidationException).errors.map { it.field }).containsExactly("extendsKey", "agentKey", "confidenceField", "parameterValues") })
    }

    @Test
    fun `a project template shadowing a global one inherits its schema through it`() {
        lib.template("review", outputSchema = "{}")
        val shadow = candidate(key = "review", extendsKey = "review", confidenceField = "confidence", scope = Scope.Project(lib.projectId))
        assertThat(fields(shadow)).isEmpty()
        assertThat(fields(candidate(key = "solo", extendsKey = "solo", scope = Scope.Project(lib.projectId))))
            .describedAs("no global template to shadow").containsExactly("extendsKey")
    }

    @Test
    fun `a cycle among existing ancestors does not make the validator loop`() {
        lib.template("aa", extendsKey = "bb")
        lib.template("bb", extendsKey = "aa")
        assertThat(fields(candidate(extendsKey = "aa"))).isEmpty()
    }

    @Test
    fun `run input names must be unique within a template`() {
        val problems = validator.problems(candidate(runInputs = listOf(RunInputField("input", "Input"), RunInputField("input", "Again", RunInputType.MULTILINE))))

        assertThat(problems.map { it.field }).containsExactly("runInputs")
        assertThat(problems.single().message).contains("input").contains("unique")
        assertThat(fields(candidate(runInputs = listOf(RunInputField("input", "Input"), RunInputField("issueKey", "Issue", RunInputType.ISSUE_KEY))))).isEmpty()
    }

    @Test
    fun `run input names and labels must not be blank`() {
        assertThatThrownBy { candidate(runInputs = listOf(RunInputField(" ", "Input"))) }.isInstanceOf(ValidationException::class.java).hasMessageContaining("name")
        assertThatThrownBy { candidate(runInputs = listOf(RunInputField("input", " "))) }.isInstanceOf(ValidationException::class.java).hasMessageContaining("label")
    }
}
