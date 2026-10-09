# Deploying with Helm

## When to use this guide

You are installing or upgrading the Back Office **control plane** on a Kubernetes cluster with the chart
in `deploy/helm/back-office`: creating the namespace and Secrets, connecting an OIDC identity provider
(or the bundled Keycloak), choosing the topology (`mode: split` or `combined`), the database
(bundled, CloudNativePG or managed) and object storage, exposing the UI through an Ingress, autoscaling,
observability, optionally Langfuse, and verifying the deployment. Agents run in the separately installed
**runner fleet** ([runner-fleet-install.md](runner-fleet-install.md)); per-cloud reference setups are in
[cloud-reference.md](cloud-reference.md). The complete values reference is in the
[chart README](../../deploy/helm/back-office/README.md); environment variables are explained in
[configuration.md](configuration.md).

## Prerequisites

- Kubernetes 1.27+ with a default StorageClass (bundled PostgreSQL/MinIO, Langfuse) and an Ingress
  controller. A CNI that enforces NetworkPolicy if you enable `networkPolicy.enabled`.
- **KEDA** 2.x for worker autoscaling (`worker.autoscaling.type=keda`, the default in `mode: split`);
  without it set `worker.autoscaling.type=hpa` or `none`. Optional: Prometheus Operator
  (`metrics.*`), External Secrets Operator (`externalSecrets.*`), CloudNativePG (`postgresql.mode=cnpg`).
- PostgreSQL 16+ and an S3-compatible bucket (or `minio.enabled=true` for evaluation).
- `helm` 3.12+, `kubectl` with rights to create Roles in the namespace (the signing-key hook Job uses a
  short-lived Role that may only create its Secret). The app pods themselves get **no** Kubernetes RBAC.
- Container images pushed to a registry the cluster can pull from: `back-office/api` (used by the api,
  worker and migrate roles) and `back-office/web`, tagged with the git SHA and signed by CI (cosign;
  `$CI_REGISTRY_IMAGE/{api,web}:$CI_COMMIT_SHORT_SHA`). For kind/minikube load them with
  `kind load docker-image back-office/api:dev` etc.
- An OIDC provider (Keycloak, Microsoft Entra ID, Google Workspace, Okta, ...) where you can register a
  confidential client - or `keycloak.enabled=true` for an evaluation install (section 2b).
- Network access from the cluster to your OIDC issuer, your LLM providers, object storage and Jira (git
  hosts are reached only by the runner fleet). The reverse direction matters too: your CI runners must reach Back Office
  (`BACKOFFICE_URL`), because the CI calls the platform, never the other way round (ADR 0013).

```bash
helm repo add langfuse https://langfuse.github.io/langfuse-k8s
helm repo add codecentric https://codecentric.github.io/helm-charts
helm dependency build deploy/helm/back-office      # pulls bitnami postgresql (OCI), langfuse and keycloakx
```

## 1. Namespace and Secrets

Development clusters can pass secrets as values (`values-dev.yaml` does). For anything else create the
Secrets yourself and tell the chart to use them. Key names must match the environment variable names.

```bash
kubectl create namespace back-office

# Application secrets (runner HMAC secret). There is no API key any more: people log in through OIDC,
# scripts use personal access tokens, CI jobs use project CI tokens (all issued in the UI, stored hashed).
# BACKOFFICE_SECRETS_KEY encrypts the tokens of Jira connections (ADR 0030); back it up separately.
kubectl -n back-office create secret generic back-office-app \
  --from-literal=RUNNER_TOKEN_SECRET="$(openssl rand -hex 32)" \
  --from-literal=BACKOFFICE_AUTH_LOCAL_SECRET_KEY="$(openssl rand -base64 32)" \
  --from-literal=BACKOFFICE_SECRETS_KEY="$(openssl rand -base64 32)"
# Optional, installations without an identity provider (section 2d):
#   --from-literal=BACKOFFICE_AUTH_LOCAL_BOOTSTRAP_ADMIN_PASSWORD='<initial password, 12+ characters>'

# OIDC client secret of the API (copied from the identity provider, see section 2a).
kubectl -n back-office create secret generic back-office-oidc \
  --from-literal=client-secret='<secret from the IdP>'

# LLM provider keys (api/worker only; sandboxed agents use the LLM gateway with a job token).
kubectl -n back-office create secret generic back-office-provider-keys \
  --from-literal=ANTHROPIC_API_KEY=sk-ant-... \
  --from-literal=OPENAI_API_KEY=sk-... \
  --from-literal=OLLAMA_BASE_URL=http://ollama.ml.svc:11434

# Integration tokens. Git tokens of agents live in the runner cluster (runner-fleet-install.md).
# Jira credentials are connections added in the UI (Administration -> Connections); JIRA_API_TOKEN is
# legacy and only needed by installations that have not migrated yet (section 2e).
kubectl -n back-office create secret generic back-office-integrations \
  --from-literal=JIRA_API_TOKEN=...   # legacy, optional

# Ed25519 signing key for attestations and runner assignments (ADR 0025) -> signing.existingSecret
deploy/scripts/gen-signing-key.sh --secret back-office-signing --namespace back-office

# Object storage credentials (skip with IRSA / Workload Identity) -> storage.s3.existingSecret
kubectl -n back-office create secret generic back-office-storage \
  --from-literal=BACKOFFICE_STORAGE_S3_ACCESS_KEY=... --from-literal=BACKOFFICE_STORAGE_S3_SECRET_KEY=...
```

