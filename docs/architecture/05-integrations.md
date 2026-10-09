# 05 — Integrations

Back Office never calls a source-control API (ADR 0013). Repositories are reached only by the agent
runner over git, and by the project's own CI, which calls Back Office.

## CI client: `backoffice-ci.sh`

Served by the platform at `GET /ci/backoffice-ci.sh` (public, versioned with the app) and attached to
GitLab releases. POSIX sh; needs `curl`, `git`, and `jq` if available (falls back to `sed` parsing).

Environment:

| Variable | Meaning |
|---|---|
| `BACKOFFICE_URL` | base URL of the platform |
| `BACKOFFICE_CI_TOKEN` | project CI token issued in the UI (Project → Settings → CI tokens) |
| `BACKOFFICE_REVIEW_TIMEOUT` | max seconds to wait for a run (default 1800) |
| `BACKOFFICE_ADVISORY` | `true` → never fail the job (overrides the entrypoint mode) |
| `BACKOFFICE_DIFF_MAX_BYTES` | cap for the uploaded diff (default 2 MB; larger diffs are truncated with a marker) |

Commands:

| Command | Behaviour | Exit code |
|---|---|---|
| `run <entrypoint> [--wait] [--diff auto\|none] [--var k=v]...` | `POST /api/v1/ci/executions` with the CI context, the diff (`auto`: MR diff `git diff $BASE...HEAD` or branch diff against the default branch) and variables; with `--wait` polls `GET /api/v1/ci/executions/{id}` every 10 s and prints the summary and findings | 0 when `gateResult == PASS` or mode is ADVISORY, 1 on `FAIL`, 2 on transport/auth errors, 3 on timeout |
| `review` | alias for `run code-review --wait --diff auto` | as above |
| `signal <name> --status success\|fail [--run <id>] [--message "..."]` | `POST /api/v1/ci/signals`; `--run` defaults to the `Back-Office-Run` git trailer of `HEAD`, else the run is matched by branch | 0 when a run was resumed or none was waiting, 2 on errors |
| `status <executionId>` | prints one execution | 0 |

CI context sent with every call (`CiContext`): `provider` (`GITLAB`, `GITHUB`, `OTHER`, detected from
`CI_*` variables), `projectPath`, `repositoryUrl` (script 2.1.0+), `pipelineId`, `jobId`, `jobUrl`, `ref`,
`commitSha`, `defaultBranch`, `mergeRequest {iid, title, description, sourceBranch, targetBranch, url, author}`
when present, `actor`.

**Which repository (ADR 0034).** A project can have several repositories, so an execution is mapped to one.
`repositoryUrl` is GitLab's `CI_PROJECT_URL`, on GitHub `$GITHUB_SERVER_URL/$GITHUB_REPOSITORY`, elsewhere the
`origin` remote with credentials stripped. Back Office compares it with the project's repository URLs after
normalisation: scheme, credentials, a `.git` suffix and a trailing slash are ignored, the host is
case-insensitive, and the scp form `git@host:group/proj` equals `https://host/group/proj`. The match is stored
as `CiExecution.repositoryKey` and passed to the run as the parameter `repository` (= key). An unknown URL is
refused with HTTP 400 that lists the project's repositories (`key: url`). Older scripts send no URL: their
`projectPath` is matched against the path suffix of the repository URLs; no match means no repository (the
execution still runs). An entrypoint with `repository` set accepts only executions of that repository;
another or an unknown repository is refused with 400.

Reference template: `ci-templates/backoffice.gitlab-ci.yml` defines `backoffice:review` (merge
requests, blocking) and `backoffice:signal-tests` (after the test stage, reports success/fail to a
waiting workflow). Projects `include:` the template and set two CI variables (the token Masked, not Protected: merge request pipelines of
feature branches do not receive protected variables).

