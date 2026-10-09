# Back Office agent runner

Container image that executes one coding or specification task for Back Office inside a sandbox (ADR 0006,
ADR 0024). The runner-controller (`backend/runner-controller`) starts it as an Agent Sandbox pod, a
Kubernetes `Job` or a `docker run`, hands it a job bundle, and Back Office receives log events and a
`RunnerResult` over HTTP. The protocol is in
[docs/architecture/03-pipelines-and-agents.md](../docs/architecture/03-pipelines-and-agents.md) and the
sandbox model in [docs/architecture/10-agent-execution.md](../docs/architecture/10-agent-execution.md).

## What is inside

| Tool | Why |
|---|---|
| Ubuntu 24.04, git, git-lfs, curl, jq, openssh-client, build-essential | cloning, scripting, native npm modules |
| Python 3.12 + `uv` | `entrypoint.py` (stdlib only) and Spec Kit (`specify`) |
| Node 22 (NodeSource) | OpenCode, Claude Code, JS/TS projects |
| Temurin JDK 21 (`JAVA_HOME=/usr/lib/jvm/default-jdk`) | Kotlin/Java projects (Gradle/Maven wrappers are used from the repository) |
| `opencode-ai` (OpenCode) | provider-agnostic coding agent, default |
| `@anthropic-ai/claude-code` | Claude Code, Anthropic best case |
| `specify-cli` (GitHub Spec Kit) | `specify init`, `/speckit.*` commands |

Versions are pinned with build args (`OPENCODE_VERSION`, `CLAUDE_CODE_VERSION`, `SPEC_KIT_REF`,
`UV_VERSION`, `NODE_MAJOR`, `TEMURIN_PACKAGE`). The process runs as user `runner` (uid 10001) with
`/workspace` as working directory; the image works with `readOnlyRootFilesystem: true` as long as
`/workspace` (and `/tmp`) are writable volumes - the entrypoint relocates `HOME` into `/workspace/.home`
when `/home/runner` is not writable.

## Protocol summary

Input: the **job bundle**, files in `RUNNER_JOB_DIR` (default `/var/run/backoffice/job`, a read-only Secret
volume, or a memory volume the controller fills in a warm sandbox):

| File | Content |
|---|---|
| `spec.json` | `RunnerJobSpec` (`mode`, `agent`, `model`, `repository` incl. `mergeRequest`, `specKitPhase`, `prompt`, `constitution`, `setupCommand`, `verifyCommand`, `timeoutSeconds`, `collectArtifacts`, `collectDiff`, `skills`, `runId`, `gateway`, `specKit`) |
| `policy.json` | resolved `SandboxPolicy` (the runner uses `tools.allow/deny/ask`) |
| `job-id`, `job-token` | job UUID and the per-job token (`bo_job_`); read into memory and deleted, exchanged for the agent's gateway-only token |
| `proxy-url` | egress proxy, e.g. `http://<jobId>:<secret>@bo-egress-pool.ns.svc:4750` |
| `ready` | written last; with `RUNNER_WAIT_FOR_JOB=1` the runner waits for it (`RUNNER_WAIT_SECONDS`, 0 = forever) |

Environment (static, set by the controller or for local debugging):

