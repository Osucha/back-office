package pl.mos.backoffice.projects.adapter.`in`.rest

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.mos.backoffice.projects.application.port.`in`.IntegrationsStatus

/** `settings` endpoints: integration status (never secrets) and connectivity tests. */
@RestController
@RequestMapping("/api/v1/settings/integrations")
class SettingsController(private val integrations: IntegrationsStatus, private val mapper: RestMapper) {

    @GetMapping
    fun status(): IntegrationsStatusDto = mapper.toDto(integrations.status())

    @PostMapping("/{name}/test")
    fun test(@PathVariable name: String): IntegrationTestResultDto = mapper.toDto(integrations.test(name))
}