## CI endpoints and tokens

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /api/v1/ci/executions` | CI token | start the workflow mapped to an entrypoint; returns `CiExecution` |
| `GET /api/v1/ci/executions/{id}` | CI token (same project) or owner key | poll |
| `POST /api/v1/ci/signals` | CI token | deliver a signal to a waiting run |
| `GET /ci/backoffice-ci.sh` | none | the client script |

Tokens: `POST /api/v1/projects/{id}/ci-tokens` returns the plaintext once (`bo_ci_` + 40 random chars);
the platform stores `SHA-256(token)`, `name`, `createdAt`, `lastUsedAt`, `revokedAt`. Authentication is a
Spring Security filter that resolves `Authorization: Bearer bo_ci_...` to `ROLE_CI` with the project id
as principal; controllers check that the entrypoint or run belongs to that project.

Idempotency: an execution for the same `(project, entrypoint, commitSha, mergeRequest.iid)` within 24 h is
returned instead of created, unless `--var force=true`.

## Merge requests without an API

The runner pushes with git push options: `-o merge_request.create -o merge_request.target=<branch>
-o merge_request.title=<title> -o merge_request.description=<text> [-o merge_request.draft]`. GitLab
creates the MR and prints its URL in the push output, which the runner captures into
`RunnerResult.mergeRequestUrl`. Every runner commit carries the trailer `Back-Office-Run: <runId>`. A step that
pushes to several repositories (ADR 0034) pushes the same job branch to each and opens one merge request per
repository, each targeting that repository's default branch; the URLs come back in
`RunnerResult.repositories[].mergeRequestUrl`.

## Jira: least-privilege connections (ADR 0030, ADR 0035)

Jira is reached through **connections**: named, scoped credentials with declared capabilities, stored
encrypted in the Projects context. A connection belongs to the platform, to one project, or to one person
(personal connection, ADR 0035). Each project has a **system connection** and a list of further connections; each
Jira step says whom it **acts as**. The former single credential from `JIRA_URL` / `JIRA_EMAIL` /
`JIRA_API_TOKEN` survives as a read-only legacy connection. User-facing set-up:
[manual/integrations-jira.md](../manual/integrations-jira.md#jira-connections).

### How Back Office talks to Jira (ADR 0035)

The backend calls the **Jira Cloud REST API v3 directly over HTTPS** (`JiraIssueTracker`, Spring `RestClient`,
HTTP Basic with the account e-mail and the API token). There is no MCP server, no CLI and no Jira credential in any
runner sandbox.

| What | Endpoint | Base URL |
|---|---|---|
| Fetch issue | `GET /rest/api/3/issue/{key}?fields=...` | classic token: the site (`https://acme.atlassian.net`); scoped token: `https://api.atlassian.com/ex/jira/{cloudId}` |
| Intake watcher, verification search | `GET /rest/api/3/search/jql` | same |
| Notify Jira (after approval) | `POST /rest/api/3/issue/{key}/comment` (ADF body) | same |
| Transition issue (after approval) | `GET` then `POST /rest/api/3/issue/{key}/transitions` | same |
| Verification | `GET /rest/api/3/myself`, `GET /rest/api/3/mypermissions` (+ scoped: comments / transitions of a sample issue) | same |
| Cloud id of a site (scoped tokens) | `GET /_edge/tenant_info` (public) | the site |

Issue links stored in runs (`issue.url`) always use the site URL. Why direct REST is the right fit here:

- **deterministic steps**: every Jira step is one or two known endpoints with inputs rendered by Back Office, not a
  tool call a model chooses;
- **least privilege per step**: the client a step gets is narrowed to the step's capabilities and the connection's
  allowed Jira projects (`ScopedIssueTracker`) before any request leaves;
- **approval before writes** (ADR 0033): Back Office knows the exact request, shows it, hashes it and sends exactly
  that after approval;
- **no extra processes or credentials**: nothing to deploy or sandbox, tokens sealed in the database and opened in
  the backend for one call only;
- **testable**: WireMock for the adapter, pure domain code for selection, verification and approvals.

MCP would make sense for an **agent that explores Jira on its own** (searching related issues while it works). The
safe pattern for that is a narrow **Back-Office-brokered tool** (the agent asks Back Office, e.g. through the MCP
server of ADR 0025 or the runner callback API; Back Office runs the call with the same selection, capability
narrowing, allowed projects, audit and, for writes, the ADR 0033 approval, and returns the result as an untrusted
data block, ADR 0029). Atlassian MCP credentials or any Jira token never go into a sandbox.

### Model

