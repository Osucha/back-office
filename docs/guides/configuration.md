# Configuration reference

## When to use this guide

You are setting an environment variable for the API, filling in Helm values, writing a
`deploy/compose/.env`, or wondering which Secret a value ends up in. Every variable read by
`backend/app/src/main/resources/application.yml`, by the runner-controller and by the runner image is listed here with its
default, whether it is a secret, and the Helm value that sets it.

Conventions: **Secret = yes** means the value must live in a Kubernetes Secret (the chart never puts it
in a ConfigMap). The "Helm value" column names the `values.yaml` key; "derived" means the chart
computes it from other values (release name, namespace, ingress host).

## Core and security

| Variable | Default | Description | Secret | Helm value |
|---|---|---|---|---|
| `RUNNER_TOKEN_SECRET` | _(required)_ | HMAC-SHA256 secret used to mint per-job runner callback tokens. It no longer falls back to an API key | yes | `secrets.runnerTokenSecret` (generated and kept across upgrades when empty) |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200` | Comma separated browser origins. Not needed when the web pod proxies `/api` (same origin) | no | `api.config.corsAllowedOrigins` (defaults to public URL) |
| `BACKOFFICE_PUBLIC_URL` | `http://localhost:8080` | External URL of the UI: run links printed by the CI client, Jira comments, notifications, and the `post_logout_redirect_uri` of the OIDC logout (must match a post-logout redirect URI registered at the provider) | no | `api.config.publicUrl` (derived from `ingress.host` when empty) |
| `LOG_LEVEL` | `INFO` | Level of `pl.mos.backoffice` loggers | no | `api.config.logLevel` |
| `SPRING_PROFILES_ACTIVE` | _(none)_ | `local` enables dev defaults (compose Keycloak as issuer, bootstrap admin `admin@example.com`, non-Secure cookie, compose auto-start, DEBUG logs). Never set in Kubernetes | no | - (`api.extraEnv`) |
| `JAVA_TOOL_OPTIONS` | set in Dockerfile | JVM flags (`MaxRAMPercentage=75`, ZGC) | no | `api.javaToolOptions` |

The API keys of iterations 1-2 (`BACKOFFICE_API_KEY`, `BACKOFFICE_CI_API_KEY`, header `X-Api-Key`)
are **removed** (ADR 0016). People log in through OIDC, scripts use personal access tokens
(`bo_pat_...`), CI uses project CI tokens (`bo_ci_...`); all three are managed in the UI and stored hashed.

## Authentication and sessions

Properties `backoffice.auth.*` (Spring relaxed binding: `backoffice.auth.oidc.issuer-uri` =
`BACKOFFICE_AUTH_OIDC_ISSUER_URI`). Flow, cookies and timeouts: [08-identity-and-access.md](../architecture/08-identity-and-access.md).

| Variable | Default | Description | Secret | Helm value |
|---|---|---|---|---|
| `BACKOFFICE_AUTH_OIDC_ISSUER_URI` | _(required; `local`: `http://localhost:8180/realms/back-office`)_ | OIDC issuer. The API fetches `<issuer>/.well-known/openid-configuration` lazily at the first sign-in (not at startup, so the API starts while the provider is still booting; a failed discovery is retried at the next sign-in), and the `iss` of ID tokens must equal it: it must be reachable from the API **and** be the URL the browser is redirected to | no | `auth.oidc.issuerUri` (derived `<bundledKeycloak url>/realms/back-office` when `keycloak.enabled`) |
| `BACKOFFICE_AUTH_OIDC_CLIENT_ID` | `back-office` | Confidential client registered at the provider (authorization code + PKCE) | no | `auth.oidc.clientId` |
| `BACKOFFICE_AUTH_OIDC_CLIENT_SECRET` | _(required; `local`: `back-office-dev-secret`)_ | Client secret | yes | `auth.oidc.existingSecret` + `existingSecretKey` (default `client-secret`), or `auth.oidc.clientSecret` -> Secret `<fullname>-app` |
| `BACKOFFICE_AUTH_OIDC_SCOPES` | `openid,profile,email` | Requested scopes, comma separated (add `groups` or similar where the provider needs a scope for the groups claim) | no | `auth.oidc.scopes` |
| `BACKOFFICE_AUTH_REGISTRATION` | `INVITE_ONLY` | `INVITE_ONLY`: only invited e-mails, allowed domains and bootstrap admins get an account on first login; `OPEN`: everyone the provider authenticates | no | `auth.registration` |
| `BACKOFFICE_AUTH_ALLOWED_EMAIL_DOMAINS` | _(empty)_ | Comma separated domains accepted without invitation in `INVITE_ONLY` mode | no | `auth.allowedEmailDomains` (list or string) |
| `BACKOFFICE_AUTH_BOOTSTRAP_ADMINS` | _(empty)_ | Comma separated e-mails that receive `platform-admin` on first login - the only way to get the first administrator | no | `auth.bootstrapAdmins` (list or string) |
| `BACKOFFICE_AUTH_SESSION_IDLE_TIMEOUT` | `PT30M` | Session idle timeout (ISO-8601); shown in the UI countdown | no | `auth.session.idleTimeout` |
| `BACKOFFICE_AUTH_SESSION_ABSOLUTE_TIMEOUT` | `PT12H` | Maximum session age since login | no | `auth.session.absoluteTimeout` |
| `BACKOFFICE_AUTH_COOKIE_SECURE` | `true` (`local`: `false`) | `Secure` flag of `BO_SESSION`. `false` only on plain-http dev setups | no | `auth.cookieSecure` |
| `BACKOFFICE_AUTH_GROUPS_CLAIM` | `groups` | ID token / userinfo claim with the user's group names (mapped to Back Office groups) | no | `auth.groupsClaim` |
| `BACKOFFICE_AUTH_SYNC_GROUPS` | `false` | Default of the platform setting "synchronise groups from the identity provider" | no | - (`api.config.extra`) |
| `BACKOFFICE_AUTH_PERSONAL_TOKEN_MAX_DAYS` | `90` | Default of the platform setting "personal token max days" | no | - (`api.config.extra`) |
| `BACKOFFICE_AUTH_OIDC_PROVIDER_NAME` | `Keycloak` | Provider name shown on the login page | no | - (`api.config.extra`) |
| `BACKOFFICE_AUTH_OIDC_ACCOUNT_URL` | derived for Keycloak issuers (`<issuer>/account`) | Link to the provider's account page (password, MFA) | no | - (`api.config.extra`) |

**Local accounts** (e-mail + password, optional TOTP; ADR 0021, [authentication-and-users.md](authentication-and-users.md) section 11).
Properties `backoffice.auth.local.*`; `application.yml` maps them to these variables:

