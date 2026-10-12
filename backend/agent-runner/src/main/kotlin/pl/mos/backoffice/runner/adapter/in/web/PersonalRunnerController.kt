package pl.mos.backoffice.runner.adapter.`in`.web

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.mos.backoffice.runner.application.port.`in`.ManagePersonalRunner
import pl.mos.backoffice.runner.domain.LlmCredentials
import pl.mos.backoffice.shared.ValidationException
import java.util.UUID

/** `/api/v1/me/runner` (openapi tag `fleet`, ADR 0040): the signed-in user's personal runner; `fleet.personal` is checked in the use case. */
@RestController
@RequestMapping("/api/v1/me/runner")
class PersonalRunnerController(private val personal: ManagePersonalRunner) {

    /** 204 when the user has no personal runner yet. */
    @GetMapping
    fun get(): ResponseEntity<PersonalRunnerJson> =
        personal.mine()?.let { ResponseEntity.ok(PersonalRunnerJson.of(it)) } ?: ResponseEntity.noContent().build()

    @PutMapping
    fun enable(@RequestBody body: PersonalRunnerInputJson): PersonalRunnerJson =
        PersonalRunnerJson.of(personal.enable(credentials(body.llmCredentials)))

    @DeleteMapping
    fun disable(): ResponseEntity<Void> {
        personal.disable()
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/registration-tokens")
    fun registrationToken(): ResponseEntity<PersonalRegistrationTokenJson> {
        val issued = personal.issueRegistrationToken()
        return ResponseEntity.status(HttpStatus.CREATED).body(PersonalRegistrationTokenJson(issued.token, issued.expiresAt, issued.dockerCommand))
    }

    @DeleteMapping("/runners/{runnerId}")
    fun revoke(@PathVariable runnerId: UUID): ResponseEntity<Void> {
        personal.revokeRunner(runnerId)
        return ResponseEntity.noContent().build()
    }

    private fun credentials(value: String): LlmCredentials =
        LlmCredentials.entries.firstOrNull { it.name == value.uppercase() }
            ?: throw ValidationException("llmCredentials must be GATEWAY or LOCAL_LOGIN", "llmCredentials")
}
