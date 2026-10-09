# Jira

Back Office reads Jira issues to start work, posts comments with the outcome and moves issues through
their workflow. It talks to **Jira Cloud** over REST API v3 through **connections**: named Jira
credentials (site, account e-mail, API token) that declare what Back Office may do with them. Every project
has a **system connection** that its Jira steps use by default, and a list of further connections; a step can
also act as the **person who approves or starts it**, through that person's own **personal connection**
(ADR 0035). **Scoped API tokens** keep least privilege even when the only Jira account you have is your own.
A project can also let Jira
start work: the **Jira intake watcher** polls Jira with a read-only connection and starts a run when an
issue enters a status (ADR 0033). Every comment or transition waits for a person to confirm the exact
write first, unless the designer switched that off for the step. This chapter explains why, how to set up
Jira accounts with exactly the permissions needed, how to add and test connections, how workflow steps
pick them, how writes are approved and how the watcher works.

**Who needs this:** platform administrator (platform connections, encryption key), project owner (project
connections, the system connection, Jira project key, intake watcher), every user (personal connections),
workflow designer (**Act as** on a node, approval before writing), approvers (confirming writes, choosing who
writes).

<a id="jira-overview"></a>
## How Back Office uses Jira

### How Back Office talks to Jira

The Back Office server calls the **Jira Cloud REST API v3** directly over HTTPS. There is no MCP server, no
command-line tool and no Jira token inside the sandboxes where coding agents run. This is deliberate:

- **Every Jira step is deterministic**: it is one or two known REST calls (read an issue, post a comment, move an
  issue) with content Back Office prepared, not something a model decides to do.
- **Least privilege is enforced per step**: before a request leaves, the client a step gets is limited to what the
  step needs and to the connection's allowed Jira projects. A "Fetch issue" step cannot comment, even through a
  connection that could.