| Variable | Default | Description | Secret | Helm value |
|---|---|---|---|---|
| `BACKOFFICE_AUTH_LOCAL_ENABLED` | `true` | Local login on/off (hard switch; the platform setting "local login" can only narrow it). With OIDC off **and** local off only bearer tokens authenticate | no | `auth.local.enabled` |
| `BACKOFFICE_AUTH_LOCAL_SECRET_KEY` | _(required while local login is enabled; `local` profile: a fixed dev key)_ | Base64 of 32 bytes (`openssl rand -base64 32`): AES-GCM key of TOTP secrets, HMAC key of recovery codes and MFA step tokens. Startup fails with a clear message when missing or not 32 bytes. Changing it invalidates enrolled authenticators and recovery codes | yes | generated into `<fullname>-app` (kept across upgrades), or `auth.local.secretKey` |
| `BACKOFFICE_AUTH_LOCAL_BOOTSTRAP_ADMIN_EMAIL` | _(empty; `local` profile: `owner@example.com`)_ | First platform admin without an identity provider; created only when the database has no user | no | `auth.local.bootstrapAdminEmail` |
| `BACKOFFICE_AUTH_LOCAL_BOOTSTRAP_ADMIN_PASSWORD` | _(empty; `local` profile: `ChangeMe-Local-123`)_ | Its initial password (must satisfy the policy; changed at the first login) | yes | `auth.local.bootstrapAdminPassword` -> `<fullname>-app` |
| `BACKOFFICE_AUTH_LOCAL_RESET_LINK_VALIDITY` | `PT24H` | Lifetime of set-password / reset links | no | `auth.local.resetLinkValidity` |
| `BACKOFFICE_AUTH_LOCAL_LOCKOUT_MAX_FAILURES` | `5` | Default of the platform setting: consecutive failures before a lockout | no | `auth.local.lockout.maxFailures` |
| `BACKOFFICE_AUTH_LOCAL_LOCKOUT_DURATION` | `PT15M` | Default first lockout (doubles per further lockout, max 24 h) | no | `auth.local.lockout.lockDuration` |
| `BACKOFFICE_AUTH_LOCAL_THROTTLE_MAX_ATTEMPTS` | `20` | Sign-in attempts per source IP and window (`/auth/login`, `/auth/login/mfa`, `POST /auth/password/reset/{token}`), per API replica | no | `auth.local.throttle.maxAttempts` |
| `BACKOFFICE_AUTH_LOCAL_THROTTLE_WINDOW` | `PT5M` | Sliding window of the throttle; above the limit `429` + `Retry-After` | no | `auth.local.throttle.window` |

Password policy, "MFA required for local accounts" and the lockout are platform settings
(**Administration → Platform settings**, `PUT /api/v1/admin/settings`: `passwordPolicy`, `mfaRequiredForLocal`,
`lockout`, `localLoginEnabled`); the variables above are only their defaults.

**Session storage**: sessions are server-side in PostgreSQL (Spring Session JDBC, tables
`SPRING_SESSION` and `SPRING_SESSION_ATTRIBUTES`, created by Flyway), so they survive API restarts, are
shared by all API replicas (no sticky sessions) and are deleted when a user is deactivated. Expired
sessions are purged by a scheduled job in the API. Cookie `BO_SESSION` (`HttpOnly`, `SameSite=Lax`),
CSRF cookie `XSRF-TOKEN` + header `X-XSRF-TOKEN`.

### Bundled Keycloak (Helm, evaluation installs)

| Helm value | Default | Description |
|---|---|---|
| `keycloak.enabled` | `false` | Deploy Keycloak 26.7 (codecentric/keycloakx subchart, official image) |
| `bundledKeycloak.url` | ingress URL, else `http://localhost:8180` | `KC_HOSTNAME`; issuer = `<url>/realms/<realm>` |
| `bundledKeycloak.realm` | `back-office` | Realm name |
| `bundledKeycloak.admin.username` / `password` | `admin` / generated | `KC_BOOTSTRAP_ADMIN_*` in Secret `<release>-keycloak-env` |
| `bundledKeycloak.database.schema` | `keycloak` | Keycloak's schema inside the Back Office database |
| `bundledKeycloak.realmImport.*` | `sslRequired: external`, no demo users | Realm import from `deploy/keycloak/realm-back-office.json` (first start only) |
| `bundledKeycloak.ingress.*` | disabled | Keycloak host (e.g. `sso.example.com`) |
| `api.oidcLoopback.*` | disabled | Dev clusters: sidecar making `http://localhost:8180` reach Keycloak from the API pod |

### Local Keycloak (compose)

| Variable (`deploy/compose/.env`) | Default | Description |
|---|---|---|
| `KEYCLOAK_PORT` | `8180` | Host port and port of `KC_HOSTNAME`; the issuer follows it |
| `KC_ADMIN` / `KC_ADMIN_PASSWORD` | `admin` / `admin` | Keycloak admin console user |

## Database

