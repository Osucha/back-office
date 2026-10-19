package pl.mos.backoffice.blob.adapter.`in`.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.mos.backoffice.blob.BlobUrlSigner
import pl.mos.backoffice.blob.FilesystemBlobStore
import pl.mos.backoffice.shared.ValidationException
import java.net.URLDecoder

/**
 * Serves the presigned links of [FilesystemBlobStore] (`/blobs/{key}?method&expires&signature`). The HMAC
 * signature is the authorisation, as with S3 presigned URLs, so the path is outside `/api`; a missing,
 * wrong or expired signature answers 403 without revealing whether the key exists. Registered only when
 * `backoffice.storage.type=filesystem`: component scanning also finds it, hence the same condition here.
 */
@RestController
@ConditionalOnProperty(prefix = "backoffice.storage", name = ["type"], havingValue = "filesystem", matchIfMissing = true)
class FilesystemBlobController(private val store: FilesystemBlobStore) {

    @GetMapping("/blobs/**")
    fun get(
        request: HttpServletRequest,
        response: HttpServletResponse,
        @RequestParam(required = false) method: String?,
        @RequestParam(required = false) expires: Long?,
        @RequestParam(required = false) signature: String?,
    ) {
        val key = keyOf(request) ?: return response.sendError(HttpStatus.BAD_REQUEST.value())
        if (!authorised("GET", method, key, expires, signature)) return response.sendError(HttpStatus.FORBIDDEN.value())
        val stat = store.stat(key) ?: return response.sendError(HttpStatus.NOT_FOUND.value())
        val input = store.open(key) ?: return response.sendError(HttpStatus.NOT_FOUND.value())
        response.contentType = stat.contentType
        response.setContentLengthLong(stat.sizeBytes)
        response.setHeader("Cache-Control", "private, no-store")
        stat.sha256?.let { response.setHeader("ETag", "\"$it\"") }
        input.use { it.transferTo(response.outputStream) }
    }

    @PutMapping("/blobs/**")
    fun put(
        request: HttpServletRequest,
        response: HttpServletResponse,
        @RequestParam(required = false) method: String?,
        @RequestParam(required = false) expires: Long?,
        @RequestParam(required = false) signature: String?,
    ) {
        val key = keyOf(request) ?: return response.sendError(HttpStatus.BAD_REQUEST.value())
        if (!authorised("PUT", method, key, expires, signature)) return response.sendError(HttpStatus.FORBIDDEN.value())
        val ref = store.put(key, request.contentType ?: "application/octet-stream", request.inputStream, request.contentLengthLong)
        response.status = HttpStatus.OK.value()
        ref.sha256?.let { response.setHeader("ETag", "\"$it\"") }
    }

    private fun authorised(expected: String, method: String?, key: String, expires: Long?, signature: String?): Boolean =
        method.equals(expected, ignoreCase = true) && expires != null && signature != null && store.signer.verify(expected, key, expires, signature)

    private fun keyOf(request: HttpServletRequest): String? {
        val path = request.requestURI.removePrefix(request.contextPath.orEmpty())
        if (!path.startsWith(BlobUrlSigner.PATH_PREFIX)) return null
        val key = path.removePrefix(BlobUrlSigner.PATH_PREFIX).split('/').joinToString("/") { URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8) }
        return try {
            pl.mos.backoffice.blob.BlobKeys.validate(key)
        } catch (e: ValidationException) {
            null
        }
    }
}
