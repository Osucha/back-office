# 0035 — Jira connection model: system connection, personal connections, "Act as", scoped API tokens; direct REST API v3

- Status: accepted
- Date: 2026-10-09
- Deciders: owner
- Related: ADR 0017 (deny by default), 0018 (audit), 0029 (prompt-injection defences), 0030 (least-privilege
  connections, amended), 0033 (approval before external writes); docs/architecture/05-integrations.md,
  docs/manual/integrations-jira.md (`jira-overview`, `jira-system-connection`, `jira-personal-connections`,
  `jira-act-as`, `jira-scoped-token`)

## Context

ADR 0030 assumed that every installation can create dedicated Jira accounts: a reader and a writer, each with
exactly the Jira permissions its capabilities need, holding a classic API token. Real installations differ:

- **No service accounts.** The owner cannot create dedicated accounts in his company's Jira. The only account
  available for Back Office is his own, and a classic API token of a personal account carries every permission of
  that person (edit, delete, administer, every project he can see). The ADR 0030 verification then reports
  "over-privileged" on every connection, and there is nothing to fix.
- **One project, several identities.** A project needs one connection it uses by default (the *system*
  connection), a few more for particular steps, and sometimes the identity of the person who acts: a comment that
  a team lead approves should appear in Jira as written by that lead, not by a shared account.
- **Least-privilege selection across every connection** (ADR 0030 amendment) does not let a project say "use this
  connection for us"; it picks the tightest connection the installation has, which may belong to another team's
  account.
- **Atlassian scoped API tokens exist** ("Create API token with scopes", id.atlassian.com → Security → API tokens).
  ADR 0030 deferred them because they must be sent to the API gateway `https://api.atlassian.com/ex/jira/{cloudId}`
  instead of the site URL. They are the only way to get least privilege on a personal account: the token is
  limited to the scopes ticked when it was created, whatever its account may do.
- The question "should Back Office use the Atlassian MCP server or a CLI instead of REST?" came up and needs a
  recorded answer.

## Decision

### 1. Three scopes of connections

| Scope | Created where | Who can use it | Who can read it |
|---|---|---|---|
| `PLATFORM` | Administration → Connections (`admin.connections.manage`) | any project that chooses it (system connection, list, or a step) | `integrations.view` |
| `PROJECT` | Project → Connections (`project.connections.manage`) | that project | `project.view` |
| `USER` (new, *personal connection*) | My account → Connections (new permission `connections.personal`, granted to every member) | only with an action of its owner: their approval of a write, or a run they started | only the owner (API `/me/connections`, everything else answers `404`); administrators (`admin.connections.manage`) see metadata only (name, site, capabilities, token kind, owner, expiry, last check) at `GET /admin/connections/personal`, never the e-mail, never the token, and cannot use or change it |

Personal connections are never candidates of the ADR 0030 selection, never a project's system connection or list
entry, never pinnable, and never used by the intake watcher.

### 2. The project's Jira settings: system connection and list

A project has a **system connection** (explicitly chosen from the project's own and the platform's connections)
and an **ordered list of further connections** its steps may use (`GET/PUT /projects/{id}/jira-connections`,
`project.view` / `project.connections.manage`, audited `PROJECT_JIRA_CONNECTIONS_UPDATE`). Stored on the project
(`project.jira_system_connection_id` with `ON DELETE SET NULL`, `project.jira_connection_ids`; deleting a
connection also removes it from every list, audited). A project without a system connection keeps the ADR 0030
rule (least privilege over every project and platform connection, legacy credential last).

### 3. Per step: "Act as" (node configuration)

| `jiraConnection` | Meaning | Extra settings |
|---|---|---|
| missing / `SYSTEM` (default) | the project's system connection, still checked for the step's capabilities and the issue's Jira project; if it does not fit, **least privilege among the project's list** (system + further connections) — reported as a fallback in the run log and the designer; none fits → `NONE` | `allowActingUser: true` (write steps): the approver may switch the write to their own personal connection |
| a connection id (`SPECIFIC`) | that project or platform connection (ADR 0030 pin); never a personal one (`PINNED_NOT_FOUND`) | |
| `ACTING_USER` | the personal connection of the **person who approves the write** (write steps with approval), or of the **person who started the run** (read steps, and write steps with `approval: NOT_REQUIRED`) — least privilege among that person's personal connections | `fallbackToSystem: true` lets the system connection be used when the person has no fitting personal connection (read steps fall back on their own; for writes the approver chooses it in the confirmation) |