| Variable | Content |
|---|---|
| `RUNNER_JOB_DIR`, `RUNNER_WAIT_FOR_JOB`, `RUNNER_WAIT_SECONDS` | see above |
| `RUNNER_GIT_TOKEN_FILE` | git token file of a single-repository spec (default `/var/run/backoffice/git/token`), read once and deleted when the volume is writable |
| `RUNNER_GIT_TOKEN_DIR` | multi-repository specs: directory with one `<key>/token` per repository (default `/var/run/backoffice/git` when `RUNNER_GIT_TOKEN_FILE` is unset or the default); only the keys of the spec's repositories are read, each once, then deleted |
| `RUNNER_SANDBOX` | `1` in the image: the stray process sweep covers every process of the runner UID (otherwise only the entrypoint's descendants) |
| `RUNNER_MAX_PROCS` | `RLIMIT_NPROC` (= `resources.pids`) |
| `GIT_USER_NAME`, `GIT_USER_EMAIL` | commit identity |
| `RUNNER_CLAUDE_PERMISSION_MODE` | optional override; default `dontAsk` with a policy, `bypassPermissions` without |
| `RUNNER_VERIFY_TIMEOUT` | optional; seconds allowed for `verifyCommand` (default 900) |
| `RUNNER_WORKSPACE` | optional; defaults to `/workspace` |
| `RUNNER_RESULT_FILE` | optional; also write the `RunnerResult` JSON to this path (local debugging) |
| `RUNNER_SPEC` (base64), `RUNNER_SPEC_FILE`, `RUNNER_POLICY` (base64), `RUNNER_JOB_ID`, `RUNNER_CALLBACK_URL`, `RUNNER_CALLBACK_TOKEN`, `RUNNER_PROXY_URL`, `GIT_TOKEN` | fallbacks when there is no bundle (local debugging, legacy v1) |
| `RUNNER_ENABLE_STUB_AGENT=1` | **tests only**: allows `agent: STUB`, which runs `prompt` as a shell script with the agent environment |

Provider keys (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, ...) are not used any more and are removed from the
agent's environment together with `GIT_TOKEN` and `RUNNER_CALLBACK_TOKEN`: agents reach models only through
the Back Office LLM gateway (`spec.gateway`) with the gateway-only agent token (job token as fallback).

Steps performed by `entrypoint.py`:

1. Read the bundle (or the fallback environment); set `HTTPS_PROXY`/`HTTP_PROXY`/`https_proxy`/`http_proxy`/`ALL_PROXY` to `proxy-url` and drop `NO_PROXY`; apply `RLIMIT_NPROC`; start the log batcher (POST `{apiBaseUrl}/api/v1/internal/runner/jobs/{id}/events` through the proxy, every 2 s or 50 lines, `Authorization: Bearer <job token>`). Logging failures never abort the job. The callback base is `spec.gateway.apiBaseUrl` (a trailing `/api/v1` is accepted).
2. Clone `repository.url` at `baseRef` into `/workspace/repo`. The git token (read once from `RUNNER_GIT_TOKEN_FILE` into memory) reaches only clone/fetch/ls-remote/push through an inline credential helper (see "Prompt-injection hardening"), so it is never written to `.git/config`, a file, logs or the agent environment. Create the run branch (or re-attach to it when it already exists on the remote). Configure `user.name`/`user.email`, then snapshot `.git/config` and pin the base commit.
3. Write `skills[]` (`{key, name, content}`, Task Library ADR 0015) to `.claude/skills/<key>/SKILL.md` and `.opencode/skills/<key>/SKILL.md`, adding a `name`/`description` frontmatter when the content has none. The directories are listed in `.git/info/exclude` so they never land in the commit.
4. Run `setupCommand` (shell) when given.
5. `specKit: false` (ADR 0036: the project does not use Spec Kit): no Spec Kit work at all (no `specify init`, no constitution, no `/speckit.implement`), and a `SPEC_KIT` job is refused. Otherwise (`true`, or absent in older specs): for `SPEC_KIT` (or when a constitution is provided, or an `IMPLEMENT` job on a `specs/**/tasks.md`): `specify init --here --force --script sh --integration <claude|opencode> --non-interactive` when `.specify/` is missing (the flags are chosen from `specify init --help`, older Spec Kit versions get `--ai`/`--no-git`), then write the constitution to `.specify/memory/constitution.md`. Failures are logged and the job continues.
6. Build the prompt: `SPEC_KIT` -> `/speckit.<phase> <prompt>` (Claude Code uses the `/speckit-<phase>` skill form when Spec Kit installed skills), `IMPLEMENT` -> `/speckit.implement` + prompt when `specs/**/tasks.md` exists and `specKit` is not false, else the prompt; `EVAL_CODING` -> the prompt.
7. Render the tool policy and run the agent with `timeoutSeconds` (minus time already spent) enforced on its process group:
   - `OPENCODE`: `opencode run --model <provider>/<modelId> --format json "<prompt>"` with a generated `opencode.json` (`OPENCODE_CONFIG`): `provider.anthropic.options.baseURL = <anthropicBaseUrl>/v1`, `provider.openai.options.baseURL = <openaiBaseUrl>`, other providers as `backoffice/<modelId>` via `@ai-sdk/openai-compatible` on the gateway's OpenAI endpoint; `apiKey = {env:BACKOFFICE_JOB_TOKEN}`; `options.reasoning` mapped to per-model options (`thinking.budgetTokens`, `reasoningEffort`, ...); `permission` from the tool policy (`bash`: `*` deny when an allowlist exists, allow patterns, then deny + ask patterns last because the last matching rule wins; `webfetch`/`websearch`/`external_directory`/`question` denied).
   - `CLAUDE_CODE`: `claude -p "<prompt>" --output-format stream-json --verbose --model <modelId> --permission-mode dontAsk --settings <file>` with `ANTHROPIC_BASE_URL=<anthropicBaseUrl without /v1>`, `ANTHROPIC_AUTH_TOKEN=<agent token>`; the settings (`permissions.allow` = file tools + `Bash(<pattern>)`, `permissions.deny` = deny + ask patterns, `defaultMode: dontAsk`) are also written to `.claude/settings.local.json` (git-excluded). `MAX_THINKING_TOKENS` from `options.reasoning`, `--effort` when the installed CLI supports it.
   - Unattended jobs cannot ask: `ask` patterns are denied and reported (PROGRESS event and `result.policy.askTreatedAsDeny`); refused tool calls (Claude Code `permission_denials`, OpenCode rejected tools) go to `result.policy.denials`.
   - Token usage: OpenCode `step_finish` events (`part.tokens.{input,output,cache.read,cache.write}`, `part.cost`, summed), Claude Code final `result` event (`usage.*`, `total_cost_usd`, `result` as summary). Unknown formats produce zero usage and a WARN line.
8. Run `verifyCommand` (shell; tail 8 KB kept) when given and the agent did not time out.
9. Collect artefacts by glob (`SPEC_KIT` default `specs/**/{spec,plan,tasks,*}.md`), named `spec`/`plan`/`tasks`/`analysis` or the relative path, 2 MB cap each; a capped file is also uploaded in full (see 12).
10. `git add -A && git commit -m "<mode>: <first prompt line>"` when there are changes. With `runId` the message ends with the trailer `Back-Office-Run: <runId>` (ADR 0013), which is how `backoffice-ci signal` in the project's CI later finds the waiting run. `diffStat` from `git diff --shortstat <baseRef>...HEAD`; with `collectDiff` (default true) the full `git diff --merge-base <baseRef> HEAD` is returned as `diff` (2 MB cap, cut on a line boundary with a marker); `digests.diffSha256` is the SHA-256 of the full diff, which is uploaded when it exceeds the cap.
11. `git -c push.pushOption= push <repository.url> HEAD:refs/heads/<branch>` when `repository.push` is true and `spec.gitAccess` is `PUSH_MR` (ADR 0031: `READ` / `NONE` jobs never push, their clone gets `remote.origin.pushurl = disabled://read-only-job`, `NONE` uses no token; a spec without `gitAccess` is derived as Back Office does: IMPLEMENT/SPEC_KIT with `push` -> PUSH_MR, else READ). Push options are whitelisted (`merge_request.create`, `.target`, `.title`, `.description`, `.draft`; never `merge_when_pipeline_succeeds`, `auto_merge`, `remove_source_branch`, `ci.skip`) (explicit URL and refspec, never `origin`, never forced; the base branch and the MR target are refused). With `repository.mergeRequest` (`{title, description, targetBranch, draft}`) the push carries GitLab push options `-o merge_request.create -o merge_request.target=<t> -o merge_request.title=<title> [-o merge_request.description=<d>] [-o merge_request.draft]` (title/description collapsed to one line, capped at 255/1000 chars); the MR URL GitLab prints (`remote:   https://.../-/merge_requests/N`) is returned as `mergeRequestUrl`. Hosts that ignore push options just receive the branch. The platform never calls an SCM API.
12. Uploads: `POST {api}/api/v1/runner/v2/jobs/{id}/uploads` (`{name, contentType, sizeBytes, sha256}`, job token) returns a presigned `PUT` target; the runner uploads full versions of capped artefacts, the full diff when over 2 MB, and always the complete runner log (`runner.log`, `digests.logSha256`). Each upload is referenced as an artefact with media type `application/vnd.backoffice.blob-ref+json` and content `{blobKey, sha256, sizeBytes, mediaType}`. Upload failures are logged, never fatal.
    Multi-repository specs: see "Multi-repository jobs" below.
13. POST `RunnerResult` (plus `digests`, `policy` and `security`) to `/api/v1/internal/runner/jobs/{id}/result` (`SUCCEEDED` when the agent exited 0 and verification passed, `TIMED_OUT` on timeout, `FAILED` otherwise) with up to 12 retries. Exit code `0` when the result was delivered (even for `FAILED`), `3` when it could not be delivered.

## Multi-repository jobs

When `spec.json` has `repositories` (non-empty, or present without the legacy `repository`), the job works
with several repositories, or none (docs/architecture/10-agent-execution.md, "Multi-repository workspace"):

```json
"repositories": [
  {"key": "src", "url": "https://gitlab.example.com/shop/api.git", "baseBranch": "main", "access": "PUSH_MR",
   "path": "src", "pushBranch": "feature/shop-1", "primary": true,
   "mergeRequest": {"create": true, "title": "SHOP-1: cart", "description": "...", "draft": false}},
  {"key": "docs", "url": "https://gitlab.example.com/shop/docs.git", "baseBranch": "main", "access": "READ",
   "path": "docs", "purpose": "Product documentation"}
]
```

- Validation before anything runs: key `^[A-Za-z0-9][A-Za-z0-9_.-]{0,62}$`, unique keys, `path` relative
  (no `..`, no component starting with `.`), unique and not nested, at most one `primary` (default: the first
  `PUSH_MR`, else the first repository), `PUSH_MR` needs `pushBranch` (never the base branch or the MR target).
- Each repository is cloned into `/workspace/<path>` with its own `Git`: its own token from
  `RUNNER_GIT_TOKEN_DIR/<key>/token` (`NONE`: none), a credential helper bound to its host **and path**
  (`credential.useHttpPath=true`, `BO_GIT_PATH`), `remote.origin.pushurl = disabled://read-only-job` for READ /
  NONE, the `pushBranch` checked out for PUSH_MR, `.git/config` snapshot and pinned base.
- Agent, `setupCommand`, `verifyCommand`, Spec Kit, skills and `collectArtifacts` run in the primary repository
  (`/workspace` when the list is empty). The other repositories are reachable (OpenCode `external_directory`
  allows exactly their paths; Claude Code `--add-dir`), and the prompt gets a `## Repositories` section.
- Afterwards, per repository: git integrity restored, commit (trailer), diff against the pinned base, and for
  PUSH_MR only `push <url> HEAD:refs/heads/<pushBranch>` with the whitelisted push options (target =
  `baseBranch`). A failure stays in that repository's entry.
- Result: `repositories: [{key, branch, commitSha, diffStat, mergeRequestUrl, pushed, error}]`; the top-level
  `branch`/`commitSha`/`diffStat`/`diff`/`mergeRequestUrl` are the primary repository's; other repositories'
  diffs are artefacts `diff:<key>`; `security.gitAccessByRepository`; status `FAILED` when a PUSH_MR repository
  failed (the error names it).

Specs without `repositories` run exactly as before (`/workspace/repo`, `gitAccess`, `RUNNER_GIT_TOKEN_FILE`).

## Prompt-injection hardening

The coding agent runs as a child of `entrypoint.py` with the **same UID** and reads untrusted repository and
issue text, so anything it can reach is assumed hostile. The entrypoint therefore:

1. **Non-dumpable entrypoint.** First thing in `main()`, `prctl(PR_SET_DUMPABLE, 0)` (Linux, ctypes): the agent
   cannot ptrace the entrypoint or read its `/proc/<pid>/mem` / `environ`. Not inherited across `execve`, so the
   agent and git behave normally.
2. **Secrets only in entrypoint memory.** `job-token` and the git token file are read once and deleted
   (`consume_secret_file`). On a read-only mount (Kubernetes Secret volume) the file cannot be deleted: a WARN
   says it stays readable by the agent; the runner-controller should deliver the bundle and the git token into a
   writable memory volume. `GIT_TOKEN` (env) is still accepted for local debugging.
3. **Gateway-only agent token.** The runner exchanges the job token for an agent token
   (`POST {api}/api/v1/internal/runner/jobs/{id}/agent-token`, `{}` -> `{"token": "bo_job_..."}`), which Back Office
   accepts only at the LLM gateway, not for `result`/`events`/`uploads`. The agent gets it as
   `BACKOFFICE_JOB_TOKEN` / `ANTHROPIC_AUTH_TOKEN`; the job token stays with the entrypoint. If the exchange fails
   (offline, 404/405 from an older Back Office) the agent gets the job token, with a WARN.
4. **Scrubbed agent environment.** Besides provider keys, `GIT_TOKEN`, `GITLAB_TOKEN`, `RUNNER_CALLBACK_TOKEN`,
   `RUNNER_GIT_TOKEN_FILE`, `RUNNER_GIT_TOKEN_DIR`, `BO_GIT_PASSWORD`, `BO_GIT_HOST` and `BO_GIT_PATH` never reach the agent, `setupCommand` or
   `verifyCommand`.
5. **Stray processes.** After the agent exits and again after `verifyCommand`, every process of our UID that is
   neither the entrypoint nor one of its ancestors (tini) is `SIGKILL`ed, so no daemon can watch the push (read the
   git process environment) or change the work tree. The image sets `RUNNER_SANDBOX=1`; without it (local runs,
   tests) only the entrypoint's own descendants are swept. Inside the image this also kills any other process of
   uid 10001 in the container (for example a debug shell from `kubectl exec`).
6. **Git integrity.** Every git call runs with `-c core.hooksPath=/dev/null -c core.fsmonitor=false`,
   `GIT_CONFIG_GLOBAL=/dev/null` (the agent can write `~/.gitconfig`) and `GIT_NO_REPLACE_OBJECTS=1`; diffs use
   `--no-ext-diff --no-textconv`. After the agent and `verifyCommand`, `.git/config` is restored from the snapshot
   taken after clone (url.insteadOf, fsmonitor, diff/filter drivers, remote URLs, includes), `.git/hooks` is emptied
   and `.git/info/grafts` removed; a `.git` that is no longer a plain directory fails the job. The base commit is
   pinned before the agent runs, so moving `origin/<base>` cannot hide changes from the diff. The credential helper
   reads no file: the password is passed only in the environment of the invocation that needs it
   (`BO_GIT_PASSWORD`, plus `BO_GIT_HOST` = host of `repository.url`), and the helper answers only `get` for
   exactly that host (a redirect or `insteadOf` to another host gets nothing).
7. **Push only to the job branch.** `git push <repository.url> HEAD:refs/heads/<branch>` with the GitLab
   `-o merge_request.*` options; never `origin`, `--force`, `--mirror`, `--all` or `--tags`. `validate_push_branch`
   refuses the base branch (also as `refs/heads/<base>`), the MR target, `HEAD`, `refs/...`, `-...` and anything
   `git check-ref-format` would reject; the job FAILS with `push refused: ...` before the agent runs.
8. **Evidence in the result.** `result.security = {processNonDumpable, gitTokenFileRemoved, jobTokenFileRemoved,
   agentTokenIsolated, strayProcessesKilled, gitConfigRestored}` (`*FileRemoved` is `null` without a file,
   `gitConfigRestored` is true when the agent had changed `.git/config`). Back Office stores it as is.

Not covered: the agent can still signal (kill) the entrypoint, which ends the job without a result; and while
a secret file stays on a read-only mount it is readable.

## Build

```bash
docker build -t back-office/runner:dev runner/
# pin different agent versions
docker build -t back-office/runner:dev --build-arg OPENCODE_VERSION=1.18.32 --build-arg CLAUDE_CODE_VERSION=2.1.283 runner/
```

## Run locally

Normally the runner-controller's `DOCKER` backend starts the image (with the egress proxy and an internal
network). To run it by hand, write a bundle and mount it:

