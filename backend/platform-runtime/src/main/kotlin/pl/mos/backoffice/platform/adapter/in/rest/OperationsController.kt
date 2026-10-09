package pl.mos.backoffice.platform.adapter.`in`.rest

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pl.mos.backoffice.platform.application.port.`in`.OperationsOverview
import pl.mos.backoffice.platform.application.port.`in`.OperationsQueries
import pl.mos.backoffice.platform.application.port.`in`.RetryWorkItem
import pl.mos.backoffice.platform.application.port.`in`.WorkItemView
import pl.mos.backoffice.shared.usage.PageOf
import java.util.UUID

/** Admin -> operations (openapi.yaml tag `operations`). Authorisation is in the use cases (`admin.settings.manage`). */
@RestController
@RequestMapping("/api/v1/admin/operations")
class OperationsController(private val queries: OperationsQueries, private val retry: RetryWorkItem) {

    @GetMapping("/overview")
    fun overview(): OperationsOverview = queries.overview()

    @GetMapping("/work-items")
    fun workItems(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) kind: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageOf<WorkItemView> = queries.workItems(status, kind, page, size)

    @PostMapping("/work-items/{itemId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun retry(@PathVariable itemId: UUID) = retry.retry(itemId)
}
