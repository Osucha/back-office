# back-office Helm chart

Deploys the Back Office control plane (ADR 0023): the web UI (Angular behind nginx) and one backend
image in the roles `api`, `worker` and `migrate` (or `api,worker` in `mode: combined`), plus optionally
PostgreSQL (Bitnami subchart or a CloudNativePG `Cluster`), MinIO, PgBouncer, Keycloak (codecentric/keycloakx
subchart, alias `keycloak`), Langfuse (langfuse-k8s subchart), ExternalSecrets, NetworkPolicies, KEDA
objects, ServiceMonitor, PrometheusRule and Grafana dashboards. The agent execution plane is the separate
chart [`back-office-runner`](../back-office-runner/README.md); this chart has no RBAC for pods or Jobs.
Reference values per cloud: `values-aws.yaml`, `values-gcp.yaml`, `values-azure.yaml`, `values-onprem.yaml`
([cloud-reference.md](../../../docs/guides/cloud-reference.md)). Users log in
through OIDC (ADR 0016); there is no API key. See [docs/guides/deploy-helm.md](../../../docs/guides/deploy-helm.md) for
the step-by-step guide and [docs/guides/configuration.md](../../../docs/guides/configuration.md) for
the mapping between values and environment variables.

```bash
helm repo add langfuse https://langfuse.github.io/langfuse-k8s
helm repo add codecentric https://codecentric.github.io/helm-charts
helm dependency build deploy/helm/back-office
helm install back-office deploy/helm/back-office -n back-office --create-namespace \
  -f deploy/helm/back-office/values-dev.yaml
```

## Objects rendered

| Object | Name | Condition |
|---|---|---|
| Deployment / Service | `<fullname>-api` (role `api`, or `api,worker` in combined mode) | always |
| Deployment / Service | `<fullname>-worker` (role `worker`) | `mode: split` |
| ConfigMap | `<fullname>-config` (shared by all roles) | always |
| Job (hook) | `<fullname>-migrate` (role `migrate`), `pre-install,pre-upgrade` (`pre-upgrade` only when the DB is in the release) | `migration.enabled` |
| init containers `wait-for-db`, `migrate` in app pods | - | `migration.initContainer` (auto: bundled/CNPG DB or External Secrets) |
| ServiceAccount | `<fullname>-api` (no token mounted, no RBAC) | `api.serviceAccount.create` |
| HorizontalPodAutoscaler | `<fullname>-api` | `api.autoscaling.enabled` |
| ScaledObject + TriggerAuthentication (KEDA PostgreSQL) / HPA | `<fullname>-worker` | `worker.autoscaling.type=keda` / `hpa` |
| PodDisruptionBudget | `<fullname>-api`, `<fullname>-worker` | `*.podDisruptionBudget.enabled` |
| Secret (app, provider keys, integrations) | `<fullname>-app`, `-provider-keys`, `-integrations` | `secrets.*.create` and not `externalSecrets.enabled` |
| Secret (storage) | `<fullname>-storage` | `minio.enabled` or `storage.s3.accessKey` |
| Secret (signing, created once by a hook Job + short-lived Role) | `<fullname>-signing` | `signing.generate` without `signing.existingSecret` |
| ExternalSecret | same names as the Secrets above, plus `<fullname>-db` | `externalSecrets.enabled` |
| StatefulSet / Service / bucket Job | `<fullname>-minio` | `minio.enabled` |
| PVC | `<fullname>-blobs` | `storage.type=filesystem` |
| Cluster, Pooler, ObjectStore, ScheduledBackup (CloudNativePG) | `<fullname>-pg` | `postgresql.mode=cnpg` |
| Deployment / Service | `<fullname>-pgbouncer` | `pgbouncer.enabled` (external DB) |
| NetworkPolicy | api, worker, web, migrate, minio, pgbouncer | `networkPolicy.enabled` |
| ServiceMonitor / PrometheusRule | `<fullname>` | `metrics.serviceMonitor.enabled` / `metrics.prometheusRule.enabled` |
| ConfigMaps (Grafana dashboards) | `<fullname>-dashboard-*` | `metrics.dashboards.enabled` |
| Deployment / Service | `<fullname>-web` | `web.enabled` |
| Ingress | `<fullname>` | `ingress.enabled` |
| PostgreSQL (subchart) | `<fullname>-postgresql` | `postgresql.enabled` |
| Keycloak (subchart) | `<release>-keycloak` StatefulSet, `<release>-keycloak-http` Service | `keycloak.enabled` |
| Secrets (Keycloak env, realm import) | `<release>-keycloak-env`, `<release>-keycloak-realm` | `keycloak.enabled` |
| Ingress (Keycloak) | `<release>-keycloak` | `keycloak.enabled` and `bundledKeycloak.ingress.enabled` |
| Langfuse (subchart) | `<release>-langfuse-web`, `<release>-langfuse-postgresql`, ... | `langfuse.enabled` |