```bash
mkdir -p job
cat > job/spec.json <<'JSON'
{
  "mode": "IMPLEMENT",
  "agent": "OPENCODE",
  "model": { "provider": "ANTHROPIC", "modelId": "claude-sonnet-4-5", "options": { "reasoning": "MEDIUM" } },
  "repository": { "url": "https://gitlab.example.com/group/demo.git", "baseRef": "main", "branch": "feature/demo-1", "push": false },
  "prompt": "Add a README section describing how to run the tests.",
  "verifyCommand": "test -f README.md",
  "timeoutSeconds": 900,
  "collectDiff": true,
  "gateway": { "anthropicBaseUrl": "http://host.docker.internal:8081/api/v1/llm/anthropic",
               "openaiBaseUrl": "http://host.docker.internal:8081/api/v1/llm/openai/v1",
               "apiBaseUrl": "http://host.docker.internal:8081/api/v1" }
}
JSON
echo '{"tools":{"deny":["curl *","sudo *"]}}' > job/policy.json
echo 00000000-0000-0000-0000-000000000001 > job/job-id
echo "<job token issued by Back Office>" > job/job-token
docker run --rm --read-only --tmpfs /tmp --tmpfs /workspace:uid=10001,gid=10001,exec \
  -v "$PWD/job:/var/run/backoffice/job:ro" back-office/runner:dev
```

