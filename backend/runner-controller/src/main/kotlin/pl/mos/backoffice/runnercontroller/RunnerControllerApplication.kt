package pl.mos.backoffice.runnercontroller

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/** Execution plane of the runner fleet (ADR 0024, docs/architecture/10-agent-execution.md). */
@SpringBootApplication
@ConfigurationPropertiesScan
class RunnerControllerApplication

fun main(args: Array<String>) {
    runApplication<RunnerControllerApplication>(*args)
}