- **Writes wait for a person** ([approval](#jira-write-approval)): Back Office knows the exact request, shows it,
  records its fingerprint and sends exactly that after approval.
- **Nothing extra to run or protect**: no additional process or credential; tokens stay encrypted in the database
  and are opened in the server only for one call.
- **It is testable**: every rule (which connection, which identity, which check) is covered by automated tests.

**When MCP would make sense.** If an AI agent should explore Jira on its own (search related issues while it
implements a change), that is a tool for a model, not a workflow step. The safe way is a narrow tool that asks Back
Office to run the search: the same connection rules, capability limits, allowed projects, audit and, for writes,
the same approval apply, and the result reaches the model as untrusted data. Back Office never hands an Atlassian
MCP credential or a Jira token to an agent: a manipulated agent (prompt injection) could otherwise use it for
anything the token allows, unseen and unapproved.

### What Back Office calls

Back Office never receives webhooks from Jira. Jira is called when a workflow node of one of the three Jira
task types runs, when the project's intake watcher polls, or when a connection is saved or tested:

| What runs | Jira call | Connection capability it needs | Jira project permissions behind it |
|---|---|---|---|
| **Fetch issue** task (executor `FETCH_ISSUE`) | `GET /rest/api/3/issue/{key}` with summary, description, type, priority, status, labels, components and the acceptance-criteria field | **Read issues** (`ISSUE_READ`) | **Browse Projects** |
| **Notify Jira** task (executor `NOTIFY`) | `POST /rest/api/3/issue/{key}/comment`, after a person confirmed it ([approval](#jira-write-approval)) | **Comment on issues** (`ISSUE_COMMENT`) | **Browse Projects**, **Add Comments** |
| **Transition issue** task (executor `TRANSITION_ISSUE`) | `GET` then `POST /rest/api/3/issue/{key}/transitions`, after a person confirmed it | **Move issues** (`ISSUE_TRANSITION`) and **Read issues** | **Browse Projects**, **Transition Issues** |
| **Jira intake watcher** of a project ([watcher](#jira-intake)) | `GET /rest/api/3/search/jql` with the fields status, updated, created and the changelog | **Read issues** (`ISSUE_READ`) | **Browse Projects** |
| Saving a connection, **Test**, **Test before saving** | `GET /rest/api/3/myself`, `GET /rest/api/3/mypermissions` | none | none (any valid account) |

All calls use HTTP Basic authentication with the connection's account e-mail and API token. A classic token is sent
to the site URL (`https://acme.atlassian.net`); a scoped token to the Atlassian API gateway
`https://api.atlassian.com/ex/jira/<cloud id>` ([scoped tokens](#jira-scoped-token)). Links to issues always use
the site URL. (Code: `projects-adapters/.../adapter/out/jira/JiraIssueTracker.kt`.) The issue key of a run comes
from the run input (**Start run** with an issue key, the MCP tool `start_workflow`, a CI variable) or from the
intake watcher.

**Limits**

- Jira Cloud only. Jira Data Center / Server personal access tokens (Bearer) are **not supported**, and
  Data Center does not serve REST API v3 the way the adapter expects.
- Atlassian API tokens: **scoped** (recommended) or **classic** ([which one](#jira-token)). OAuth apps are not
  supported.
- The **JQL filter** field on the project form narrows what the intake watcher sees. It does not restrict
  runs started by hand, by CI or through MCP.
- Comments and moves are made as the account of the connection the step uses. A step set to act as the person who
  approves or starts it writes as that person, through their own personal connection, and only after their
  approval ([Act as](#jira-act-as)).

<a id="jira-least-privilege"></a>
## Least privilege: a reader and a writer account

**No dedicated accounts possible?** Many companies do not allow extra Jira accounts. Then use your own account with
**scoped API tokens** ([steps](#jira-scoped-token)): a scoped token can do only what its scopes allow, whatever your
account may do, so a reader token with read scopes cannot write even though you can. Create one scoped token for
reading and one for writing, add them as two connections, and choose the reader as the project's
[system connection](#jira-system-connection). The rest of this section describes the classic set-up with dedicated
accounts.

A classic Jira API token inherits **every** permission of its account; Atlassian offers no way to narrow a
classic token. If one account reads, comments and transitions for every project, then a step that only
reads an issue holds the right to write everywhere, and a leaked token, a bug or an agent output steered by
prompt injection can touch any issue the account sees. The **principle of least privilege** therefore means
**least-privileged accounts**: give each Jira account only the permissions its job needs, and let Back
Office use the weakest account that is enough.

Back Office helps in three ways:

- each connection declares **capabilities**, and a step only gets a connection whose capabilities cover what
  it needs, preferring the one with the fewest extra capabilities ([how steps choose](#jira-tasks));
- the Jira client handed to a step is restricted to that step's capabilities and to the connection's
  **Allowed projects**: a **Fetch issue** step cannot comment even when it runs through a writer connection;
- saving or testing a connection asks Jira which permissions the account really has and shows both
  **missing** and **excess** (over-privileged) permissions ([Test](#jira-test)).

### Recommended set-up

| Jira account | Jira project permissions (through a project role) | Back Office connection | Capabilities |
|---|---|---|---|
| `backoffice-jira-reader@example.com` | **Browse Projects** only | `Jira reader` | **Read issues** |
| `backoffice-jira-writer@example.com` | **Browse Projects**, **Add Comments**, **Transition Issues** | `Jira writer` | **Comment on issues**, **Move issues**, **Read issues** |

- Use **separate accounts** for reader and writer. A connection's capabilities limit what Back Office does
  with a token; only the account's Jira permissions limit what the token can do if it leaks.
- Grant the permissions through a project role that holds **exactly** these permissions, only in the Jira
  projects the connection is meant for. Do not use roles that can edit, delete, assign or administer.
- Never use a person's or an administrator's account.
- If comments and transitions must come from different accounts, split the writer into two connections
  (**Comment on issues** + **Read issues**, **Move issues** + **Read issues**).

With this set-up the workflow **Fetch issue → … → Notify Jira → Transition issue** uses the reader for the
first step and the writer for the last two, and the designer shows no warning. The writer also gets
**Read issues** because **Transition issue** reads the issue's transitions first; otherwise a transition
step would find no connection that covers both capabilities. The [intake watcher](#jira-intake) uses the
reader as well, and refuses to poll without one.

<a id="jira-token"></a>
## Create the API tokens

Atlassian offers two kinds of API tokens. Back Office supports both:

| | Scoped API token (recommended) | Classic API token |
|---|---|---|
| Created at | id.atlassian.com → Security → API tokens → **Create API token with scopes** | the same page → **Create API token** |
| Can do | only what its scopes allow | everything its account can |
| Least privilege | through the scopes, also on your own account | only through a dedicated account with few Jira permissions |
| Back Office calls | `https://api.atlassian.com/ex/jira/<cloud id>` | the site URL |
| On save | the cloud id is found automatically; the scopes are checked | you confirm "this token can do everything its account can" |

<a id="jira-scoped-token"></a>
### Scoped API token (recommended)

1. Sign in to Atlassian with the account Back Office should act as. When you cannot create a dedicated account,
   your own account is fine: the scopes limit the token.
2. Open https://id.atlassian.com/manage-profile/security/api-tokens (**Account settings** → **Security** →
   **API tokens**) and choose **Create API token with scopes**.
3. Give it a name, for example `back-office-reader`, and an expiry date (1 to 365 days).
4. Choose the app **Jira**.
5. Tick exactly the scopes for what the connection will do. Back Office's add dialog lists them and fills them in
   with **Use the recommended scopes**:

   | Connection may | Granular scopes to tick (recommended) | Or the classic scopes |
   |---|---|---|
   | (always, to identify the account) | `read:user:jira` | `read:jira-user` |
   | **Read issues** | `read:issue:jira`, `read:issue-details:jira`, `read:jql:jira`, `read:issue-meta:jira`, `read:field-configuration:jira`, `read:status:jira`, `read:issue.changelog:jira`, `read:field:jira`, `read:avatar:jira` | `read:jira-work` |
   | **Comment on issues** | `write:comment:jira`, `read:comment:jira`, `read:comment.property:jira`, `read:project:jira`, `read:project-role:jira`, `read:group:jira`, `read:avatar:jira` | `read:jira-work`, `write:jira-work` |
   | **Move issues** | `write:issue:jira`, `write:issue.property:jira`, `read:issue.transition:jira`, `read:status:jira`, `read:field-configuration:jira` | `read:jira-work`, `write:jira-work` |

   Prefer the granular scopes: `write:jira-work` also lets the token create, edit and delete issues, and the test
   warns about it. Moving issues needs `write:issue:jira`, which also allows editing issues; Back Office still only
   moves them, after a person approved it. Atlassian sometimes regroups scopes: if the **Test** reports missing
   scopes for an endpoint, add the scope Jira names in its answer.
6. Create the token and copy it (it is shown once). Note the expiry date.
7. In Back Office add the connection with **Kind of API token** = **Scoped API token**, paste the token and list the
   same scopes. Leave **Cloud id** empty: Back Office reads it from `https://<your-site>.atlassian.net/_edge/tenant_info`
   when you save (enter it by hand only if your network blocks that address).

Docs: https://support.atlassian.com/atlassian-account/docs/manage-api-tokens-for-your-atlassian-account/ and the
scope list https://developer.atlassian.com/cloud/jira/platform/scopes-for-oauth-2-3LO-and-forge-apps/

### Classic API token with dedicated accounts

Repeat for each account (reader, writer).

1. **Create the account.** In Atlassian Administration (admin.atlassian.com) → **Directory** → **Users** →
   **Invite user**, enter the account e-mail (for example `backoffice-jira-reader@example.com`) and give it
   access to the **Jira** product. The account needs a Jira licence seat; an account without product access
   gets `401`/`403` on every call.
   Docs: https://support.atlassian.com/user-management/docs/invite-users/
2. **Create a project role with only the needed permissions** (company-managed projects; a Jira
   administrator does this once). **Settings** (cog) → **System** → **Project roles**: add *Back Office
   reader* and *Back Office writer*. In the permission scheme of the Jira projects (**Settings** →
   **Issues** → **Permission schemes** → **Permissions**) grant **Browse Projects** to both roles, and
   **Add Comments** and **Transition Issues** to the writer role only. Check that the accounts do not get
   more through groups (for example *jira-software-users*) that the scheme grants permissions to.
   Docs: https://support.atlassian.com/jira-cloud-administration/docs/manage-project-permissions/
3. **Add the account to the role** in each Jira project it should work in: **Project settings** →
   **People** → **Add people**, choose the role. Team-managed projects have coarser roles: *Viewer* is
   enough for the reader, *Member* for the writer (Member can also create and edit issues, which **Test**
   reports as excess). If issues use issue security levels, the account must be allowed to see those
   levels too.
4. **Create a classic API token.** Sign in to Atlassian **as that account**, open
   https://id.atlassian.com/manage-profile/security/api-tokens (**Account settings** → **Security** →
   **Create and manage API tokens**), choose **Create API token** (not **Create API token with scopes**),
   give it a name such as `back-office-prod-reader`, pick an expiry date (Atlassian caps API tokens at one
   year) and copy the token. It is shown once.
   Docs: https://support.atlassian.com/atlassian-account/docs/manage-api-tokens-for-your-atlassian-account/
5. **Keep the expiry date**: you enter it on the connection, and Back Office reminds you before it expires.

**A classic token has every right of its account**, which is why the account itself must hold only the
permissions above. Saving a classic token asks you to confirm exactly that ("I understand: this classic token can
do everything its account can in Jira"); connections saved before this question existed count as confirmed. With
your own account, prefer a [scoped token](#jira-scoped-token).

<a id="jira-connections"></a>
## Connections

**Connections** hold the Jira credentials. There are three scopes:

| Scope | Where | Who can change | Who sees the list | Used by |
|---|---|---|---|---|
| Platform | **Administration** → **Connections** (also in the command palette) | `admin.connections.manage` (role `platform-admin`) | `integrations.view` (every member) | projects that choose it ([system connection](#jira-system-connection)), steps that name it |
| Project | Project page → **Connections** tab | `project.connections.manage` (role **Owner** only, not **Maintainer**) | `project.view` (every project member) | this project only |
| Personal | **My account** → **Connections** | its owner (`connections.personal`, every member) | only its owner; administrators see that it exists | only with its owner's own action ([personal connections](#jira-personal-connections)) |

Which connection a step uses is decided by the project's [system connection](#jira-system-connection) and the
step's [Act as](#jira-act-as) setting. Deleting a project deletes its connections; deleting a connection removes it
from every project's system connection and list. Every change is
audited (`CONNECTION_CREATE`, `CONNECTION_UPDATE`, `CONNECTION_ROTATE`, `CONNECTION_DELETE`,
`CONNECTION_VERIFY`, `CONNECTION_TEST`); the token never appears in the audit log, the API, the UI or the
logs.

### Add a connection

1. Open **Administration** → **Connections** (platform) or the project → **Connections** (project), then
   **Add connection**.
2. **Type**: **Jira**.
3. **Name**: what people see in the designer and the run log, for example `Jira reader`.
4. **Site URL**: `https://<your-site>.atlassian.net` (https only).
5. **Account e-mail**: the e-mail of the Jira account the token belongs to.
6. **Kind of API token**: **Scoped API token (recommended)** or **Classic API token** ([which one](#jira-token)).
7. **API token**: the token. The field is write-only: after saving, the form only shows that a token is set.
   - Scoped: **Scopes of the token** (the scopes you ticked; **Use the recommended scopes** fills them in for the
     selected capabilities; the dialog warns at once when the scopes do not cover a capability or contain write
     scopes nobody needs) and, optionally, **Cloud id of the site** (empty: found automatically on save).
   - Classic: tick **I understand: this classic token can do everything its account can in Jira**. Saving is not
     possible without it.
8. **Capabilities**: tick what this connection may be used for. The dialog explains each capability and
   shows a step-by-step guide for the Jira side that follows your choice.

   | Capability | Lets Back Office | Jira permissions the account needs |
   |---|---|---|
   | **Read issues** (`ISSUE_READ`) | load issues (**Fetch issue**, **Transition issue**) | Browse Projects |
   | **Comment on issues** (`ISSUE_COMMENT`) | post comments (**Notify Jira**) | Browse Projects, Add Comments |
   | **Move issues** (`ISSUE_TRANSITION`) | move issues to another status (**Transition issue**) | Browse Projects, Transition Issues |

   **Comment on issues** and **Move issues** are write capabilities. Tick only what the account is meant
   for.
9. **Allowed projects** (optional): the Jira project keys this connection may touch, for example `SHOP`,
   `WEB`. Empty means any project the account can see. When set, the connection is not chosen for an issue
   of another Jira project, and a step that tries is refused.
10. **Project key for the test** (only when **Allowed projects** is empty): a sample Jira project key used
   to check permissions.
11. **Token expiry date**: the expiry date you chose at Atlassian. Back Office reminds you 14 days before
    it ([Token expiry](#jira-token-expiry)).
12. **Test before saving** (recommended): Back Office checks the draft against Jira and shows the account,
    and the granted, missing and excess permissions, without storing anything ([Test](#jira-test)). Fix
    the account or the capabilities until nothing is missing and nothing is excess.
13. **Save**. Back Office tests the connection again right away. A failed test does not prevent saving,
    but a failed connection is chosen only when nothing else qualifies.

**Add connection** answers "BACKOFFICE_SECRETS_KEY is not configured" when the operator has not set the
encryption key ([Configure Back Office](#jira-config)).

<a id="jira-system-connection"></a>
### The project's system connection and connection list

On the project's **Connections** tab, **Jira connections of this project** (owners change it; everyone with
access to the project sees it):

- **System connection**: the connection every Jira step of the project uses unless the step chooses otherwise.
  Choose it from the project's own connections or the platform connections. It is still checked for each step: a
  step that needs a capability the system connection lacks (for example **Move issues** when the system connection
  only reads), or an issue outside its **Allowed projects**, takes the least-privileged connection from the list
  below instead; the designer and the run log say so.
- **Further connections for this project's steps**: an ordered list of other project or platform connections the
  project's steps may use, for example a writer next to a reader system connection. Add, reorder or remove them,
  then **Save Jira connections**.
- **None** as system connection: steps choose from every project and platform connection with the fewest extra
  capabilities (the earlier rule; the environment-variable credential last).

Personal connections never appear here. The change is audited as `PROJECT_JIRA_CONNECTIONS_UPDATE`.

**After the upgrade** every project that already used Jira got a system connection automatically: among its own
connections and the platform connections that may touch its Jira project, the one that can read with the fewest
capabilities (then: project before platform, then the last test result, then the name); every other of these
connections became a further connection, in the same order. So steps keep using the connections they used before.
Projects without Jira, and projects whose only credential is the environment-variable one, have no system
connection and keep the earlier rule.

<a id="jira-personal-connections"></a>
### Personal connections

A **personal connection** is a Jira connection you keep for yourself under **My account** → **Connections**. It
lets workflow steps comment on or move issues **as you**, in Jira, instead of as a shared account.

- **Only with your own action.** It is used only when a step is set to act as the person who approves or starts it
  ([Act as](#jira-act-as)), and only for **your** approval of a write or a run **you** started. It is never used by
  the intake watcher, by CI, or in a run of someone else unless you approve that write.
- **Nobody else can see or use it.** Other users cannot read, test or change it (they get "not found").
  Administrators see under **Administration** → **Connections** → **Personal connections** that it exists (name,
  site, capabilities, kind of token, owner, expiry, last test), never the e-mail or the token, and they cannot use
  it.
- **Use a scoped token** ([steps](#jira-scoped-token)): it is your own account, so the scopes are what keeps the
  token to "comment" or "read".
- Expiry reminders come to you (Your turn card, desktop notification).
- The audit log records writes through it as "via personal connection of <your name>".

<a id="jira-token-expiry"></a>
### Token expiry and rotation

The list marks a token **expiring soon** 30 days before its expiry date and **expired** after it. In
addition, Back Office reminds the people who can change the connection when its token expires within
**14 days** or has expired:

- a card in **Your turn**: "The Jira token of <name> expires in 5 days" or "The Jira token of <name> has expired",
  with the account and the date, linking to the connection; it stays until the token is replaced
  ([Your turn](your-turn.md#write-confirmations)). Platform connections are shown to holders of
  `admin.connections.manage`, project connections to holders of `project.connections.manage` (project
  owners);
- once per expiry date, a daily check (07:00 UTC by default) sends a desktop notification of the type
  **Connection expiring**, on by default for administrators and off for everyone else
  ([Desktop notifications](account.md#notifications)), and writes the audit record
  `CONNECTION_EXPIRY_REMINDER`.

Without a **Token expiry date** on the connection there is no reminder. Rotate before the date, and
immediately if a token leaks:

1. Sign in to Atlassian as the connection's account and create a new classic token
   ([steps](#jira-token)).
2. In Back Office open the connection, choose **Replace token**, paste the new token, set the new **Token
   expiry date** and save. The connection is tested again; the next Jira step uses the new token. No
   restart is needed.
3. Revoke the old token on the account's **API tokens** page.

Editing the other fields keeps the stored token.

<a id="jira-config"></a>
## Configure Back Office

Connections live in the database; their tokens are encrypted with a key that the operator provides.

| Setting | Environment variable | Helm value (chart `back-office`) | Secret |
|---|---|---|---|
| Encryption key of connection secrets (base64 of 32 bytes) | `BACKOFFICE_SECRETS_KEY` | `secrets.connectionsKey` (generated and kept across upgrades when empty and `secrets.create=true`), or key `BACKOFFICE_SECRETS_KEY` in `secrets.existingSecret` | yes |
| Id of that key | `BACKOFFICE_SECRETS_KEY_ID` | `secrets.connectionsKeyId` (default `k1`) | no |
| Acceptance-criteria custom field, for example `customfield_10042` | `JIRA_ACCEPTANCE_CRITERIA_FIELD` | `integrations.jira.acceptanceCriteriaField` | no |

Generate a key with `openssl rand -base64 32` (PowerShell 7:
`$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Fill($b); [Convert]::ToBase64String($b)`).
To rotate it, set the new key with a new `BACKOFFICE_SECRETS_KEY_ID` and move the old one to
`BACKOFFICE_SECRETS_PREVIOUS_KEYS` (`id:base64`); the worker re-encrypts on start, after which the old key
can be removed. Details for operators: `docs/guides/configuration.md`, section "Connection secrets".
**Back up the key**: if it is lost, every connection token must be entered again. When connections exist
and the key is missing, the API and worker refuse to start with a message naming `BACKOFFICE_SECRETS_KEY`.
After a lost key, start once with a new key and `BACKOFFICE_SECRETS_ALLOW_UNREADABLE=true`, use **Replace token**
on every connection, then remove the switch.

The acceptance-criteria field is global: it applies to every connection.

### Find the acceptance-criteria field id

When your issues keep acceptance criteria in a custom field, Back Office can expose it to prompts as
`issue.acceptanceCriteria`.

1. As a Jira administrator open **Settings** (cog) → **Issues** → **Custom fields**, find the field, open
   **⋯** → **Edit details** (or **Contexts and default value**). The browser URL contains `id=10042`.
2. The value for Back Office is `customfield_` plus that number: `customfield_10042`.
3. Alternative without admin rights: `GET https://acme.atlassian.net/rest/api/3/field` with a connection's
   e-mail and token lists every field with its `id` and `name`.

### Turn Jira on for a project

1. Open the project → **Settings** tab (needs `project.manage`) and scroll to **Issue tracker (optional)**.
2. Switch the toggle to **Jira**.
3. **Jira project key**: the key of the Jira project (for example `SHOP`); empty means "same as the Back
   Office project key". The designer uses it to check which connection a Jira node would get (against
   **Allowed projects**); at run time the key of the actual issue counts.
4. **JQL filter** (optional): narrows the issues the [Jira intake watcher](#jira-intake) reacts to.
5. **Save changes**. The change is audited.
6. Optionally turn on the **Jira intake watcher** on the same tab (its own card next to the form; [Jira intake watcher](#jira-intake)).

See [projects.md](projects.md#create-project) and [Project connections](projects.md#project-connections).

### Legacy: Jira from environment variables

Before connections, one Jira account was configured with environment variables:

| Environment variable | Helm value |
|---|---|
| `JIRA_URL` | `integrations.jira.url` |
| `JIRA_EMAIL` | `integrations.jira.email` |
| `JIRA_API_TOKEN` | `secrets.integrations.jiraApiToken`, or key `JIRA_API_TOKEN` in `secrets.integrations.existingSecret` |

They still work. When all three are set, the platform list shows a read-only connection **Jira
(environment variables)** marked **legacy**, with **all three capabilities**. It cannot be edited in the UI
(change the variables and restart instead), its test result is shown but not stored, and it is always
chosen **last**, after every project and platform connection that qualifies. Because it can do everything,
every Jira node that uses it shows the "more rights than needed" warning. **Settings** and
**Administration** → **Connections** show a notice to migrate to scoped connections.

**Migrate to scoped connections**

1. Create the reader and writer Jira accounts and tokens ([steps](#jira-token)).
2. Add the two connections, as platform connections or per project ([Add a connection](#jira-connections)),
   and **Test** each until neither shows missing permissions.
3. Open every workflow with Jira tasks in the designer and validate it: each Jira node should show a
   connection other than **Jira (environment variables)** and no error.
4. Remove `JIRA_API_TOKEN` (and `JIRA_URL`, `JIRA_EMAIL`) from the Secret or `deploy/compose/.env` and
   restart the API and worker (`helm upgrade` rolls them when the chart owns the Secret). Keep
   `JIRA_ACCEPTANCE_CRITERIA_FIELD`.
5. Revoke the old token at Atlassian.

<a id="jira-test"></a>
## Test a connection

Press **Test** on a connection (needs the right to change it). Back Office also tests automatically when a
connection is created, edited or its token replaced.

**Scoped tokens** are tested through the API gateway and differently: Back Office compares the scopes you
listed with the capabilities (**Missing scopes** is an error, **write scopes no capability needs** and
`write:jira-work` are warnings), then calls the read endpoints each capability needs in the first project it checks:
a search for one issue (**Read issues**), the comments of that issue (**Comment on issues**) and its transitions
(**Move issues**). Jira answering `401`/`403` there means the token lacks the scopes of that endpoint. Nothing is
ever written by a test. What your account itself may do beyond the capabilities is shown as information only: the
scopes limit the token.

**Test before saving** in the **Add connection** and edit dialogs runs the same test on what you typed,
before anything is stored: a new connection, or changed fields of an existing one (without a new token
the stored token is used). The token is held in memory only for the test; nothing is written to the
database and the connection list does not change. The test is audited as `CONNECTION_TEST`, never with
the token. Use it to check a new account's permissions before the connection can be chosen by any step.

The test:

1. calls `GET /rest/api/3/myself` to prove the e-mail and token, and shows the account's display name;
2. for each of the **Allowed projects** (else the **Project key for the test**) asks Jira which of these
   permissions the account holds: Browse Projects, Add Comments, Transition Issues, Edit Issues, Delete
   Issues, Assign Issues, Create Issues, Administer Projects, Manage Watchers;
3. compares the answer with the capabilities and shows chips per Jira project: **granted**, **missing** and
   **excess**.

| Result | Meaning | What to do |
|---|---|---|
| **OK** | the account has exactly what the capabilities need | nothing |
| **Missing** permissions (status failed) | a capability cannot work, for example **Comment on issues** without Add Comments; the steps that need it will fail | add the permission to the account's project role in that Jira project, or untick the capability |
| **Excess** permissions (status warning, "over-privileged") | the account can do more than the capabilities need, for example "this account can also delete issues / administer the project" | use a dedicated account with only the listed permissions; the connection keeps working meanwhile |
| No project to check (warning) | no **Allowed projects** and no **Project key for the test** | enter one of them, otherwise permissions cannot be checked |

| Message | Meaning | Fix |
|---|---|---|
| Authentication failed (`401`) | wrong e-mail/token pair, expired or revoked token | **Replace token** |
| Site unreachable | DNS, proxy or firewall between Back Office and the site, or a wrong site URL | check the URL; allow egress from the API and worker pods to `*.atlassian.net` (and `api.atlassian.com` for scoped tokens) |
| Project not found (`404`) | the key does not exist or the account cannot browse it | fix the key; add the account to the project role |
| `HTTP 403` | the account has no Jira product access | give it Jira access in Atlassian Administration |
| Secret unavailable | the token cannot be decrypted (the encryption key changed or was lost) | restore the key, or **Replace token** |
| Missing scopes | a scoped token lacks the scopes of a capability (declared or answered `401`/`403` by an endpoint) | create a new scoped token with the missing scopes and **Replace token**, and update **Scopes of the token** |
| Write scopes no capability needs | the scoped token may write more than the connection is for | create a token without them |
| Broad scope `write:jira-work` | the classic write scope also allows creating, editing and deleting issues | use the granular scopes |
| Cloud id could not be found (on save) | `<site>/_edge/tenant_info` cannot be reached or is not a Jira Cloud site | check the site URL; enter the cloud id by hand |

The result and its time are kept on the connection (except for the legacy one) and affect which connection
a step gets.

<a id="jira-tasks"></a>
## Jira tasks in workflows

The task library ships three Jira templates; drop them on the canvas as task nodes
([workflows.md](workflows.md#nodes)). The palette shows the capabilities each one needs and marks the two
that write to Jira.

| Template | Configuration | Needs | Behaviour |
|---|---|---|---|
| **Fetch issue** (`fetch-issue`) | `issueKeyFrom`: `input` (default) or the name of a run parameter | **Read issues** | Loads the issue, stores it as the `issue` artefact and in `state.issue`; fails when the run has no issue key |
| **Notify Jira** (`notify-jira`) | `messageTemplate` (required): the comment body, a template over the run state; `approval` (default `REQUIRED`) and `approvers` | **Comment on issues** | Renders the comment, waits for a person to confirm it ([approval](#jira-write-approval)), then posts exactly that text on `state.issue.key`, else the run's issue key |
| **Transition issue** (`transition-issue`) | `toStatus` (required), for example `In Review`; `approval` (default `REQUIRED`) and `approvers` | **Move issues** + **Read issues** | Waits for a person to confirm the move, then lists the issue's available transitions and executes the first whose **target status name** or **transition name** equals `toStatus` (case-insensitive) |

<a id="jira-act-as"></a>
### Act as: which connection a step uses

Select a Jira node in the designer; the **Jira connection** section shows the capabilities the step needs and
**Act as**:

| Act as | Stored as `jiraConnection` | The step uses |
|---|---|---|
| **System connection of the project** (default) | missing or `SYSTEM` | the project's [system connection](#jira-system-connection). It must have the step's capabilities and allow the issue's Jira project (the prefix of the issue key: `SHOP` for `SHOP-123`); otherwise the qualifying connection of the project's list with the **fewest extra capabilities** (ties: project before platform, then last test OK, warning, never tested, failed, then name). Without a system connection: the same rule over every project and platform connection, the environment-variable credential last. |
| **A specific connection** | the connection's id | exactly that project or platform connection; it must cover the step's capabilities and the issue's Jira project, otherwise the step fails. Personal connections cannot be chosen. |
| **The person who approves the write** (write steps) / **The person who starts the run** (read steps) | `ACTING_USER` | that person's own [personal connection](#jira-personal-connections) (the one with the fewest extra capabilities). For a write that needs approval this is the approver; for reads and for writes without approval, the person who started the run. |

Extra options:

- **Without a personal connection, the system connection may be used** (`fallbackToSystem`, for **The person
  who…**): when the person has no fitting personal connection (or nobody started the run, as with the intake
  watcher or CI), reads use the system connection, and an approver may choose the system connection in the
  confirmation. Without this option the step then fails, and the approver can only reject.
- **The approver may write with their own personal connection instead** (`allowActingUser`, for write steps on
  the system connection): the confirmation offers the approver to write as themselves.

The section also shows which connection the step would use now (for **The person who…** with the fallback: which
system connection it would fall back to). **The person who starts the run** in a workflow with the Jira intake
trigger gets a warning: runs started by the watcher have no such person.

### Warnings and errors

| Where | Message | Meaning |
|---|---|---|
| Designer (validation, save) | warning "no Jira connection with capabilities [...]; the workflow can be saved, but runs refuse to start until a connection exists" | no connection qualifies: add one, or extend its **Allowed projects**. Saving works (installations without Jira can keep the seeded workflows) |
| Designer | warning on a pinned connection that no longer exists | the pinned connection was deleted: pin another one or remove the pin; saving works, runs refuse to start |
| Designer | error on a pinned connection | the pinned connection lacks a capability or the Jira project, or the connection id is invalid; saving is blocked |
| Designer | warning "uses a connection with more rights than needed (...)" | allowed, but create a connection with only the needed capabilities |
| Designer, run log | "the system connection ... lacks ...; uses ... from the project's connection list" | the system connection cannot do this step; another connection of the list is used |
| Run log, node failed | "the step acts as the person who approves or starts it, but this run was not started by a person ..." | **Act as** = the person, the run came from the intake watcher or CI; set the fallback option or start the run yourself |
| **Start run**, run log | "<name> has no personal Jira connection with capabilities [...]" | add one under **My account** → **Connections**, or allow the fallback |
| Designer | warning "this step writes to Jira (...) without anybody confirming the exact write first ..." | **Approval before writing to Jira** is set to not required ([approval](#jira-write-approval)) |
| **Start run** (also CI, MCP, the intake watcher) | refused (`409`) "workflow X cannot start: ... (manual: integrations-jira#jira-connections)" | a Jira step of the workflow has no usable connection; the message names the step. Add the connection, then start again |
| Run log | `INFO Jira connection: Jira reader (project) for ISSUE_READ` | which connection the step used, and its scope |
| Run log | `WARN ... uses a connection with more rights than needed (extra: ISSUE_COMMENT, ISSUE_TRANSITION) - create a connection with only ISSUE_READ` | the step ran through an over-privileged connection |
| Run log, node failed | "no Jira connection with capabilities [ISSUE_READ] for project SHOP (Jira project SHOP); add one under Project → Connections or Administration → Connections" | nothing qualifies at run time; the node fails (port `failure`) |

Template variables useful in comments: `{{run.short}}`, `{{run.workflow}}`, `{{node.name}}`,
`{{issue.key}}`, `{{issue.summary}}`, `{{branch}}`, `{{mr.url}}`, `{{project.name}}`.

Notes:

- A transition only works from a status that has a transition to the target. Model the order in the
  workflow (for example **To Do** → **In Progress** before **In Review**).
- A transition with a required screen field fails: give the transition no mandatory fields, or set a
  default in Jira.
- A failing Jira call fails the node (port `failure`); `5xx` and `429` answers are marked retryable.
  Connect the failure port if a Jira outage must not stop delivery.
- Jira text in Atlassian Document Format is converted to Markdown for prompts; comments are converted back.

<a id="jira-write-approval"></a>
## Approval before writing to Jira

A comment or a status change in Jira cannot be undone from Back Office, and its content can come from model
output or from issue text that someone outside the team wrote (prompt injection, see
[How agent jobs run](agent-execution.md#prompt-injection)). Therefore **Notify Jira** and **Transition
issue** ask a person to confirm the exact write before it happens (ADR 0033).

### How it works

1. When the run reaches the step, Back Office renders the write **once**: the comment text after the
   template values are filled in and the text is cleaned up, or the target status.
2. It checks that a connection for the write exists. Without one the step fails at once: nobody is asked
   to approve a write that cannot happen.
3. The run stops before writing and a **write confirmation** appears in **Your turn** for the people who
   may decide it: "Agent wants to comment on SHOP-42" with the exact comment, or "Agent wants to move
   SHOP-42 to In Review". The run shows "waiting for confirmation" on the step.
4. **Approve**: Back Office writes **exactly** what was shown. **Who writes to Jira** in the confirmation says
   through which identity: the system connection (named there) or **you, through your personal connection**. When
   the step acts as the approver, it is your own connection; when the step allows both, you can switch. If you have
   no fitting personal connection, the confirmation says so, links to **My account** → **Connections**, and offers
   the system connection only when the step allows that fallback; otherwise you can only reject. Nothing is
   rendered again after the approval.
5. **Reject**: nothing is written. The step leaves through its `failure` port with "write rejected by
   <name>, nothing was written (...) - <comment>". If `failure` is not connected, the run fails, as for any
   failed step. Connect `failure` to an **End** with **Failure** or to a step that reworks the text.

A write confirmation has only these two options: the text cannot be edited, because the approved text
must be the written text. To change it, reject with a comment and let the workflow rework it. Write
confirmations never time out; the node's **Timeout (s)** limits the execution, not the wait.

How to decide one: [Confirming external writes](your-turn.md#write-confirmations).

### The setting on the node

Select a **Notify Jira** or **Transition issue** node in the designer. The section **Approval before
writing to Jira** of its properties shows:

| Setting | Meaning |
|---|---|
| **Approval** | **Required** (default): a person confirms each write. **Not required**: the step writes immediately. Workflows saved before this feature behave as **Required**. |
| **Who approves** | Like **Who decides this step** on a gate ([Gate settings](workflows.md#gates-config)): **Project roles**, **Named people**, **Accountable role**, **Forbid self-approval**. Empty: holders of `run.approve` with the project role **Owner** or **Maintainer**; accountable role **Owner**. |

Owners and maintainers can hand a waiting confirmation to other people for that run with **Assign…**
(`run.assign`, [Assigning a step](your-turn.md#assign-steps)). The four-eyes rule and the roles still
apply.

With **Not required** the designer shows a warning on the node: "this step writes to Jira (...) without
anybody confirming the exact write first; text from issues or model output can reach it (prompt
injection) - keep approval REQUIRED unless the content is fixed". The run log repeats it as a `WARN`
line every time the step writes. Choose **Not required** only for fixed text, such as "Run started", that
no model and no issue can change.

### Audit

| Record | When | Details |
|---|---|---|
| `RUN_WRITE_DECIDED` | someone approves or rejects | action, issue key, target status, `contentSha256` of the previewed text |
| `EXTERNAL_WRITE` | the step writes (or tries to) | action, issue, connection, `contentSha256`, approval `APPROVED` or `NOT_REQUIRED`, approved by, result `WRITTEN` or `FAILED`, identity `SYSTEM` or `PERSONAL`, "via personal connection of <name>" |

The SHA-256 digests let an auditor prove that the text written to Jira is the text that was approved.

### The default workflow

**Issue to reviewed MR** ends with the step **Move issue to In Review** (**Transition issue**). It now
asks for confirmation by default: after the **Merge?** gate, a write confirmation "Agent wants to move
SHOP-42 to In Review" appears for the project's owners and maintainers. To keep the old unattended
behaviour, set **Approval** to **Not required** on that node and save a new version.

<a id="jira-intake"></a>
## Jira intake watcher

The **Jira intake watcher** lets Jira start work: when an issue enters a status (for example *Ready for
AI*), or when a new issue is created, Back Office starts a workflow run for it with the issue key. Each
project has one watcher. It polls Jira; it needs no webhook and no inbound connection from Atlassian, so it
works behind a firewall.

### What you need

1. **A reader connection**: a connection with **Read issues** that qualifies for the project's Jira
   project key. The watcher uses only such a connection and refuses to poll without one. Best is a
   dedicated reader account (see **Reader account for the watcher** below); a writer connection works but shows
   a least-privilege warning, and the watcher can only read through it anyway.
2. **A workflow with a Jira trigger.** In the designer, with nothing selected, add the trigger
   **Jira (intake watcher)** under **Triggers** (stored as `JIRA_TRANSITION`) ([Starting a run](workflows.md#start-run)):
   - with a **status** (for example `Ready for AI`): an issue that **enters** that status starts one run;
   - without a status: a **new** issue, created after the watcher started, starts one run.

   The workflow must be enabled and should begin with **Fetch issue** so the run reads the issue.
3. **Jira turned on for the project**, with the **Jira project key** and, optionally, a **JQL filter** on
   the project's **Settings** tab ([Turn Jira on for a project](#jira-config)). The filter narrows which
   issues count, for example `issuetype = Story AND labels = ai`. `ORDER BY` in it is ignored.

### Turn the watcher on

Needs `project.manage` (project **Owner** or **Maintainer**).

1. Open the project → **Settings** tab; the **Jira intake watcher** card is next to the project form.
2. Switch on **Watch Jira and start runs**.
3. **Poll every (minutes)**: 1 to 1440, default 5.
4. **Workflow to start**: a workflow of this project with a **Jira (intake watcher)** trigger; the list
   shows each workflow with what starts it ("when an issue enters Ready for AI", "for new issues") and
   greys out workflows without the trigger. Choosing one
   without the trigger is refused: "workflow X has no JIRA_TRANSITION trigger; add one in the designer
   (with the status that starts a run, or empty for new issues)".
5. Save. The change is audited as `JIRA_INTAKE_UPDATE`.

**The first poll only sets a starting point (baseline).** Issues that are already in the status, or
already exist, never start runs; only changes after that moment do. The same happens after the watcher
is switched off and on again and after the workflow to start changes. To start a run for an issue of the
backlog, move it out of the status and back, or use **Start run** with its key.

### What Back Office asks Jira

Every poll sends one search (`GET /rest/api/3/search/jql`):

```
project = "SHOP" AND (<JQL filter>) AND status CHANGED TO "Ready for AI" AFTER -12m ORDER BY updated ASC
project = "SHOP" AND (<JQL filter>) AND created >= -12m ORDER BY updated ASC
```

The first line is for a trigger with a status, the second for new issues; without a **JQL filter** the
part in brackets is left out. `-12m` is the time since the
last successful poll plus a two-minute overlap (at least 2 minutes, at most 7 days). Relative times do not
depend on the time zone of the Jira account. When the connection has **Allowed projects**, the connection
adds `project in (...) AND (...)` around the query. The watcher reads at most 100 issues per poll (pages
of 50) and only their key, status, created and updated times and the status changes in the history. It
never reads the summary, description or comments: issue text reaches the run only through its **Fetch
issue** step, as untrusted data.

### One run per transition

Every start is guarded by a claim that only one Back Office replica can take:

- an issue entering the status is identified by the history entry of that change, so it starts at most
  one run per transition. If it leaves the status and enters it again later, that is a new transition and
  starts a new run;
- a new issue starts at most one run, ever.

If the run cannot start (for example a Jira step of the workflow has no connection), the claim is released
and the error is shown on the watcher; the next poll tries again. At most 20 runs start per poll; with
more events, the next poll continues where this one stopped.

Limit: if Jira returns no history for an issue, an issue that leaves and re-enters the status between two
polls may be counted only once.

Runs started by the watcher show the actor **Jira intake watcher** and the trigger `JIRA`, and appear in
**Runs** like any other run. Their start is audited as `RUN_START` with `trigger=JIRA` and the claim
(`intakeDedupeKey`).

### The status card

The section shows the watcher's state for everyone with `project.view`:

| Field | Meaning |
|---|---|
| State | on or off |
| Workflow to start | the workflow and what starts it: **when an issue enters** `Ready for AI`, or **for new issues** |
| Query sent to Jira | the query Back Office sends (shown for one poll interval) |
| Read connection | the connection the watcher would use, with **OK**, **over-privileged** (works, but has write capabilities) or **none** (the watcher refuses to poll) |
| Last poll, Last success, Next poll | times of the last attempt, the last successful poll and the next planned poll |
| Last poll failed, Failed polls in a row | why the last poll failed and how many in a row (the next poll then waits longer) |
| Runs started | runs started by the watcher in total |
| Problems | what prevents the watcher from working, for example a missing trigger, a disabled workflow or no read connection |

**Poll now** (needs `project.manage`, the watcher must be enabled) schedules a poll right away instead of
waiting for the interval; a worker performs it within seconds. It is audited as `JIRA_INTAKE_POLL_NOW`.

### Errors and back-off

When Jira answers `429` (rate limit), `5xx` or another error, the watcher records the error and waits
longer before the next poll: the poll interval doubles with each failure in a row, up to one hour. When
Jira sends `Retry-After`, the watcher waits at least that long. The first successful poll resets the
interval. Run steps are not affected.

| Message on the watcher | Meaning | Fix |
|---|---|---|
| "refused: no Jira connection with capabilities [ISSUE_READ] ..." | no connection with **Read issues** qualifies for the project's Jira key | add a reader connection (**Reader account for the watcher** below) or extend **Allowed projects** |
| "workflow X has no JIRA_TRANSITION trigger" | the trigger was removed from the workflow | add the **Jira (intake watcher)** trigger and save a new version |
| "workflow X is disabled" | the workflow to start is switched off | enable it, or choose another workflow |
| "the workflow to start no longer exists; choose another one" | the workflow was deleted | choose another workflow |
| "Jira answered HTTP 401: ..." | the reader's token expired or was revoked | **Replace token** ([rotation](#jira-token-expiry)) |
| "Jira answered HTTP 400: ..." | the **JQL filter** is not valid JQL, or the status name does not exist | fix the filter or the trigger's status |
| "Jira answered HTTP 429: ..." | Jira rate limit | nothing; the watcher backs off. Increase the poll interval if it repeats |
| "workflow X cannot start: ..." | a Jira step of the workflow has no usable connection | add the connection; the next poll retries |
| over-privileged warning | the read connection can also write | use a dedicated reader connection |

### Reader account for the watcher

The watcher needs **Browse Projects** and nothing else. If you already created a reader account
([steps](#jira-token)), add a connection for it and skip to step 5. Otherwise, with an Atlassian
organisation administrator and a Jira administrator:

1. **Create a dedicated account.** admin.atlassian.com → **Directory** → **Users** → **Invite user**:
   e-mail for example `backoffice-jira-reader@acme.com`, product access **Jira** only, no administrator
   roles.
2. **Create a group and add the account.** admin.atlassian.com → **Directory** → **Groups** → **Create
   group**: name `backoffice-readers`; then **Add group members** and add the account.
3. **Grant only Browse Projects** in each Jira project the watcher reads:
   - **Company-managed project**: **Project settings** → **Permissions** shows the permission scheme. As a
     Jira administrator open **Settings** (cog) → **Issues** → **Permission schemes**; edit the scheme,
     or **Copy** it first if other projects share it and assign the copy to the project (**Project
     settings** → **Permissions** → **Actions** → **Use a different scheme**). Next to **Browse Projects**
     choose **Update** (or **Grant access**) and add the group `backoffice-readers`. Grant the group
     nothing else.
   - **Team-managed project**: **Project settings** → **Access** → **Add people**, add the account with
     the role **Viewer**. Depending on your Jira plan, Viewer can also comment; **Test** then reports Add
     Comments as excess. Where custom roles are available (**Access** → **Manage roles**), create a role
     with only browsing.

   Make sure the group (and the account through any other group, for example `jira-software-users`) does
   **not** get **Add Comments**, **Transition Issues**, **Edit Issues**, **Create Issues**, **Assign
   Issues**, **Delete Issues**, **Administer Projects** or **Manage Watchers**. If issues use issue
   security levels, the account must be allowed to see them.
4. **Create the API token as that account.** Sign in as `backoffice-jira-reader@acme.com`, open
   id.atlassian.com → **Security** → **API tokens** (https://id.atlassian.com/manage-profile/security/api-tokens)
   → **Create API token** (the classic one, not **with scopes**), name it for example
   `back-office-reader`, choose the expiry date, copy the token (shown once) and note the date.
5. **Add the connection in Back Office.** Project → **Connections** (or **Administration** →
   **Connections** for all projects) → **Add connection**: type **Jira**, the site URL, the account
   e-mail, the token, capability **Read issues** only, **Allowed projects** = the Jira project key (for
   example `SHOP`), **Token expiry date**. Click **Test before saving**: it must show **Browse Projects**
   as granted and no excess permissions. Then **Save**.

The status card of the watcher should now show this connection with **OK**.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Node fails: "no Jira connection with capabilities [...]" | no project or platform connection has the capability, or **Allowed projects** exclude the issue's Jira project | add a connection with that capability ([Add a connection](#jira-connections)), or add the key to **Allowed projects** |
| Node fails on a pinned connection | the pinned connection was deleted, lost a capability, or does not allow the issue's Jira project | pin another connection, or remove the pin |
| Designer warning "more rights than needed" | the best match has extra capabilities (often the legacy credential) | create a connection with only the needed capabilities |
| `HTTP 401` in a step | token expired or revoked | **Replace token** |
| `HTTP 403` / `404` in a step | the account cannot browse the project or the issue (role, issue security) | add the account to the project role; **Test** shows the missing permission |
| `has no transition to status X` | no transition from the current status leads to `X` | fix `toStatus` or the Jira workflow |
| **Add connection**: "BACKOFFICE_SECRETS_KEY is not configured" | the operator has not set the encryption key | set it ([Configure Back Office](#jira-config)) |
| API or worker does not start: secrets key missing or unknown key id | connections exist but the key is not configured, or a previous key was removed before the re-wrap | restore `BACKOFFICE_SECRETS_KEY` / `BACKOFFICE_SECRETS_PREVIOUS_KEYS` |
| **Test** shows "Secret unavailable" | the token cannot be decrypted with the configured keys | restore the key, or **Replace token** |
| **Start run** refused: "workflow X cannot start: ..." | a Jira step has no usable connection (saving the workflow only warned) | add the connection named in the message ([Add a connection](#jira-connections)) |
| Run waits at **Notify Jira** / **Transition issue** | the write waits for confirmation | decide it in **Your turn** ([Confirming external writes](your-turn.md#write-confirmations)) |
| Step failed: "write rejected by ..., nothing was written" | an approver rejected the write | read the comment, rework the text, run again; connect `failure` if the run should continue |
| Intake watcher starts nothing | first poll (baseline), no trigger, filter too narrow, or "refused: ..." | see [Jira intake watcher](#jira-intake), status card and messages |
| Approve is disabled: "you have no personal Jira connection ..." | the step writes as the approver and you have none for this capability and Jira project | add a [personal connection](#jira-personal-connections), or ask the designer to allow the system connection as fallback |
| Every connection shows "over-privileged" | classic tokens of personal accounts | use [scoped tokens](#jira-scoped-token) |

Related: [troubleshooting.md](troubleshooting.md#runs) for runs that fail on a Jira step.