Without a reachable API (offline debugging) leave out `apiBaseUrl` and `job-id`; events are printed to
stdout and the result can be captured with `-e RUNNER_RESULT_FILE=/workspace/result.json`.

## Tests

```bash
python3 -m unittest discover -s runner/tests -v
```

The tests cover spec and bundle parsing (files, fallback env, waiting for `ready`), gateway configuration
for OpenCode and Claude Code (no provider keys, job token, proxy variables, scrubbed environment), tool
policy rendering (OpenCode `permission`, Claude Code settings, ask-as-deny, `permission_denials`), the
prompt-injection suite (secret files consumed, scrubbed agent environment, credential helper host check run
through real `git credential fill` and `sh`, per-invocation password, hooks/fsmonitor/ext-diff/textconv disabled,
`.git/config` and hooks restored, pinned base, push branch validation and explicit refspec, stray process
sweep on a fake `/proc`, `prctl` with a fake libc, agent token exchange with a fake opener), uploads with presigned URLs against a local HTTP server,
model/provider mapping, usage parsing, artefact naming, result building, skill files, the commit trailer,
push option composition and MR URL parsing, the diff cap, and a real `git push -o ...` to a bare repository
(`receive.advertisePushOptions=true`); one test runs `entrypoint.py` end to end against a fake `opencode`
binary (POSIX only). `test_multi_repo.py` covers multi-repository jobs: parsing and refusals, per-repository
token files, the host+path-bound credential helper (`sh` and `git credential fill`), a READ docs repository next
to a PUSH_MR source repository against local bare repositories (only the source is pushed, the docs push URL is
disabled, a NONE repository gets no token), failure isolation between two pushing repositories, an empty
repository list, and the legacy single-repository spec. The sandboxed end-to-end run (runner-controller DOCKER backend, egress proxy, STUB
agent) is `DockerEndToEndTest` in `backend/runner-controller`. Run the unit tests inside the image to use the
shipped Python and git:

```bash
docker run --rm -v "$PWD/runner:/src:ro" -w /src --entrypoint python3 back-office/runner:dev -m unittest discover -s tests -v
```
