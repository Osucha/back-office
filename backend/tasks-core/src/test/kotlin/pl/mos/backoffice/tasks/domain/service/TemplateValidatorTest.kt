package pl.mos.backoffice.tasks.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.tasks.RunInputField
import pl.mos.backoffice.shared.tasks.RunInputType
import pl.mos.backoffice.shared.tasks.TaskExecutor
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
        executor: TaskExecutor? = TaskExecutor.LLM,
        implements: List<String> = emptyList(),
        skills: List<String> = emptyList(),
        outputSchema: String? = null,
        confidenceField: String? = null,
        scope: Scope = Scope.Global,
        runInputs: List<RunInputField> = emptyList(),
    ) = TaskTemplate.create(
        TaskTemplate.Content(
            key = key, name = key, extendsKey = extendsKey, abstract = abstract, executor = executor, implements = implements,
            skills = skills, outputSchema = outputSchema, confidenceField = confidenceField, scope = scope, runInputs = runInputs,
        ),
        lib.now,
    )

    private fun fields(t: TaskTemplate) = validator.problems(t).map { it.field }

    @Test
    fun `a well formed concrete template passes`() {
        lib.iface("reviewer")
        lib.skill("ss")
        validator.validate(candidate(implements = listOf("reviewer"), skills = listOf("ss"), outputSchema = """{"type":"object"}""", confidenceField = "confidence"))
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
        lib.template("parent", abstract = true, executor = TaskExecutor.LLM)
        assertThat(fields(candidate(extendsKey = "parent"))).isEmpty()
    }

    @Test
    fun `parent lookup honours project shadowing`() {
        lib.template("parent", abstract = true, executor = TaskExecutor.LLM, scope = Scope.Project(lib.projectId))
        assertThat(fields(candidate(extendsKey = "parent", scope = Scope.Project(lib.projectId)))).isEmpty()
        assertThat(fields(candidate(extendsKey = "parent"))).containsExactly("extendsKey")
    }

    @Test
    fun `a concrete template needs an executor unless an ancestor has one`() {
        assertThat(fields(candidate(executor = null))).containsExactly("executor")
        assertThat(fields(candidate(executor = null, abstract = true))).isEmpty()

        lib.template("root", abstract = true, executor = TaskExecutor.SPEC_KIT)
        lib.template("middle", extendsKey = "root", abstract = true)
        assertThat(fields(candidate(executor = null, extendsKey = "middle"))).isEmpty()

        lib.template("bare", abstract = true)
        assertThat(fields(candidate(executor = null, extendsKey = "bare"))).containsExactly("executor")
    }

    @Test
    fun `output schema must be well formed JSON and confidence field needs a schema`() {
        assertThat(validator.problems(candidate(outputSchema = """{"type": }""")).single().message).contains("not well-formed JSON")
        assertThat(fields(candidate(confidenceField = "confidence"))).containsExactly("confidenceField")

        lib.template("root", abstract = true, executor = TaskExecutor.LLM, outputSchema = "{}")
        assertThat(fields(candidate(extendsKey = "root", confidenceField = "confidence"))).isEmpty()
    }

    @Test
    fun `implemented interfaces and referenced skills must exist`() {
        lib.iface("known")
        lib.skill("known")
        lib.skill("mine", scope = Scope.Project(lib.projectId))

        val problems = validator.problems(candidate(implements = listOf("known", "nope"), skills = listOf("known", "nope", "mine")))
        assertThat(problems.map { it.field to it.message }).containsExactly(
            "implements" to "interface nope does not exist",
            "skills" to "skill nope does not exist",
            "skills" to "skill mine does not exist",
        )
        assertThat(fields(candidate(skills = listOf("mine"), scope = Scope.Project(lib.projectId)))).isEmpty()
    }

    @Test
    fun `validate collects every problem into one exception`() {
        assertThatThrownBy { validator.validate(candidate(extendsKey = "ghost", executor = null, confidenceField = "cc", implements = listOf("i"))) }
            .isInstanceOf(ValidationException::class.java)
            .satisfies({ e -> assertThat((e as ValidationException).errors.map { it.field }).containsExactly("extendsKey", "executor", "confidenceField", "implements") })
    }

    @Test
    fun `a cycle among existing ancestors does not make the validator loop`() {
        lib.template("aa", extendsKey = "bb", executor = TaskExecutor.LLM)
        lib.template("bb", extendsKey = "aa")
        assertThat(fields(candidate(extendsKey = "aa", executor = null))).isEmpty()
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