**Never without consent.** A personal connection is used only when its owner acted: they approved exactly this
write (ADR 0033 confirmation), or they started this run (`startedBy` of kind `USER` or `PERSONAL_TOKEN`). Runs
started by the intake watcher, CI or the system have no acting person: `ACTING_USER` steps fail there unless
`fallbackToSystem` is set. Another user's personal connection is never used in someone else's run, not even when
the run's starter has none. The workflow validator warns when an `ACTING_USER` read step without fallback is in a
workflow with a `JIRA_TRANSITION` trigger; the run start refuses read steps (and unapproved writes) whose acting
person has no fitting personal connection.

### 4. The write confirmation chooses the identity

`WritePreview` carries `connectionMode`, `personalAllowed`, `systemAllowed` and `defaultIdentity` (the digest is
unchanged). `GET /approvals/{id}/write-identity` tells the caller which identities they could approve as (the system
connection name, their own fitting personal connection, or why not). `Decision.writeAs` = `SYSTEM` | `PERSONAL`
(default: `PERSONAL` for `ACTING_USER`, else `SYSTEM`). Approving as `PERSONAL` without a fitting personal
connection is refused (`409`), a choice the step does not allow is `400`; rejecting needs no identity. The dialog
shows who will write, lets the approver switch when the step allows it, and links to My account → Connections. The
write is then made through exactly that identity; if it vanished meanwhile, the step fails with "nothing was
written". Audit `EXTERNAL_WRITE` gains `identity` (`SYSTEM` / `PERSONAL`), `via` ("personal connection of Anna
Nowak ("Anna's Jira")") and `personalOwnerId`; `RUN_WRITE_DECIDED` gains `writeAs`.

### 5. Scoped API tokens

A connection has `tokenKind` = `CLASSIC` | `SCOPED`.

- **SCOPED**: created at id.atlassian.com → Security → API tokens → **Create API token with scopes** (name, expiry
  1-365 days, app **Jira**, scopes). It must be sent to `https://api.atlassian.com/ex/jira/{cloudId}/rest/api/3/...`
  with HTTP Basic (account e-mail : token), like a classic token. The connection stores the site's `cloudId`
  (discovered on save from the public `GET https://<site>.atlassian.net/_edge/tenant_info` → `{"cloudId": ...}`, or
  entered by hand) and the **declared scopes** (`tokenScopes`). REST calls use the gateway base URL; issue links
  (`<site>/browse/<key>`) keep the site URL. The gateway base is `backoffice.integrations.jira.api-gateway-url`
  (default `https://api.atlassian.com`; overridable for tests).
- **CLASSIC**: as before; saving a classic token now needs the acknowledgement "this token can do everything its
  account can; Back Office limits each step to its capability" (`acknowledgeClassicToken`, stored as
  `classic_acknowledged`; connections created before this change are migrated as acknowledged).

Scopes per capability (`JiraScopes`, one option suffices; to be re-checked against Atlassian's per-endpoint
"Scopes" boxes when Atlassian changes them):