| Variable | Default | Description | Secret | Helm value |
|---|---|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/backoffice` | JDBC URL of the Hikari pool; only the `migrate` role runs Flyway, LangGraph4j creates its checkpoint tables | no | derived: the pooler when configured, else the primary (`<fullname>-postgresql`, `<cnpg>-rw`, `externalDatabase.host/port/database` + `jdbcParams`) |
| `SPRING_DATASOURCE_USERNAME` | `backoffice` | DB user | no | `postgresql.auth.username` / `externalDatabase.username` |
| `SPRING_DATASOURCE_PASSWORD` | `backoffice` | DB password | yes | `postgresql.auth.password` (Secret `<fullname>-postgresql`, key `password`) or `externalDatabase.password` / `externalDatabase.existingSecret` + `passwordKey` |
| `DB_POOL_SIZE` | `10` | Hikari maximum pool size (pipeline executor + LangGraph checkpoints share it) | no | `api.config.dbPoolSize` |
| `BACKOFFICE_DATASOURCE_DIRECT_URL` | = `SPRING_DATASOURCE_URL` | Direct connection to the primary for LISTEN/NOTIFY (one per pod, `backoffice.platform.events.direct-url`, same username/password as the pool); never a transaction pooler | no | derived: primary host (`externalDatabase.host`, `<cnpg>-rw`, `<fullname>-postgresql`) |

With a pooler (`cnpg.pooler.enabled`, `pgbouncer.enabled`, `externalDatabase.pooler.host`) the chart
points `SPRING_DATASOURCE_URL` at it and appends `prepareThreshold=0` (transaction pooling). Database modes
(`postgresql.mode`): `bundled` (Bitnami, dev), `cnpg` (CloudNativePG `Cluster` rendered by the chart,
password from Secret `<cluster>-app`), `external` (managed). Connection budget:
[runbooks/scaling.md](../runbooks/scaling.md#postgresql-connection-budget).

## Object storage and data retention

Large content (artefacts above 64 kB, diffs, runner logs, uploads) lives in a `BlobStore` (ADR 0023,
module `backend/blob-storage`); PostgreSQL keeps the metadata (`blob_key`, `size_bytes`, `sha256`) and
small content. Keys: `runs/<runId>/artifacts/<artifactId>` (edited versions get a `.<sha256 prefix>`
suffix, keys are never overwritten). Properties `backoffice.storage.*`; every property also binds from
the relaxed environment name (`backoffice.storage.s3.sse-kms-key-id` = `BACKOFFICE_STORAGE_S3_SSE_KMS_KEY_ID`).

Two sets of defaults apply. **App default** is what the API uses when the variable is not set at all
(`backend/app/src/main/resources/application.yml`, `StorageProperties` in `backend/blob-storage`): compose,
`bootRun`, a plain container. **Chart default** is what the `back-office` Helm chart renders from its
`storage.*` values (`deploy/helm/back-office/values.yaml`). The chart renders the `s3` variables only when
`storage.type=s3` and `BACKOFFICE_STORAGE_FILESYSTEM_ROOT` only when `storage.type=filesystem`; variables
without a Helm value are set through `api.extraEnv` (secrets through an `extraEnvFrom` Secret).

| Variable | App default | Chart default | Description | Secret | Helm value |
|---|---|---|---|---|---|
| `BACKOFFICE_STORAGE_TYPE` | `filesystem` | `s3` | `s3` for any S3-compatible store (AWS S3, MinIO, Ceph RGW, GCS XML interop, Cloudflare R2, Azure through an S3 gateway); `filesystem` stores files under a local directory - one replica only (local dev, tests, one-VM installs). Several replicas need `s3` | no | `storage.type` |
| `BACKOFFICE_STORAGE_S3_ENDPOINT` | _(blank = AWS regional endpoint)_ | _(blank; derived for `minio.enabled`)_ | Custom endpoint, e.g. `http://minio:9000`, `https://storage.googleapis.com`, `https://<account>.r2.cloudflarestorage.com` | no | `storage.s3.endpoint` |
| `BACKOFFICE_STORAGE_S3_PRESIGN_ENDPOINT` | _(blank = endpoint)_ | _(not rendered when empty)_ | Endpoint written into presigned URLs when clients (sandboxes, browsers) reach the store under another host name than the API (in-cluster MinIO behind an ingress). Sandboxes must be allowed to reach that host: add it to the runner chart's `egressProxy.alwaysAllowHosts` (`runner-controller.egress.always-allow`) | no | `storage.s3.presignEndpoint` |
| `BACKOFFICE_STORAGE_S3_REGION` | `us-east-1` | `us-east-1` | Region (MinIO and most S3-compatible stores accept any; R2 uses `auto`) | no | `storage.s3.region` |
| `BACKOFFICE_STORAGE_S3_BUCKET` | _(blank; required for `s3`)_ | `back-office` | Bucket; must exist unless `create-bucket` is on (the bundled MinIO creates it). Enable versioning on it for backups (doc 09) | no | `storage.s3.bucket` |
| `BACKOFFICE_STORAGE_S3_PATH_STYLE` | `false` | `false` (forced `true` with `minio.enabled`) | `true` for MinIO, Ceph, most self-hosted stores and gateways (`https://host/bucket/key`) | no | `storage.s3.pathStyle` |
| `BACKOFFICE_STORAGE_S3_ACCESS_KEY` / `BACKOFFICE_STORAGE_S3_SECRET_KEY` | _(blank = AWS default chain)_ | _(blank = ambient credentials)_ | Static credentials / GCS HMAC key / gateway key. Leave blank on AWS to use IRSA / EKS Pod Identity / instance roles (the SDK default chain) | yes | `storage.s3.accessKey/secretKey` → Secret `<fullname>-storage`, or `storage.s3.existingSecret`, or generated for `minio.enabled` |
| `BACKOFFICE_STORAGE_S3_SSE` | `NONE` | _(not rendered when `storage.s3.sse` is empty = `NONE`)_ | Server-side encryption on every write: `NONE`, `S3` (SSE-S3, AES256) or `KMS` (SSE-KMS) | no | `storage.s3.sse` |
| `BACKOFFICE_STORAGE_S3_SSE_KMS_KEY_ID` | _(blank = default KMS key)_ | - | KMS key id / ARN for `sse=KMS` | no | - (`api.extraEnv`) |
| `BACKOFFICE_STORAGE_S3_MULTIPART_THRESHOLD` / `..._PART_SIZE` | `16MB` / `8MB` | - | Streams above the threshold (or of unknown size) upload as multipart; parts ≥ 5 MB | no | - (`api.extraEnv`) |
| `BACKOFFICE_STORAGE_S3_CREATE_BUCKET` | `false` | - | Create the bucket at startup if missing (dev / MinIO; keep off on managed S3) | no | - (`api.extraEnv`) |
| `AWS_REQUEST_CHECKSUM_CALCULATION` / `AWS_RESPONSE_CHECKSUM_VALIDATION` | SDK default | SDK default | `when_required` for GCS and S3 gateways that reject CRC32 trailers | no | `storage.s3.checksumWhenRequired` |
| `BACKOFFICE_STORAGE_FILESYSTEM_ROOT` | `${java.io.tmpdir}/back-office/blobs` | `/var/lib/back-office/blobs` | Directory of the filesystem store. Use a persistent volume (the chart mounts a PVC there) | no | `storage.filesystem.path` |
| `BACKOFFICE_STORAGE_FILESYSTEM_BASE_URL` | _(= `BACKOFFICE_PUBLIC_URL`)_ | - | Base of presigned links, served by the API at `/blobs/<key>?method&expires&signature` (HMAC-SHA256, expiring) | no | - (`api.extraEnv`) |
| `BACKOFFICE_STORAGE_FILESYSTEM_SIGNING_KEY` | _(random per start)_ | - | HMAC key of the presigned links; set it so links survive restarts | yes | - (`extraEnvFrom` Secret) |
| `BACKOFFICE_STORAGE_ARTIFACTS_INLINE_MAX_BYTES` | `65536` | - | Artefacts above this size go to the BlobStore | no | - (`api.extraEnv`) |
| `BACKOFFICE_STORAGE_ARTIFACTS_CACHE_MAX_BYTES` | `67108864` | - | In-process cache of blob-backed artefact content per replica | no | - (`api.extraEnv`) |

Presigned URLs (runner uploads of large artefacts and logs) point at the presign endpoint (or the
endpoint), so that host must be reachable from sandboxes through the egress proxy: list it in the runner
chart's `egressProxy.alwaysAllowHosts`.

`/actuator/health` has a `blobStore` component (S3: `HeadBucket` with a 3 s timeout; filesystem: root
writable). Existing artefacts above 64 kB written before this version stay inline until the projects
retention job moves them (`migrate-inline-artifacts` per run, oldest first); reads work either way.

