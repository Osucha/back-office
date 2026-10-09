package pl.mos.backoffice.runnercontroller.protocol

import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

/** What registration returns and every later start needs: the runner token (`bo_rt_`). */
data class RunnerCredentials(val runnerId: String?, val runnerToken: String, val poolId: String?)

/** Persists the runner token so a restart does not need a new (single-use) registration token. */
interface RunnerCredentialStore {
    fun load(): RunnerCredentials?
    fun save(credentials: RunnerCredentials)
}

/** JSON file (Docker / laptop), written atomically with mode 0600 where the file system supports it. */
class FileCredentialStore(private val path: Path, private val json: JsonMapper) : RunnerCredentialStore {
    override fun load(): RunnerCredentials? =
        if (Files.isRegularFile(path)) json.readValue(Files.readString(path), RunnerCredentials::class.java) else null

    override fun save(credentials: RunnerCredentials) {
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val tmp = Files.createTempFile(path.toAbsolutePath().parent, ".runner-credentials", ".tmp")
        Files.writeString(tmp, json.writeValueAsString(credentials))
        runCatching { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------")) }
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

/** Kubernetes Secret in the controller's namespace (keys `runnerId`, `runnerToken`, `poolId`). */
class KubernetesSecretCredentialStore(
    private val client: KubernetesClient,
    private val namespace: String,
    private val secretName: String,
) : RunnerCredentialStore {
    override fun load(): RunnerCredentials? {
        val secret = client.secrets().inNamespace(namespace).withName(secretName).get() ?: return null
        val data = secret.data.orEmpty().mapValues { String(Base64.getDecoder().decode(it.value)) } + secret.stringData.orEmpty()
        val token = data["runnerToken"]?.takeIf { it.isNotBlank() } ?: return null
        return RunnerCredentials(data["runnerId"], token, data["poolId"])
    }

    override fun save(credentials: RunnerCredentials) {
        val secret = SecretBuilder()
            .withNewMetadata()
            .withName(secretName)
            .withNamespace(namespace)
            .addToLabels("app.kubernetes.io/managed-by", "back-office-runner-controller")
            .endMetadata()
            .withType("Opaque")
            .addToStringData("runnerToken", credentials.runnerToken)
            .addToStringData("runnerId", credentials.runnerId ?: "")
            .addToStringData("poolId", credentials.poolId ?: "")
            .build()
        client.secrets().inNamespace(namespace).resource(secret).createOr { it.update() }
    }
}
