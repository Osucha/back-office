package pl.mos.backoffice.runnercontroller.config

import org.springframework.boot.context.properties.ConfigurationProperties
import pl.mos.backoffice.runnercontroller.git.RepoCredential

enum class BackendChoice { AUTO, AGENT_SANDBOX, K8S_JOB, DOCKER }

enum class CredentialStoreKind { AUTO, FILE, KUBERNETES_SECRET }

/** `runner-controller.*` (see application.yaml and backend/runner-controller/README.md). */
@ConfigurationProperties("runner-controller")
data class ControllerProperties(
    /** false = do not connect to Back Office at start (tests, `--dry-run` style wiring checks). */
    val enabled: Boolean = true,
    val backOffice: BackOffice = BackOffice(),
    /** Name reported at registration; defaults to the host name. */
    val name: String? = null,
    val cluster: String? = null,
    val backend: BackendChoice = BackendChoice.AUTO,
    val maxConcurrent: Int = 4,
    val lease: Lease = Lease(),
    val heartbeatSeconds: Long = 15,
    val monitorIntervalMillis: Long = 2000,
    /** On shutdown: stop leasing, let running jobs finish for this long, then hand the rest back (LOST). */
    val shutdownGraceSeconds: Long = 300,
    val imagePullGraceSeconds: Long = 90,
    val startTimeoutSeconds: Long = 900,
    /** Added to the policy timeout for the pod deadline, so the runner can still post its result. */
    val deadlineGraceSeconds: Long = 120,
    val verification: Verification = Verification(),
    val defaults: Defaults = Defaults(),
    val egress: Egress = Egress(),
    val git: Git = Git(),
    val kubernetes: Kubernetes = Kubernetes(),
    val docker: Docker = Docker(),
    val localLogin: LocalLogin = LocalLogin(),
    /** Keep finished sandboxes for debugging instead of deleting them (never in production). */
    val keepFinishedSandboxes: Boolean = false,
) {
    data class BackOffice(
        /** Back Office origin, e.g. `https://backoffice.example.com` (a trailing `/api/v1` is accepted). */
        val url: String = "",
        /** Single-use registration token (`bo_rrt_`) used when no runner token is stored yet. */
        val registrationToken: String = "",
        val credentialStore: CredentialStoreKind = CredentialStoreKind.AUTO,
        val credentialFile: String = "./runner-credentials.json",
        val credentialSecret: String = "back-office-runner-credentials",
        /** Namespace of [credentialSecret]; empty = the controller's own namespace (never the sandbox namespace). */
        val credentialSecretNamespace: String = "",
    )

    data class Lease(val waitSeconds: Int = 25, val maxJobsPerLease: Int = 4)

    data class Verification(val requireSignature: Boolean = true, val clockSkewSeconds: Long = 5)

    data class Defaults(
        /** Image when neither the policy nor the ceiling name one. */
        val image: String? = null,
        val cpu: String = "2",
        val memory: String = "4Gi",
        val ephemeralStorage: String = "10Gi",
        val pids: Int = 2048,
        val timeoutSeconds: Int = 3600,
    )

    data class Egress(
        /** Hosts every job may reach in addition to the gateway and git host (e.g. the object storage of upload URLs). */
        val alwaysAllow: List<String> = emptyList(),
        /** Proxy <-> controller token; generated and stored in Secret `bo-egress-<pool>` when empty. */
        val token: String = "",
        val proxyImage: String = "back-office/egress-proxy:dev",
        val proxyPort: Int = 4750,
        /** Smokescreen `--allow-range` (CIDR): private ranges the proxy may connect to, e.g. an in-cluster Back Office. */
        val allowRanges: List<String> = emptyList(),
        /** Smokescreen `--allow-address` (IP[:PORT]). */
        val allowAddresses: List<String> = emptyList(),
    )

    data class Git(
        /**
         * Least privilege (ADR 0031): repository pattern -> read Secret (READ jobs) and push Secret (PUSH_MR jobs),
         * key `token`, in the sandbox namespace. See [RepoCredential] for the patterns; the most specific wins.
         */
        val repoSecrets: List<RepoCredential> = emptyList(),
        /**
         * Compatibility fallback, PUSH level: repository host -> Secret (key `token`); `*` = default. Used only when
         * no [repoSecrets] entry matches; READ jobs are refused with it unless [allowPushCredentialForRead].
         */
        val secrets: Map<String, String> = emptyMap(),
        /** Hand a push credential to READ jobs when no read credential exists (not recommended). */
        val allowPushCredentialForRead: Boolean = false,
        /** Docker backend only: controller env var with the read-only git token (READ jobs, every repository). */
        val tokenReadEnv: String = "BO_GIT_TOKEN_READ",
        /** Docker backend only: controller env var with the push token (PUSH_MR jobs, every repository). */
        val tokenPushEnv: String = "BO_GIT_TOKEN_PUSH",
        /** Docker backend only: legacy env var with one git token for all hosts, treated as push-level fallback. */
        val tokenEnv: String = "BO_GIT_TOKEN",
        /** Commit identity passed to every sandbox as `GIT_USER_NAME` / `GIT_USER_EMAIL`; empty = the runner's default. */
        val userName: String = "",
        val userEmail: String = "",
    ) {
        /** Sandbox environment for the commit identity (only the values that are set). */
        fun identityEnv(): Map<String, String> = buildMap {
            if (userName.isNotBlank()) put("GIT_USER_NAME", userName)
            if (userEmail.isNotBlank()) put("GIT_USER_EMAIL", userEmail)
        }
    }

    /**
     * ADR 0040, personal runners (DOCKER backend): jobs whose pool uses LOCAL_LOGIN run Claude Code with the owner's
     * own login. The controller reads it from [tokenEnv] (`claude setup-token`), delivers it as a bundle file the
     * runner deletes after reading, and lets the sandbox reach [hosts].
     */
    data class LocalLogin(
        val tokenEnv: String = "CLAUDE_CODE_OAUTH_TOKEN",
        val hosts: List<String> = listOf("api.anthropic.com"),
    )

    data class Kubernetes(
        /** Namespace for sandboxes and pool resources; empty = the controller's own namespace. */
        val namespace: String = "",
        /** Isolation -> RuntimeClass name; empty value = no runtimeClassName. */
        val runtimeClasses: Map<String, String> = mapOf("STANDARD" to "gvisor", "HIGH" to "kata"),
        /** Refuse K8S_JOB/AGENT_SANDBOX jobs whose RuntimeClass does not exist in the cluster. */
        val requireRuntimeClass: Boolean = true,
        val jobTtlSecondsAfterFinished: Int = 600,
        /** Service URL of this controller as seen by the egress proxy pods. */
        val controllerUrl: String = "http://back-office-runner-controller:8090",
        val proxyReplicas: Int = 2,
        /** Cache name (gradle, maven, npm, pnpm, pip, cargo, go) -> PersistentVolumeClaim name. */
        val cachePvcs: Map<String, String> = emptyMap(),
        /** Label selector of the cluster DNS pods (egress to UDP/TCP 53 is allowed only to them). */
        val dnsNamespace: String = "kube-system",
        val dnsPodLabels: Map<String, String> = mapOf("k8s-app" to "kube-dns"),
        /** Overrides `RunnerConfig.warmPoolSize` (Agent Sandbox backend only); null = use the pool's value. */
        val warmPoolSize: Int? = null,
        /** Private CIDRs never reachable from the egress proxy pods (NetworkPolicy `except`). */
        val privateCidrs: List<String> = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "100.64.0.0/10"),
    )

    data class Docker(
        val executable: String = "docker",
        /** Directory for per-job bundle files (mounted read-only into the container). */
        val workDir: String = "",
        /** Extra `--add-host` entries for the proxy container, e.g. `host.docker.internal:host-gateway`. */
        val proxyExtraHosts: List<String> = listOf("host.docker.internal:host-gateway"),
        /** Extra environment of the agent container (development only, e.g. `RUNNER_ENABLE_STUB_AGENT=1` in tests). */
        val extraEnv: Map<String, String> = emptyMap(),
    )
}