`<fullname>` is the release name when it contains `back-office`, otherwise `<release>-back-office`
(override with `fullnameOverride`).

## Values

### Global

| Key | Type | Default | Description |
|---|---|---|---|
| `nameOverride` | string | `""` | Override the chart name part of resource names |
| `fullnameOverride` | string | `""` | Override the full resource name prefix |
| `imagePullSecrets` | list | `[]` | Pull secrets for every pod of the chart |
| `commonLabels` | object | `{}` | Labels added to every object |
| `commonAnnotations` | object | `{}` | Annotations added to every object |

### API (`api.*`)

| Key | Type | Default | Description |
|---|---|---|---|
| `api.image.repository` | string | `back-office/api` | API image |
| `api.image.tag` | string | `""` (= `appVersion`) | Image tag; CI uses git SHAs |
| `api.image.pullPolicy` | string | `IfNotPresent` | |
| `api.replicas` | int | `2` | Replicas when autoscaling is off |
| `api.strategy` | object | RollingUpdate 1/0 | Deployment strategy |
| `api.service.type` / `port` / `annotations` | | `ClusterIP` / `8080` / `{}` | API Service |
| `api.javaToolOptions` | string | `""` | Extra `JAVA_TOOL_OPTIONS` |
| `api.resources` | object | 500m/1Gi requests, 2Gi limit | Container resources |
| `api.probes.startup.enabled` / `failureThreshold` / `periodSeconds` | | `true` / `30` / `5` | Startup probe on `/actuator/health/liveness` |
| `api.probes.liveness.*`, `api.probes.readiness.*` | object | see values | Liveness/readiness probe timings |
| `api.podAnnotations`, `api.podLabels` | object | `{}` | Extra pod metadata |
| `api.nodeSelector`, `api.tolerations`, `api.affinity` | | `{}` / `[]` / `{}` | Scheduling |
| `api.priorityClassName` | string | `""` | |
| `api.terminationGracePeriodSeconds` | int | `60` | Graceful shutdown window |
| `api.podSecurityContext`, `api.securityContext` | object | non-root 10001, read-only root fs | Security contexts |
| `api.extraEnv` | list | `[]` | Extra `EnvVar`s (override everything else) |
| `api.extraEnvFrom` | list | `[]` | Extra `envFrom` sources |
| `api.extraVolumes`, `api.extraVolumeMounts` | list | `[]` | Extra volumes |
| `api.serviceAccount.create` / `name` / `annotations` | | `true` / `""` / `{}` | API service account |
| `api.autoscaling.enabled` / `minReplicas` / `maxReplicas` / `targetCPUUtilizationPercentage` / `targetMemoryUtilizationPercentage` | | `false` / `1` / `3` / `80` / `""` | HPA. Sessions are in PostgreSQL, so replicas share them; provider rate limits are per replica |
| `api.oidcLoopback.enabled` / `port` / `target` / `image` | | `false` / `8180` / `<release>-keycloak-http:80` / `alpine/socat:1.8.0.3` | Dev only: sidecar forwarding `localhost:<port>` to Keycloak so the issuer `http://localhost:<port>/...` works with `kubectl port-forward` |
| `api.podDisruptionBudget.enabled` / `minAvailable` / `maxUnavailable` | | `false` / `1` / unset | PDB |
| `api.config.publicUrl` | string | `""` (derived) | `BACKOFFICE_PUBLIC_URL`; defaults to the ingress URL or the web Service |
| `api.config.corsAllowedOrigins` | string | `""` (= publicUrl) | `CORS_ALLOWED_ORIGINS` |
| `api.config.logLevel` | string | `INFO` | `LOG_LEVEL` |
| `api.config.aiLogPrompts` / `aiLogCompletions` | bool | `false` | `AI_LOG_PROMPTS` / `AI_LOG_COMPLETIONS` |
| `api.config.llmTimeout` | string | `PT10M` | `LLM_TIMEOUT` |
| `api.config.llmConcurrencyPerProvider` | int | `4` | `LLM_CONCURRENCY_PER_PROVIDER` |
| `api.config.dbPoolSize` | int | `10` | `DB_POOL_SIZE` (per pod; connection budget in docs/runbooks/scaling.md) |
| `api.config.tracingSampling` | string | `"1.0"` | `TRACING_SAMPLING` |
| `api.config.pipelineExecutorThreads` | int | `16` | `PIPELINE_EXECUTOR_THREADS` |
| `api.config.pipelineRecoverOnStartup` | bool | `true` | `PIPELINE_RECOVER_ON_STARTUP` |
| `api.config.evalConcurrencyPerProvider` | int | `2` | `EVAL_CONCURRENCY_PER_PROVIDER` |
| `api.config.evalSeedStarterContent` | bool | `true` | `EVAL_SEED_STARTER_CONTENT` |
| `api.config.extra` | object | `{}` | Additional ConfigMap entries (string values) |

