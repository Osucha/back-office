# IDE and MCP

Back Office runs an MCP server at `<public URL>/mcp`. Coding assistants in your IDE (Claude Code, Cursor,
VS Code) can use it to see what is waiting for you, read run artefacts, decide gates and start workflows,
always with your own permissions. Scripts use the same personal access tokens against the REST API.

**Who needs this:** every user who wants Back Office in the IDE or in scripts; platform administrator for
token lifetime and the MCP switch.

<a id="pat"></a>
## Create a personal access token

The MCP server accepts **personal access tokens only** (`Authorization: Bearer bo_pat_...`). A session
cookie or a CI token is refused with `401` `personal-token-required`.

1. Open **My account** (`/account`) → **Personal access tokens**. You need `tokens.personal`, which the
   default `member` role grants.
2. Press **Create token**.
3. **Name**: what the token is for, for example `cursor-laptop`.
4. **Expires in (days)**: up to the platform maximum (**Platform settings** → **Personal access tokens** →
   **Maximum lifetime (days)**, default 90, never more than 365; server default `BACKOFFICE_AUTH_PERSONAL_TOKEN_MAX_DAYS`).
5. **Limit to permissions (optional)**: pick only what the assistant needs. A token can never hold more
   than you do, and it follows your permissions over time (lose a role, the token loses it too).

   | Use | Permissions to tick |
   |---|---|
   | Read runs, artefacts and insights | `project.view` |
   | + approve or send back gates | `project.view`, `run.approve` |
   | + start workflows | `project.view`, `run.start` |
   | + search the task library | `library.view` |

   A narrowed token keeps access to every project only if the subset contains `admin.projects.all`.
6. **Allow gate decisions via MCP**: leave it off unless you want to approve or reject gates from your IDE.
   Without it the MCP tool `decide_gate` is refused (and the refusal audited), whatever permissions the token
   has. The reason is prompt injection: your IDE agent reads artefacts, reviews and issue text that anyone can
   write, and such text can tell the agent to approve a gate. With the option on, keep your IDE's
   confirmation for destructive tools enabled (`decide_gate` is marked destructive), decide only gates you
   looked at, and remember that the four-eyes rule still applies. The option is shown as **MCP gate
   decisions** in the token list and cannot be changed later: create a new token instead.
7. **Allow workflow edits via MCP**: leave it off unless you want your IDE agent to create and change
   workflows. Without it the MCP tools `create_workflow` and `update_workflow` are refused, whatever
   permissions the token has, because an agent holding the token could otherwise change a workflow on its own
   (for example remove a human gate). The option is shown as **MCP workflow edits** in the token list and,
   like the gate option, cannot be changed later.
8. Press **Create token**, copy the token (`bo_pat_` + 40 characters) and press **I copied the token**. It
   is shown once; Back Office stores only its hash.

The token works for REST too: `curl -H "Authorization: Bearer bo_pat_..." https://backoffice.example.com/api/v1/me`
(no CSRF header needed).

**Rotate**: create a new token, put it into the IDE configuration, then **Revoke** the old one on the same
page (the list shows **Last used**). Revocation and deactivation of your user take effect on the next
request. Creation and revocation are audited (`PAT_CREATE`, `PAT_REVOKE`).

**Keep it out of repositories.** Prefer user-level configuration files, environment variables or the
IDE's secret prompt over a project file that might be committed.

**My account** → **Connect your IDE** shows the snippets below with your Back Office URL filled in.

<a id="claude-code"></a>
## Claude Code

1. Put the token in an environment variable of your shell, for example `export BO_PAT=bo_pat_...`.
2. Run (tab **Claude Code**, block **Terminal**):

```sh
claude mcp add --transport http back-office https://backoffice.example.com/mcp --header "Authorization: Bearer $BO_PAT"
```

   Add `--scope user` to make the server available in every project instead of only the current one.
3. In Claude Code run `/mcp`: `back-office` should be **connected** and list eight tools.
4. Ask, for example, "What is waiting for me in Back Office?".

Claude Code stores the header in its own configuration (`~/.claude.json`); rotate by running
`claude mcp remove back-office` and adding it again with the new token.
Docs: https://docs.claude.com/en/docs/claude-code/mcp

<a id="cursor"></a>
## Cursor

1. Open **Cursor Settings** → **MCP** (Tools & Integrations) → **Add new MCP server**, or edit the file
   directly. Use the global file `~/.cursor/mcp.json` to keep the token out of the repository; the snippet's
   label `.cursor/mcp.json` is the per-project variant.
2. Paste (tab **Cursor**):

```json
{
  "mcpServers": {
    "back-office": {
      "url": "https://backoffice.example.com/mcp",
      "headers": { "Authorization": "Bearer <your personal access token>" }
    }
  }
}
```