**Retention** (`backoffice.retention.*`, docs/architecture/09-scale-and-operations.md "Storage and data
lifecycle"). Jobs are plain `@Scheduled` methods guarded by a PostgreSQL advisory lock
(`projects-data-retention`, `identity-data-retention`), so exactly one replica runs each pass; the lock
names are the ShedLock names once ShedLock wraps them. The advisory lock needs a session-mode connection
(no PgBouncer transaction pooling on the application pool).

| Variable | Default | Description |
|---|---|---|
| `BACKOFFICE_RETENTION_ENABLED` | `true` | Projects retention job on this replica |
| `BACKOFFICE_RETENTION_INTERVAL` | `PT1H` | Pause between passes (first pass `BACKOFFICE_RETENTION_INITIAL_DELAY`, `PT5M`) |
| `BACKOFFICE_RETENTION_ARTIFACTS` | `P180D` | Blob content of artefacts of runs finished longer ago is deleted (`runs/<runId>/…`); the rows stay and read as "content removed by the data retention policy" |
| `BACKOFFICE_RETENTION_CHECKPOINTS` | `P30D` | LangGraph checkpoints (`lg4jcheckpoint`, `lg4jthread`, `lg4jthreadtag`) of runs finished longer ago; threads whose run no longer exists go after 1 day |
| `BACKOFFICE_RETENTION_RUNNER_JOB_EVENTS` | `P30D` | `runner_job_event` rows older than this |
| `BACKOFFICE_RETENTION_BATCH_SIZE` / `..._MAX_BATCHES` | `500` / `20` | Rows or runs per transaction, batches per step and pass |
| `BACKOFFICE_RETENTION_MIGRATE_INLINE_ARTIFACTS` | `200` | Legacy inline artefacts above the inline limit moved to the BlobStore per pass (`0` = off) |
| `BACKOFFICE_RETENTION_AUDIT_ENABLED` | `true` | Audit partition maintenance (every `PT6H`, `..._AUDIT_INTERVAL`) |
| `BACKOFFICE_RETENTION_AUDIT_MONTHS_AHEAD` | `3` | Monthly `audit_log_pYYYY_MM` partitions created ahead; rows outside land in `audit_log_default` and are moved into their month when it is created |
| `BACKOFFICE_RETENTION_AUDIT_RETENTION_MONTHS` | `24` | Months of audit history kept online |
| `BACKOFFICE_RETENTION_AUDIT_DROP_EXPIRED` | `false` | Detach and drop partitions older than the retention. Off by default: export them first (`pg_dump -t audit_log_p2024_01`) |

Deleting a project queues its runs' blob prefixes in `run_blob_gc` in the same transaction; the next
retention pass deletes the objects, so a rolled-back delete never loses content.

## LLM providers

A provider is active when its key (or base URL for Ollama/OpenAI-compatible) is set. Ollama counts as
configured only when `OLLAMA_BASE_URL` is set explicitly (non-blank): the built-in fallback
`http://localhost:11434` of `spring.ai.ollama.base-url` alone does not make it show as available. Only api/worker
pods read Secret `<fullname>-provider-keys`; sandboxed agents call the LLM gateway with a per-job token
and never see a provider key (ADR 0024).

| Variable | Default | Description | Secret | Helm value |
|---|---|---|---|---|
| `ANTHROPIC_API_KEY` | _(empty)_ | Anthropic (Spring AI + OpenCode + Claude Code) | yes | `secrets.providerKeys.anthropicApiKey` |
| `OPENAI_API_KEY` | _(empty)_ | OpenAI | yes | `secrets.providerKeys.openaiApiKey` |
| `GOOGLE_GENAI_API_KEY` | _(empty)_ | Google Gemini (the runner also exports it as `GOOGLE_GENERATIVE_AI_API_KEY` for OpenCode) | yes | `secrets.providerKeys.googleGenaiApiKey` |
| `MISTRAL_API_KEY` | _(empty)_ | Mistral | yes | `secrets.providerKeys.mistralApiKey` |
| `OPENAI_COMPATIBLE_BASE_URL` | _(empty)_ | Base URL of an OpenAI-compatible endpoint (OpenRouter, Groq, vLLM, LM Studio) | no (stored in the provider-keys Secret for convenience) | `secrets.providerKeys.openaiCompatibleBaseUrl` |
| `OPENAI_COMPATIBLE_API_KEY` | _(empty)_ | Key for that endpoint | yes | `secrets.providerKeys.openaiCompatibleApiKey` |
| `OLLAMA_BASE_URL` | _(empty = Ollama not configured; Spring AI falls back to `http://localhost:11434`)_ | Ollama server; setting it is what marks Ollama as configured. The runner appends `/v1` for OpenCode | no (same Secret) | `secrets.providerKeys.ollamaBaseUrl` |
| `LLM_TIMEOUT` | `PT10M` | Per-call timeout (ISO-8601 duration) | no | `api.config.llmTimeout` |
| `LLM_CONCURRENCY_PER_PROVIDER` | `4` | Parallel calls per provider for pipeline/LLM steps | no | `api.config.llmConcurrencyPerProvider` |
| `AI_LOG_PROMPTS` | `false` | Log prompt contents (Spring AI observations). Development only | no | `api.config.aiLogPrompts` |
| `AI_LOG_COMPLETIONS` | `false` | Log completions. Development only | no | `api.config.aiLogCompletions` |

## Roles (ADR 0023)

One image, the role is chosen per process. The chart sets it per Deployment; do not set it yourself in
Kubernetes.

| Variable | Default | Description | Secret | Helm value |
|---|---|---|---|---|
| `BACKOFFICE_ROLE` | `api,worker` | Comma list: `api` (HTTP, SSE, auth, CI and runner v2 endpoints, LLM gateway, MCP), `worker` (work queue, schedulers, outbox relay), `migrate` (Flyway, then exit). `api,worker` for single-process installs | no | derived: `api` / `worker` in `mode: split`, `api,worker` in `mode: combined`, `migrate` in the hook Job and init container |
| `SPRING_FLYWAY_ENABLED` | `false` | Only the `migrate` role migrates; it forces Flyway on regardless of this variable | no | derived: `false` on app pods, `true` for `migrate` |
| `POD_NAME` / `POD_NAMESPACE` | - | Downward API; lease owner id and telemetry resource attributes | no | derived |

## Agent runner (execution plane, ADR 0024)

The control plane no longer creates pods: runner-controllers of the `back-office-runner` chart pull jobs
over `/api/v1/runner/v2/**`. The variables of the push model (`RUNNER_IMAGE`, `RUNNER_CALLBACK_URL`,
`RUNNER_SERVICE_ACCOUNT`, `RUNNER_PROVIDER_KEYS_SECRET`, `RUNNER_INTEGRATIONS_SECRET`, `RUNNER_CPU_*`,
`RUNNER_MEMORY_*`, `RUNNER_TTL_AFTER_FINISHED`) are gone from the chart; the chart's `runner.image`,
`runner.resources`, `runner.serviceAccount`, `runner.networkPolicy` and `api.rbac` values were removed.

| Variable (control plane) | Default | Description | Secret | Helm value |
|---|---|---|---|---|
| `RUNNER_MODE` | `FLEET` | Shown on the integrations page only; runner-controllers always pull jobs (the embedded `DOCKER` mode of a host-run API is gone) | no | `runner.mode` (`FLEET`) |
| `RUNNER_DEFAULT_TIMEOUT` | `PT60M` | Timeout when a step/attempt has none | no | `runner.defaultTimeout` |
| `RUNNER_TOKEN_SECRET` | _(required)_ | HMAC secret of per-job tokens (`bo_job_`: LLM gateway, events, results) | yes | `secrets.runnerTokenSecret` (generated) |

**runner-controller** (chart `deploy/helm/back-office-runner`, compose service `runner-controller`):

| Variable | Default | Description | Secret | Helm value (runner chart) |
|---|---|---|---|---|
| `RUNNER_BACKOFFICE_URL` | _(required)_ | Public URL of Back Office (the API lives under `/api/v1`) | no | `backOffice.url` |
| `RUNNER_REGISTRATION_TOKEN` | _(first start)_ | Single-use `bo_rrt_` token; exchanged for the runner token. It also decides the pool (the controller has no pool setting) | yes | `registration.token` → Secret `<fullname>-registration`, or `registration.existingSecret` (key `RUNNER_REGISTRATION_TOKEN`) |
| `RUNNER_TOKEN_SECRET_NAME` | `back-office-runner-credentials` | Secret the controller writes the runner token into (keys `runnerToken`, `runnerId`, `poolId`) and reads it from, in `RUNNER_TOKEN_SECRET_NAMESPACE` (by default the controller's own namespace, never the sandbox namespace) | no | `tokenSecret.name` (`<fullname>-token`) |
| `RUNNER_TOKEN_SECRET_NAMESPACE` | _(empty = the controller's own namespace)_ | Namespace of that Secret (`runner-controller.back-office.credential-secret-namespace`). Keep it empty so agents never share a namespace with the runner token and KEDA (release namespace) can read it | no | - (not set by the chart; the token Secret lives in the release namespace) |
| `RUNNER_TOKEN_FILE` | `./runner-credentials.json` | DOCKER backend: file instead of a Secret | no | - (compose) |
| `RUNNER_BACKEND` | `AUTO` | `AGENT_SANDBOX`, `K8S_JOB`, `DOCKER` (laptops; the chart rejects it), `AUTO` | no | `backend` |
| `RUNNER_RUNTIME_CLASS_STANDARD` | `gvisor` | RuntimeClass for `isolation: STANDARD` (empty = none) | no | `runtimeClasses.standard` |
| `RUNNER_RUNTIME_CLASS_HIGH` | `kata` | RuntimeClass for `isolation: HIGH` (empty = none) | no | `runtimeClasses.high` |
| `RUNNER_NAMESPACE` | _(empty = the controller's namespace)_ | Namespace of sandbox pods and the egress proxy `bo-egress-<pool>` (not of the runner-token Secret, see `RUNNER_TOKEN_SECRET_NAMESPACE`) | no | `sandboxNamespace.*` (`<release-ns>-sandboxes`) |
| `RUNNER_MAX_CONCURRENT` | `4` | Sandboxes per controller replica | no | `maxConcurrent` (`5`) |
| `RUNNER_WARM_POOL_SIZE` | _(empty = the pool's value)_ | `SandboxWarmPool` replicas per template | no | `warmPool.size` |
| `RUNNER_DEFAULT_IMAGE` | - | Sandbox image when neither the job policy nor the pool ceiling names one | no | - (`extraEnv`) |
| `RUNNER_EGRESS_PROXY_IMAGE` | `back-office/egress-proxy:dev` | Image of the Smokescreen proxy (built from `backend/runner-controller/egress-proxy/`) | no | `egressProxy.image` |
| `RUNNER_EGRESS_PROXY_PORT` | `4750` | Proxy port (sandboxes may reach only this) | no | `egressProxy.port` |
| `RUNNER_CONTROLLER_URL` | `http://back-office-runner-controller:8090` | The controller as seen by the egress proxy pods (allowlist pull, decisions) | no | derived: `http://<fullname>-controller.<release-ns>.svc:8090` |
| `PORT` | `8090` | HTTP port of the controller (probes, metrics, `/egress/v1/*`) | no | `controller.port` |
| `BO_GIT_TOKEN_READ` | _(empty)_ | DOCKER backend only (ADR 0031): git token written into the sandboxes of READ jobs (reviews, spec analysis, benchmarks, `push=false`; `runner-controller.git.token-read-env`). Project or group access token, role Reporter, scope `read_repository` | yes | - (compose: `GIT_TOKEN_READ` in `.env`; Kubernetes uses `gitCredentials.repoSecrets[].read`) |
| `BO_GIT_TOKEN_PUSH` | _(empty)_ | DOCKER backend only: git token written into the sandboxes of PUSH_MR jobs (Implement / Spec Kit with `push`; `runner-controller.git.token-push-env`). Role Developer, scope `write_repository` only; cannot merge into a protected default branch | yes | - (compose: `GIT_TOKEN_PUSH`; Kubernetes: `gitCredentials.repoSecrets[].push`) |
| `BO_GIT_TOKEN` | _(empty)_ | DOCKER backend only, legacy: one git token for every repository host (`runner-controller.git.token-env`), treated as a **push** token; READ jobs that only have it fail unless `RUNNER_GIT_ALLOW_PUSH_CREDENTIAL_FOR_READ=true` | yes | - (compose: `GIT_TOKEN`; Kubernetes: `gitCredentials.existingSecret` / `hostSecrets`) |
| `RUNNER_GIT_ALLOW_PUSH_CREDENTIAL_FOR_READ` | `false` | Hand a push token to READ jobs that have no read token (not recommended; ADR 0031) | no | `gitCredentials.allowPushCredentialForRead` (compose: `GIT_ALLOW_PUSH_CREDENTIAL_FOR_READ`) |
| `RUNNER_GIT_USER_NAME` / `RUNNER_GIT_USER_EMAIL` | _(empty = `Back Office Agent` / `agent@backoffice.local`)_ | Commit identity of the agents, passed to every sandbox as `GIT_USER_NAME` / `GIT_USER_EMAIL` (every runner commit also carries the `Back-Office-Run: <runId>` trailer) | no | `gitCredentials.userName` / `userEmail` (compose: `GIT_USER_NAME` / `GIT_USER_EMAIL` in `.env`) |
| `SPRING_APPLICATION_JSON` | - | Maps and lists without a single variable: `runner-controller.kubernetes.cache-pvcs`, `.git.repo-secrets` (`{repository, read, push}`: read Secret for READ jobs, push Secret for PUSH_MR jobs, key `token`, ADR 0031), `.git.secrets` (legacy push-level fallback: repository host or `*` → Secret), `.egress.allow-ranges` | no | derived from `caches.*`, `gitCredentials.*`, `egressProxy.allowedCidrs` |

The older names `BO_URL`, `BO_REGISTRATION_TOKEN`, `BO_RUNNER_BACKEND`, `BO_RUNNER_MAX_CONCURRENT` and
`BO_RUNNER_DEFAULT_IMAGE` are still accepted as fallbacks. Allowed sandbox images are part of the pool
configuration in Back Office. Labels set by the controller (NetworkPolicy selectors of the chart): sandbox
pods `backoffice.io/sandbox=true` and `backoffice.io/pool=<pool>`, egress proxy pods
`app.kubernetes.io/name=back-office-egress-proxy`; the controller pods must carry
`app.kubernetes.io/name=back-office-runner-controller`.

Variables set **inside a sandbox** by the controller (never configure them): the job token
(`ANTHROPIC_AUTH_TOKEN` / OpenCode provider key), `ANTHROPIC_BASE_URL` and the OpenAI-compatible base
URL of the LLM gateway, `HTTPS_PROXY`/`HTTP_PROXY`, the rendered job spec. Provider keys never reach a
sandbox.

## Object storage (BlobStore, ADR 0023)

All `BACKOFFICE_STORAGE_*` variables, with the app defaults and the Helm chart defaults side by side, are in
[Object storage and data retention](#object-storage-and-data-retention) above.

## Signing keys (ADR 0025)

Ed25519 keys for in-toto/DSSE attestations and signed runner assignments; public keys are published at
`/.well-known/back-office/keys`. Indexed `0..n` so old keys remain for verification.

| Variable | Description | Secret | Helm value |
|---|---|---|---|
| `BACKOFFICE_SIGNING_KEYS_<n>_ID` | Key id (`kid`), e.g. `bo-20260928` | yes (same Secret) | Secret `<fullname>-signing` generated once by a hook Job (`signing.generate`), or `signing.existingSecret` created with `deploy/scripts/gen-signing-key.sh` |
| `BACKOFFICE_SIGNING_KEYS_<n>_PRIVATE_KEY_PKCS8_BASE64` | Base64 of the PKCS#8 DER private key | yes | same |
| `BACKOFFICE_SIGNING_KEYS_<n>_PUBLIC_KEY_X509_BASE64` | Base64 of the X.509 SubjectPublicKeyInfo DER | yes | same |
| `BACKOFFICE_SIGNING_KEYS_<n>_ACTIVE` | `true` for the one key that signs; others only verify | yes | same |
| `BACKOFFICE_SIGNING_ALLOW_EPHEMERAL` | Without any key (entries with neither public nor private key are ignored): `true` (default, local/compose) generates a per-start key with a warning, `false` refuses to start | no | fixed `false` |

Rotation: [runbooks/upgrade.md](../runbooks/upgrade.md#signing-key-rotation-adr-0025).

## Connection secrets (ADR 0030)

Tokens of **connections** (Jira credentials added in **Administration → Connections** and on a project's
**Connections** tab, [manual](../manual/integrations-jira.md#jira-connections)) are stored in the database
with envelope encryption: a random data key per secret encrypts the token (AES-256-GCM, the connection id
as associated data), and the data key is wrapped with the key-encryption key (KEK) configured here.
Properties `backoffice.secrets.*`.

| Variable | Default | Description | Secret | Helm value |
|---|---|---|---|---|
| `BACKOFFICE_SECRETS_KEY` | _(empty; `local` profile: a fixed dev key)_ | Active KEK, base64 of 32 bytes (`backoffice.secrets.key`). Required as soon as a connection exists | yes | `secrets.connectionsKey` → `<fullname>-app` (generated on install and kept across upgrades when empty and `secrets.create=true`); with `secrets.existingSecret` add key `BACKOFFICE_SECRETS_KEY` to your Secret |
| `BACKOFFICE_SECRETS_KEY_ID` | `k1` | Id of the active KEK, stored with every secret it wraps. Change it whenever the key changes | no | `secrets.connectionsKeyId` → ConfigMap |
| `BACKOFFICE_SECRETS_PREVIOUS_KEYS` | _(empty)_ | Old KEKs still needed to unwrap, comma separated `id:base64` (`k1:AbC...=,k2:XyZ...=`); only during a rotation | yes | `secrets.connectionsPreviousKeys` → Secret key `BACKOFFICE_SECRETS_PREVIOUS_KEYS` (or put it into your `existingSecret`) |
| `BACKOFFICE_SECRETS_ALLOW_UNREADABLE` | `false` | Recovery switch after a lost key: start although stored secrets cannot be opened (warning instead of a failed start). Affected connections fail **Test** with "secret unavailable" until **Replace token**. Remove it afterwards | no | `api.extraEnv` / `worker.extraEnv` (temporary) |

**Generate a key** (32 random bytes, base64):

```bash
openssl rand -base64 32
```

```powershell
# PowerShell 7 (.NET Core)
$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Fill($b); [Convert]::ToBase64String($b)
# Windows PowerShell 5.1
$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```

**Startup behaviour** (api and worker roles):

| Situation | Result |
|---|---|
| no key, no connections | starts; **Add connection** answers `409` "BACKOFFICE_SECRETS_KEY is not configured" |
| no key, connections exist | refuses to start with a message naming `BACKOFFICE_SECRETS_KEY` |
| a stored secret uses a key id that is neither `BACKOFFICE_SECRETS_KEY_ID` nor in `BACKOFFICE_SECRETS_PREVIOUS_KEYS` | refuses to start, naming the unknown key id |

**Rotate the key** (yearly, or at once if it may have leaked):

1. Generate a new key.
2. Set it as `BACKOFFICE_SECRETS_KEY` with a new `BACKOFFICE_SECRETS_KEY_ID` (for example `k2`), and put the
   old one into `BACKOFFICE_SECRETS_PREVIOUS_KEYS` as `k1:<old base64>`. Helm: `secrets.connectionsKey`,
   `secrets.connectionsKeyId`, `secrets.connectionsPreviousKeys` (or the same keys in your Secret).
3. Roll out. On start the worker re-wraps every data key with the new KEK; only the small data keys are
   re-encrypted, the tokens are never decrypted into the database. Each re-wrap is audited
   `CONNECTION_SECRET_REWRAP`.
4. After the audit log shows the re-wrap (or **Test** works on every connection), remove the old key from
   `BACKOFFICE_SECRETS_PREVIOUS_KEYS` and roll out again.

Rotating the KEK does not rotate the Jira tokens themselves; those are replaced per connection with
**Replace token**.

**Back up the key** together with the database backups, but stored separately (for example in your password
manager or secret store). A database backup without the key cannot reveal tokens; the key without the
database is useless. **Losing the key means every connection secret must be entered again**: there is no
way to recover the tokens, so new Jira tokens have to be created and entered with **Replace token**.

**Recover from a lost key**: set a new `BACKOFFICE_SECRETS_KEY` with a new `BACKOFFICE_SECRETS_KEY_ID` and start
once with `BACKOFFICE_SECRETS_ALLOW_UNREADABLE=true` (otherwise the start fails because the stored key id is
unknown). Replace the token of every connection (**Replace token** seals it with the new key; **Test** shows
"secret unavailable" until then), then remove the switch and roll out again.

## Integrations

There is no SCM configuration (ADR 0013): Back Office never calls GitLab or any other SCM API.
`GITLAB_URL`, `GITLAB_TOKEN` and `GITLAB_WEBHOOK_SECRET` from iteration 1 are gone; sandboxes reach
repositories over git with tokens that exist only on the runner side (runner chart `gitCredentials`,
compose `BO_GIT_TOKEN_READ` / `BO_GIT_TOKEN_PUSH`, see the runner-controller table above; the control plane
has no git variable and sends each job only its access level `NONE | READ | PUSH_MR`, ADR 0031),
merge requests are opened with git push options and the project's CI calls Back Office with project CI
tokens (see [ci-integration.md](ci-integration.md)).

Jira: Jira Cloud only (REST API v3, basic auth with a classic API token; scoped API tokens, which need the
`api.atlassian.com` gateway URL, and Jira Data Center personal access tokens are not supported). Jira
credentials are **connections** managed in the UI (ADR 0030, [Connection secrets](#connection-secrets-adr-0030),
[manual](../manual/integrations-jira.md#jira-connections)), each with only the capabilities it needs (read,
comment, transition) and backed by a Jira account with exactly those project permissions.

The `JIRA_URL` / `JIRA_EMAIL` / `JIRA_API_TOKEN` variables are **legacy**: when all three are set they appear
as a read-only platform connection "Jira (environment variables)" with every capability, always chosen
last, and the UI asks to migrate. Remove them once scoped connections exist
([migration](../manual/integrations-jira.md#jira-config)).

| Variable | Default | Description | Secret | Helm value |
|---|---|---|---|---|
| `JIRA_URL` | _(empty)_ | **Legacy.** Jira Cloud base URL, e.g. `https://acme.atlassian.net` | no | `integrations.jira.url` |
| `JIRA_EMAIL` | _(empty)_ | **Legacy.** User for basic auth | no | `integrations.jira.email` |
| `JIRA_API_TOKEN` | _(empty)_ | **Legacy.** Jira API token; replaced by connections | yes | `secrets.integrations.jiraApiToken` |
| `JIRA_ACCEPTANCE_CRITERIA_FIELD` | _(empty)_ | Custom field id (e.g. `customfield_10042`) exposed as `issue.acceptanceCriteria`; global, applies to every connection (not legacy) | no | `integrations.jira.acceptanceCriteriaField` |
| `JIRA_API_GATEWAY_URL` | `https://api.atlassian.com` | Atlassian API gateway that scoped API tokens are sent to (`<gateway>/ex/jira/<cloudId>`, ADR 0035); change only for tests or an egress proxy | no | _(env only)_ |

Jira schedulers (worker role; each runs on one replica at a time):

| Property (environment variable) | Default | Description | Secret | Helm value |
|---|---|---|---|---|
| `backoffice.jira-intake.scan-delay` (`BACKOFFICE_JIRAINTAKE_SCANDELAY`) | `30000` (ms) | How often the Jira intake watcher scheduler (ShedLock `jira-intake-scan`) looks for projects whose watcher is due and enqueues a `JIRA_INTAKE_POLL` work item for each (ADR 0033). The poll interval itself is set per project in the UI (1–1440 minutes); this only bounds how late a due poll can start | no | - (`worker.extraEnv`) |
| `backoffice.connections.expiry-reminder-cron` (`BACKOFFICE_CONNECTIONS_EXPIRYREMINDERCRON`) | `0 0 7 * * *` (Spring cron, zone UTC) | When the daily check for connection tokens expiring within 14 days runs (cluster lock `connection-expiry-reminder`); publishes `ConnectionExpiring` once per expiry date (ADR 0030 amendment) | no | - (`worker.extraEnv`) |

Project CI tokens are not environment variables: they are created per project in the UI, shown once
and stored as SHA-256 hashes in the database. The CI job receives one as `BACKOFFICE_CI_TOKEN`.

## Observability

| Variable | Default | Description | Secret | Helm value |
|---|---|---|---|---|
| `TRACING_SAMPLING` | `1.0` | Trace sampling probability | no | `api.config.tracingSampling` |
| `OTEL_TRACES_ENABLED` | `false` | Export traces over OTLP/HTTP | no | derived: `true` when `langfuse.enabled` or `otel.enabled` |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://localhost:4318/v1/traces` | OTLP traces endpoint | no | derived: `http://<release>-langfuse-web:3000/api/public/otel/v1/traces` or `otel.tracesEndpoint` |
| `OTEL_EXPORTER_OTLP_HEADERS_AUTHORIZATION` | _(empty)_ | `Authorization` header for the exporter; Langfuse needs `Basic base64(pk:sk)` | yes | `langfuseAuth.publicKey`+`secretKey` (rendered) or `langfuseAuth.existingSecret`; `otel.headersAuthorization` |
| `LANGFUSE_ENABLED` | `false` | Show trace links and Langfuse cost views in the UI | no | derived from `langfuse.enabled` |
| `LANGFUSE_URL` | _(empty)_ | Langfuse base URL for links | no | `langfuseAuth.url` (default in-cluster service) |
| `OTEL_SERVICE_NAME` / `OTEL_RESOURCE_ATTRIBUTES` | - | `back-office-<component>`; namespace, pod, `backoffice.role`, `deployment.environment` | no | derived when `otel.enabled` or `langfuse.enabled` (`otel.environment`) |
| `OTEL_METRICS_ENABLED` / `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT` | `false` | OTLP metrics (alternative to scraping `/actuator/prometheus`) | no | `otel.metricsEnabled` / `otel.metricsEndpoint` |
| `OTEL_LOGS_ENABLED` / `OTEL_EXPORTER_OTLP_LOGS_ENDPOINT` | `false` | OTLP logs | no | `otel.logsEnabled` / `otel.logsEndpoint` |
| `MANAGEMENT_METRICS_DISTRIBUTION_PERCENTILESHISTOGRAM_HTTP_SERVER_REQUESTS` | `false` | Latency histograms for the p95 alert and dashboards | no | `api.config.httpHistogram` (`true`) |

Prometheus objects: `metrics.serviceMonitor`, `metrics.prometheusRule` (alerts: API 5xx ratio, p95
latency, oldest ready work item > 5 min, DEAD items, pool with queued jobs and no runner, budget
exceeded/near, LISTEN reconnects, Hikari pending) and `metrics.dashboards` (ConfigMaps of
`deploy/observability/dashboards/*.json`). Collector example: `deploy/observability/otel-collector.yaml`.

## Pipelines and evaluation

| Variable | Default | Description | Secret | Helm value |
|---|---|---|---|---|
| `PIPELINE_EXECUTOR_THREADS` | `16` | Virtual-thread executor size for graph execution | no | `api.config.pipelineExecutorThreads` |
| `PIPELINE_RECOVER_ON_STARTUP` | `true` | Resume `RUNNING` runs from checkpoints after a restart | no | `api.config.pipelineRecoverOnStartup` |
| `EVAL_CONCURRENCY_PER_PROVIDER` | `2` | Parallel benchmark attempts per provider | no | `api.config.evalConcurrencyPerProvider` |
| `EVAL_SEED_STARTER_CONTENT` | `true` | Seed model profiles with pricing and sample task suites on first start | no | `api.config.evalSeedStarterContent` |

## Helm secrets layout

The chart never mixes secret and non-secret configuration. api and worker pods read all of these
through `envFrom`; the migrate role gets only the database password.

```
<fullname>-app                (secrets.create / secrets.existingSecret)          -> api, worker
  RUNNER_TOKEN_SECRET, BACKOFFICE_AUTH_LOCAL_SECRET_KEY (generated),
  BACKOFFICE_SECRETS_KEY (generated), BACKOFFICE_SECRETS_PREVIOUS_KEYS (only during a key rotation),
  BACKOFFICE_AUTH_LOCAL_BOOTSTRAP_ADMIN_PASSWORD (only when set),
  BACKOFFICE_AUTH_OIDC_CLIENT_SECRET (unless auth.oidc.existingSecret or no issuer),
  SPRING_DATASOURCE_PASSWORD (external DB without existingSecret),
  OTEL_EXPORTER_OTLP_HEADERS_AUTHORIZATION (Langfuse / OTLP auth)

<fullname>-provider-keys      (secrets.providerKeys.*)   -> api, worker (never sandboxes)
  ANTHROPIC_API_KEY, OPENAI_API_KEY, GOOGLE_GENAI_API_KEY, MISTRAL_API_KEY,
  OPENAI_COMPATIBLE_API_KEY, OPENAI_COMPATIBLE_BASE_URL, OLLAMA_BASE_URL

<fullname>-integrations       (secrets.integrations.*)   -> api, worker
  JIRA_API_TOKEN (legacy, ADR 0030; GIT_* only for the embedded DOCKER runner of a laptop)

<fullname>-storage            (storage.s3.* / minio.enabled)  -> api, worker, bundled MinIO
  BACKOFFICE_STORAGE_S3_ACCESS_KEY, BACKOFFICE_STORAGE_S3_SECRET_KEY   (absent with IRSA / Workload Identity)

<fullname>-signing            (hook Job, or signing.existingSecret)  -> api, worker
  BACKOFFICE_SIGNING_KEYS_<n>_ID / _PRIVATE_KEY_PKCS8_BASE64 / _PUBLIC_KEY_X509_BASE64 / _ACTIVE

<fullname>-migrate            (hook, external DB with inline password)  -> migrate Job only, deleted after success
<fullname>-postgresql         (Bitnami subchart)          -> key `password`
<cnpg-cluster>-app            (CloudNativePG operator)    -> key `password`
<fullname>-db                 (External Secrets, external DB)  -> key `password`
auth.oidc.existingSecret      (you create it)             -> key `client-secret`

<release>-keycloak-env        (keycloak.enabled)          -> Keycloak pod: KC_HOSTNAME, KC_BOOTSTRAP_ADMIN_*, KC_DB_*
<release>-keycloak-realm      (keycloak.enabled)          -> realm import file (contains the client secret)

runner chart: <fullname>-registration (RUNNER_REGISTRATION_TOKEN), <fullname>-token (key `runnerToken`,
written by the controller into its own release namespace, never the sandbox namespace), gitCredentials.repoSecrets[].read / .push
and the legacy gitCredentials.existingSecret / hostSecrets (key `token`, sandbox namespace)
```

For production create the Secrets yourself (see [deploy-helm.md](deploy-helm.md)) and set
`secrets.create=false`, `secrets.providerKeys.create=false`, `secrets.integrations.create=false` with
the corresponding `existingSecret` names - or `externalSecrets.enabled=true`: the chart then renders
`ExternalSecret`s (external-secrets.io/v1, `dataFrom.extract` of one remote secret per target:
`externalSecrets.app/providerKeys/integrations/database/storage/signing.remoteKey`) instead of its own
Secrets. Key names inside your Secrets must match the variable names above exactly.

## Names awaiting the backend implementation

The chart and compose use these names from the iteration-5 design; the backend branches implement them.
If a name changes there, change it here, in `deploy/helm/**/templates` and in `deploy/compose`:
`BACKOFFICE_ROLE`, `SPRING_FLYWAY_ENABLED`, `BACKOFFICE_DATASOURCE_DIRECT_URL`, `RUNNER_MODE=FLEET`,
`BACKOFFICE_STORAGE_TYPE`, `BACKOFFICE_STORAGE_S3_ENDPOINT|REGION|BUCKET|PATH_STYLE|ACCESS_KEY|SECRET_KEY|SSE`,
`BACKOFFICE_STORAGE_FILESYSTEM_ROOT`, `BACKOFFICE_SIGNING_KEYS_<n>_ID|PRIVATE_KEY_PKCS8_BASE64|PUBLIC_KEY_X509_BASE64|ACTIVE`,
`BACKOFFICE_SIGNING_ALLOW_EPHEMERAL`, runner-controller `RUNNER_BACKOFFICE_URL`, `RUNNER_REGISTRATION_TOKEN`,
`RUNNER_TOKEN_SECRET_NAME`, `RUNNER_TOKEN_SECRET_NAMESPACE`, `RUNNER_TOKEN_FILE`, `RUNNER_BACKEND`,
`RUNNER_RUNTIME_CLASS_STANDARD`, `RUNNER_RUNTIME_CLASS_HIGH`, `RUNNER_NAMESPACE`, `RUNNER_MAX_CONCURRENT`,
`RUNNER_WARM_POOL_SIZE`, `RUNNER_EGRESS_PROXY_IMAGE`, `RUNNER_EGRESS_PROXY_PORT`, `RUNNER_CONTROLLER_URL`,
`PORT`, `SPRING_APPLICATION_JSON` (runner-controller maps); metrics `backoffice_work_items`,
`backoffice_work_oldest_ready_seconds`, `backoffice_work_latency_seconds`, `backoffice_runs_active`,
`backoffice_gate_wait_seconds`, `backoffice_runner_jobs`, `backoffice_runner_jobs_completed_total`,
`backoffice_sandbox_start_seconds`, `backoffice_llm_tokens_total`, `backoffice_llm_cost_usd_total`,
`backoffice_budget_utilisation`, `backoffice_sse_subscribers`, `backoffice_events_listen_reconnects_total`;
the runner-controller's actuator probes on `:8090`.

## Where to look when it fails

- **`helm install` fails with `auth.oidc.issuerUri is required` / `auth.oidc.clientSecret or
  auth.oidc.existingSecret is required`** - configure the OIDC client (or `keycloak.enabled=true`).
- **`helm upgrade` fails with `secrets.apiKey / secrets.ciApiKey were removed`** - delete those keys from
  your values file ([deploy-helm.md](deploy-helm.md#upgrading-from-iteration-2)).
- **Sign-in fails with `Unable to resolve Configuration with the provided Issuer`** (the API itself starts;
  discovery runs at the first sign-in and is retried at the next one) - the API pod cannot
  reach `BACKOFFICE_AUTH_OIDC_ISSUER_URI` (DNS, egress, TLS trust) or the discovery document announces a
  different `issuer` (trailing slash, http vs https, internal vs external host name).
- **Login loops or `401` right after login** - the `BO_SESSION` cookie is dropped: `BACKOFFICE_AUTH_COOKIE_SECURE=true`
  on an http URL, or `/login/oauth2`, `/oauth2` are not routed to the API.
- **Provider "not configured" in Settings** - the key is missing from `<fullname>-provider-keys`;
  `kubectl describe secret` shows key names (never values). Remember `envFrom` is read at pod start:
  restart the API after editing a Secret out of band (the chart adds checksum annotations only for
  inline values).
- **Pods stuck in `CreateContainerConfigError`** - a referenced Secret is missing: `<fullname>-signing`
  (hook Job failed or `signing.existingSecret` wrong), `<fullname>-storage`, or an ExternalSecret has not
  synced yet (`kubectl get externalsecrets`).
- **Traces do not appear in Langfuse** - check `OTEL_TRACES_ENABLED=true`, the endpoint host
  (`<release>-langfuse-web`) and that the `Basic` header decodes to `pk-lf-...:sk-lf-...`.
- Effective configuration of a running pod: `kubectl exec deploy/<fullname>-api -- env | sort | grep -v KEY`.
