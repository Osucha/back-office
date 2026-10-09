package pl.mos.backoffice.runnercontroller.egress

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest

/**
 * Pull API of the per-pool egress proxy (`egress-proxy/main.go`, dynamic mode). Bearer token = the pool's
 * egress token (Secret `bo-egress-<pool>`); the NetworkPolicy of the controller should admit only the proxy pods.
 */
@RestController
class EgressEndpoints(private val registry: EgressRegistry, private val token: EgressToken) {

    @GetMapping("/egress/v1/acl")
    fun acl(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestHeader("If-None-Match", required = false) ifNoneMatch: String?,
    ): ResponseEntity<Any> {
        if (!token.matches(authorization)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val snapshot = registry.snapshot()
        val version = snapshot["version"] as String
        if (ifNoneMatch == version) return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(version).build()
        return ResponseEntity.ok().eTag(version).body(snapshot)
    }

    @PostMapping("/egress/v1/decisions")
    fun decisions(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestBody decisions: List<EgressDecision>,
    ): ResponseEntity<Void> {
        if (!token.matches(authorization)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        registry.record(decisions)
        return ResponseEntity.accepted().build()
    }
}

/** Shared secret between the controller and its pool's egress proxy. */
class EgressToken(@Volatile var value: String = "") {
    fun matches(authorization: String?): Boolean {
        val presented = authorization?.removePrefix("Bearer ")?.trim() ?: return false
        return value.isNotEmpty() && MessageDigest.isEqual(presented.toByteArray(), value.toByteArray())
    }
}
