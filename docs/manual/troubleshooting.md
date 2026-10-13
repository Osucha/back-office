# Troubleshooting

Symptoms, their usual causes and the fix, condensed from the runbooks in `docs/runbooks/`. Start with the
audit log (**Administration** → **Audit log**, search by the `X-Correlation-Id` of the failing response)
and the API logs (`kubectl -n back-office logs deploy/back-office-api`).

**Who needs this:** platform administrator, project owner, anyone whose run or sign-in fails.

<a id="login"></a>
## Sign-in and access

| Symptom | Cause | Fix |
|---|---|---|
| `/login?error=not-invited` | the provider authenticated the person, but no invitation, allowed domain or open registration matches | **Users** → **Invite user**, add the domain under **Platform settings** → **Registration**, or for the first admin set `BACKOFFICE_AUTH_BOOTSTRAP_ADMINS` and restart |
| `error=email-not-verified` | the provider sent `email_verified=false` | verify the address at the provider (Keycloak: user → **Email verified**) |
| `error=email-missing` | no `email` claim | request scope `email`; Entra ID: add the optional `email` claim ([integrations-identity.md](integrations-identity.md#entra)) |
| `error=identity-conflict` | the e-mail is already bound to another provider identity (user recreated, provider switched) | a DBA clears the binding: `issuer`, `subject` to null, status `INVITED` (`docs/runbooks/cannot-log-in.md`) |
| `error=deactivated` | account deactivated | **Users** → user → **Activate** |
| Redirect loop after the provider login | `BO_SESSION` cookie dropped: TLS terminated at the ingress without `X-Forwarded-Proto: https`, or UI and API on different sites | send forwarded headers; serve UI, `/api`, `/oauth2`, `/login` from one origin; `BACKOFFICE_AUTH_COOKIE_SECURE=false` only for plain-HTTP development |
| Provider says "Invalid parameter: redirect_uri" | redirect URI not registered exactly (scheme, host, port) | register `<public URL>/login/oauth2/code/oidc` |
| `error=invalid-id-token` | clock skew over 60 s, or issuer mismatch (`iss` in tokens differs from the configured issuer) | enable NTP; make the issuer URL identical for API and browser |
| `invalid-token-response` | wrong client secret, or the API cannot reach the provider's token endpoint | fix the Secret; check egress and TLS trust from the API pod |
| Nobody can sign in, no provider button | issuer empty, so OIDC is off | set the issuer, or sign in locally |
| `403` with `"code":"csrf"` on writes | the `X-XSRF-TOKEN` header is missing (proxy strips it) | fix the proxy; scripts use a personal token instead of a session |
| `403` with `"permission": "..."` | the person lacks that permission | **Users** → user → **Effective permissions**; a Deny at user or group level beats roles |
| Signed out after a short time | idle timeout (24 h) or absolute timeout (24 h from sign-in), deactivation, sessions ended by an admin | expected; adjust `BACKOFFICE_AUTH_SESSION_*` |
| Local sign-in `401 invalid-credentials` | wrong password, locked, method not allowed, never set a password (the audit log has the real reason) | **Unlock account**, **Sign-in method**, **Issue set-password link** |
| `429 too-many-attempts` | 20 attempts per IP in 5 minutes | wait for `Retry-After` |
| Script gets `401` `invalid-token` | personal token expired or revoked, owner deactivated | create a new token ([integrations-ide.md](integrations-ide.md#pat)) |
| IDE: `401` `personal-token-required` from `/mcp` | not a `bo_pat_` token (session, CI token, none) | use a personal access token |
| IDE gets HTML or `404` from `/mcp` | the web pod or ingress serves `/mcp` as part of the UI | route `/mcp` (and `/.well-known/back-office/`) to the API service like `/api` |

Full runbook: `docs/runbooks/cannot-log-in.md`.

<a id="jobs"></a>
## Agent jobs and runners

| Symptom (job reason) | Cause | Fix |
|---|---|---|
| `QUEUED`, `no online runner in pool <id>` | no controller of the pool called in the last 90 s | check the controller pod and logs, outbound HTTPS to Back Office, that the runner was not revoked |
| `QUEUED`, `no runner pool can run this job` | pool labels, project list or allowed images do not fit the job's policy | fix the pool or the template/project policy; jobs are re-routed every 10 s |
| `QUEUED`, no reason | pool or runner at capacity | raise **Max concurrent jobs** or `maxConcurrent`, add replicas |
| `registration failed: 401` in the controller log | registration token used, expired (24 h) or for another pool | **Add runner** → new token |
| Runner online, jobs fail at once with `policy-violation: ...` | image not in **Allowed images**, RuntimeClass missing, ceiling lowered | fix the pool or install the RuntimeClass |
| `FAILED` / `ImagePullBackOff` | the cluster cannot pull the agent image | registry credentials (`imagePullSecrets`), image name |
| `FAILED` / `StartTimeout`, pod `Pending` | no node offers the RuntimeClass or resources | node pool with gVisor/Kata, `runtimeClasses.scheduling`, capacity |
| `FAILED` / `OOMKilled` | memory limit too small | raise `resources.memory` in the policy (within the ceiling) |
| `TIMED_OUT` / `DeadlineExceeded` | job ran past its timeout | raise `timeoutSeconds`, split the task |
| `TIMED_OUT` / `queue-timeout` | nothing leased it for 24 h | see the `QUEUED` rows, then retry the node |
| `LOST` | controller stopped heartbeating while the job might have pushed | check the node and controller; retry the node |
| `FAILED` / `lease-not-acknowledged` | leased 5 times without a heartbeat | controller cannot start sandboxes: RuntimeClass, image, RBAC |
| `budget-exceeded: ...` | job, project or platform hard limit reached | raise the limit or switch hard stop off, retry ([agent-execution.md](agent-execution.md#budgets-in-sandbox)) |
| Agent log: `model-not-allowed` (403) | agent asked for a model other than the job's (Claude Code background model) | `RUNNER_EXTRA_ALLOWED_MODELS=anthropic/claude-haiku-*` |
| **Egress denied** lists a host the build needs | host not in the effective allowlist | add it to the project or template policy, within the pool ceiling |
| `git clone failed` / `git push failed` | token missing or without `write_repository`/Contents write, branch protected, push rules, private Git host not in `egressProxy.allowedCidrs` | [integrations-git.md](integrations-git.md#gitlab-token) |
| `the receiving end does not support push options` | repository is not on GitLab | set `mergeRequest` to `false` on the node |
| Pushed, but no merge request URL | token role below Developer, target branch missing, MR already existed | fix the role or branch |

Runbooks: `docs/runbooks/agent-jobs.md`, `docs/runbooks/runner-job-failed.md`.

<a id="runs"></a>
## Runs that do not move

| Symptom | Cause | Fix |
|---|---|---|
| `RUNNING` on an agent step for long | the agent job is queued or running | open the job in **Agent jobs** and use the table above |
| `RUNNING` after an API restart | recovery resumes from the last checkpoint and re-runs the interrupted node | wait; if it reads `Resume request without a valid checkpoint`, cancel and start again |
| `WAITING` for CI, signal was sent | signal name differs from the node's, or the run could not be matched (no `Back-Office-Run` trailer, squash) | send the right name or `--run <id>`; someone with `run.signal` can send it from the run page |
| `WAITING` for a person, nothing in the inbox | the gate has an approver rule you do not satisfy, or the gate timed out | check the gate's approvers; see `docs/runbooks/stuck-pipeline-run.md` |
| Jira step fails with `HTTP 401/403/404` or "no Jira connection with capabilities" | expired token, licence, project permission, or no connection with the needed capability | [integrations-jira.md](integrations-jira.md#jira-test), [connections](integrations-jira.md#jira-connections) |
| `has no transition to status X` | no transition from the current status to `X` | fix `toStatus` or the Jira workflow |
| Step failed, **Operations** → **Dead items** lists it | the step failed all retries | fix the cause, then **Retry** |
| Oldest ready work item keeps ageing | no worker running or all busy | check worker pods; scale workers (`docs/runbooks/work-queue.md`) |
| UI still shows a finished run as active | the live-update stream was cut (proxy timeout for `text/event-stream`) | reload; raise the ingress read timeout |

<a id="providers"></a>
## LLM providers

| Symptom | Cause | Fix |
|---|---|---|
| **Settings** shows **no API key** | key missing in the provider-keys Secret, or the pod started before it was set | fix the Secret, restart api and worker |
| `provider is not configured on this server` | as above, or OpenAI-compatible with only URL or only key | set both values |
| `HTTP 401` | wrong, revoked or mistyped key (trailing newline) | rotate the key ([integrations-llm.md](integrations-llm.md#llm-overview)) |
| `HTTP 403` with a valid key | the key's project/workspace has no access to the model, or restricted permissions miss chat completions | grant the model or permission at the vendor |
| `HTTP 429` | vendor rate limit or spend cap | lower `LLM_CONCURRENCY_PER_PROVIDER` / `EVAL_CONCURRENCY_PER_PROVIDER`, raise the vendor tier |
| `HTTP 5xx` | vendor outage | retried automatically; retry the node later |
| Model not found / bad request | wrong model id in the profile, model retired | fix the profile on **Models**, test it |
| Ollama unreachable | `OLLAMA_BASE_URL` not reachable from the API pods, NetworkPolicy | fix the URL or allow the egress |
| Calls stop with `402 budget-exceeded` | a hard budget is reached | raise the budget or switch hard stop off |

Use the test button on **Models**: when it succeeds but real calls fail, the difference is size, structured
output or concurrency, not credentials. Runbook: `docs/runbooks/provider-errors.md`.

<a id="ci"></a>
## CI integration

| Symptom | Cause | Fix |
|---|---|---|
| `BACKOFFICE_CI_TOKEN is required` | the variable is not visible to the job: marked **Protect variable** while the branch is unprotected, wrong environment scope, not passed to the GitHub step | untick **Protect variable**, keep **Masked** ([integrations-gitlab-ci.md](integrations-gitlab-ci.md#ci-variables)) |
| Exit 2, `HTTP 401` | token revoked or mistyped, or a personal token instead of a CI token | create a new CI token |
| Exit 2, `HTTP 403` | the token belongs to another project | use the project's own token |
| Exit 2, `HTTP 404` unknown entrypoint | entrypoint not mapped on the project's **CI** tab, or `BACKOFFICE_URL` points elsewhere | map it, fix the URL |
| `Diff: empty` | shallow clone without the target branch | `GIT_DEPTH: "0"` / `fetch-depth: 0` |
| Exit 3 | `BACKOFFICE_REVIEW_TIMEOUT` passed | open the `Run:` link; raise the timeout or use a faster model |
| Execution `FAILED` with `Error:` | provider or workflow error | see [LLM providers](#providers) and the run page |
| Signal: "no run is waiting" | run already moved on, name differs, no trailer | pass `--run <id>` and the right name |
| Same review returned on retry | executions are idempotent per commit for 24 h | `--var force=true` |

Guide: `docs/guides/ci-integration.md`.