### Web (`web.*`)

| Key | Type | Default | Description |
|---|---|---|---|
| `web.enabled` | bool | `true` | Deploy the UI |
| `web.image.repository` / `tag` / `pullPolicy` | | `back-office/web` / `""` / `IfNotPresent` | |
| `web.replicas` | int | `1` | |
| `web.service.type` / `port` / `nodePort` / `annotations` | | `ClusterIP` / `8080` / `""` / `{}` | |
| `web.apiUpstream` | string | `""` (= `http://<fullname>-api:8080`) | `API_UPSTREAM` for the nginx `/api` proxy |
| `web.resources` | object | 50m/64Mi, 128Mi limit | |
| `web.podAnnotations`, `web.podLabels`, `web.nodeSelector`, `web.tolerations`, `web.affinity` | | | Scheduling/metadata |
| `web.podSecurityContext`, `web.securityContext` | object | nginx-unprivileged uid 101 | |
| `web.extraEnv` | list | `[]` | |

### Topology, worker, migrations

| Key | Type | Default | Description |
|---|---|---|---|
| `mode` | string | `split` | `split` (api + worker Deployments) or `combined` (one `api,worker` Deployment) |
| `topologySpread.enabled` / `zoneWhenUnsatisfiable` / `hostWhenUnsatisfiable` | | `true` / `ScheduleAnyway` / `ScheduleAnyway` | Spread api and worker pods over zones and nodes |
| `api.preStopSleepSeconds` | int | `10` | preStop sleep before graceful shutdown (endpoint removal) |
| `api.autoscaling.requestsPerSecond.*` | | disabled | Extra HPA Pods metric from a custom-metrics adapter |
| `api.config.httpHistogram` | bool | `true` | Latency histograms for `http.server.requests` |
| `worker.replicas` / `resources` / `probes` / `terminationGracePeriodSeconds` | | `1` / 500m-1Gi / actuator / `90` | Worker Deployment |
| `worker.autoscaling.type` | string | `keda` | `keda`, `hpa` or `none` |
| `worker.autoscaling.minReplicas` / `maxReplicas` | int | `1` / `10` | |
| `worker.autoscaling.keda.query` / `targetQueryValue` / `activationTargetQueryValue` | | ready+leased `work_item` count / `20` / `0` | PostgreSQL scaler (direct connection) |
| `worker.autoscaling.keda.sslmode` / `pollingInterval` / `cooldownPeriod` / `scaleDownStabilizationSeconds` / `fallbackReplicas` | | `disable` / `15` / `300` / `300` / `2` | |
| `worker.podDisruptionBudget.*` | | enabled, `minAvailable: 1` | |
| `migration.enabled` | bool | `true` | Migrate Job (role `migrate`) |
| `migration.initContainer` | string | `auto` | Also migrate in an init container of every app pod (`true`/`false`/`auto`) |
| `migration.backoffLimit` / `activeDeadlineSeconds` / `ttlSecondsAfterFinished` / `hookDeletePolicy` | | `2` / `1800` / `86400` / `before-hook-creation` | |
| `migration.resources` / `javaToolOptions` / `waitImage` / `extraEnv` | | | |
| `runner.mode` | string | `FLEET` | `RUNNER_MODE` |
| `runner.defaultTimeout` | string | `PT60M` | `RUNNER_DEFAULT_TIMEOUT` |