Instead of creating Secrets by hand you can let External Secrets Operator sync them from Vault, AWS
Secrets Manager, GCP Secret Manager or Azure Key Vault: `externalSecrets.enabled=true` plus a
`secretStoreRef` and one remote secret per target whose keys are the variable names
([configuration.md](configuration.md#helm-secrets-layout)).

Use the release name `back-office` so the generated names (`back-office-api`, `back-office-postgresql`)
match the ones above; otherwise name your Secrets `<fullname>-...` or pass any name via `existingSecret`.

## 2. Topology, database and storage

| Setting | Small install | Production |
|---|---|---|
| `mode` | `combined`: one Deployment `<fullname>-api` with role `api,worker` | `split`: `<fullname>-api` (role `api`, HPA CPU 70 %, PDB, zone spread, preStop drain) + `<fullname>-worker` (role `worker`, KEDA on the work queue, PDB, 90 s grace) |
| Database | `postgresql.enabled=true` (Bitnami) | `postgresql.mode=cnpg` (CloudNativePG `Cluster`, 3 instances, pooler, backups) or managed (`externalDatabase.*`, optional `externalDatabase.pooler` / `pgbouncer.enabled`) |
| Object storage | `minio.enabled=true` (single node) | S3 / GCS / S3 gateway (`storage.s3.*`) |
| Migrations | init container of the app pods (the DB is part of the release) | `pre-install,pre-upgrade` hook Job `<fullname>-migrate` (role `migrate`) |

Migrations never run in app pods (`SPRING_FLYWAY_ENABLED=false`): the migrate Job runs before the
rollout and a failure stops the upgrade with the old version still serving
([runbooks/upgrade.md](../runbooks/upgrade.md)). With a pooler, the Hikari pools use it and LISTEN/NOTIFY
keeps a direct connection to the primary (`BACKOFFICE_DATASOURCE_DIRECT_URL`).

## 2a. Identity provider (OIDC)

Back Office is its own backend-for-frontend (ADR 0016): the API performs the authorization code flow
with PKCE as a **confidential client**, keeps the tokens server-side and gives the browser only the
`BO_SESSION` cookie. Register one client at your provider:

| Setting | Value |
|---|---|
| Client type | confidential / web application, authorization code flow (PKCE S256 if the IdP lets you require it) |
| Redirect URI | `https://back-office.example.com/login/oauth2/code/oidc` (= `<BACKOFFICE_PUBLIC_URL>/login/oauth2/code/oidc`) |
| Post-logout redirect URI | `https://back-office.example.com/` (or `/*` where wildcards are allowed) |
| Scopes | `openid profile email` (+ what the IdP needs for groups) |
| Groups claim | a claim listing the user's group names, default name `groups` (`auth.groupsClaim`) |

Groups are only *mapped* to Back Office groups; permissions are granted in Back Office (ADR 0017).

**Keycloak (your own)**: client `back-office`, *Client authentication* on, *Standard flow* only, *PKCE
method* S256 (Advanced tab), redirect/post-logout URIs as above, *Web origins* `+`. Add a mapper *Group
Membership*, token claim name `groups`, *Full group path* off, in ID token and userinfo. Issuer:
`https://sso.example.com/realms/<realm>`. `deploy/keycloak/realm-back-office.json` is a complete example.

**Microsoft Entra ID**: *App registrations > New registration*, platform *Web*, redirect URI as above,
*Front-channel logout URL* not needed. *Certificates & secrets > New client secret* goes into
`back-office-oidc`. Issuer: `https://login.microsoftonline.com/<tenant-id>/v2.0`, client id = *Application
(client) ID*. Groups: *Token configuration > Add groups claim* (security groups, emitted as `groups`);
Entra sends group **object ids** (or `sAMAccountName` for synced groups), so map those ids in Back
Office. Users with more than 200 groups get an overage claim instead - assign groups to the app
(*Enterprise applications > Properties > Assignment required*, "Groups assigned to the application").
Add the optional claim `email` or make sure users have a mail attribute: Back Office matches invitations by e-mail.

**Google Workspace**: *Google Cloud console > APIs & Services > Credentials > OAuth client ID*, type *Web
application*, authorised redirect URI as above. Issuer `https://accounts.google.com`, scopes `openid,profile,email`.
Google ID tokens carry no groups claim: leave `auth.groupsClaim` at its default (no groups are mapped)
and manage groups in Back Office, or put a groups-capable broker (Keycloak with Google as identity
provider) in front. Restrict sign-ins with `auth.allowedEmailDomains: [example.com]` or
`auth.registration: INVITE_ONLY` (default) - a Google client otherwise accepts any Google account.

Values for an external provider:

```yaml
auth:
  oidc:
    issuerUri: https://sso.example.com/realms/back-office
    clientId: back-office
    existingSecret: back-office-oidc          # key client-secret
    scopes: "openid,profile,email"
  registration: INVITE_ONLY
  allowedEmailDomains: []
  bootstrapAdmins: [jane.doe@example.com]     # platform-admin on first login
  session: { idleTimeout: PT30M, absoluteTimeout: PT12H }
  cookieSecure: true
  groupsClaim: groups
```

The API pods must reach the issuer URL (egress, DNS, a CA the JVM trusts) and it must be the exact
`iss` the IdP writes into tokens - the API reads `<issuer>/.well-known/openid-configuration` at the first sign-in (lazily, not at
start; a failed discovery shows up as a failed login and is retried at the next one).

**Sessions** live in PostgreSQL (Spring Session JDBC, table `SPRING_SESSION`). Nothing to configure, but
it means: API restarts do not log users out, several API replicas share sessions without sticky
sessions, and deactivating a user deletes their sessions immediately.

## 2b. Optional: bundled Keycloak (evaluation installs)

`keycloak.enabled=true` deploys Keycloak 26.7 from the
[codecentric/keycloakx](https://github.com/codecentric/helm-charts/tree/master/charts/keycloakx) chart
(official `quay.io/keycloak/keycloak` image; the Bitnami chart was not used because it is frozen at 26.3
and its image is no longer published). The chart then:

- stores Keycloak's data in the Back Office PostgreSQL, schema `keycloak` (created by an init container),
- imports the realm `back-office` from `deploy/keycloak/realm-back-office.json` with the client secret,
  redirect URI and post-logout URI rewritten for your public URL, `sslRequired: external`, and without the
  demo users unless `bundledKeycloak.realmImport.includeDemoUsers=true`,
- derives `auth.oidc.issuerUri` = `<bundledKeycloak url>/realms/back-office`,
- optionally exposes Keycloak on its own host (`bundledKeycloak.ingress`).

```yaml
keycloak:
  enabled: true
auth:
  oidc:
    clientSecret: "<openssl rand -hex 32>"    # written into the realm and into <fullname>-app
  bootstrapAdmins: [jane.doe@example.com]
bundledKeycloak:
  ingress:
    enabled: true
    className: nginx
    host: sso.example.com
    tls: [{ secretName: sso-tls, hosts: [sso.example.com] }]
```

Read the generated admin password with
`kubectl -n back-office get secret back-office-keycloak-env -o jsonpath="{.data.KC_BOOTSTRAP_ADMIN_PASSWORD}" | base64 -d`
and create the users in the admin console (`https://sso.example.com/admin`). Keycloak imports the realm
**only on the first start** (an existing realm is skipped), so later changes to the realm values have
no effect: manage the realm in Keycloak, export it for backups
([runbooks/keycloak-realm.md](../runbooks/keycloak-realm.md)). For production run Keycloak as its own
release (or use the organisation's IdP) and treat the import as a starting point, not as configuration.

Without an Ingress for Keycloak (kind/minikube, `values-dev.yaml`), the issuer is
`http://localhost:8180/realms/back-office`: port-forward Keycloak for the browser
(`kubectl port-forward svc/back-office-keycloak-http 8180:80`) and enable `api.oidcLoopback`, a socat
sidecar that makes the same URL reach Keycloak from inside the API pod.

## 2c. Values file

`values-prod.yaml` (keep it outside the repository or in your GitOps repo):

```yaml
mode: split
api:
  image: { repository: registry.example.com/mos/back-office/api, tag: "a1b2c3d4" }
  autoscaling: { enabled: true, minReplicas: 2, maxReplicas: 6 }
  resources:
    requests: { cpu: 500m, memory: 1Gi }
    limits: { memory: 2Gi }
worker:
  autoscaling: { type: keda, minReplicas: 1, maxReplicas: 10 }
web:
  image: { repository: registry.example.com/mos/back-office/web, tag: "a1b2c3d4" }
storage:
  s3: { endpoint: https://s3.example.com, region: eu-1, bucket: back-office, pathStyle: true, existingSecret: back-office-storage }
signing:
  existingSecret: back-office-signing
  generate: false
networkPolicy:
  enabled: true
metrics:
  serviceMonitor: { enabled: true }
  prometheusRule: { enabled: true }
  dashboards: { enabled: true }
imagePullSecrets: [{ name: registry-pull }]
integrations:
  jira: { acceptanceCriteriaField: customfield_10042 }   # url/email are legacy (section 2e)
auth:
  oidc:
    issuerUri: https://sso.example.com/realms/back-office
    existingSecret: back-office-oidc
  bootstrapAdmins: [jane.doe@example.com]
secrets:
  create: false
  existingSecret: back-office-app
  providerKeys: { create: false, existingSecret: back-office-provider-keys }
  integrations: { create: false, existingSecret: back-office-integrations }
postgresql:
  enabled: true
  auth:
    existingSecret: back-office-postgresql-auth     # keys: password, postgres-password
  primary:
    persistence: { size: 20Gi }
    resourcesPreset: small
ingress:
  enabled: true
  className: nginx
  host: back-office.example.com
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt
    nginx.ingress.kubernetes.io/proxy-read-timeout: "3600"     # SSE event stream
  tls:
    - secretName: back-office-tls
      hosts: [back-office.example.com]
```

## 2d. Local accounts (with or without an identity provider)

Local accounts (e-mail + password, optional TOTP; ADR 0021) are on by default (`auth.local.enabled`).
They need `BACKOFFICE_AUTH_LOCAL_SECRET_KEY` in the app Secret: with `secrets.create=true` the chart
generates it on the first install and keeps it on upgrades (like `RUNNER_TOKEN_SECRET`); with
`secrets.existingSecret` add it yourself (section 1). Without it the API refuses to start while local
login is enabled. Do not rotate it casually: it encrypts the users' TOTP secrets.

**No identity provider at all**: leave `auth.oidc.issuerUri` empty and `keycloak.enabled=false`, and set the
bootstrap administrator:

```yaml
auth:
  local:
    enabled: true
    bootstrapAdminEmail: ops@example.com
    bootstrapAdminPassword: "<initial password, 12+ characters>"   # or put it into your existingSecret
```

On the first start (empty database) the API creates that user as a local `platform-admin` who must change
the password at the first login; remove both values afterwards. No `oauth2` routes and no client secret
are needed then. The throttle (`auth.local.throttle`, 20 attempts per IP per 5 minutes) counts per API
replica, and the source IP comes from `X-Forwarded-For` of your ingress (`server.forward-headers-strategy`):
make sure the ingress sets it and clients cannot spoof it.

To use **OIDC only**, set `auth.local.enabled=false` (the secret key is then optional).

## 2e. Jira connections and the secrets key (ADR 0030)

Jira credentials are **connections** that administrators and project owners add in the UI
([manual](../manual/integrations-jira.md#jira-connections)). Their tokens are encrypted in the database with
`BACKOFFICE_SECRETS_KEY` ([configuration.md](configuration.md#connection-secrets-adr-0030)):

| Value | Meaning |
|---|---|
| `secrets.connectionsKey` | the key (base64 of 32 bytes). Empty with `secrets.create=true`: generated on install and kept on upgrades. With `secrets.existingSecret`: put `BACKOFFICE_SECRETS_KEY` into your Secret (section 1) |
| `secrets.connectionsKeyId` | id of the key, default `k1` (ConfigMap `BACKOFFICE_SECRETS_KEY_ID`); change it with every new key |
| `secrets.connectionsPreviousKeys` | only while rotating: `id:base64` of old keys (Secret key `BACKOFFICE_SECRETS_PREVIOUS_KEYS`) |

Without the key the API starts as long as no connection exists, and adding one is refused. Once connections
exist, a missing key or an unknown key id stops the api and worker pods at startup (`CrashLoopBackOff` with a
message naming the variable). **Back up the key** outside the cluster: losing it means re-entering every
connection token. Rotation: [configuration.md](configuration.md#connection-secrets-adr-0030).

**Legacy Jira values.** `integrations.jira.url`, `integrations.jira.email` and
`secrets.integrations.jiraApiToken` (or `JIRA_API_TOKEN` in `secrets.integrations.existingSecret`) still work
as a read-only, all-capabilities platform connection "Jira (environment variables)" that is chosen last and
flagged in the UI. Migrate: add reader and writer connections, validate the workflows in the designer, then
remove these values and `helm upgrade` ([migration steps](../manual/integrations-jira.md#jira-config)).
`integrations.jira.acceptanceCriteriaField` stays.

## 3. Install

```bash
helm upgrade --install back-office deploy/helm/back-office \
  -n back-office -f values-prod.yaml --wait --timeout 10m
```

Quick dev install on kind/minikube (everything inline, NodePort 30080):

```bash
helm upgrade --install back-office deploy/helm/back-office -n back-office --create-namespace \
  -f deploy/helm/back-office/values-dev.yaml \
  --set secrets.providerKeys.anthropicApiKey=$ANTHROPIC_API_KEY
```

The chart prints `NOTES.txt` with the URL, the redirect URI to register, the bootstrap admins and the
port-forward commands. Log in with a bootstrap admin e-mail first, then invite the others (Administration > Users).
`values-dev.yaml` bundles Keycloak with the demo users of the local realm (`admin@example.com` / `admin`, ...).

## 4. Upgrade

### Upgrading from iteration 2

Iteration 3 replaces the owner API key with OIDC login (ADR 0016). Before `helm upgrade`:

1. Register the OIDC client (section 2a) or decide on the bundled Keycloak (2b).
2. Remove `secrets.apiKey` and `secrets.ciApiKey` from your values - the chart **fails** with an upgrade
   hint while they are set. With `secrets.existingSecret`, drop `BACKOFFICE_API_KEY` /
   `BACKOFFICE_CI_API_KEY` from that Secret and make sure it contains `RUNNER_TOKEN_SECRET` (it no longer
   falls back to the API key; with `secrets.create=true` the chart generates one and keeps it).
   Changing `RUNNER_TOKEN_SECRET` invalidates the callback tokens of runner Jobs in flight - upgrade
   when no agent step is running.
3. Add `auth.oidc.*` and at least one `auth.bootstrapAdmins` e-mail: the first administrator is created
   on the first login of that e-mail; there is no other way in.
4. Make sure the Ingress routes `/oauth2`, `/login/oauth2` and `/logout` through the web pod to the API
   (the chart's single Ingress on `/` does this with the iteration-3 web image) and that TLS is on
   (`auth.cookieSecure=true`).
5. Scripts that sent `X-Api-Key` switch to personal access tokens (`Authorization: Bearer bo_pat_...`,
   created per user in the UI). CI jobs already use project CI tokens and are unaffected.

After the upgrade existing projects have no members yet: the bootstrap admin (who has
`admin.projects.all`) adds members and project roles.

### Upgrading to iteration 4 (local accounts)

Flyway `V402__local_accounts.sql` adds `identity_user.auth_mode` (existing users become `OIDC`) and the
credential, TOTP, recovery-code and reset-link tables. Local login is enabled by default, so the API now
needs `BACKOFFICE_AUTH_LOCAL_SECRET_KEY`: with `secrets.create=true` the upgrade generates it; with an
existing Secret add the key before upgrading, or set `auth.local.enabled=false`.

### Routine upgrades

```bash
helm upgrade back-office deploy/helm/back-office -n back-office -f values-prod.yaml \
  --set api.image.tag=$NEW_SHA --set web.image.tag=$NEW_SHA
```

- The `pre-upgrade` hook Job `<fullname>-migrate` runs Flyway first; only when it succeeds do the
  Deployments roll (`maxUnavailable: 0`, PDBs). Big migrations: raise `migration.activeDeadlineSeconds`.
- Workers hand their leases back on shutdown; another worker resumes each run from its PostgreSQL
  checkpoint. Agent jobs keep running in the fleet across control-plane upgrades.
- Migrations must be backwards compatible (expand, migrate, contract across two releases) so that
  `helm rollback` works: [runbooks/upgrade.md](../runbooks/upgrade.md).
- Inline Secret/ConfigMap changes roll the API automatically (checksum annotations). Externally managed
  Secrets need `kubectl -n back-office rollout restart deploy/back-office-api`.
- Rollback: `helm rollback back-office <revision>`; migrations are forward-only and backwards compatible,
  so version N-1 runs on schema N ([runbooks/upgrade.md](../runbooks/upgrade.md#rollback)).

### Upgrading to iteration 5 (scale-out, runner fleet)

- The chart is split into roles: `<fullname>-api` + `<fullname>-worker` (`mode: split`, default) or one
  `api,worker` Deployment (`mode: combined`). The ConfigMap is now `<fullname>-config`.
- Removed values: `runner.image`, `runner.resources`, `runner.serviceAccount`, `runner.networkPolicy`,
  `runner.ttlSecondsAfterFinished`, `api.rbac.*`. The API Role/RoleBinding and the runner service
  account are deleted by the upgrade: install the fleet with the `back-office-runner` chart and keep
  `runner.mode: FLEET` (default). Upgrade when no agent step of the old push model is running.
- New pieces: object storage (`storage.*` or `minio.enabled`), the signing key (generated once by a hook
  Job, or `signing.existingSecret`), KEDA for `worker.autoscaling.type=keda`.
- `GIT_TOKEN` moves from `<fullname>-integrations` to the runner cluster (`gitCredentials`): the values
  `secrets.integrations.gitToken` / `gitUserName` / `gitUserEmail` are removed (a set `gitToken` fails the
  install); the commit identity is `gitCredentials.userName` / `userEmail` of the runner chart.

### Upgrading to least-privilege connections (ADR 0030)

- Migrations `V107` (table `connection`) and `V408` (adds `project.connections.manage` to the stored `owner`
  role) run in the migrate Job.
- With `secrets.create=true` the upgrade generates `BACKOFFICE_SECRETS_KEY`; with `secrets.existingSecret`
  add the key to your Secret before anyone creates a connection (section 2e). Back it up.
- Jira keeps working through the legacy `integrations.jira.*` / `JIRA_API_TOKEN` values; the UI shows a
  migration notice and designer warnings until scoped connections replace them.

## 5. External database

```yaml
postgresql:
  enabled: false
externalDatabase:
  host: pg.example.internal
  port: 5432
  database: backoffice
  username: backoffice
  existingSecret: back-office-db          # key: password
  passwordKey: password
  jdbcParams: "sslmode=require"
```

PostgreSQL 16 or newer. The user needs to own the database (Flyway creates tables; LangGraph4j
creates its checkpoint tables on first use). `externalDatabase.host` must be the **primary** endpoint
(Flyway, LISTEN/NOTIFY, KEDA); a transaction pooler goes into `externalDatabase.pooler` (RDS Proxy,
Azure flexible server PgBouncer on 6432, Cloud SQL managed pooling) or `pgbouncer.enabled=true`. Connection
budget: every app pod uses `api.config.dbPoolSize` (default 10) + 1 listener connection
([runbooks/scaling.md](../runbooks/scaling.md#postgresql-connection-budget)).

CloudNativePG instead of a managed service:

```yaml
postgresql: { enabled: false, mode: cnpg }
cnpg:
  instances: 3
  storage: { size: 50Gi }
  pooler: { enabled: true }
  backup:
    enabled: true
    method: plugin                       # Barman Cloud Plugin (install it with the operator)
    destinationPath: s3://back-office-pg-backups/
    endpointURL: https://s3.example.internal
    s3Credentials: { secretName: back-office-pg-backup-s3 }
```

## 6. Enabling Langfuse

Langfuse (v3+) brings web, worker, its own PostgreSQL, ClickHouse (via the ClickHouse operator), Valkey
and SeaweedFS - a sizeable footprint. Install the
[ClickHouse operator](https://github.com/Altinity/clickhouse-operator) first (the subchart checks for
its CRDs; use `--set langfuse.clickhouse.crdCheck=false` only for offline rendering).

```yaml
langfuse:
  enabled: true
  langfuse:
    nextauth:
      url: https://langfuse.example.com
      secret: { value: "<openssl rand -base64 32>" }
    salt: { value: "<openssl rand -base64 32>" }
    encryptionKey: { value: "<openssl rand -hex 32>" }
  postgresql:
    auth: { password: "<strong password>" }
```

Install/upgrade, open Langfuse (port-forward `svc/back-office-langfuse-web 3000`), create an
organisation and a project, copy the project keys and wire the API to them:

```bash
# either as values ...
helm upgrade back-office deploy/helm/back-office -n back-office -f values-prod.yaml \
  --set langfuseAuth.publicKey=pk-lf-... --set langfuseAuth.secretKey=sk-lf-...
# ... or as a Secret you manage (value must be the full header)
kubectl -n back-office create secret generic back-office-langfuse-otel \
  --from-literal=OTEL_EXPORTER_OTLP_HEADERS_AUTHORIZATION="Basic $(printf 'pk-lf-...:sk-lf-...' | base64 -w0)"
#   values: langfuseAuth.existingSecret: back-office-langfuse-otel
```

The chart then sets `OTEL_TRACES_ENABLED=true`,
`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://back-office-langfuse-web:3000/api/public/otel/v1/traces`,
`LANGFUSE_ENABLED=true` and `LANGFUSE_URL`. To send traces to an existing collector instead use
`otel.enabled=true` with `otel.tracesEndpoint`.

Naming note: the Langfuse subchart's stores are rendered as `back-office-langfuse-postgresql`,
`back-office-langfuse-redis`, `back-office-langfuse-s3` (the chart sets `nameOverride`s so they do not
collide with the application database `back-office-postgresql`). Do not remove those overrides.

## 7. Ingress and TLS

One host, one path: the web pod serves the SPA and proxies `/api`, `/oauth2`, `/login/oauth2` and
`/logout` to `back-office-api`, so the browser talks to a single origin, the session cookie is
first-party and no CORS configuration is needed. Set
`nginx.ingress.kubernetes.io/proxy-read-timeout` (or the equivalent for your controller) to at least
an hour: the UI keeps an SSE connection open for live events. With cert-manager, add the issuer
annotation and a `tls` entry; `BACKOFFICE_PUBLIC_URL` is derived as `https://<host>` automatically.

**TLS and `auth.cookieSecure`**: `BO_SESSION` is `Secure` by default, so browsers only send it over
https. Keep `auth.cookieSecure=true` whenever users reach the UI through TLS (also when TLS terminates at
the Ingress or a load balancer - the flag concerns the browser, not the pod). Set it to `false` only
for plain-http dev clusters; the chart warns in `NOTES.txt` when the public URL is http and the flag is
true. Behind a TLS-terminating proxy the API must see `X-Forwarded-Proto: https` (ingress-nginx sets it)
so it builds `https://` redirect URIs.

Without an Ingress use `web.service.type=NodePort`/`LoadBalancer` or port-forward
(`kubectl -n back-office port-forward svc/back-office-web 8080:8080`).

## 7b. Observability

- `metrics.serviceMonitor.enabled` scrapes `/actuator/prometheus` of api and worker;
  `metrics.prometheusRule.enabled` ships the SLO alerts (API 5xx ratio, p95 read latency, oldest ready
  work item > 5 min, DEAD work items, pool with queued jobs and no runner, budget exceeded, LISTEN
  reconnects, Hikari pending); `metrics.dashboards.enabled` renders the four Grafana dashboards of
  `deploy/observability/dashboards/` as ConfigMaps for the Grafana sidecar (label `grafana_dashboard: "1"`).
- `otel.enabled` sends traces (and optionally metrics/logs) over OTLP to a collector; an example
  collector is `deploy/observability/otel-collector.yaml`. Resource attributes carry pod, namespace and
  `backoffice.role`.

## 8. Resource sizing

| Component | Small (1 owner, a few runs/day) | Busy (benchmarks with many attempts) |
|---|---|---|
| API | `mode: combined`, 1 replica 500m / 1.5 Gi | `mode: split`, api 3-12 x 1 CPU / 1-2 Gi (HPA) |
| Worker | - | 3-30 x 1 CPU / 1.5 Gi (KEDA on the work queue) |
| Web | 50m / 64-128 Mi (default) | 2 replicas |
| Sandboxes | runner fleet chart ([runner-fleet-install.md](runner-fleet-install.md)) | 2 vCPU / 4 Gi per sandbox by default policy |
| PostgreSQL | `resourcesPreset: small`, 8-20 Gi | `medium`, 50 Gi; artefacts and log events are stored in the database |
| Langfuse | ClickHouse alone wants 2 CPU / 4 Gi | see Langfuse sizing docs |

Any number of replicas is safe (ADR 0023): a run is advanced only by the worker holding its lease;
sessions, SSE fan-out (LISTEN/NOTIFY), login throttles and provider rate limits are shared through
PostgreSQL. Capacity guide and connection budget: [cloud-reference.md](cloud-reference.md#sizing-from-doc-09),
[runbooks/scaling.md](../runbooks/scaling.md).

## 9. Verifying

```bash
kubectl -n back-office get pods,svc,jobs
kubectl -n back-office rollout status deploy/back-office-api
kubectl -n back-office port-forward svc/back-office-api 8081:8080 &
curl -s http://localhost:8081/actuator/health/readiness          # {"status":"UP"}
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/api/v1/me   # 401 without a session: auth is on
curl -s -H "Authorization: Bearer $BO_PAT" http://localhost:8081/api/v1/dashboard | head -c 300   # with a PAT
kubectl -n back-office auth can-i create jobs --as system:serviceaccount:back-office:back-office-api   # no (ADR 0024)
kubectl -n back-office get job back-office-migrate -o jsonpath="{.status.succeeded}"          # 1 (until TTL cleanup)
kubectl -n back-office get scaledobject back-office-worker                                  # READY True
```

Then open the UI, log in with a bootstrap admin e-mail (you are redirected to the IdP and back), go to *Settings* and confirm the providers and integrations show
`configured: true`. With a runner pool installed ([runner-fleet-install.md](runner-fleet-install.md)),
start a workflow of a test project against a throw-away repository: a sandbox appears in the fleet's
sandbox namespace and its events stream into the run page. Finally
check that the CI client is served and that a CI token works from outside the cluster:

```bash
curl -fsS https://back-office.example.com/ci/backoffice-ci.sh | head -3
BACKOFFICE_URL=https://back-office.example.com BACKOFFICE_CI_TOKEN=bo_ci_... \
  sh backoffice-ci.sh signal smoke --status success     # "no run is waiting" is the expected answer
```

## 10. Uninstall

```bash
helm uninstall back-office -n back-office
kubectl -n back-office delete pvc -l app.kubernetes.io/instance=back-office     # database data!
kubectl -n back-office delete secret back-office-signing   # created by the hook, not by Helm - keep it while attestations must verify
kubectl delete namespace back-office
```

Secrets you created by hand are not removed by Helm.

## Where to look when it fails

- **`helm dependency build` fails** - no network to `registry-1.docker.io` (OCI) or
  `langfuse.github.io`; mirror the charts or vendor `charts/*.tgz`.
- **`Error: execution error ... secrets.apiKey / secrets.ciApiKey were removed`** - see
  [Upgrading from iteration 2](#upgrading-from-iteration-2).
- **`auth.oidc.issuerUri is required` / `auth.oidc.clientSecret or auth.oidc.existingSecret is required`** -
  configure the OIDC client (section 2a) or enable the bundled Keycloak.
- **API crashes with `Unable to resolve Configuration with the provided Issuer`** - the pod cannot reach
  the issuer or the discovery document announces another `issuer`. Test from inside:
  `kubectl exec deploy/back-office-api -- wget -qO- <issuer>/.well-known/openid-configuration`.
- **IdP error "invalid redirect_uri"** - the registered redirect URI differs from
  `<BACKOFFICE_PUBLIC_URL>/login/oauth2/code/oidc` (scheme, host, trailing slash) or the API builds
  `http://` URIs behind TLS (missing `X-Forwarded-Proto`).
- **Login succeeds at the IdP, then back on the login page** - the cookie is dropped (`auth.cookieSecure=true`
  over http) or `/login/oauth2` is not proxied to the API; `/login?error=not-invited` means the e-mail
  was not invited (`INVITE_ONLY`).
- **Bundled Keycloak pod stuck in `Init`** - the `create-schema` init container waits for PostgreSQL;
  `kubectl logs back-office-keycloak-0 -c create-schema`.
- **API pod `CrashLoopBackOff`** - `kubectl logs deploy/back-office-api --previous`. Usual causes:
  wrong `SPRING_DATASOURCE_*` (check the ConfigMap and the DB Secret key name), Flyway checksum
  mismatch after a downgrade, or the JVM OOM-killed (raise `api.resources.limits.memory`).
- **Readiness never green but logs look fine** - the startup probe budget is too short for the first
  Flyway run; raise `api.probes.startup.failureThreshold`.
- **`UPGRADE FAILED: pre-upgrade hooks failed`** - the migrate Job failed; the old version keeps running.
  `kubectl logs job/back-office-migrate`, then [runbooks/upgrade.md](../runbooks/upgrade.md).
- **App pods stuck in `Init:1/2`** - the migrate init container (bundled/CNPG database) waits or failed:
  `kubectl logs <pod> -c migrate`.
- **Pods `CreateContainerConfigError` on `back-office-signing`** - the signing-key hook Job failed
  (`kubectl get job -l app.kubernetes.io/component=signing`) or `signing.existingSecret` does not exist.
- **Worker count never changes** - KEDA missing or the ScaledObject not ready:
  [runbooks/scaling.md](../runbooks/scaling.md#keda-does-not-scale).
- **Agent steps stay QUEUED** - no runner pool is online: [runner-fleet-install.md](runner-fleet-install.md).
- **Runner push rejected / no merge request created** - the push token of the runner cluster
  (`gitCredentials.repoSecrets[].push`) needs `write_repository` on the target project; see [runner-job-failed](../runbooks/runner-job-failed.md).
  Jira `403`: the connection's account must see the project (**Test** on the connection shows missing
  permissions).
- **API / worker `CrashLoopBackOff` mentioning `BACKOFFICE_SECRETS_KEY` or an unknown key id** - connections
  exist but the key is missing, or an old key was removed from `secrets.connectionsPreviousKeys` before the
  re-wrap; restore it (section 2e).
- **CI job gets `401` / cannot reach the platform** - the CI token was revoked, or the runners cannot
  resolve `BACKOFFICE_URL` (the Ingress host); see [ci-integration.md](ci-integration.md).
- **Langfuse pods pending** - no StorageClass or the ClickHouse operator is missing
  (`kubectl get crd | grep clickhouse`).
- **Ingress returns 404 for `/api`** - the web pod proxies `/api` to `API_UPSTREAM`; check
  `kubectl exec deploy/back-office-web -- env | grep API_UPSTREAM` resolves to `http://back-office-api:8080`.
- Runbooks: [scaling](../runbooks/scaling.md), [upgrade](../runbooks/upgrade.md), [runner-job-failed](../runbooks/runner-job-failed.md), [stuck-pipeline-run](../runbooks/stuck-pipeline-run.md), [provider-errors](../runbooks/provider-errors.md).