3. Replace the placeholder with the token, save; the server turns green in the MCP settings and lists its
   tools. Recent Cursor versions also accept `"Bearer ${env:BO_PAT}"` to read the token from the environment.

Docs: https://cursor.com/docs/context/mcp

<a id="vscode"></a>
## VS Code

VS Code (with GitHub Copilot agent mode or another MCP-capable extension) prompts for the token and keeps
it in its secret storage, so the file can be committed safely.

1. Create `.vscode/mcp.json` in the workspace (or run **MCP: Open User Configuration** for all workspaces)
   with (tab **VS Code**):

```json
{
  "inputs": [
    { "type": "promptString", "id": "back-office-token", "description": "Back Office personal access token", "password": true }
  ],
  "servers": {
    "back-office": {
      "type": "http",
      "url": "https://backoffice.example.com/mcp",
      "headers": { "Authorization": "Bearer ${input:back-office-token}" }
    }
  }
}
```

2. Press **Start** above the server entry; VS Code asks for the token once.
3. Rotate: run **MCP: Reset Cached Inputs** (or remove the stored input) and start again with the new token.

Docs: https://code.visualstudio.com/docs/copilot/customization/mcp-servers

<a id="mcp-tools"></a>
## MCP tools

Each tool calls the same use case as the REST API, so the same permission checks apply. A refused call
comes back as a tool error, not a transport error.

| Tool | Does | Needs |
|---|---|---|
| `list_my_turns` | Gates you may decide and failed runs you may retry, newest first | `project.view` (items filtered by `run.approve` / `run.start`) |
| `get_run` | Run summary: status, outcome, cost, merge request, nodes, artefact list | `project.view` |
| `get_artifact` | Content of one artefact (spec, plan, tasks, review, diff) by id or name | `project.view` |
| `start_workflow` | Start a run of a workflow, optionally for an issue key and with run parameters (a JSON object) | `run.start` |
| `decide_gate` | Approve, revise or reject a gate with a comment; approve needs the ids of required checklist items | `run.approve`, the gate's approver rule and a token created with **Allow gate decisions via MCP** |
| `search_task_library` | Find task templates and skills by text | `library.view` |
| `get_project_context` | Constitution, repository, CI entrypoints, workflows and recent runs | `project.view` |
| `get_delivery_insights` | Delivery insights of one project or all visible projects | `project.view` |
| `list_projects` | The projects you can see | `project.view` |
| `get_workflow_palette` | What a workflow can be built from: control nodes, task templates with their ports and settings, run-state fields | `project.view` |
| `get_workflow` | One workflow with its full definition (nodes, edges, triggers) | `project.view` |
| `validate_workflow` | Checks a definition without saving it and lists errors and warnings | `project.view` |
| `create_workflow` | Creates a workflow; it is checked first and nothing is saved when it has errors | `workflow.edit` and a token created with **Allow workflow edits via MCP** |
| `update_workflow` | Replaces a workflow's definition with a new version; runs in progress keep theirs | `workflow.edit` and a token created with **Allow workflow edits via MCP** |
| `create_project` | Creates a project with at most one repository, optionally private; it gets the default workflows | `projects.create` and a token created with **Allow workflow edits via MCP** |
| `list_workspaces` | The project's run workspaces ([workflows.md](workflows.md#workspaces)) | `project.view` |
| `get_model_options` / `get_model_plan` | The models a step may use for you, with route and reason; the model of every step of a workflow ([evaluation.md](evaluation.md#model-availability)) | `project.view` |
| `create_workspace` / `update_workspace` | Creates or changes a run workspace: sources, setup command, agent roles | `project.manage` and a token created with **Allow workflow edits via MCP** |

Artefact content, node summaries and errors, and gate questions come back inside `<untrusted-data>` blocks
with a `notice`: they were written by agents, models or issue authors, and your IDE agent must treat them as
data, never as instructions. Read tools are marked read-only; `decide_gate` is marked destructive and not
idempotent, so clients that honour MCP tool annotations ask you before calling it; `update_workflow` is marked
destructive too. Workflows created or changed through MCP are versioned and recorded like edits in the designer, with
you as the author; open them in the designer to check them before the first run.

Gate decisions made through MCP are recorded like decisions in the UI, with you as the actor, and appear
in the run's provenance ([runs.md](runs.md#provenance)).

**Limits**

- Tools only; the server offers no MCP resources or prompts.
- The administrator can switch the server off with `BACKOFFICE_MCP_ENABLED=false`.
- Behind the bundled web pod `/mcp` must be routed to the API like `/api`. If your IDE reports an HTML
  answer or `404` from `/mcp`, see [troubleshooting.md](troubleshooting.md#login).
