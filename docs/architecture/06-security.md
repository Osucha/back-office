# 06 — Security

This page is the overview. Details: [08-identity-and-access.md](08-identity-and-access.md) (login, permissions,
tokens, audit), [10-agent-execution.md](10-agent-execution.md) (runner fleet, sandboxes, egress, credentials),
[12-prompt-injection.md](12-prompt-injection.md) (untrusted text reaching models and agents).

## Trust boundaries

| Zone | Trusted with | Talks to |
|---|---|---|
| Browser (web SPA behind nginx) | the HttpOnly session cookie (and the CSRF cookie); no bearer token in JavaScript storage | `api` over one origin (`/api` proxied by nginx) |
| `api` processes | the database, the secrets key, provider keys | browser, CI jobs, MCP clients, runner-controllers, LLM providers, Jira, git hosts |
| `worker` processes | same as `api` (one image, different role) | takes work from the PostgreSQL queue; LLM providers, Jira |
| `runner-controller` | its runner token (`bo_rt_`) and its X25519 private key | Back Office over HTTP (it pulls; Back Office never calls it), its sandbox backend |
| Sandbox (runner image + coding agent) | one job's bundle: job token, released repository / connector tokens | Back Office callbacks and LLM proxy, the git hosts and endpoints its egress policy allows |
| LLM gateway / proxy (in `api`) | provider keys | LLM providers; agents reach models only through it (unless a personal pool uses its owner's own login) |
| External systems | — | Jira issues, repository content, MR diffs and CI payloads are untrusted input |

## Tokens

All Back Office tokens are opaque random strings with a recognisable prefix; only their SHA-256 hash is stored and
the plaintext is shown once.

| Token | Prefix | Who holds it | What it reaches |
|---|---|---|---|
| Session cookie | `BO_SESSION` | the browser, after OIDC login (the API is the BFF, PKCE) or local e-mail + password login | the API; HttpOnly, SameSite=Lax, Secure configurable; Spring Session JDBC; CSRF via `XSRF-TOKEN` cookie + `X-XSRF-TOKEN` header |
| Personal access token | `bo_pat_` | a user, for scripts and MCP clients | `/api` with the user's permissions (optionally narrowed); the only credential `/mcp` accepts; `decide_gate` needs a token created with the opt-in |
| Project CI token | `bo_ci_` | a project's CI job (`backoffice-ci.sh`) | `ci.execute`, `ci.signal` on its own project |
| Runner registration token | `bo_rrt_` | an operator, once | single use, 24 h: registers one runner-controller in one pool |
| Runner token | `bo_rt_` | a runner-controller | runner protocol `/api/v1/runner/v2/**` (lease, heartbeat, complete, credential release) for its pool |
| Job token | `bo_job_` | the runner entrypoint in one sandbox | callbacks and uploads of that job, the LLM proxy; valid while the job is active |
| Agent token | `bo_job_` (scope `GATEWAY`) | the coding agent process | the LLM proxy `/api/v1/llm/**` only; callbacks refuse it, so the agent cannot post or forge the job result |

Bearer tokens are exempt from CSRF; `RunnerTokenFilter` (agent-runner) and `JobTokenFilter` (llm-gateway) refuse a
token of the wrong kind with 401.

## Secrets at rest and in transit

- Secrets stored in the database (SCM connections and project repository tokens, Jira connections, LLM provider
  connections, connector secrets) are sealed with envelope encryption: AES-256-GCM with a random data key per secret,
  wrapped by the installation's key-encryption key `BACKOFFICE_SECRETS_KEY` (id `BACKOFFICE_SECRETS_KEY_ID`, older
  keys in `BACKOFFICE_SECRETS_PREVIOUS_KEYS` for rotation). Without the key no secret can be stored. Provider keys
  and a legacy Jira credential can also come from environment variables (Kubernetes Secrets); those are not stored.
- Secrets are write-only through the API: responses carry `hasSecret` / `configured`, never a value.
- Repository and connector tokens leave Back Office only for one job: the signed job spec names them by reference;
  the runner-controller holding the lease fetches them once, HPKE-sealed to its own X25519 key, and delivers them as
  files the runner reads and deletes before the agent starts. TOTP secrets of local accounts are AES-GCM encrypted
  with their own key (`backoffice.auth.local.secret-key`).
- Job assignments are signed (Ed25519 over `jobId|specDigest|leaseExpiresAt`) and verified by the controller.

## Git and agents

- Per repository the least-privileged fitting token is chosen: a verified read-only token for jobs that only read,
  a push token only for `DELIVER` jobs (the `DELIVER_CHANGES` action), which commit the run directory, push the job
  branch and open the merge request with push options. Coding agent jobs never push.
- A `GITLAB_READ` connector gets only a verified read-only `CI_READ` token; MCP connector secrets are set only in the
  agent process's environment.
- Kubernetes sandboxes: no service account token, non-root, read-only root filesystem, no privilege escalation, all
  capabilities dropped, seccomp `RuntimeDefault`, gVisor or Kata runtime class, resource requests = limits, deadline;
  egress only through the pool's egress proxy and its allowlist.

## Prompt-injection posture

Full threat model and controls: [12-prompt-injection.md](12-prompt-injection.md) (ADR 0029). In short:

- Untrusted text (issues, diffs, CI payloads, repository content, earlier artefacts, benchmark outputs) reaches
  models only inside `<untrusted-data>` blocks with a random per-prompt boundary, sanitised of bidi and
  zero-width characters; the system prompt says blocks are data, never instructions.
- Deterministic guards decide, not the model: review answers are validated and fail closed, the verdict is
  computed from findings, injection markers turn a BLOCKING review into "needs human review", and
  model-derived `confidence` is forced to 0 when the inputs carry markers.
- The coding agent never holds the git token or a token that can post results (gateway-only agent token);
  only `DELIVER` jobs push, only to the job branch.
- The human gate before merge is the last line of defence and is recommended `ALWAYS` for production
  repositories; code review cannot execute code.

## Transport and headers

TLS terminates at the Ingress. The web container (nginx) proxies `/api` to the API service so the browser talks to
one origin, and sets `Content-Security-Policy` (`default-src 'self'`, `frame-ancestors 'none'`, no inline scripts),
`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin` and
`Permissions-Policy`.