| Field | Notes |
|---|---|
| `id`, `scope`, `projectId` | `PLATFORM` (projectId null), `PROJECT` or `USER` (personal, ADR 0035); table `connection` (Flyway `V107`, `V110`), FK to the project with `ON DELETE CASCADE` |
| `ownerUserId`, `ownerName` | `USER` only: the owner (and their name at creation, for the administrators' list) |
| `type` | `JIRA` (Jira Cloud, REST API v3, HTTP Basic with e-mail + API token) |
| `tokenKind` | `CLASSIC` (site URL, every permission of the account; needs `classicTokenAcknowledged`) or `SCOPED` (Atlassian API token with scopes, called through `https://api.atlassian.com/ex/jira/{cloudId}`) |
| `cloudId`, `tokenScopes` | `SCOPED` only: the site's cloud id (discovered on save from `<site>/_edge/tenant_info` or entered) and the scopes declared for the token |
| `name`, `siteUrl`, `accountEmail` | `siteUrl` must be `https://`; names unique per owner (platform, project or user) |
| secret | Atlassian API token (classic or scoped), **write-only**: sealed on write, never returned (`secretSet` only), never logged or audited |
| `capabilities` | subset of `ISSUE_READ`, `ISSUE_COMMENT`, `ISSUE_TRANSITION`; COMMENT and TRANSITION carry `write = true`; extensible |
| `allowedProjectKeys` | optional allow-list of Jira project keys, enforced at run time |
| `verifyProjectKey` | sample key used for verification when the allow-list is empty |
| `secretRotatedAt`, `secretExpiresAt`, `expiryStatus` | `EXPIRING_SOON` within 30 days of `secretExpiresAt`, `EXPIRED` after it |
| `expiryRemindedFor` | the expiry date the last reminder was sent for (column `expiry_reminded_for`, `V108`), so each date is reminded once |
| `createdBy/At`, `updatedBy/At`, `lastVerifiedAt`, verification | the last verification result (status, account, per-project granted / missing / excess, messages) |

Capability → Jira project permission:

| Capability | Jira permissions | Needed by |
|---|---|---|
| `ISSUE_READ` | Browse Projects | `FETCH_ISSUE` (Fetch issue) |
| `ISSUE_COMMENT` | Browse Projects + Add Comments | `NOTIFY` (Notify Jira) |
| `ISSUE_TRANSITION` | Browse Projects + Transition Issues | `TRANSITION_ISSUE` (Transition issue, together with `ISSUE_READ`) |

Executors declare their required capabilities; the task palette exposes them as `requiredCapabilities`.

Scopes of a scoped token per capability (`JiraScopes`; a capability is covered by the classic set or the granular
set; ADR 0035 lists the recommended extra read scopes):

| Capability | Classic | Granular (required) |
|---|---|---|
| verification (`myself`) | `read:jira-user` | `read:user:jira` |
| `ISSUE_READ` | `read:jira-work` | `read:issue:jira`, `read:issue-details:jira`, `read:jql:jira` |
| `ISSUE_COMMENT` | `read:jira-work`, `write:jira-work` | `write:comment:jira`, `read:comment:jira` |
| `ISSUE_TRANSITION` | `read:jira-work`, `write:jira-work` | `write:issue:jira`, `read:issue.transition:jira`, `read:status:jira` |

### Ports and adapters

| Piece | Module | Role |
|---|---|---|
| `Connection`, `ConnectionCapability` (with `write` flag and Jira permissions), `SealedSecret`, `ConnectionSelector`, `ConnectionVerificationPolicy` | `projects-core` domain | pure model, least-privilege choice, and turning a Jira probe (granted permissions) into missing / excess / status |
| Connection use cases (manage platform / project connections, verify, resolve, list usable) | `projects-core` application | `AuthorizationGate` + audit; secrets enter only as plaintext to be sealed |
| `SecretSealer` | `projects-core` port (generic, not Jira-specific) | `seal(plaintext, associatedData)` → sealed envelope; core never decrypts |
| `AesGcmSecretCipher` | `projects-adapters` | implements `SecretSealer`, and the adapter-only opening used to build clients; KEK handling, re-wrap |
| `IssueTrackerFactory` | `projects-core` port, `projects-adapters` implementation | opens a per-connection `JiraIssueTracker`, restricted to the step's capabilities and the allow-list |
| `JiraIssueTracker` | `projects-adapters` (`adapter/out/jira`) | Jira Cloud REST v3: `fetch(issueKey)`, `comment(issueKey, body)`, `transition(issueKey, toStatus)`, verification calls |
| Jira probe | `projects-adapters` (Jira adapter) | `/myself` + `/mypermissions` calls producing a `JiraProbe` (below) |

Port `IssueTracker`: `fetch(issueKey)`, `search(jql)`, `watch(jql, max)` (intake watcher: key, status,
updated, created and the status changes of the changelog only), `comment(issueKey, body)`,
`transition(issueKey, toStatus)`. Issue snapshot exposed to templates: `key, summary, description (ADF flattened to markdown),
type, priority, status, labels, components, acceptanceCriteria (custom field id from
JIRA_ACCEPTANCE_CRITERIA_FIELD, still a global setting), url`.

### Secrets at rest

Envelope encryption, per secret:

1. a random 256-bit **data key** encrypts the token with AES-256-GCM (96-bit random nonce); the connection
   id is the GCM associated data, so a ciphertext copied to another row fails authentication;
2. the data key is wrapped (AES-GCM) with the **key-encryption key** (KEK) from `backoffice.secrets.key`
   (`BACKOFFICE_SECRETS_KEY`, base64 of 32 bytes);
3. the row stores a `SealedSecret`: the ciphertext (with its nonce), the wrapped data key and the KEK id
   (`BACKOFFICE_SECRETS_KEY_ID`, default `k1`).

Rotation: the new KEK becomes `BACKOFFICE_SECRETS_KEY` with a new id and the old one is listed in
`BACKOFFICE_SECRETS_PREVIOUS_KEYS` (`id:base64,...`). On start the worker re-wraps every data key that
is not under the active KEK (only the 32-byte data key is re-encrypted; tokens are never written
decrypted), audited `CONNECTION_SECRET_REWRAP`; then the previous key can be dropped.

Startup check (api and worker roles): connections exist and the key is missing, or a stored KEK id is
neither active nor previous → startup fails with a message naming the variable. No key and no connections →
the app starts, `POST .../connections` answers `409` "BACKOFFICE_SECRETS_KEY is not configured". The `local`
profile has a fixed dev key. Operator detail: [guides/configuration.md](../guides/configuration.md#connection-secrets-adr-0030).

### Selection: system connection, Act as, least privilege (ADR 0030, ADR 0035)

Input: required capabilities, the project (its `jiraConnections`: system connection + ordered list), the Jira
project key (issue-key prefix at run time; the project's Jira project key at design time), the node configuration
(`jiraConnection`, `fallbackToSystem`, `allowActingUser`, stored in `overrides.config`) and, at run time, the
**acting user**: the approver of the write, or the person who started the run (`startedBy` of kind `USER` /
`PERSONAL_TOKEN`; none for the intake watcher, CI and the system).

| `jiraConnection` | Rule |
|---|---|
| missing / `SYSTEM` | the project's system connection when it covers the step and allows the key; else least privilege (below) among the project's list (system + further connections); `NONE` when nothing fits. A project without a system connection uses the ADR 0030 rule over **all** project and platform connections (legacy included). |
| a connection id | **pinned**: must be one of this project's or a platform connection (incl. legacy; never personal) → else `PINNED_NOT_FOUND`; capabilities ⊇ required and allow-list → else `PINNED_INSUFFICIENT` |
| `ACTING_USER` | least privilege among the acting user's own personal connections; no acting user → `NO_ACTING_USER`; none fits → `NO_PERSONAL_CONNECTION`; with `fallbackToSystem` the `SYSTEM` rule is used instead (read steps automatically, writes when the approver chooses it) |

Least privilege among candidates: qualifies = capabilities ⊇ required **and** (allow-list empty **or** contains
the key); fewest extra capabilities wins; ties: project before platform, then verification `OK` > `WARNING` > not
verified > `FAILED`, then non-legacy before legacy, then name. Result `OK`, `OVER_PRIVILEGED` (allowed; extra
capabilities listed in `excess`), `NONE`, plus `fallback: true` when the system connection did not fit.

Personal connections are never candidates of the `SYSTEM` rule, the ADR 0030 rule, a pin or the intake watcher,
and a person's connection is used only for that person's own action (their approval, their run start) or, read-only,
by that person's own personal intake rules (ADR 0038, `JiraConnectionAccess.openOwnReader`).

`GET /projects/{id}/connections/resolve` returns this result for the designer (system or pinned; the designer
explains `ACTING_USER` itself and resolves the system connection as its fallback). The workflow validator
(designer and save), field `task.config.jiraConnection`: `NONE` and `PINNED_NOT_FOUND` are **warnings**
("...; the workflow can be saved, but runs refuse to start until a connection exists"),
`PINNED_INSUFFICIENT` and a malformed connection id are errors, `OVER_PRIVILEGED` is a warning "uses a
connection with more rights than needed (...)". **Run start** (`RunService`, every trigger) checks every
Jira step of the definition and refuses with `409` `InvalidStateException` "workflow X cannot start:
<step>: ... (manual: integrations-jira#jira-connections)" when one has no usable connection; `ACTING_USER` steps
are checked against the person starting the run unless they are writes that wait for approval (the approver is
not known yet). The validator also warns about an `ACTING_USER` read step without fallback in a workflow with a
`JIRA_TRANSITION` trigger (watcher runs have no acting person).

At run time the executor logs `INFO Jira connection: <name> (<scope>)`, logs the over-privileged warning
as `WARN` ("... extra: ISSUE_COMMENT, ISSUE_TRANSITION - create a connection with only ISSUE_READ"), or
fails the node on `NONE` with "no Jira connection with capabilities [ISSUE_READ] for project <KEY> (Jira
project SHOP); add one under Project → Connections or Administration → Connections". The client handed
to the step only allows the operations of its required capabilities (a Fetch step cannot comment through
a writer connection) and refuses issues whose project is not in the allow-list.

### Verification

Run on create, update and rotate (failure does not block saving), on `POST .../verify` ("Test"), and on
an unsaved draft with `POST .../connections/test` ("Test before saving": the draft's token is sealed in
memory only and nothing is persisted; editing without a new token uses the stored one; audited
`CONNECTION_TEST`):

1. `GET /rest/api/3/myself`: `401` → `AUTHENTICATION_FAILED`, network error → `SITE_UNREACHABLE`, other
   status → `HTTP_ERROR`;
2. for each allowed project key, else the sample key (`verifyProjectKey` or the request's `projectKey`):
   `GET /rest/api/3/mypermissions?projectKey=<key>&permissions=BROWSE_PROJECTS,ADD_COMMENTS,TRANSITION_ISSUES,EDIT_ISSUES,DELETE_ISSUES,ASSIGN_ISSUES,CREATE_ISSUES,ADMINISTER_PROJECTS,MANAGE_WATCHERS`
   (`404` → `PROJECT_NOT_FOUND`);
3. **missing** = permissions needed by the declared capabilities but not granted → `ERROR`
   `MISSING_PERMISSIONS`, status `FAILED`; **excess** = granted permissions beyond what the capabilities
   need → `WARNING` `EXCESS_PERMISSIONS` ("over-privileged ..."), status `WARNING`; no key to check →
   `WARNING` `NO_PROJECT_TO_CHECK`.

**Scoped tokens** (ADR 0035): the calls go to the API gateway. `mypermissions` answered `401`/`403` is only a
warning (the token may lack the scope to read permissions). Then, on the first checked project, the read endpoints
each declared capability needs: `GET /search/jql?maxResults=1` (`ISSUE_READ`, also yields a sample issue), `GET
/issue/{sample}/comment?maxResults=1` (`ISSUE_COMMENT`) and `GET /issue/{sample}/transitions` (`ISSUE_TRANSITION`);
`401`/`403` → `MISSING_SCOPES` (error). Writes are never tried. The declared scopes are compared with the scope
table: an uncovered capability is `MISSING_SCOPES`, a declared `write:*` / `delete:*` / `manage:*` scope no
capability needs is `EXCESS_SCOPES` (warning), `write:jira-work` with a write capability is `BROAD_SCOPE`
(warning). Account permissions beyond the capabilities are `INFO` for scoped tokens (the scopes bound the token)
and `WARNING` for classic ones.

The result is stored on the connection (`lastVerifiedAt`). For the legacy connection it is returned but
not stored.

### Legacy credential

When `JIRA_URL`, `JIRA_EMAIL` and `JIRA_API_TOKEN` are set, the registry adds a synthetic platform
connection "Jira (environment variables)" (`legacy: true`, all three capabilities, not editable, not
stored). It is always chosen last, so every Jira node using it is `OVER_PRIVILEGED` unless the step needs
all capabilities. `IntegrationsStatus.jira` reports `legacy` and `platformConnections`; Settings and
Administration → Connections show a migration notice.

### REST and permissions

| Endpoint | Permission |
|---|---|
| `GET /admin/connections`, `GET /admin/connections/{id}` | `integrations.view` |
| `POST/PUT/DELETE /admin/connections[/{id}]`, `PUT .../{id}/secret`, `POST .../{id}/verify`, `POST /admin/connections/test` | `admin.connections.manage` |
| `GET /projects/{projectId}/connections[/{id}]`, `.../usable`, `.../resolve` | `project.view` |
| `POST/PUT/DELETE /projects/{projectId}/connections[/{id}]`, `.../secret`, `.../verify`, `POST /projects/{projectId}/connections/test` | `project.connections.manage` |
| `GET /connections/expiring` | filtered per caller: platform connections need `admin.connections.manage`, project connections `project.connections.manage`, personal ones their owner |
| `GET/POST /me/connections`, `GET/PUT/DELETE /me/connections/{id}`, `.../secret`, `.../verify`, `POST /me/connections/test` | `connections.personal` (people only); another user's connection answers `404` |
| `GET /admin/connections/personal` | `admin.connections.manage`: metadata only (no e-mail, no use) |
| `GET /projects/{projectId}/jira-connections` / `PUT` | `project.view` / `project.connections.manage` (audited `PROJECT_JIRA_CONNECTIONS_UPDATE`) |
| `GET /connections/jira-scopes` | any signed-in user (the scope table) |
| `GET /approvals/{id}/write-identity` | `project.view`: the identities the caller could approve a write as |
| `GET /projects/{projectId}/jira-intake` | `project.view` |
| `PUT /projects/{projectId}/jira-intake`, `POST /projects/{projectId}/jira-intake/poll` (`202`) | `project.manage` |

Audit codes: `CONNECTION_CREATE`, `CONNECTION_UPDATE`, `CONNECTION_ROTATE`, `CONNECTION_DELETE`,
`CONNECTION_VERIFY`, `CONNECTION_TEST`, `CONNECTION_EXPIRY_REMINDER`, `CONNECTION_SECRET_REWRAP`; details
never contain the secret. **Connections** is in the Administration group of the navigation rail and in the
command palette for holders of `admin.connections.manage`.

### Token expiry reminders

A daily job on one worker replica (`@Scheduled` cron `backoffice.connections.expiry-reminder-cron`,
default `0 0 7 * * *`, zone UTC, cluster lock `connection-expiry-reminder`) selects connections whose
`secretExpiresAt` is within 14 days or past and whose `expiryRemindedFor` differs from that date,
publishes `ConnectionExpiring` (outbox, SSE) and stores the date, audited `CONNECTION_EXPIRY_REMINDER`.
The UI shows a **Your turn** card from `GET /connections/expiring` and a desktop notification type
"Connection expiring" (ADR 0032). The list badge `EXPIRING_SOON` keeps its 30-day horizon.
`DomainEventEnvelope.type` lists `ConnectionExpiring`, `BudgetAlert` and `BudgetExceeded`.

### Jira intake watcher (ADR 0033)

Per project, table `jira_intake` (`V108`): `enabled`, `pollIntervalMinutes` (1–1440, default 5),
`workflowId`, `cursor` (start of the last successful poll), last poll / success / error, consecutive
failures, next poll, started total. The JQL filter is the project's `issueTracker.jql`.

| Piece | Where | Role |
|---|---|---|
| `JiraIntakeService` | `projects-core` application | configure (`JIRA_INTAKE_UPDATE`), poll now (`JIRA_INTAKE_POLL_NOW`), `due()`, `poll(projectId)` (`@SystemOperation`) |
| `JiraIntakePlanner` | `projects-core` domain service | JQL, window, mode, dedupe keys, events from the changelog |
| `JiraIntakeJobs` | `projects-adapters` (`adapter/out/scheduling`) | scan every `backoffice.jira-intake.scan-delay` ms (default 30000) under ShedLock `jira-intake-scan`, enqueues `JIRA_INTAKE_POLL` per due project (dedupe key `JIRA_INTAKE_POLL:<projectId>`); the work handler runs one poll |
| `JiraIssueTracker.watch` | `projects-adapters` | `GET /rest/api/3/search/jql?fields=status,updated,created&expand=changelog`, pages of 50 via `nextPageToken`, at most 100 issues |

Poll:

1. Workflow checks: it exists, is enabled and has a `JIRA_TRANSITION` trigger; otherwise `lastError`.
2. Connection: `ConnectionSelector` for `{ISSUE_READ}` (cross-tier least privilege). `NONE` → no Jira call,
   `lastError` "refused: ..."; `OVER_PRIVILEGED` → poll with a warning. The client is narrowed to
   `ISSUE_READ` and the allow-list, which wraps the query as `project in (KEYS) AND (...)`.
3. No `cursor` yet (first poll, re-enable, workflow change) → baseline only: store the cursor, start
   nothing.
4. JQL: `project = "<key>" AND (<jql without ORDER BY>) AND <window> ORDER BY updated ASC`, window
   `status CHANGED TO "<status>" AFTER -<n>m` (transition mode, trigger with status) or `created >= -<n>m`
   (new-issue mode); `n` = minutes since `cursor` + 2 (min 2, max 7 days). Relative dates avoid the Jira
   user's time zone.
5. Events and dedupe keys: transition mode `T:<issue>:<changelog history id>` of the latest change into the
   status (fallback `T:<issue>:<status>:<updated millis>`), new-issue mode `N:<issue>`. Each start first
   inserts the key into `jira_intake_claim` (primary key project + key, wins on exactly one replica), then
   `RunService.startForJira` (actor "Jira intake watcher", trigger `JIRA`, audit `RUN_START` with
   `trigger=JIRA`, `intakeDedupeKey`). A failed start releases the claim. Claims older than 30 days are
   purged.
6. At most 20 starts per poll; with more events the cursor is not advanced.
7. Errors (`429`, `5xx`, others): `lastError`, `consecutiveFailures + 1`, next poll after interval ×
   2^failures, capped at 1 h; a longer `Retry-After` wins.

Only key, status, `updated`, `created` and changelog status items are read; issue text never enters the
watcher (ADR 0029). Known limit: without a changelog, a leave-and-re-enter within one window may be
missed.

### Personal intake rules (ADR 0038)

Next to the project watcher, every user may keep **personal intake rules** (`PersonalIntakeRule`, table
`personal_intake_rule`, V112; use case `ManagePersonalIntake` under `/me/intake`, permission `connections.personal`).
A rule names one of the owner's personal connections with `ISSUE_READ`, a JQL, an interval (1–1440 minutes) and the
default workflow (one the owner may start). The scheduler (`PersonalIntakeScheduler`, ShedLock
`personal-intake-scan`, every `backoffice.personal-intake.scan-delay`) enqueues `PERSONAL_INTAKE_POLL` (one live item
per rule); a poll opens the owner's connection through `JiraConnectionAccess.openOwnReader` (refused for a connection
that is not personal, not the owner's or cannot read), runs `GET /rest/api/3/search/jql` with the rule's JQL
(`ORDER BY updated DESC` appended unless the JQL orders itself, at most 50 issues, fields
`summary,issuetype,status,priority`) and inserts one `intake_proposal` per new issue key (`UNIQUE (rule_id, issue_key)`,
`ON CONFLICT DO NOTHING`). "New" = not seen by the rule before, whatever made the issue match. The first poll stores
the current matches as `BASELINE` unless `includeExisting`. New proposals publish `IntakeProposalsFound` with
`ownerUserId`, so `EventStreamController` delivers it to the owner only. Errors back off like the project watcher
(interval doubled per failure up to 1 h, `Retry-After` honoured).

`POST /me/intake/proposals/{id}/start` calls `StartRun.start` as the owner (`run.start` checked, `startedBy` = the
owner, trigger `MANUAL`, parameter `intakeProposalId`), so `ACTING_USER` steps may use the owner's personal connection.
Summaries are display-only (escaped by Angular); the run reads the issue through its Fetch issue step (ADR 0029).


### Approval before external writes (ADR 0033)

`NOTIFY` and `TRANSITION_ISSUE` (required capabilities with the `write` flag; palette `externalWrite: true`)
read node configuration `approval` (`REQUIRED` default, missing = `REQUIRED`, `NOT_REQUIRED`; anything else
is a validator error) and `approvers` (same shape as gate approvers; default project roles owner and
maintainer with `run.approve`, accountable owner).

- `REQUIRED`: the executor renders the comment (templates + sanitising) or takes `toStatus`, checks that a
  usable connection exists, and returns `NodeResult(pendingWrite = ...)` without writing. The engine
  stores it as `pendingWrite` and pauses at `<nodeId>::confirm` (see 03-pipelines-and-agents.md); an
  approval of kind `WRITE_CONFIRMATION` stores the preview in `approval_request.write_preview` (action,
  issue key, comment or status, connection name, `contentSha256`). Approve → exactly the preview is
  written; reject → port `failure`, "write rejected by <name>, nothing was written (...) - <comment>".
- `NOT_REQUIRED`: written immediately, run-log warning; validator warning on the node.
- Identity (ADR 0035): the preview carries `connectionMode`, `personalAllowed`, `systemAllowed` and
  `defaultIdentity`; the approver's `Decision.writeAs` (`SYSTEM` / `PERSONAL`) decides who writes. `PERSONAL`
  writes through the approver's own fitting personal connection (refused with `409` when they have none); a step
  in `ACTING_USER` mode without `fallbackToSystem` cannot be approved with `SYSTEM` (`400`). With `NOT_REQUIRED`
  an `ACTING_USER` write uses the person who started the run.
- Audit: `RUN_WRITE_DECIDED` (action, issueKey, toStatus, contentSha256, writeAs), `EXTERNAL_WRITE` (action, issue,
  connection, contentSha256, approval `APPROVED` / `NOT_REQUIRED`, approvedBy, result `WRITTEN` /
  `FAILED`, identity `SYSTEM` / `PERSONAL`, via "personal connection of X (...)", personalOwnerId).
- `RunWaitingForHuman.kind` = `GATE` / `WRITE_CONFIRMATION`; the desktop notifier uses it for the title
  "Confirm a Jira write".

## LLM providers (Spring AI)

`SpringAiLlmGateway` (module `llm-gateway`) keeps one `ChatModel` per configured provider and builds a
`ChatClient` per call with the profile's options:

| Provider | Spring AI starter | Options mapping |
|---|---|---|
| ANTHROPIC | `spring-ai-starter-model-anthropic` | `thinkingAdaptive()`, `effort` from `reasoning`, `maxTokens` |
| OPENAI | `spring-ai-starter-model-openai` | `reasoningEffort`, `maxCompletionTokens` |
| GOOGLE | `spring-ai-starter-model-google-genai` | `thinkingLevel`, `maxOutputTokens` |
| MISTRAL | `spring-ai-starter-model-mistral-ai` | `reasoningEffort`, `maxTokens` |
| OLLAMA | `spring-ai-starter-model-ollama` | `numPredict`, `enableThinking` |
| OPENAI_COMPATIBLE | OpenAI starter with custom `baseUrl` (OpenRouter, Groq, vLLM…) | as OPENAI |

Auto-configuration of a provider is enabled only when its key is present; the gateway constructs models
explicitly so several providers coexist. Token usage is read from `ChatResponse.metadata.usage` (plus
native cache tokens for Anthropic) and recorded through `UsageLedger`.

## Observability: OpenTelemetry and Langfuse

Spring AI emits `gen_ai.client.operation` observations with `gen_ai.usage.*` tokens. The `app` module
bridges Micrometer tracing to OTLP. When `observability.langfuse.enabled=true` the OTLP endpoint is
Langfuse's `/api/public/otel/v1/traces` with basic auth from public/secret keys; otherwise any OTLP
collector works. Span attributes added by the gateway: `backoffice.run_id`, `backoffice.node_id`,
`backoffice.benchmark_id`, `backoffice.attempt_id`, `backoffice.cost_usd`.