### Storage and signing

| Key | Type | Default | Description |
|---|---|---|---|
| `storage.type` | string | `s3` | `BACKOFFICE_STORAGE_TYPE` (`s3` / `filesystem`) |
| `storage.s3.endpoint` / `region` / `bucket` / `pathStyle` / `sse` | | `""` / `us-east-1` / `back-office` / `false` / `""` | `BACKOFFICE_STORAGE_S3_*` |
| `storage.s3.accessKey` / `secretKey` / `existingSecret` | | `""` | Static keys (empty = IRSA / Workload Identity) |
| `storage.s3.checksumWhenRequired` | bool | `false` | `AWS_REQUEST_CHECKSUM_CALCULATION=when_required` (GCS, gateways) |
| `storage.filesystem.path` / `existingClaim` / `size` / `storageClass` / `accessMode` | | | `BACKOFFICE_STORAGE_FILESYSTEM_ROOT` + PVC |
| `minio.enabled` | bool | `false` | Bundled single-node MinIO (`cgr.dev/chainguard/minio`) |
| `minio.image.*`, `minio.clientImage`, `minio.persistence.*`, `minio.resources` | | | |
| `signing.existingSecret` | string | `""` | Secret with `BACKOFFICE_SIGNING_KEYS_<n>_*` (`deploy/scripts/gen-signing-key.sh`) |
| `signing.generate` | bool | `true` | Hook Job creating `<fullname>-signing` once |
| `signing.keyIdPrefix` / `optional` / `opensslImage` / `kubectlImage` | | `bo` / `false` / `alpine/openssl:3.5.2` / `alpine/k8s:1.34.1` | |

### Database extras

| Key | Type | Default | Description |
|---|---|---|---|
| `postgresql.mode` | string | `""` (auto) | `bundled`, `cnpg` or `external` |
| `externalDatabase.pooler.host` / `port` | | `""` / `5432` | Managed pooler for the Hikari pools (RDS Proxy, Azure 6432, Cloud SQL) |
| `cnpg.*` | object | 3 instances, 20Gi, pooler off, backup off | CloudNativePG `Cluster` / `Pooler` / Barman Cloud Plugin `ObjectStore` / `ScheduledBackup` |
| `pgbouncer.*` | object | disabled | PgBouncer Deployment in front of an external DB |

### NetworkPolicy, External Secrets, metrics

| Key | Type | Default | Description |
|---|---|---|---|
| `networkPolicy.enabled` | bool | `false` | Control-plane policies |
| `networkPolicy.ingressController.namespaceLabels` / `podLabels` / `peers` | | ingress-nginx namespace | Who may reach web/api |
| `networkPolicy.monitoring.namespaceLabels` | object | `monitoring` namespace | Prometheus scrape access |
| `networkPolicy.database.cidrs` | list | `[]` | External DB addresses |
| `networkPolicy.egress.internet.*` / `otelCollector` / `extra` | | 443 to public addresses | |
| `networkPolicy.api.extraIngress` / `web.extraIngress` / `minio.extraIngress` | list | `[]` | |
| `externalSecrets.enabled` / `refreshInterval` / `secretStoreRef` | | `false` / `1h` / `ClusterSecretStore back-office` | |
| `externalSecrets.{app,providerKeys,integrations,database,storage,signing}.remoteKey` / `data` | | | One remote secret per target (`dataFrom.extract`) or explicit `data` |
| `metrics.serviceMonitor.*` / `prometheusRule.*` (incl. `thresholds`) / `dashboards.*` | | disabled | Prometheus Operator objects and Grafana dashboard ConfigMaps |
| `otel.metricsEnabled` / `metricsEndpoint` / `logsEnabled` / `logsEndpoint` / `environment` | | | OTLP metrics/logs, resource attributes |