| Capability | Classic scopes | Granular scopes (required) | Granular (recommended to tick) |
|---|---|---|---|
| verification (`myself`) | `read:jira-user` | `read:user:jira`, `read:group:jira`, `read:application-role:jira`, `read:avatar:jira`, `read:permission:jira` (corrected 2026-10-13: `myself` answers 401 without the first four; the last one reads `mypermissions`) | |
| `ISSUE_READ` | `read:jira-work` | `read:issue:jira`, `read:issue-details:jira`, `read:jql:jira`, `read:audit-log:jira` (added 2026-10-13: `search/jql` answers 401 without it) | + `read:issue-meta:jira`, `read:field-configuration:jira`, `read:status:jira`, `read:issue.changelog:jira`, `read:field:jira`, `read:user:jira`, `read:avatar:jira` |
| `ISSUE_COMMENT` | `read:jira-work`, `write:jira-work` | `write:comment:jira`, `read:comment:jira` | + `read:comment.property:jira`, `read:project:jira`, `read:project-role:jira`, `read:group:jira`, `read:user:jira`, `read:avatar:jira` |
| `ISSUE_TRANSITION` | `read:jira-work`, `write:jira-work` | `write:issue:jira`, `read:issue.transition:jira`, `read:status:jira` | + `write:issue.property:jira`, `read:field-configuration:jira` |

**Verification of a scoped token**: `GET /myself`; `GET /mypermissions` per project (a `401`/`403` there only means
the token cannot read permissions: a warning); then the read endpoints each declared capability needs, on the first
checked project: a JQL search limited to one issue (`ISSUE_READ`, also yields a sample issue), the comments of that
issue (`ISSUE_COMMENT`, reading half) and its transitions (`ISSUE_TRANSITION`). A `401`/`403` is `MISSING_SCOPES`
(error). Writes are never tried. The declared scopes are compared with the table: a capability no option covers is
`MISSING_SCOPES` (error); declared write scopes (`write:*`, `delete:*`, `manage:*`) no capability needs are
`EXCESS_SCOPES` (warning); `write:jira-work` with a write capability is `BROAD_SCOPE` (warning: it also allows
creating, editing and deleting issues). Account permissions beyond the capabilities are only `INFO` for scoped
tokens (the scopes bound the token), but stay the ADR 0030 `WARNING` for classic ones.

The UI recommends scoped tokens (default in the add dialog, "Use the recommended scopes"); the manual's
step-by-step set-up is written for them.

### 6. How Back Office talks to Jira: direct REST API v3, no MCP, no CLI

Back Office calls the **Jira Cloud REST API v3 over HTTPS from the backend** (`JiraIssueTracker`, Spring
`RestClient`): `GET /issue/{key}`, `GET /search/jql`, `POST /issue/{key}/comment`, `GET`/`POST
/issue/{key}/transitions`, `GET /myself`, `GET /mypermissions`. This is deliberate:

- the Jira steps are **deterministic**: each step is exactly one or two known endpoints, with inputs rendered by
  Back Office, not chosen by a model;
- **least privilege per step** is enforced in code: the client a step receives is narrowed to its capabilities and
  the connection's allowed projects (`ScopedIssueTracker`), before any call leaves;
- **approval before writes** (ADR 0033) works because Back Office knows the exact request it will make and can show
  it, hash it and make exactly that request after approval;
- **no extra processes or credentials**: no MCP server or CLI to deploy, patch and sandbox, and no Jira credential in
  any runner sandbox; tokens stay sealed in the database and are opened only in the backend for one call;
- **testable**: WireMock covers the adapter; the selection, verification and approval rules are pure domain code.

