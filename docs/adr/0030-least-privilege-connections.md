# 0030 — Least-privilege connections: scoped, encrypted, capability-checked Jira credentials

- Status: accepted; amended by ADR 0035 (system connection, personal connections, scoped tokens)
- Date: 2026-10-08
- Deciders: owner
- Related: ADR 0013 (no SCM API), 0017 (deny by default), 0018 (audit log), 0029 (prompt-injection defences);
  docs/architecture/05-integrations.md, docs/manual/integrations-jira.md

## Context

Until now Jira was one platform-wide credential read from `JIRA_URL`, `JIRA_EMAIL` and `JIRA_API_TOKEN`. The
same account read issues, posted comments and moved issues for every project of the installation:

- a classic Atlassian API token carries every permission of its account; the account needed Browse Projects,
  Add Comments and Transition Issues in every Jira project any Back Office project touched, so a step that only
  reads an issue ran with the right to write to all of them;
- a leaked token, or an agent output that reaches a Jira step through prompt injection (ADR 0029), could act on
  any issue the bot could see, including projects unrelated to the run;
- project owners could not bring their own Jira account or site, and nobody could tell from Back Office
  whether the account was over-privileged (the "Test connection" button only called `/myself`);
- rotating the token meant editing a Kubernetes Secret and restarting pods; there was no expiry reminder;
- upcoming features (a Jira intake watcher, "approval before write") need to know which credential reads and
  which writes.

## Decision

1. **A connection registry** ("Connections") in the Projects context: `projects-core` holds the domain and use
   cases, `projects-adapters` the persistence (table `connection`, Flyway `V107`) and the Jira adapter. A
   connection has a type (`JIRA`), name, site URL (https, Jira Cloud), account e-mail, a write-only secret (a
   classic Atlassian API token), **capabilities**, an optional allow-list of Jira project keys, an optional
   sample project key for verification, the token's expiry date and the last verification result. Scope is
   `PLATFORM` (Administration → Connections, permission `admin.connections.manage`) or `PROJECT` (project page →
   Connections, permission `project.connections.manage`, built-in role `owner` only). Deleting a project deletes
   its connections (foreign key cascade).
2. **Capabilities, not credentials, are what steps ask for.** `ISSUE_READ` (Jira Browse Projects),
   `ISSUE_COMMENT` (Browse Projects + Add Comments) and `ISSUE_TRANSITION` (Browse Projects + Transition Issues);
   COMMENT and TRANSITION carry a `write` flag. The set is extensible. Task executors declare what they need:
   `FETCH_ISSUE` → `ISSUE_READ`, `NOTIFY` → `ISSUE_COMMENT`, `TRANSITION_ISSUE` → `ISSUE_TRANSITION` +
   `ISSUE_READ`; the palette exposes them as `requiredCapabilities`.