### Integrations (`integrations.*`, non-secret part)

| Key | Type | Default | Description |
|---|---|---|---|
| `integrations.jira.url` | string | `""` | `JIRA_URL` |
| `integrations.jira.email` | string | `""` | `JIRA_EMAIL` |
| `integrations.jira.acceptanceCriteriaField` | string | `""` | `JIRA_ACCEPTANCE_CRITERIA_FIELD` |

### Secrets (`secrets.*`)

| Key | Type | Default | Description |
|---|---|---|---|
| `secrets.create` | bool | `true` | Render `<fullname>-app` from inline values (ignored with `externalSecrets.enabled`) |
| `secrets.existingSecret` | string | `""` | Use this Secret instead (keys `RUNNER_TOKEN_SECRET`, `BACKOFFICE_AUTH_OIDC_CLIENT_SECRET` unless `auth.oidc.existingSecret`, `SPRING_DATASOURCE_PASSWORD` for external DB, `OTEL_EXPORTER_OTLP_HEADERS_AUTHORIZATION`) |
| `secrets.runnerTokenSecret` | string | `""` (generated, kept on upgrade) | `RUNNER_TOKEN_SECRET` |
| ~~`secrets.apiKey`~~, ~~`secrets.ciApiKey`~~ | | | Removed in iteration 3; setting them fails the install with an upgrade hint |
| `secrets.providerKeys.create` / `existingSecret` | | `true` / `""` | `<fullname>-provider-keys` |
| `secrets.providerKeys.anthropicApiKey` | string | `""` | `ANTHROPIC_API_KEY` |
| `secrets.providerKeys.openaiApiKey` | string | `""` | `OPENAI_API_KEY` |
| `secrets.providerKeys.googleGenaiApiKey` | string | `""` | `GOOGLE_GENAI_API_KEY` |
| `secrets.providerKeys.mistralApiKey` | string | `""` | `MISTRAL_API_KEY` |
| `secrets.providerKeys.openaiCompatibleApiKey` / `openaiCompatibleBaseUrl` | string | `""` | `OPENAI_COMPATIBLE_API_KEY` / `OPENAI_COMPATIBLE_BASE_URL` |
| `secrets.providerKeys.ollamaBaseUrl` | string | `""` | `OLLAMA_BASE_URL` |
| `secrets.integrations.create` / `existingSecret` | | `true` / `""` | `<fullname>-integrations` |
| `secrets.integrations.jiraApiToken` | string | `""` | `JIRA_API_TOKEN` |
| ~~`secrets.integrations.gitToken`~~, ~~`gitUserName`~~ / ~~`gitUserEmail`~~ | | | Removed: git credentials and the commit identity belong to the runner chart (`gitCredentials.existingSecret` / `hostSecrets`, `gitCredentials.userName` / `userEmail`); a set `gitToken` fails the install |

### Authentication (`auth.*`)

