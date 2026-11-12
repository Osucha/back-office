# 0064 — Connectors: the tools agents use, with credentials, by MCP

- Status: accepted
- Date: 2026-11-12
- Deciders: owner
- Related: ADR 0024 (sandbox, egress), 0051 (project tokens, sealed delivery, agent tools), 0057 (agent MCP servers
  without credentials), 0061 (agent audit), 0063 (delivery is an action)

## Context

Agents use tools while they work: read CI pipelines and job logs, read an issue tracker, search documentation. Back
Office had two half-answers: the step setting `agentTools` (one hard-wired tool, `gitlab-read` = `glab` with a
read-only project token, ADR 0051) and agent MCP servers without credentials (ADR 0057). Neither lets a team add a tool
of its own, and most useful servers need a token.

MCP is the standard for this: Claude's connectors are MCP servers, and Claude Code, OpenCode, Copilot, Cursor and Codex
all speak it; the community publishes servers for most systems. Back Office should not invent a plugin API for agent
tools; it should manage MCP servers and their credentials and hand them to agents safely.

## Decision

1. **Connector.** A library entry with scope **platform** (administrators, every project) or **project** (project
   owners): `key`, `name`, `description`, `kind` and its settings:
   - `MCP_HTTP`: `url` (http/https, streamable HTTP) and `auth`: `NONE`, `BEARER` (Authorization: Bearer <secret>) or
     `HEADER` (`headerName`: <secret>). The secret is write-only, stored encrypted like other connection secrets,
     never returned;
   - `GITLAB_READ`: the former agent tool: `glab` with the project's verified read-only `CI_READ` token of a
     repository (`repository`, default the primary; ADR 0051), project scope only.
   A project connector shadows the platform connector of the same key. **Test** checks reachability (and for MCP the
   `initialize` handshake) from Back Office and records the result.
2. **Agents use connectors.** `Agent.connectors` lists connector keys. At a step they are looked up in the run's
   project, then the platform; a key with no connector there is skipped with a log line (and reported by the workflow's
   agent audit as `UNKNOWN_CONNECTOR`). Built-in coding agents list `gitlab-read`, so a project that defines it gets the
   tool without editing agents. Model answer agents have no tools; their connectors are ignored.
3. **Delivery to the sandbox.** The job spec lists the connectors (`connectors`: key, kind, url, auth type and header
   name, repository) and names their secrets in `credentials` (capability `CONNECTOR`, `connector` = key), never the
   values. The runner-controller fetches them sealed (ADR 0051) and delivers each as the bundle file
   `connector-token-<key>`; the runner gives the agent process the secret as an environment variable only, configures
   the harness (Claude Code `--mcp-config` with `${VAR}` header expansion; OpenCode `mcp` with `{env:VAR}`) and redacts
   it everywhere. `GITLAB_READ` keeps its ADR 0051 delivery.
4. **Network.** Whether the sandbox reaches a connector's host stays the egress policy's decision (ADR 0024); the
   connector page shows the host to allow.
5. **Replaced.** Agent `mcpServers` become platform connectors (`MCP_HTTP`, `NONE`) listed by their agents; step
   `agentTools` become the project connector `gitlab-read` in projects whose steps used it.

## Alternatives considered

- **A Back Office plugin API for tools.** Duplicates MCP and its ecosystem. Rejected.
- **Secrets in the agent's MCP config file.** Files in the run directory outlive the step and other agents share the
  directory (ADR 0060). Rejected: environment of the agent process only, redacted.
- **Connectors per step.** Tools are part of how an agent works (ADR 0057). Rejected.

## Consequences

- OpenAPI: `/connectors` (CRUD, test), `Connector`, `ConnectorInput`; `AgentInput.connectors` (`mcpServers` removed);
  `RunnerJobSpec.connectors` (`mcpServers`, `agentTools` removed); credential capability `CONNECTOR`; agent audit
  finding `UNKNOWN_CONNECTOR`.