3. **Least-privilege selection.** A node may pin a connection (node configuration `jiraConnection` = connection
   id); a pinned connection must belong to the project or the platform and cover the required capabilities,
   otherwise the step fails (`PINNED_NOT_FOUND`, `PINNED_INSUFFICIENT`). Without a pin the candidates are
   **all** the project's and the platform's connections (the legacy credential included; amended
   2026-10-08, see below: originally the platform tier was only a fallback). A connection qualifies when its
   capabilities contain the required ones and its allow-list is empty or contains the Jira project key (the
   issue-key prefix at run time, the project's Jira key at design time). Among the qualifying ones the
   connection with the **fewest extra capabilities** wins; ties go to the project connection, then
   verification OK > WARNING > not verified > FAILED, then non-legacy before legacy, then name. The result is
   `OK`, `OVER_PRIVILEGED` (allowed, with a warning) or `NONE`. The workflow validator reports
   `PINNED_INSUFFICIENT` as an error, `NONE` and `PINNED_NOT_FOUND` as warnings (amended: saving is not
   blocked, the run start is) and `OVER_PRIVILEGED` as a warning on the node; the run log names the chosen
   connection and repeats the warning.
4. **Run-time restriction.** The Jira client a step receives is narrowed to that step's required capabilities and
   to the connection's allow-list: a Fetch step cannot comment even through a writer connection, and an issue of
   a Jira project outside the allow-list is refused.
5. **Secrets at rest use envelope encryption.** A random 256-bit data key per secret encrypts the token with
   AES-256-GCM (96-bit nonce, the connection id as associated data, so a ciphertext copied to another row does
   not decrypt). The data key is wrapped with the key-encryption key (KEK) from `BACKOFFICE_SECRETS_KEY`
   (`backoffice.secrets.key`, base64 of 32 bytes); each secret stores the KEK id (`BACKOFFICE_SECRETS_KEY_ID`,
   default `k1`). Core sees only the sealing port `SecretSealer`; decryption lives in the adapter
   (`AesGcmSecretCipher`) and is used only to open a per-connection Jira client. The secret is never returned
   by the API, never logged and never part of an audit record (the API returns `secretSet`).
6. **Key rotation without decrypting tokens to the database.** The new KEK becomes `BACKOFFICE_SECRETS_KEY` with a
   new id, the old one moves to `BACKOFFICE_SECRETS_PREVIOUS_KEYS` (`id:base64`, comma separated); on start the
   worker re-wraps every data key with the active KEK (audited `CONNECTION_SECRET_REWRAP`), after which the old
   key can be removed. The api and worker roles refuse to start when connections exist and the key is missing
   or a stored key id is unknown; without connections and without a key the app starts and creating a
   connection answers `409` "BACKOFFICE_SECRETS_KEY is not configured". The `local` profile has a fixed dev
   key; the Helm chart generates and keeps one (`secrets.connectionsKey`).
7. **Verification against Jira.** On create, update, rotate and on demand ("Test"): `GET /rest/api/3/myself`, then
   `GET /rest/api/3/mypermissions` for each allowed project key (or the sample key) with Browse Projects, Add
   Comments, Transition Issues, Edit, Delete, Assign and Create Issues, Administer Projects and Manage
   Watchers. Permissions the declared capabilities need but the account lacks are **missing** (status
   `FAILED`); permissions the account has beyond them are **excess** (status `WARNING`, "over-privileged"). A
   failed verification does not block saving. The result is stored on the connection.
8. **Legacy credential stays, read-only and last.** `JIRA_URL` / `JIRA_EMAIL` / `JIRA_API_TOKEN` appear as the
   platform connection "Jira (environment variables)" with all three capabilities (`legacy: true`); it is never
   editable, always chosen last, and Settings and Administration → Connections show a "migrate to scoped
   connections" notice. `JIRA_ACCEPTANCE_CRITERIA_FIELD` remains a global setting.
9. **Audit**: `CONNECTION_CREATE`, `CONNECTION_UPDATE`, `CONNECTION_ROTATE`, `CONNECTION_DELETE`,
   `CONNECTION_VERIFY`, `CONNECTION_SECRET_REWRAP`; details never contain the secret.
10. **Designed for, built in ADR 0033**: a Jira intake watcher that polls with `ISSUE_READ`-only connections,
    and "approval before write" that gates steps whose required capabilities have the `write` flag.

## Alternatives considered

- **Keep one environment credential, document least privilege.** Rejected: one account still has to hold the
  union of all permissions for all projects; nothing narrows a read step, nothing verifies the account, and
  project owners cannot bring their own credential.
- **Atlassian scoped API tokens, OAuth 2.0 (3LO) apps or Atlassian service accounts.** Not now. Scoped tokens and
  service-account credentials are accepted only through `https://api.atlassian.com/ex/jira/<cloudId>`, which
  changes every URL and the issue links Back Office stores; OAuth 3LO needs an app registration in the
  Atlassian developer console, a consent flow, refresh-token storage and per-site cloud ids. The connection
  model (type, site, capabilities, sealed secret) leaves room for a later `JIRA_OAUTH` or scoped-token type;
  the capability check and selection rule stay the same.
- **An external secret manager only (HashiCorp Vault, cloud KMS / secret managers).** Kept as a future option:
  it adds an operational dependency that small installations do not have. The sealing port `SecretSealer` and
  the adapter split allow a Vault- or KMS-backed implementation (or KMS-wrapped KEKs) later without changing the
  domain.
- **One Kubernetes Secret per connection.** Rejected: the API would need RBAC to create and read Secrets in its
  namespace (a broad privilege), compose installations would have no equivalent, and project owners would
  manage cluster objects indirectly. Database rows with envelope encryption work the same everywhere.
- **Encrypt with the PostgreSQL extension `pgcrypto`.** Rejected: the key would travel with every SQL statement
  (visible in `pg_stat_activity`, logs and pooler traces), managed databases do not always allow the extension,
  and key rotation would be a database operation. Encryption in the application keeps the key out of the
  database entirely.
- **A new bounded context (`connections` or `secrets`).** Rejected for now: the only consumers are the Jira task
  executors of the Projects context and the ownership and permissions are project-scoped; a new context would
  need its own Flyway range, ArchUnit rules and cross-context ports for no gain. The encryption port is generic,
  so a later `secrets` module can take it over.

## Consequences

- New migrations: projects `V107` (table `connection`), identity `V408` (adds `project.connections.manage` to the
  stored built-in `owner` role, audited `ROLE_UPDATE` by `system`). `maintainer` deliberately does not get it.
- New permissions: `admin.connections.manage` (PLATFORM, in `platform-admin`) and `project.connections.manage`
  (PROJECT, in `owner`). Lists are visible with `integrations.view` (platform) and `project.view` (project).
- New configuration: `BACKOFFICE_SECRETS_KEY` (required as soon as a connection exists), `BACKOFFICE_SECRETS_KEY_ID`,
  `BACKOFFICE_SECRETS_PREVIOUS_KEYS`; Helm values `secrets.connectionsKey`, `secrets.connectionsKeyId`,
  `secrets.connectionsPreviousKeys`. **Operators must back up the key**: losing it means re-entering every
  connection secret. The recovery switch `BACKOFFICE_SECRETS_ALLOW_UNREADABLE=true` lets the platform start with a
  new key so that the tokens can be replaced through the UI.
- Existing installations keep working through the legacy credential, with a migration notice and an
  `OVER_PRIVILEGED` warning on every Jira node until scoped connections exist. Migration: create reader and writer
  connections, run the designer validation, then remove `JIRA_API_TOKEN`.
- Jira nodes now fail at design time (validator error) and at run time when no connection qualifies, instead of
  failing with `401`/`403` from Jira.
- Verification calls Jira on every save; a slow or unreachable Jira makes saving slower but never blocks it.
- Jira steps no longer share one platform client: `IssueTrackerFactory` opens a client per connection, restricted
  to the step.

## Amendment (2026-10-08)

Decided together with ADR 0033 after the first installations used connections.

1. **A missing connection no longer blocks saving a workflow.** At save and validate time `NONE` (no
   qualifying connection) and `PINNED_NOT_FOUND` (the pinned connection no longer exists) are **warnings**
   ("... the workflow can be saved, but runs refuse to start until a connection exists").
   `PINNED_INSUFFICIENT` and a malformed connection id stay errors. **When a run starts**, every Jira step of
   the workflow must resolve to a usable connection; otherwise the start is refused with HTTP `409` and a
   message that names the step and links to the manual (`integrations-jira#jira-connections`). The step
   itself still fails at run time if the connection disappears later. Reason: the seeded workflows contain
   Jira steps, and installations without Jira could not save them; a workflow is a design, the credential is
   an operational prerequisite that belongs at run start. The consequence "Jira nodes now fail at design time
   (validator error)" above is replaced by "warn at design time, refused at run start".
2. **Least privilege across both tiers.** The selector no longer prefers the project tier and falls back to
   the platform. Among **all** project and platform connections that fit (capabilities and allow-list), the
   one with the fewest extra capabilities wins; ties prefer the project connection, then a successful
   verification (OK > WARNING > not verified > FAILED), then non-legacy, then name. A tight platform reader
   therefore beats a project writer for a read step. Project owners who want their own account used for
   reads add a project reader; it wins the tie.
3. **Connections in the navigation.** **Connections** is listed in the Administration group of the
   navigation rail and in the command palette for holders of `admin.connections.manage`.
4. **Test before saving.** `POST /api/v1/admin/connections/test` and
   `POST /api/v1/projects/{projectId}/connections/test` verify an unsaved draft (`/myself` +
   `/mypermissions`) without persisting anything. The token is sealed in memory only; when an existing
   connection is edited without a new token, the stored one is used. Audited `CONNECTION_TEST` (never with
   the secret). The add/edit dialog offers it as **Test before saving**.
5. **Token expiry reminders.** A daily job on one worker replica (cron
   `backoffice.connections.expiry-reminder-cron`, default `0 0 7 * * *` UTC, ShedLock
   `connection-expiry-reminder`) finds connections whose token expires within 14 days or has expired and
   publishes `ConnectionExpiring` once per expiry date (column `connection.expiry_reminded_for`, audited
   `CONNECTION_EXPIRY_REMINDER`). `GET /api/v1/connections/expiring` lists them for the caller (platform
   connections need `admin.connections.manage`, project connections `project.connections.manage`); the UI
   shows a **Your turn** card ("The Jira token of <name> expires in N days" / "... has expired") and a
   desktop notification type
   "Connection expiring" (on by default for administrators, off otherwise). The list badge **expiring soon**
   keeps its 30-day horizon.
6. **Events in the contract.** `DomainEventEnvelope.type` in the OpenAPI contract lists `BudgetAlert`,
   `BudgetExceeded` and `ConnectionExpiring` (see the update in ADR 0032).