| Key | Type | Default | Description |
|---|---|---|---|
| `auth.oidc.issuerUri` | string | `""` (derived from the bundled Keycloak) | `BACKOFFICE_AUTH_OIDC_ISSUER_URI`; required without `keycloak.enabled` |
| `auth.oidc.clientId` | string | `back-office` | `BACKOFFICE_AUTH_OIDC_CLIENT_ID` |
| `auth.oidc.clientSecret` | string | `""` | `BACKOFFICE_AUTH_OIDC_CLIENT_SECRET` stored in `<fullname>-app` (dev) |
| `auth.oidc.existingSecret` / `existingSecretKey` | | `""` / `client-secret` | Secret with the client secret (production) |
| `auth.oidc.scopes` | string/list | `openid,profile,email` | `BACKOFFICE_AUTH_OIDC_SCOPES` |
| `auth.registration` | string | `INVITE_ONLY` | `BACKOFFICE_AUTH_REGISTRATION` (`INVITE_ONLY` or `OPEN`) |
| `auth.allowedEmailDomains` | list/string | `[]` | `BACKOFFICE_AUTH_ALLOWED_EMAIL_DOMAINS` |
| `auth.bootstrapAdmins` | list/string | `[]` | `BACKOFFICE_AUTH_BOOTSTRAP_ADMINS`: platform-admin on first login |
| `auth.session.idleTimeout` / `absoluteTimeout` | string | `PT30M` / `PT12H` | `BACKOFFICE_AUTH_SESSION_IDLE_TIMEOUT` / `..._ABSOLUTE_TIMEOUT` |
| `auth.cookieSecure` | bool | `true` | `BACKOFFICE_AUTH_COOKIE_SECURE` |
| `auth.groupsClaim` | string | `groups` | `BACKOFFICE_AUTH_GROUPS_CLAIM` |

### Bundled Keycloak (`keycloak.*` subchart values, `bundledKeycloak.*` chart settings)

| Key | Type | Default | Description |
|---|---|---|---|
| `keycloak.enabled` | bool | `false` | Deploy Keycloak 26.7 (codecentric/keycloakx, official image) for evaluation installs |
| `keycloak.database.existingSecret` / `existingSecretKey` | | `""` | Needed only when the DB password is in an existing Secret |
| `bundledKeycloak.url` | string | `""` (ingress URL, else `http://localhost:<api.oidcLoopback.port>`) | `KC_HOSTNAME`, the public URL; issuer = `<url>/realms/<realm>` |
| `bundledKeycloak.realm` | string | `back-office` | Realm name |
| `bundledKeycloak.admin.username` / `password` | | `admin` / generated | Keycloak bootstrap admin (Secret `<release>-keycloak-env`) |
| `bundledKeycloak.database.schema` | string | `keycloak` | Schema in the Back Office database (created by an init container) |
| `bundledKeycloak.realmImport.sslRequired` | string | `external` | `sslRequired` of the imported realm |
| `bundledKeycloak.realmImport.includeDemoUsers` | bool | `false` | Import the test users of `deploy/keycloak/realm-back-office.json` |
| `bundledKeycloak.realmImport.clientSecret` | string | `""` (= `auth.oidc.clientSecret`) | Client secret written into the realm |
| `bundledKeycloak.realmImport.extraRedirectUris` | list | `[]` | Besides `<publicUrl>/login/oauth2/code/oidc` |
| `bundledKeycloak.ingress.enabled` / `className` / `annotations` / `host` / `tls` | | `false` / `""` / `{}` / `sso.example.com` / `[]` | Ingress for Keycloak |

The realm file is copied into the chart as `files/keycloak/realm-back-office.json` (Helm cannot read
files outside the chart); CI fails when the copy differs from `deploy/keycloak/realm-back-office.json`.
Keycloak imports it only on the first start.

### Database

| Key | Type | Default | Description |
|---|---|---|---|
| `postgresql.enabled` | bool | `true` | Deploy the Bitnami PostgreSQL subchart |
| `postgresql.auth.username` / `password` / `database` | string | `backoffice` | Application credentials |
| `postgresql.auth.existingSecret` / `secretKeys.*` | | `""` | Bring your own password Secret |
| `postgresql.architecture` | string | `standalone` | |
| `postgresql.primary.persistence.enabled` / `size` | | `true` / `8Gi` | |
| `postgresql.primary.resourcesPreset` | string | `small` | Bitnami sizing preset |
| `externalDatabase.host` | string | `""` | Required when `postgresql.enabled=false` |
| `externalDatabase.port` | int | `5432` | |
| `externalDatabase.database` / `username` | string | `backoffice` | |
| `externalDatabase.password` | string | `""` | Stored in `<fullname>-app` as `SPRING_DATASOURCE_PASSWORD` |
| `externalDatabase.existingSecret` / `passwordKey` | | `""` / `password` | Read the password from your Secret |
| `externalDatabase.jdbcParams` | string | `""` | Appended to the JDBC URL (`sslmode=require`) |