**When MCP would make sense**: an *agent* that explores Jira on its own (searching related issues, reading linked
tickets while it implements a change). That is not a workflow step but a tool given to a model. The safer pattern
for that future case is a **narrow Back-Office-brokered tool**: the agent asks Back Office (the existing MCP server
of ADR 0025 or the runner's callback API) for "search issues" / "read issue", and Back Office executes it through
the same connection selection, capability narrowing, allowed-project checks, audit and — for writes — the ADR 0033
approval; results reach the prompt as untrusted data blocks (ADR 0029). **Never** put Atlassian MCP credentials (or
any Jira token) into a sandbox: a prompt-injected agent could use them for anything the token allows, without an
approval and outside the audit log.

## Alternatives considered

- **Keep only dedicated reader/writer accounts (ADR 0030).** Impossible where service accounts cannot be created;
  personal classic tokens would remain permanently over-privileged with no recourse.
- **OAuth 2.0 (3LO) with an Atlassian app per installation.** Real per-user consent and refresh tokens, but it needs
  an app registration in the developer console, a public redirect URL, refresh-token storage and rotation, and
  admin approval in many organisations. Scoped API tokens give the same least privilege per person with what every
  user can do alone. Kept as a later `tokenKind`.
- **Personal connections usable by anyone in the project.** Rejected: acting in Jira as someone requires that
  person's consent per action; borrowing a colleague's identity would also defeat the audit trail.
- **Choose the acting identity at design time only.** Rejected: the approver is known only when the write is
  approved, and approvers can be reassigned (ADR 0028). The node says which identities are allowed; the person
  approving chooses among them.
- **Keep least privilege over all connections, add "preferred" flags.** Rejected: the owner wants an explicit
  system connection per project; least privilege remains the fallback within the project's list.
- **Atlassian MCP server or `acli` instead of REST.** Rejected for the reasons in decision 6: non-deterministic tool
  use, credentials in extra processes or sandboxes, no per-step narrowing, harder approvals and tests.
- **Trust declared scopes without calling Jira.** Rejected: the declaration may be wrong; the endpoint checks catch a
  missing read scope before a run fails. Write scopes cannot be tested without writing, so they rely on the
  declaration and on the excess check.

## Consequences

- **Migrations.** Projects `V110__jira_connection_model_and_optional_spec_kit.sql`: `connection.scope` gains `USER`,
  columns `owner_user_id`, `owner_name`, `token_kind` (default `CLASSIC`), `cloud_id`, `token_scopes`,
  `classic_acknowledged` (existing rows set to true), constraints, unique names per owner; `project`
  gains `jira_system_connection_id` and `jira_connection_ids`. For every project that uses Jira (a Jira project key
  or JQL filter, a project connection, an enabled intake watcher, or a workflow with a Jira step) the system
  connection becomes the **least-privileged reader-or-writer** among its own connections and the platform
  connections whose allow-list admits its Jira key: connections with `ISSUE_READ` first, then the fewest
  capabilities, then project before platform, then the last check (OK, WARNING, none, FAILED), then name; every
  other candidate becomes a further connection in the same order, so `SYSTEM` steps see the same connections as
  before. Projects without a stored candidate keep the ADR 0030 rule (with the legacy environment credential, which
  is unchanged). Identity `V409`: `connections.personal` added to the stored member, project-manager, evaluator and
  library-curator roles (audited `ROLE_UPDATE`).
- **API.** New: `/me/connections` (CRUD, test, verify, secret), `GET /admin/connections/personal`, `GET/PUT
  /projects/{id}/jira-connections`, `GET /connections/jira-scopes`, `GET /approvals/{id}/write-identity`. Changed:
  `ConnectionInput`/`Connection` (`tokenKind`, `cloudId`, `tokenScopes`, `acknowledgeClassicToken` /
  `classicTokenAcknowledged`, `ownerUserId`, `ownerName`), `ConnectionScope` (`USER`), `ConnectionResolution`
  (`mode`, `fallback`, statuses `NO_ACTING_USER`, `NO_PERSONAL_CONNECTION`), `ConnectionMessage.code`
  (`MISSING_SCOPES`, `EXCESS_SCOPES`, `BROAD_SCOPE`), `WritePreview`, `Decision.writeAs`.
- **UI.** My account → Connections; project → Connections shows the system connection and the list; the designer's
  Jira section has **Act as**; the write confirmation shows who will write; Administration → Connections lists
  personal connections as metadata. All texts with `@@ids` and Polish translations.
- **Residual risks.** A granular `write:issue:jira` (needed to transition) also allows editing issues; Back Office
  only transitions, after approval, but a leaked token is bounded by the scope, not by Back Office. Atlassian may
  rename or regroup granular scopes; the table lives in one class (`JiraScopes`) and the endpoint checks report
  `MISSING_SCOPES` if it drifts. A personal connection whose owner leaves keeps existing until deleted, but it can
  never be used without that person's action.
- ADR 0030 stays valid for platform and project connections; this record amends its selection (system connection
  first) and its "classic tokens only" limit.
