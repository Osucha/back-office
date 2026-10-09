# 06 — Security

## Threat model in one paragraph

A single trusted owner uses the UI. Untrusted inputs are: Jira issue text, repository contents and
merge-request diffs (all may contain prompt injection aimed at the agents), and network callers of the
API (CI jobs, runner containers). Secrets at risk: provider API keys, GitLab/Jira tokens.

## Authentication

| Caller | Mechanism |
|---|---|
| Browser UI | `X-Api-Key: <BACKOFFICE_API_KEY>` header on every request; the key is entered once and kept in `localStorage`. Spring Security filter `ApiKeyAuthenticationFilter` grants role `OWNER`. |
| Project CI job (`backoffice-ci.sh`) | Per-project bearer token `bo_ci_...` issued in Project → CI, stored as SHA-256, revocable. Valid only for `POST /api/v1/ci/executions`, `GET /api/v1/ci/executions/{id}` and `POST /api/v1/ci/signals`, and only for its own project (ADR 0013). |
| Runner container | Per-job bearer token: `HMAC-SHA256(jobId + expiry, RUNNER_TOKEN_SECRET)`, valid only for `/api/v1/internal/runner/jobs/{jobId}/**`. |

Single-owner scope is deliberate (see ADR 0009); an OIDC login can replace the API key filter without
touching the rest of the application.

## Secrets

- Provider keys and integration tokens are never stored in the database; they come from environment variables
  populated from Kubernetes Secrets (`back-office-provider-keys`, `back-office-integrations`) or a
  `.env` file locally.
- The runner receives only the keys it needs for the selected provider, injected as env from the same
  Secret with `optional: true` so absent providers do not fail the Job.
- Project CI tokens are the one secret the platform issues itself: only the hash is stored, the plaintext is
  shown once, and revocation is immediate.
- API responses never echo secrets; settings endpoints return `configured: true/false` only.

## Agent sandboxing

- Runner Jobs run as non-root, read-only root filesystem except `/workspace` (emptyDir), no service
  account token mounted, `NetworkPolicy` (optional, values flag) allowing egress only to the API
  service, the git host and provider endpoints.
- Resource limits per Job (`runner.resources` values) and `activeDeadlineSeconds`.
- Agents work on a branch and can only push that branch; least privilege per job (ADR 0031): READ jobs get a
  read-only token (Reporter, `read_repository`) and never push, PUSH_MR jobs a project access token with
  `write_repository`, role Developer and no `api` scope, which cannot merge into the protected default branch. The platform itself holds no SCM API
  token at all; merge requests are opened with git push options.

## Prompt-injection posture

Full threat model and controls: [12-prompt-injection.md](12-prompt-injection.md) (ADR 0029). In short:

- Untrusted text (issues, diffs, CI payloads, repository content, earlier artefacts, benchmark outputs) reaches
  models only inside `<untrusted-data>` blocks with a random per-prompt boundary, sanitised of bidi and
  zero-width characters; the system prompt says blocks are data, never instructions.
- Deterministic guards decide, not the model: review answers are validated and fail closed, the verdict is
  computed from findings, injection markers turn a BLOCKING review into "needs human review", and
  model-derived `confidence` is forced to 0 when the inputs carry markers.
- The coding agent never holds the git token or a token that can post results (gateway-only agent token);
  only the runner pushes, only to the job branch.
- `decide_gate` over MCP needs a personal access token created with the opt-in.
- The human gate before merge is the last line of defence and is recommended `ALWAYS` for production
  repositories; code review cannot execute code.

## Transport and headers

TLS terminates at the Ingress. The web container sets `Content-Security-Policy`,
`X-Content-Type-Options`, `Referrer-Policy`, and proxies `/api` to the API service so the browser talks
to one origin.