### Ingress

| Key | Type | Default | Description |
|---|---|---|---|
| `ingress.enabled` | bool | `false` | |
| `ingress.className` | string | `""` | |
| `ingress.annotations` | object | `{}` | e.g. cert-manager issuer, proxy timeouts for SSE |
| `ingress.host` | string | `back-office.example.com` | Single host: `/` to web (web proxies `/api`, `/oauth2`, `/login/oauth2`, `/logout`) |
| `ingress.path` / `pathType` | | `/` / `Prefix` | |
| `ingress.tls` | list | `[]` | Standard Ingress TLS list |

### Observability

| Key | Type | Default | Description |
|---|---|---|---|
| `otel.enabled` | bool | `false` | Export traces to any OTLP/HTTP collector (`OTEL_TRACES_ENABLED`) |
| `otel.tracesEndpoint` | string | `http://otel-collector:4318/v1/traces` | `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` |
| `otel.headersAuthorization` | string | `""` | `OTEL_EXPORTER_OTLP_HEADERS_AUTHORIZATION` |
| `langfuse.enabled` | bool | `false` | Deploy Langfuse and point tracing at it (`LANGFUSE_ENABLED=true`, `LANGFUSE_URL`, OTLP endpoint `http://<release>-langfuse-web:3000/api/public/otel/v1/traces`) |
| `langfuse.langfuse.nextauth.url` | string | `http://localhost:3000` | Public URL of Langfuse |
| `langfuse.langfuse.nextauth.secret.value`, `langfuse.langfuse.salt.value`, `langfuse.langfuse.encryptionKey.value` | string | `""` (auto-generated on first install) | Langfuse secrets; pin them for reproducible upgrades |
| `langfuse.postgresql.*`, `langfuse.clickhouse.*`, `langfuse.redis.*`, `langfuse.s3.*` | object | subchart defaults with `nameOverride` set | Langfuse data stores (`clickhouse.crdCheck=false` for offline rendering) |
| `langfuseAuth.publicKey` / `secretKey` | string | `""` | Langfuse project keys; rendered as `Basic base64(pk:sk)` into the app Secret |
| `langfuseAuth.existingSecret` / `existingSecretKey` | | `""` / `OTEL_EXPORTER_OTLP_HEADERS_AUTHORIZATION` | Secret already holding the full header value |
| `langfuseAuth.url` | string | `""` (= in-cluster URL) | `LANGFUSE_URL` shown in the UI |

## Development

```bash
cd deploy/helm/back-office
helm repo add langfuse https://langfuse.github.io/langfuse-k8s
helm repo add codecentric https://codecentric.github.io/helm-charts
helm dependency build .
OIDC="--set auth.oidc.issuerUri=https://sso.example.com/realms/back-office --set auth.oidc.clientSecret=x"
helm lint . $OIDC
for v in values-dev values-aws values-gcp values-azure values-onprem; do helm lint . -f $v.yaml && helm template bo . -f $v.yaml > /dev/null; done
helm template bo . $OIDC --set postgresql.enabled=false --set postgresql.mode=cnpg --set cnpg.pooler.enabled=true
helm template bo . $OIDC --set postgresql.enabled=false --set externalDatabase.host=db --set externalDatabase.password=pw --set pgbouncer.enabled=true --set networkPolicy.enabled=true
helm template bo . --set keycloak.enabled=true --set auth.oidc.clientSecret=x \
  --set bundledKeycloak.ingress.enabled=true --set bundledKeycloak.ingress.host=sso.example.com
helm template bo . $OIDC --set langfuse.enabled=true --set langfuse.clickhouse.crdCheck=false
```

The full set of CI variants and negative checks is in `.gitlab-ci.yml` (`helm:lint`). Dashboards are
copies of `deploy/observability/dashboards/*.json` in `files/dashboards/` (CI checks they are identical).
