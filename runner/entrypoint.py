#!/usr/bin/env python3
"""Back Office agent runner entrypoint.

Implements the sandbox side of the runner protocol (docs/architecture/03-pipelines-and-agents.md and
docs/architecture/10-agent-execution.md, ADR 0024):

* reads the job bundle from RUNNER_JOB_DIR (default /var/run/backoffice/job: spec.json, policy.json, job-id,
  job-token, proxy-url, ready), waiting for `ready` in warm sandboxes (RUNNER_WAIT_FOR_JOB=1); falls back to
  the environment (RUNNER_SPEC base64, RUNNER_JOB_ID, RUNNER_CALLBACK_URL, RUNNER_CALLBACK_TOKEN)
* sends everything through the egress proxy (HTTPS_PROXY) and talks to LLMs only through the Back Office
  LLM gateway (spec.gateway); provider keys are never used and are scrubbed
* clones the repository, writes skills, prepares Spec Kit, renders the tool policy into OpenCode `permission` /
  Claude Code permissions, runs the agent, verifies, commits (with the `Back-Office-Run` trailer), pushes (with
  merge request push options) and collects the diff
* streams log events to POST {api}/api/v1/internal/runner/jobs/{id}/events
* uploads large artefacts and the full log with presigned URLs from POST {api}/api/v1/runner/v2/jobs/{id}/uploads
* posts the final RunnerResult (with digests) to POST {api}/api/v1/internal/runner/jobs/{id}/result

Prompt-injection model: the coding agent is a child process with the SAME UID that reads untrusted repository and
issue text, so everything it can reach must be assumed hostile. Therefore:

* the entrypoint marks itself non-dumpable (prctl PR_SET_DUMPABLE=0) before reading any secret, so the agent
  cannot ptrace it or read /proc/<pid>/mem|environ;
* the job token and the git token are read once into entrypoint memory and their files deleted (when the volume
  is writable; otherwise a WARN and `result.security` say they stayed readable);
* the agent gets a gateway-only agent token (POST .../agent-token), never the job token that can post results,
  events and uploads (fallback for older Back Office: the job token, flagged in `result.security`);
* the git password reaches only the git invocations that talk to the remote, in their environment
  (BO_GIT_PASSWORD), through an inline credential helper that answers only `get` for the spec repository host;
* every git call disables hooks and fsmonitor and ignores the global git config, diffs use --no-ext-diff
  --no-textconv; after the agent and verifyCommand stray same-UID processes are killed, `.git/config` is
  restored from a snapshot and `.git/hooks` emptied; the push goes to the explicit spec URL with the explicit
  refspec HEAD:refs/heads/<branch> (never origin, never forced, never the base branch).

Least-privilege git (ADR 0031): `spec.gitAccess` is NONE (no credential used), READ (clone/fetch only: the runner
never pushes, `remote.origin.pushurl` is set to `disabled://`, `Git.push` refuses) or PUSH_MR (push the job branch
and open a merge request). Push options are whitelisted (`ALLOWED_PUSH_OPTIONS`): merge_request.create, .target,
.title, .description and .draft; never merge_when_pipeline_succeeds / auto_merge / remove_source_branch / ci.skip,
and `push.pushOption` from any git config is reset, so the runner can open a merge request but never merge one.

Multi-repository jobs (`spec.repositories`, docs/architecture/10-agent-execution.md "Multi-repository workspace"):
each entry `{key, url, baseBranch, access, path, pushBranch?, mergeRequest?, primary}` is cloned into
/workspace/<path> with its OWN access level and its OWN token (`<RUNNER_GIT_TOKEN_DIR>/<key>/token`, read once and
deleted; NONE repositories never get one), the credential helper is bound to that repository's host and path; the
agent works in the primary repository (the workspace when the list is empty). After the agent every repository is
restored, diffed against its pinned base, committed, and - PUSH_MR only - pushed to its own job branch; results go
to `result.repositories[]` (one failing push does not hide the others; the job FAILS when a PUSH_MR repository
failed) and the primary repository fills the legacy top-level fields. Specs without `repositories` (older control
planes) run exactly as before: one repository in /workspace/repo with `spec.gitAccess`.

Standard library only. Exit code is 0 whenever the result was delivered (even when the job FAILED)
so that the Kubernetes Job does not retry; non-zero only when the callback could not be reached.
"""

from __future__ import annotations

import base64
import fnmatch
import hashlib
import json
import os
import re
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Dict, Iterable, List, Optional, Tuple

# --------------------------------------------------------------------------------------------
# Constants
# --------------------------------------------------------------------------------------------

EVENT_FLUSH_SECONDS = 2.0
EVENT_BATCH_SIZE = 50
SUMMARY_MAX_BYTES = 20 * 1024
ARTIFACT_MAX_BYTES = 2 * 1024 * 1024
DIFF_MAX_BYTES = 2 * 1024 * 1024
VERIFY_TAIL_BYTES = 8 * 1024
RUN_TRAILER = "Back-Office-Run"
CLAUDE_DIR = ".claude"  # Claude Code project directory (skills, commands, settings)
SPECIFY_DIR = ".specify"  # Spec Kit project directory
JSON_MEDIA_TYPE = "application/json"
HTTPS_PREFIX = "https://"
USER_AGENT = "back-office-runner/2"
SKILL_DIRS = (Path(CLAUDE_DIR) / "skills", Path(".opencode") / "skills")
HTTP_TIMEOUT_SECONDS = 15
RESULT_MAX_ATTEMPTS = 12  # ~6 minutes with the backoff below

PROVIDER_TO_OPENCODE = {
    "ANTHROPIC": "anthropic",
    "OPENAI": "openai",
    "GOOGLE": "google",
    "MISTRAL": "mistral",
    "OLLAMA": "ollama",
    "OPENAI_COMPATIBLE": "openai-compatible",
}

# ModelOptions.reasoning -> OpenAI style reasoningEffort
REASONING_TO_EFFORT = {
    "LOW": "low",
    "MEDIUM": "medium",
    "HIGH": "high",
    "XHIGH": "xhigh",
    "MAX": "xhigh",
}
# ModelOptions.reasoning -> Anthropic thinking budget (tokens)
REASONING_TO_BUDGET = {
    "LOW": 4000,
    "MEDIUM": 10000,
    "HIGH": 24000,
    "XHIGH": 48000,
    "MAX": 64000,
}
# ModelOptions.reasoning -> Claude Code effort level (only used when the CLI supports --effort)
REASONING_TO_CLAUDE_EFFORT = {
    "LOW": "low",
    "MEDIUM": "medium",
    "HIGH": "high",
    "XHIGH": "max",
    "MAX": "max",
}

DEFAULT_SPEC_KIT_ARTIFACTS = [
    "specs/**/spec.md",
    "specs/**/plan.md",
    "specs/**/tasks.md",
    "specs/**/*.md",
]

ARTIFACT_NAME_BY_FILE = {
    "spec.md": "spec",
    "plan.md": "plan",
    "tasks.md": "tasks",
    "analysis.md": "analysis",
}

SECRET_ENV_NAMES = (
    "GIT_TOKEN",
    "GITLAB_TOKEN",
    "RUNNER_CALLBACK_TOKEN",
    "ANTHROPIC_API_KEY",
    "OPENAI_API_KEY",
    "GOOGLE_GENAI_API_KEY",
    "GOOGLE_GENERATIVE_AI_API_KEY",
    "GEMINI_API_KEY",
    "MISTRAL_API_KEY",
    "OPENAI_COMPATIBLE_API_KEY",
    "JIRA_API_TOKEN",
)

# Provider credentials are never used in a sandbox (ADR 0024: LLM gateway + job token). They are removed from
# the agent's environment in case an operator still injects them.
PROVIDER_ENV_NAMES = (
    "ANTHROPIC_API_KEY",
    "ANTHROPIC_AUTH_TOKEN",
    "OPENAI_API_KEY",
    "GOOGLE_GENAI_API_KEY",
    "GOOGLE_GENERATIVE_AI_API_KEY",
    "GEMINI_API_KEY",
    "MISTRAL_API_KEY",
    "OPENAI_COMPATIBLE_API_KEY",
    "OPENAI_COMPATIBLE_BASE_URL",
    "OLLAMA_BASE_URL",
    "CLAUDE_CODE_OAUTH_TOKEN",
)
# Per-invocation environment of the git calls that need credentials (see Git._cred_env).
GIT_PASSWORD_ENV = "BO_GIT_PASSWORD"
GIT_HOST_ENV = "BO_GIT_HOST"
GIT_PATH_ENV = "BO_GIT_PATH"
# Never passed to the agent process: credentials that only the runner itself (callbacks, git helper) uses.
RUNNER_ONLY_ENV_NAMES = (
    "GIT_TOKEN",
    "GITLAB_TOKEN",
    "RUNNER_CALLBACK_TOKEN",
    "RUNNER_SPEC",
    "RUNNER_POLICY",
    "RUNNER_PROXY_URL",
    "RUNNER_GIT_TOKEN_FILE",
    "RUNNER_GIT_TOKEN_DIR",
    GIT_PASSWORD_ENV,
    GIT_HOST_ENV,
    GIT_PATH_ENV,
)

DEFAULT_JOB_DIR = "/var/run/backoffice/job"
DEFAULT_GIT_TOKEN_DIR = "/var/run/backoffice/git"
DEFAULT_GIT_TOKEN_FILE = DEFAULT_GIT_TOKEN_DIR + "/token"
# Multi-repository jobs (`spec.repositories`): one token file per repository, <token dir>/<key>/token.
REPO_KEY_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,62}$")
# ADR 0044: run workspaces (a Docker volume / PVC at /workspace shared by the steps of one run)
WORKSPACE_NAME_RE = re.compile(r"^bo-ws-[0-9a-f]{32}-[a-z][a-z0-9-]{0,19}$")
WORKSPACE_KEY_RE = re.compile(r"^[a-z][a-z0-9-]{0,19}$")
# runner state inside a run workspace: which clones the runner made, agent session data per role
WORKSPACE_STATE_DIR = ".bo"
# prctl(2) option: 0 = not dumpable -> no ptrace attach and /proc/<pid>/{mem,environ,...} owned by root.
PR_SET_DUMPABLE = 4
# Added to EVERY git invocation of the runner: hooks and fsmonitor run arbitrary commands the agent could have
# planted in .git (hooks/, core.fsmonitor), and they would run with the git password in the environment.
GIT_SAFETY_ARGS = ("-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false")
# A diff/textconv driver configured by the agent could rewrite the diff reviewers see.
GIT_DIFF_SAFETY_ARGS = ("--no-ext-diff", "--no-textconv")
# Inline credential helper (valid sh). It reads nothing from disk: the password is only in the environment of the
# git invocation that needs it, and it is answered only for `get` and only for the spec repository host (a
# url.insteadOf rewrite or a redirect to another host gets nothing). Multi-repository jobs also bind it to the
# repository path (BO_GIT_PATH, with `credential.useHttpPath=true`): each repository's token is answered only for
# that repository, never for a sibling on the same host.
GIT_CREDENTIAL_HELPER = (
    '!f() { test "$1" = get || exit 0; h=; p=; '
    'while IFS= read -r l || test -n "$l"; do case "$l" in host=*) h="${l#host=}";; path=*) p="${l#path=}";; esac; done; '
    'test -n "$BO_GIT_HOST" && test "$h" = "$BO_GIT_HOST" || exit 0; '
    'if test -n "$BO_GIT_PATH"; then p="${p#/}"; p="${p%/}"; p="${p%.git}"; test "$p" = "$BO_GIT_PATH" || exit 0; fi; '
    'echo username=oauth2; printf "password=%s\\n" "$BO_GIT_PASSWORD"; }; f'
)
# Git rights of a job (ADR 0031): `spec.gitAccess`.
GIT_ACCESS_NONE = "NONE"
GIT_ACCESS_READ = "READ"
GIT_ACCESS_PUSH_MR = "PUSH_MR"
GIT_ACCESS_LEVELS = (GIT_ACCESS_NONE, GIT_ACCESS_READ, GIT_ACCESS_PUSH_MR)
# Modes whose result is pushed when `repository.push` is set (as Back Office derives the level).
PUSHING_MODES = ("IMPLEMENT", "SPEC_KIT")
# Push URL of READ / NONE clones: any `git push` (by the agent or by mistake) fails before contacting a host.
DISABLED_PUSH_URL = "disabled://read-only-job"
# The only GitLab push options the runner sends: open (or update) a merge request, never merge it. Anything else,
# e.g. merge_request.merge_when_pipeline_succeeds, merge_request.auto_merge, merge_request.remove_source_branch,
# ci.skip or ci.variable, is refused by `validate_push_options`.
ALLOWED_PUSH_OPTIONS = (
    "merge_request.create",
    "merge_request.target",
    "merge_request.title",
    "merge_request.description",
    "merge_request.draft",
)
PROXY_ENV_NAMES = ("HTTPS_PROXY", "HTTP_PROXY", "https_proxy", "http_proxy", "ALL_PROXY", "all_proxy")
STUB_AGENT = "STUB"
# Claude Code tools that are always allowed in dontAsk mode (file work inside the workspace).
CLAUDE_BASE_TOOLS = ("Read", "Edit", "Write", "MultiEdit", "Glob", "Grep", "LS", "NotebookEdit", "TodoWrite", "Task")


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


# --------------------------------------------------------------------------------------------
# Redaction
# --------------------------------------------------------------------------------------------


class Redactor:
    """Replaces secret values with *** in anything that is logged or sent to the API."""

    def __init__(self, secrets: Iterable[str]):
        self._secrets = sorted({s for s in secrets if s and len(s) >= 6}, key=len, reverse=True)
        self._url_re = re.compile(r"(https?://)([^/@\s]+)@")

    def add(self, *secrets: str) -> None:
        """Secrets learnt after start-up (the agent token) are redacted from then on."""
        self._secrets = sorted(set(self._secrets) | {s for s in secrets if s and len(s) >= 6}, key=len, reverse=True)

    def __call__(self, text: str) -> str:
        if not text:
            return text
        for s in self._secrets:
            text = text.replace(s, "***")
        return self._url_re.sub(r"\1***@", text)


# --------------------------------------------------------------------------------------------
# Process hardening and secrets (prompt-injection model, see the module docstring)
# --------------------------------------------------------------------------------------------


def harden_process(log: Callable[[str], None], libc: Any = None, platform: Optional[str] = None) -> bool:
    """prctl(PR_SET_DUMPABLE, 0): the agent runs with our UID, and a dumpable process of the same UID can be
    ptraced and its /proc/<pid>/mem and /proc/<pid>/environ read - i.e. every token held in memory. The flag is
    not inherited across execve, so the agent and git stay normal processes. Returns True when it was applied."""
    platform = platform if platform is not None else sys.platform
    if not platform.startswith("linux"):
        log(f"[runner] WARN process hardening not available on {platform}: the entrypoint stays dumpable")
        return False
    try:
        if libc is None:
            import ctypes

            libc = ctypes.CDLL(None, use_errno=True)
        rc = libc.prctl(PR_SET_DUMPABLE, 0, 0, 0, 0)
    except (OSError, AttributeError, TypeError) as e:
        log(f"[runner] WARN prctl(PR_SET_DUMPABLE, 0) unavailable: {e}")
        return False
    if rc != 0:
        log(f"[runner] WARN prctl(PR_SET_DUMPABLE, 0) failed (rc={rc}): the entrypoint stays dumpable")
        return False
    log("[runner] entrypoint is non-dumpable: same-UID processes cannot ptrace it or read its /proc memory and environment")
    return True


def consume_secret_file(path: Path) -> Tuple[Optional[str], bool]:
    """Read a secret file into memory and delete it, so the agent (same UID) cannot read it later.

    Returns (value or None, removed). A read-only mount (Kubernetes Secret volume) cannot be deleted from: the
    value is still returned and removed=False, which the caller reports.
    """
    try:
        value = Path(path).read_text(encoding="utf-8").strip()
    except OSError:
        return None, False
    try:
        os.remove(path)
        removed = True
    except OSError:
        removed = False
    return (value or None), removed


def load_git_token(env: Dict[str, str]) -> Tuple[str, Optional[bool]]:
    """Git token from `RUNNER_GIT_TOKEN_FILE` (default /var/run/backoffice/git/token), consumed once, or
    `GIT_TOKEN` for local debugging. Returns (token or "", file removed: True/False, None without a file)."""
    path = Path(env.get("RUNNER_GIT_TOKEN_FILE") or DEFAULT_GIT_TOKEN_FILE)
    if path.is_file():
        value, removed = consume_secret_file(path)
        return (value or env.get("GIT_TOKEN", "")), removed
    return env.get("GIT_TOKEN", ""), None


def git_token_dir(env: Dict[str, str]) -> Optional[Path]:
    """Directory of the per-repository token files: `RUNNER_GIT_TOKEN_DIR`, else the default directory when
    `RUNNER_GIT_TOKEN_FILE` is unset or the default file (older controllers do not set the variable). A custom
    `RUNNER_GIT_TOKEN_FILE` without `RUNNER_GIT_TOKEN_DIR` means no per-repository tokens (never guess a directory
    whose files would be deleted)."""
    if env.get("RUNNER_GIT_TOKEN_DIR"):
        return Path(env["RUNNER_GIT_TOKEN_DIR"])
    if env.get("RUNNER_GIT_TOKEN_FILE") in (None, "", DEFAULT_GIT_TOKEN_FILE):
        return Path(DEFAULT_GIT_TOKEN_DIR)
    return None


def load_repo_tokens(env: Dict[str, str], keys: Iterable[str]) -> Tuple[Dict[str, str], Optional[bool]]:
    """Per-repository git tokens (`<token dir>/<key>/token`) of the job's repositories, each read once into memory
    and its file deleted (ADR 0029). Only keys that passed `REPO_KEY_RE` are looked up. Returns ({key: token},
    every file removed: True / False when one stayed readable / None when there was no file)."""
    directory = git_token_dir(env)
    tokens: Dict[str, str] = {}
    removed_all: Optional[bool] = None
    if directory is None:
        return tokens, None
    for key in keys:
        if not REPO_KEY_RE.match(key):
            continue
        path = directory / key / "token"
        if not path.is_file() or path.is_symlink():
            continue
        value, removed = consume_secret_file(path)
        removed_all = removed if removed_all is None else (removed_all and removed)
        if value:
            tokens[key] = value
    return tokens, removed_all


def _combine_removed(*flags: Optional[bool]) -> Optional[bool]:
    """True when every present secret file was removed, False when one stayed, None without files."""
    present = [f for f in flags if f is not None]
    return all(present) if present else None


def _proc_status(proc_root: Path, pid: int) -> Optional[Dict[str, str]]:
    try:
        text = (proc_root / str(pid) / "status").read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None
    fields: Dict[str, str] = {}
    for line in text.splitlines():
        key, sep, value = line.partition(":")
        if sep:
            fields[key.strip()] = value.strip()
    return fields


def _ppid(status: Dict[str, str]) -> int:
    try:
        return int((status.get("PPid") or "0").split()[0])
    except (ValueError, IndexError):
        return 0


def _ancestors(proc_root: Path, pid: int) -> set:
    """`pid` and every ancestor (PPid chain from /proc/<pid>/status), e.g. tini as PID 1 in the image."""
    seen: set = set()
    current = pid
    while current > 0 and current not in seen:
        seen.add(current)
        status = _proc_status(proc_root, current)
        if status is None:
            break
        current = _ppid(status)
    return seen


def _sigkill(pid: int) -> None:
    os.kill(pid, signal.SIGKILL)  # SIGKILL only exists on POSIX; only called on Linux


def _read_statuses(proc_root: Path) -> Dict[int, Dict[str, str]]:
    """/proc/<pid>/status of every numeric /proc entry that is still readable."""
    statuses: Dict[int, Dict[str, str]] = {}
    for entry in proc_root.iterdir():
        if not entry.name.isdigit():
            continue
        status = _proc_status(proc_root, int(entry.name))
        if status is not None:
            statuses[int(entry.name)] = status
    return statuses


def _descendants(statuses: Dict[int, Dict[str, str]], root_pid: int) -> set:
    """Every (transitive) child of `root_pid` according to the PPid fields, without `root_pid` itself."""
    children: Dict[int, List[int]] = {}
    for pid, status in statuses.items():
        children.setdefault(_ppid(status), []).append(pid)
    found: set = set()
    todo = list(children.get(root_pid, []))
    while todo:
        pid = todo.pop()
        if pid not in found:
            found.add(pid)
            todo.extend(children.get(pid, []))
    return found


def _real_uid(status: Dict[str, str]) -> Optional[int]:
    try:
        return int((status.get("Uid") or "").split()[0])
    except (ValueError, IndexError):
        return None


def _is_stray(status: Dict[str, str], uid: int) -> bool:
    """A live (non-zombie) process of `uid`."""
    return _real_uid(status) == uid and not (status.get("State") or "").startswith("Z")


def _kill_quietly(pid: int, kill: Callable[[int], None], log: Callable[[str], None]) -> bool:
    """True when the signal was sent; a vanished process or a refused kill (logged) count as not killed."""
    try:
        kill(pid)
    except ProcessLookupError:
        return False
    except OSError as e:
        log(f"[sandbox] WARN cannot kill stray process {pid}: {e}")
        return False
    return True


def _sweep_once(
    proc_root: Path,
    uid: int,
    self_pid: int,
    skip: set,
    kill: Callable[[int], None],
    log: Callable[[str], None],
    only_descendants: bool,
) -> List[str]:
    """One sweep: kill every stray process not in `skip` (adds the killed pids to `skip`); returns `pid (name)`."""
    statuses = _read_statuses(proc_root)
    allowed = _descendants(statuses, self_pid) if only_descendants else None
    found: List[str] = []
    for pid, status in sorted(statuses.items()):
        if pid in skip or (allowed is not None and pid not in allowed):
            continue
        if _is_stray(status, uid) and _kill_quietly(pid, kill, log):
            skip.add(pid)
            found.append(f"{pid} ({status.get('Name') or '?'})")
    return found


def kill_stray_processes(
    log: Callable[[str], None],
    proc_root: Path = Path("/proc"),
    uid: Optional[int] = None,
    self_pid: Optional[int] = None,
    kill: Optional[Callable[[int], None]] = None,
    platform: Optional[str] = None,
    only_descendants: bool = False,
    rounds: int = 3,
) -> int:
    """SIGKILL every process of our real UID that is neither us nor one of our ancestors.

    Untrusted code (the agent, verifyCommand, setupCommand) can leave daemons behind that would watch the git
    push (reading /proc/<git pid>/environ for the password) or rewrite the work tree between commit and diff.
    Run after the agent and after verifyCommand. Repeated a few rounds to catch processes forked while we kill.
    `only_descendants` limits the sweep to our own process tree (used outside the runner image, so a local run on
    a developer machine never kills the user's other processes). Returns the number of processes killed.
    """
    platform = platform if platform is not None else sys.platform
    if not platform.startswith("linux") or not proc_root.is_dir():
        return 0
    uid = os.getuid() if uid is None else uid  # type: ignore[attr-defined]
    self_pid = os.getpid() if self_pid is None else self_pid
    kill = kill or _sigkill
    protected = _ancestors(proc_root, self_pid)
    skip: set = set(protected)
    killed = 0
    for round_no in range(rounds):
        found = _sweep_once(proc_root, uid, self_pid, skip, kill, log, only_descendants)
        if not found:
            break
        killed += len(found)
        log(f"[sandbox] killed {len(found)} stray process(es) left by untrusted code: {', '.join(found)[:2000]}")
        if round_no < rounds - 1:
            time.sleep(0.2)
    return killed


# --------------------------------------------------------------------------------------------
# Callback client
# --------------------------------------------------------------------------------------------


def api_base(url: str) -> str:
    """Back Office origin without a trailing `/api/v1` (callers append `/api/v1/...`)."""
    url = (url or "").strip().rstrip("/")
    return url[: -len("/api/v1")] if url.endswith("/api/v1") else url


def build_opener(proxy_url: Optional[str]) -> urllib.request.OpenerDirector:
    """urllib opener that sends every request through the egress proxy (Proxy-Authorization from the URL)."""
    if proxy_url:
        return urllib.request.build_opener(urllib.request.ProxyHandler({"http": proxy_url, "https": proxy_url}))
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


class CallbackClient:
    def __init__(self, base_url: str, job_id: str, token: str, redact: Redactor, proxy_url: Optional[str] = None):
        self.base_url = api_base(base_url) if base_url else ""
        self.job_id = job_id
        self.token = token
        self.redact = redact
        self.enabled = bool(self.base_url and job_id)
        self.opener = build_opener(proxy_url)

    def _url(self, suffix: str) -> str:
        return f"{self.base_url}/api/v1/internal/runner/jobs/{self.job_id}/{suffix}"

    def request_json(self, url: str, payload: Any, timeout: float = HTTP_TIMEOUT_SECONDS) -> Any:
        """POST JSON with the job token and return the decoded response (raises on HTTP errors)."""
        req = urllib.request.Request(
            url,
            data=json.dumps(payload).encode("utf-8"),
            method="POST",
            headers={
                "Content-Type": JSON_MEDIA_TYPE,
                "Accept": JSON_MEDIA_TYPE,
                "Authorization": f"Bearer {self.token}",
                "User-Agent": USER_AGENT,
            },
        )
        with self.opener.open(req, timeout=timeout) as resp:
            body = resp.read()
        return json.loads(body.decode("utf-8")) if body else None

    def put_bytes(self, url: str, data: bytes, headers: Dict[str, str], timeout: float = 120) -> int:
        req = urllib.request.Request(url, data=data, method="PUT", headers=dict(headers))
        with self.opener.open(req, timeout=timeout) as resp:
            return resp.status

    def post_json(self, suffix: str, payload: Any, attempts: int, base_delay: float) -> Tuple[bool, str]:
        if not self.enabled:
            return True, "callback disabled"
        data = json.dumps(payload).encode("utf-8")
        last_error = ""
        for attempt in range(1, attempts + 1):
            delivered, last_error = self._post_once(self._url(suffix), data)
            if delivered is not None:
                return delivered, last_error
            if attempt < attempts:
                time.sleep(min(base_delay * (2 ** (attempt - 1)), 60))
        return False, self.redact(last_error)

    def _post_once(self, url: str, data: bytes) -> Tuple[Optional[bool], str]:
        """One POST attempt: (True, "") delivered, (bool, error) final answer, (None, error) worth retrying."""
        req = urllib.request.Request(
            url,
            data=data,
            method="POST",
            headers={
                "Content-Type": JSON_MEDIA_TYPE,
                "Authorization": f"Bearer {self.token}",
                "User-Agent": USER_AGENT,
            },
        )
        try:
            with self.opener.open(req, timeout=HTTP_TIMEOUT_SECONDS) as resp:
                status = resp.status
        except urllib.error.HTTPError as e:
            # 4xx other than 408/429 will not get better by retrying (409 = result already posted).
            final = 400 <= e.code < 500 and e.code not in (408, 429)
            return ((e.code == 409) if final else None), f"HTTP {e.code}"
        except (urllib.error.URLError, OSError, ValueError) as e:  # network, DNS, refused
            return None, str(e)
        return (True, "") if 200 <= status < 300 else (None, f"HTTP {status}")


def exchange_agent_token(client: CallbackClient, log: Callable[[str], None]) -> Optional[str]:
    """POST {api}/api/v1/internal/runner/jobs/{id}/agent-token (job token) -> {"token": "bo_job_..."}.

    The agent token is accepted only by the LLM gateway, not by result/events/uploads, so a prompt-injected agent
    cannot forge the job result. Returns None (caller falls back to the job token, flagged in the result) when the
    exchange is not possible: offline mode, or an older Back Office without the endpoint (404/405).
    """
    if not client.enabled or not client.token:
        log("[runner] WARN no callback: agent token exchange skipped, the agent gets the job token")
        return None
    try:
        answer = client.request_json(client._url("agent-token"), {})
    except urllib.error.HTTPError as e:
        log(f"[runner] WARN agent token exchange answered HTTP {e.code} (older Back Office?): the agent gets the job token")
        return None
    except (urllib.error.URLError, OSError, ValueError) as e:
        log(f"[runner] WARN agent token exchange failed ({client.redact(str(e))}): the agent gets the job token")
        return None
    token = answer.get("token") if isinstance(answer, dict) else None
    if not isinstance(token, str) or not token.strip() or token.strip() == client.token:
        log("[runner] WARN agent token exchange returned no separate token: the agent gets the job token")
        return None
    return token.strip()


class EventSink:
    """Buffers LOG/PROGRESS events and posts them in batches (every 2 s or 50 lines)."""

    def __init__(self, client: CallbackClient, redact: Redactor, log_file: Optional["LogFile"] = None):
        self.client = client
        self.redact = redact
        self.log_file = log_file
        self._buf: List[Dict[str, str]] = []
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._wake = threading.Event()
        self._thread = threading.Thread(target=self._loop, name="event-sink", daemon=True)
        self._failures = 0

    def start(self) -> None:
        self._thread.start()

    def log(self, message: str, kind: str = "LOG") -> None:
        message = self.redact(message.rstrip("\n"))
        line = f"[{kind}] {message}" if kind != "LOG" else message
        try:
            print(line, flush=True)
        except Exception:
            pass
        if self.log_file is not None:
            self.log_file.write(f"{now_iso()} {line}")
        with self._lock:
            self._buf.append({"type": kind, "message": message[:16000], "at": now_iso()})
            if len(self._buf) >= EVENT_BATCH_SIZE:
                self._wake.set()

    def progress(self, message: str) -> None:
        self.log(message, "PROGRESS")

    def _loop(self) -> None:
        while not self._stop.is_set():
            self._wake.wait(EVENT_FLUSH_SECONDS)
            self._wake.clear()
            self._flush()

    def _flush(self) -> None:
        with self._lock:
            if not self._buf:
                return
            batch, self._buf = self._buf[:EVENT_BATCH_SIZE * 4], self._buf[EVENT_BATCH_SIZE * 4:]
        ok, err = self.client.post_json("events", batch, attempts=3, base_delay=1.0)
        if not ok:
            self._failures += 1
            if self._failures <= 3 or self._failures % 20 == 0:
                print(f"[runner] WARN could not deliver {len(batch)} log events: {err}", file=sys.stderr, flush=True)

    def close(self) -> None:
        self._stop.set()
        self._wake.set()
        self._thread.join(timeout=10)
        for _ in range(3):
            with self._lock:
                empty = not self._buf
            if empty:
                break
            self._flush()


# --------------------------------------------------------------------------------------------
# Spec parsing
# --------------------------------------------------------------------------------------------


@dataclass
class ModelRef:
    provider: str
    model_id: str
    reasoning: Optional[str] = None
    options: Dict[str, Any] = field(default_factory=dict)


@dataclass
class MergeRequest:
    """`repository.mergeRequest`: opened through git push options (ADR 0013), no SCM API involved."""

    title: str
    target_branch: str
    description: Optional[str] = None
    draft: bool = False
    create: bool = True


@dataclass
class Repository:
    url: str
    base_ref: str = "main"
    branch: Optional[str] = None
    push: bool = False
    merge_request: Optional[MergeRequest] = None


@dataclass
class RepoSpec:
    """One entry of `spec.repositories` (multi-repository jobs): cloned into <workspace>/<path> with its own access
    level (NONE | READ | PUSH_MR) and its own token; only PUSH_MR repositories push `push_branch`."""

    key: str
    url: str
    base_ref: str
    access: str
    path: str
    push_branch: Optional[str] = None
    merge_request: Optional[MergeRequest] = None
    primary: bool = False
    purpose: Optional[str] = None

    @property
    def can_push(self) -> bool:
        return self.access == GIT_ACCESS_PUSH_MR


@dataclass
class WorkspaceSpec:
    """ADR 0044: `spec.workspace`: the run workspace mounted at /workspace (clones of earlier steps are reused),
    the agent session role to continue, and prepare-only (clone + setupCommand, no agent)."""

    name: str
    key: str
    session_role: Optional[str] = None
    prepare_only: bool = False
    # the workspace's own setup (declared on the workflow): run once per workspace, again only when it changes
    setup_command: Optional[str] = None


@dataclass
class Skill:
    key: str
    name: str
    content: str


@dataclass
class Gateway:
    """`RunnerJobSpec.gateway`: Back Office LLM gateway endpoints (ADR 0024). The job token is the API key."""

    anthropic_base_url: Optional[str] = None
    openai_base_url: Optional[str] = None
    api_base_url: Optional[str] = None


@dataclass
class JobSpec:
    mode: str
    agent: str
    model: ModelRef
    prompt: str
    timeout_seconds: int
    repository: Optional[Repository] = None
    spec_kit_phase: Optional[str] = None
    constitution: Optional[str] = None
    setup_command: Optional[str] = None
    verify_command: Optional[str] = None
    collect_artifacts: List[str] = field(default_factory=list)
    collect_diff: bool = True
    skills: List[Skill] = field(default_factory=list)
    run_id: Optional[str] = None
    gateway: Optional[Gateway] = None
    # NONE | READ | PUSH_MR (ADR 0031); only PUSH_MR jobs push. Multi-repository jobs: the highest level of any
    # repository (logging and `result.security` only; every repository uses its own level).
    git_access: str = GIT_ACCESS_NONE
    # True when the spec carries `repositories` (then `repository` is None); False = legacy single-repo spec.
    multi_repo: bool = False
    repositories: List[RepoSpec] = field(default_factory=list)
    # ADR 0036: False = the project does not use Spec Kit: no `specify init`, no constitution, no `/speckit.*` command.
    # None = a spec from an older Back Office (or a legacy-built job): the earlier behaviour (Spec Kit for SPEC_KIT
    # jobs, jobs with a constitution and IMPLEMENT jobs on a tasks.md).
    spec_kit: Optional[bool] = None
    # ADR 0040: GATEWAY (the Back Office LLM gateway) or LOCAL_LOGIN (Claude Code uses the personal runner owner's own
    # login, delivered as <job dir>/claude-login-token; other harnesses still use the gateway).
    llm_credentials: str = "GATEWAY"
    # ADR 0044: the run workspace; None = a throwaway workspace and a new agent session
    workspace: Optional[WorkspaceSpec] = None

    @property
    def local_login(self) -> bool:
        return self.llm_credentials == LLM_CREDENTIALS_LOCAL_LOGIN

    @property
    def spec_kit_allowed(self) -> bool:
        return self.spec_kit is not False

    @property
    def primary_repository(self) -> Optional[RepoSpec]:
        return next((r for r in self.repositories if r.primary), None)


def stub_agent_enabled(env: Optional[Dict[str, str]] = None) -> bool:
    """The STUB agent (runs `prompt` as a shell script) exists for end-to-end tests only."""
    return (env if env is not None else os.environ).get("RUNNER_ENABLE_STUB_AGENT") == "1"


def parse_gateway(raw: Any) -> Optional[Gateway]:
    if not isinstance(raw, dict):
        return None
    gw = Gateway(
        anthropic_base_url=(str(raw["anthropicBaseUrl"]) if raw.get("anthropicBaseUrl") else None),
        openai_base_url=(str(raw["openaiBaseUrl"]) if raw.get("openaiBaseUrl") else None),
        api_base_url=(str(raw["apiBaseUrl"]) if raw.get("apiBaseUrl") else None),
    )
    return gw if (gw.anthropic_base_url or gw.openai_base_url or gw.api_base_url) else None


REQUIRED_SPEC_KEYS = ("mode", "agent", "model", "prompt", "timeoutSeconds")
LLM_CREDENTIALS_LOCAL_LOGIN = "LOCAL_LOGIN"
LLM_CREDENTIALS = ("GATEWAY", LLM_CREDENTIALS_LOCAL_LOGIN)
LOCAL_LOGIN_FILE = "claude-login-token"
BILLING_SUBSCRIPTION = "SUBSCRIPTION"
SPEC_MODES = ("SPEC_KIT", "IMPLEMENT", "EVAL_CODING")
SPEC_AGENTS = ("OPENCODE", "CLAUDE_CODE")


def _opt_str(raw: Dict[str, Any], key: str) -> Optional[str]:
    """`str(raw[key])` when the value is truthy, else None."""
    value = raw.get(key)
    return str(value) if value else None


def _parse_model(model_raw: Dict[str, Any]) -> ModelRef:
    options = dict(model_raw.get("options") or {})
    reasoning = _opt_str(options, "reasoning")
    model = ModelRef(
        provider=str(model_raw["provider"]).upper(),
        model_id=str(model_raw["modelId"]),
        reasoning=reasoning.upper() if reasoning else None,
        options=options,
    )
    if model.provider not in PROVIDER_TO_OPENCODE:
        raise ValueError(f"unknown model provider {model.provider}")
    return model


def _parse_merge_request(repo_raw: Dict[str, Any]) -> Optional[MergeRequest]:
    mr_raw = repo_raw.get("mergeRequest")
    if not isinstance(mr_raw, dict) or not mr_raw.get("create", True):
        return None
    target = str(mr_raw.get("targetBranch") or repo_raw.get("baseRef") or "main")
    title = str(mr_raw.get("title") or "").strip() or f"Back Office: {str(repo_raw.get('branch') or 'agent changes')}"
    return MergeRequest(
        title=title,
        target_branch=target,
        description=_opt_str(mr_raw, "description"),
        draft=bool(mr_raw.get("draft", False)),
        create=True,
    )


def _parse_repository(repo_raw: Any) -> Optional[Repository]:
    if not repo_raw or not repo_raw.get("url"):
        return None
    return Repository(
        url=str(repo_raw["url"]),
        base_ref=str(repo_raw.get("baseRef") or "main"),
        branch=_opt_str(repo_raw, "branch"),
        push=bool(repo_raw.get("push", False)),
        merge_request=_parse_merge_request(repo_raw),
    )


def _parse_skills(raw_skills: Any) -> List[Skill]:
    """Entries without a key or content (or not objects) are skipped."""
    return [
        Skill(key=str(s["key"]), name=str(s.get("name") or s["key"]), content=str(s["content"]))
        for s in raw_skills or []
        if isinstance(s, dict) and s.get("key") and s.get("content")
    ]


def _require_spec_keys(raw: Any) -> None:
    if not isinstance(raw, dict):
        raise ValueError("RunnerJobSpec must be a JSON object")
    for key in REQUIRED_SPEC_KEYS:
        if key not in raw or raw[key] in (None, ""):
            raise ValueError(f"RunnerJobSpec.{key} is required")


def _validate_mode_and_agent(mode: str, agent: str) -> None:
    if mode not in SPEC_MODES:
        raise ValueError(f"unknown mode {mode}")
    if agent not in SPEC_AGENTS and not (agent == STUB_AGENT and stub_agent_enabled()):
        raise ValueError(f"unknown agent {agent}")


def derive_git_access(mode: str, repo: Optional[Repository]) -> str:
    """The level of a spec without `gitAccess` (Back Office before ADR 0031): same rule as Back Office."""
    if repo is None:
        return GIT_ACCESS_NONE
    return GIT_ACCESS_PUSH_MR if mode in PUSHING_MODES and repo.push else GIT_ACCESS_READ


def _parse_git_access(raw: Dict[str, Any], mode: str, repo: Optional[Repository]) -> str:
    """`gitAccess` of the spec (validated) or the derived level. A job below PUSH_MR never pushes: `push` and the
    merge request of its repository are switched off here, whatever the rest of the spec says."""
    declared = raw.get("gitAccess")
    if declared in (None, ""):
        access = derive_git_access(mode, repo)
    else:
        access = str(declared).upper()
        if access not in GIT_ACCESS_LEVELS:
            raise ValueError(f"unknown gitAccess {declared!r} (expected NONE, READ or PUSH_MR)")
    if repo is None:
        return GIT_ACCESS_NONE
    if access != GIT_ACCESS_PUSH_MR:
        repo.push = False
        repo.merge_request = None
    return access


# Workspace entries of the runner itself; no repository may be cloned over them.
_RESERVED_WORKSPACE_NAMES = ("opencode.json",)


def normalise_repo_path(raw_path: Any, key: str) -> str:
    """`path` of a repository entry: relative, `/`-separated, no `.`/`..`/empty components, no component starting
    with `.` (the runner's `.home`, `.runner`, `.claude-config` live in the workspace), not the workspace itself.
    Defaults to the key. Raises ValueError."""
    text = (str(raw_path).strip() if raw_path not in (None, "") else key).replace("\\", "/")
    if text.startswith("/") or ":" in text:
        raise ValueError(f"repositories[{key}].path {raw_path!r} must be a relative path below the workspace")
    text = text.strip("/")
    if not text:
        raise ValueError(f"repositories[{key}].path {raw_path!r} must be a relative path below the workspace")
    parts = text.split("/")
    for part in parts:
        if part in ("", ".", "..") or part.startswith(".") or not re.match(r"^[A-Za-z0-9_.-]+$", part):
            raise ValueError(f"repositories[{key}].path {raw_path!r} has an invalid component {part!r}")
    if parts[0] in _RESERVED_WORKSPACE_NAMES:
        raise ValueError(f"repositories[{key}].path {raw_path!r} is reserved by the runner")
    return "/".join(parts)


def _parse_repo_merge_request(entry: Dict[str, Any], base_ref: str, branch: Optional[str]) -> Optional[MergeRequest]:
    mr_raw = entry.get("mergeRequest")
    if not isinstance(mr_raw, dict) or not mr_raw.get("create", True):
        return None
    title = str(mr_raw.get("title") or "").strip() or f"Back Office: {branch or 'agent changes'}"
    return MergeRequest(
        title=title,
        target_branch=str(mr_raw.get("targetBranch") or base_ref),
        description=_opt_str(mr_raw, "description"),
        draft=bool(mr_raw.get("draft", False)),
        create=True,
    )


def _parse_repo_access(entry: Dict[str, Any], key: str) -> str:
    """`access` of a repository entry; absent = READ (the safe default)."""
    declared = entry.get("access")
    access = GIT_ACCESS_READ if declared in (None, "") else str(declared).upper()
    if access not in GIT_ACCESS_LEVELS:
        raise ValueError(f"repositories[{key}].access {declared!r} is unknown (expected NONE, READ or PUSH_MR)")
    return access


def _parse_repo_entry(index: int, entry: Any) -> RepoSpec:
    if not isinstance(entry, dict):
        raise ValueError(f"repositories[{index}] must be an object")
    key = str(entry.get("key") or "").strip()
    if not REPO_KEY_RE.match(key):
        raise ValueError(f"repositories[{index}].key {entry.get('key')!r} must match {REPO_KEY_RE.pattern}")
    url = str(entry.get("url") or "").strip()
    if not url:
        raise ValueError(f"repositories[{key}].url is required")
    access = _parse_repo_access(entry, key)
    base_ref = str(entry.get("baseBranch") or entry.get("baseRef") or "main")
    push_branch = _opt_str(entry, "pushBranch") if access == GIT_ACCESS_PUSH_MR else None
    if access == GIT_ACCESS_PUSH_MR and not push_branch:
        raise ValueError(f"repositories[{key}] has access PUSH_MR but no pushBranch")
    return RepoSpec(
        key=key,
        url=url,
        base_ref=base_ref,
        access=access,
        path=normalise_repo_path(entry.get("path"), key),
        push_branch=push_branch,
        # READ / NONE repositories never push: their merge request is dropped whatever the entry says
        merge_request=_parse_repo_merge_request(entry, base_ref, push_branch) if access == GIT_ACCESS_PUSH_MR else None,
        primary=entry.get("primary") is True,
        purpose=(str(entry.get("purpose") or entry.get("description") or "").strip() or None),
    )


def _check_repo_layout(repos: List[RepoSpec]) -> None:
    """Unique keys, unique and non-nested paths, at most one primary repository."""
    keys = [r.key for r in repos]
    duplicates = sorted({k for k in keys if keys.count(k) > 1})
    if duplicates:
        raise ValueError(f"repositories: duplicate key(s) {', '.join(duplicates)}")
    for a in repos:
        for b in repos:
            if a is not b and (a.path == b.path or b.path.startswith(a.path + "/")):
                raise ValueError(f"repositories: path {b.path!r} ({b.key}) is inside or equal to {a.path!r} ({a.key})")
    if sum(1 for r in repos if r.primary) > 1:
        raise ValueError("repositories: more than one primary repository")


def parse_repositories(raw_list: Any) -> List[RepoSpec]:
    """`spec.repositories` -> validated entries. Without an explicit primary the first PUSH_MR repository (else the
    first one) becomes the primary, i.e. the agent's working directory."""
    if not isinstance(raw_list, list):
        raise ValueError("repositories must be a list")
    repos = [_parse_repo_entry(i, e) for i, e in enumerate(raw_list)]
    _check_repo_layout(repos)
    if repos and not any(r.primary for r in repos):
        (next((r for r in repos if r.can_push), None) or repos[0]).primary = True
    return repos


def highest_git_access(repos: List[RepoSpec]) -> str:
    levels = {r.access for r in repos}
    for level in (GIT_ACCESS_PUSH_MR, GIT_ACCESS_READ):
        if level in levels:
            return level
    return GIT_ACCESS_NONE


def is_multi_repo_spec(raw: Dict[str, Any]) -> bool:
    """`repositories` present and non-empty, or present (even empty) without the legacy `repository`: the new
    contract. An absent / empty list next to the legacy `repository` (older control plane) is the legacy spec."""
    repos = raw.get("repositories")
    if not isinstance(repos, list):
        return False
    legacy = raw.get("repository")
    return bool(repos) or not (isinstance(legacy, dict) and legacy.get("url"))


def parse_workspace(raw: Any) -> Optional[WorkspaceSpec]:
    """`spec.workspace` (ADR 0044); names and keys are validated (the name is a volume the controller mounted)."""
    if raw is None:
        return None
    if not isinstance(raw, dict):
        raise ValueError("workspace must be an object")
    name, key = str(raw.get("name") or ""), str(raw.get("key") or "")
    if not WORKSPACE_NAME_RE.match(name):
        raise ValueError(f"workspace.name {name!r} is not a run workspace name")
    if not WORKSPACE_KEY_RE.match(key):
        raise ValueError(f"workspace.key {key!r} is not valid")
    role = raw.get("sessionRole")
    if role is not None and not WORKSPACE_KEY_RE.match(str(role)):
        raise ValueError(f"workspace.sessionRole {role!r} is not valid")
    setup = raw.get("setupCommand")
    return WorkspaceSpec(
        name=name, key=key, session_role=str(role) if role else None, prepare_only=raw.get("prepareOnly") is True,
        setup_command=str(setup) if setup else None,
    )


def parse_spec(raw: Dict[str, Any]) -> JobSpec:
    _require_spec_keys(raw)
    model = _parse_model(raw["model"])
    workspace = parse_workspace(raw.get("workspace"))
    # a workspace job without repositories still takes the multi-repository path (the agent works in /workspace)
    multi = is_multi_repo_spec(raw) or (workspace is not None and not (isinstance(raw.get("repository"), dict) and raw["repository"].get("url")))
    repositories = parse_repositories(raw.get("repositories") or []) if multi else []
    repo = None if multi else _parse_repository(raw.get("repository"))
    skills = _parse_skills(raw.get("skills"))
    mode = str(raw["mode"]).upper()
    agent = str(raw["agent"]).upper()
    _validate_mode_and_agent(mode, agent)
    git_access = highest_git_access(repositories) if multi else _parse_git_access(raw, mode, repo)
    collect = list(raw.get("collectArtifacts") or [])
    if not collect and mode == "SPEC_KIT":
        collect = list(DEFAULT_SPEC_KIT_ARTIFACTS)
    phase = _opt_str(raw, "specKitPhase")
    spec_kit = raw.get("specKit")
    if spec_kit is not None and not isinstance(spec_kit, bool):
        raise ValueError("specKit must be a boolean")
    if mode == "SPEC_KIT" and spec_kit is False:
        raise ValueError("a SPEC_KIT job with specKit false: Spec Kit is off for this project")
    llm_credentials = str(raw.get("llmCredentials") or "GATEWAY").upper()
    if llm_credentials not in LLM_CREDENTIALS:
        raise ValueError(f"llmCredentials must be one of {', '.join(LLM_CREDENTIALS)}")
    return JobSpec(
        mode=mode,
        agent=agent,
        model=model,
        prompt=str(raw["prompt"]),
        timeout_seconds=int(raw["timeoutSeconds"]),
        repository=repo,
        spec_kit_phase=phase.upper() if phase else None,
        constitution=raw.get("constitution") or None,
        setup_command=raw.get("setupCommand") or None,
        verify_command=raw.get("verifyCommand") or None,
        collect_artifacts=collect,
        collect_diff=bool(raw.get("collectDiff", True)),
        skills=skills,
        run_id=_opt_str(raw, "runId"),
        gateway=parse_gateway(raw.get("gateway")),
        git_access=git_access,
        multi_repo=multi,
        repositories=repositories,
        spec_kit=spec_kit,
        llm_credentials=llm_credentials,
        workspace=workspace,
    )


def decode_spec(b64: str) -> JobSpec:
    try:
        raw = json.loads(base64.b64decode(b64).decode("utf-8"))
    except Exception as e:  # noqa: BLE001
        raise ValueError(f"RUNNER_SPEC is not base64 JSON: {e}") from e
    return parse_spec(raw)


# --------------------------------------------------------------------------------------------
# Job bundle (ADR 0024): files mounted by the runner-controller
# --------------------------------------------------------------------------------------------


@dataclass
class JobContext:
    job_id: str
    token: str
    callback_url: str
    spec: JobSpec
    policy: Optional[Dict[str, Any]]
    proxy_url: Optional[str]
    source: str
    # True: job-token file deleted after reading; False: it stays readable (read-only mount); None: no file.
    token_file_removed: Optional[bool] = None


def _read_text(path: Path) -> Optional[str]:
    try:
        return path.read_text(encoding="utf-8").strip()
    except OSError:
        return None


def wait_for_bundle(job_dir: Path, timeout_seconds: float, poll: float = 0.5) -> bool:
    """Warm sandboxes start before they are claimed: wait until the controller wrote `ready`."""
    deadline = time.monotonic() + timeout_seconds if timeout_seconds > 0 else None
    while True:
        if (job_dir / "ready").exists():
            return True
        if deadline is not None and time.monotonic() >= deadline:
            return False
        time.sleep(poll)


def _load_spec(env: Dict[str, str], job_dir: Path) -> Tuple[JobSpec, str]:
    """(spec, source): `RUNNER_SPEC_FILE` or <job dir>/spec.json, else base64 `RUNNER_SPEC`."""
    spec_file = Path(env["RUNNER_SPEC_FILE"]) if env.get("RUNNER_SPEC_FILE") else job_dir / "spec.json"
    if spec_file.is_file():
        try:
            raw = json.loads(spec_file.read_text(encoding="utf-8"))
        except (OSError, ValueError) as e:
            raise ValueError(f"{spec_file} is not JSON: {e}") from e
        return parse_spec(raw), str(spec_file)
    if env.get("RUNNER_SPEC"):
        return decode_spec(env["RUNNER_SPEC"]), "RUNNER_SPEC"
    raise ValueError(f"no job spec: neither {spec_file} nor RUNNER_SPEC")


def _load_policy(env: Dict[str, str], job_dir: Path) -> Optional[Dict[str, Any]]:
    """<job dir>/policy.json, else base64 `RUNNER_POLICY`; None when absent or not a JSON object."""
    policy_text = _read_text(job_dir / "policy.json")
    if policy_text is None and env.get("RUNNER_POLICY"):
        policy_text = base64.b64decode(env["RUNNER_POLICY"]).decode("utf-8")
    if not policy_text:
        return None
    try:
        loaded = json.loads(policy_text)
    except ValueError as e:
        raise ValueError(f"policy.json is not JSON: {e}") from e
    return loaded if isinstance(loaded, dict) else None


def _load_job_token(env: Dict[str, str], job_dir: Path) -> Tuple[str, Optional[bool]]:
    """The job token can post the result: keep it only in memory (the agent can read any file we can).
    Returns (token, file removed: True/False, None without a file)."""
    token_file = job_dir / "job-token"
    token: Optional[str] = None
    token_removed: Optional[bool] = None
    if token_file.is_file():
        token, token_removed = consume_secret_file(token_file)
    return (token or env.get("RUNNER_CALLBACK_TOKEN", "")), token_removed


def load_local_login_token(env: Dict[str, str], ctx: Optional[JobContext]) -> Tuple[str, Optional[bool]]:
    """ADR 0040: the owner's Claude Code login of a LOCAL_LOGIN job, read once from the bundle and deleted, so only the
    Claude Code process gets it (as CLAUDE_CODE_OAUTH_TOKEN). ("", None) for gateway jobs or without the file."""
    if ctx is None or not ctx.spec.local_login:
        return "", None
    path = Path(env.get("RUNNER_JOB_DIR") or DEFAULT_JOB_DIR) / LOCAL_LOGIN_FILE
    if not path.is_file():
        return "", None
    token, removed = consume_secret_file(path)
    return token or "", removed


def _callback_url(spec: JobSpec, env: Dict[str, str]) -> str:
    """spec.gateway.apiBaseUrl wins over `RUNNER_CALLBACK_URL`."""
    gateway_api = spec.gateway.api_base_url if spec.gateway else None
    return gateway_api or env.get("RUNNER_CALLBACK_URL", "")


def load_job_context(env: Dict[str, str]) -> JobContext:
    """Bundle files first (`RUNNER_JOB_DIR`), then the legacy environment variables."""
    job_dir = Path(env.get("RUNNER_JOB_DIR") or DEFAULT_JOB_DIR)
    if env.get("RUNNER_WAIT_FOR_JOB") == "1" and not wait_for_bundle(job_dir, float(env.get("RUNNER_WAIT_SECONDS") or 0)):
        raise ValueError(f"no job bundle appeared in {job_dir}")
    spec, source = _load_spec(env, job_dir)
    policy = _load_policy(env, job_dir)
    job_id = _read_text(job_dir / "job-id") or env.get("RUNNER_JOB_ID", "")
    token, token_removed = _load_job_token(env, job_dir)
    proxy_url = _read_text(job_dir / "proxy-url") or env.get("RUNNER_PROXY_URL") or None
    callback = _callback_url(spec, env)
    return JobContext(
        job_id=job_id, token=token, callback_url=callback, spec=spec, policy=policy, proxy_url=proxy_url, source=source,
        token_file_removed=token_removed,
    )


def apply_proxy_env(env: Dict[str, str], proxy_url: Optional[str]) -> None:
    """All traffic of the runner and the agent goes through the egress proxy; nothing bypasses it."""
    if not proxy_url:
        return
    for name in PROXY_ENV_NAMES:
        env[name] = proxy_url
    for name in ("NO_PROXY", "no_proxy"):
        env.pop(name, None)


def apply_process_limit(env: Dict[str, str], log: Callable[[str], None]) -> None:
    """RLIMIT_NPROC = SandboxPolicy.resources.pids (`RUNNER_MAX_PROCS`); Kubernetes has no pod pids field."""
    value = env.get("RUNNER_MAX_PROCS")
    if not value or os.name != "posix":
        return
    try:
        import resource  # POSIX only

        limit = int(value)
        soft, hard = resource.getrlimit(resource.RLIMIT_NPROC)
        new_hard = limit if hard == resource.RLIM_INFINITY else min(hard, limit)
        resource.setrlimit(resource.RLIMIT_NPROC, (min(limit, new_hard), new_hard))
        log(f"[runner] process limit RLIMIT_NPROC={min(limit, new_hard)}")
    except (ValueError, OSError) as e:
        log(f"[runner] WARN cannot apply RUNNER_MAX_PROCS={value}: {e}")


# --------------------------------------------------------------------------------------------
# Tool policy (SandboxPolicy.tools) -> OpenCode `permission` and Claude Code permissions
# --------------------------------------------------------------------------------------------


@dataclass
class ToolPolicy:
    allow: List[str] = field(default_factory=list)
    deny: List[str] = field(default_factory=list)
    ask: List[str] = field(default_factory=list)

    @property
    def effective_deny(self) -> List[str]:
        """Unattended jobs cannot ask anybody: `ask` means deny (and is reported)."""
        return list(dict.fromkeys(self.deny + self.ask))


def tool_policy_from(policy: Optional[Dict[str, Any]]) -> Optional[ToolPolicy]:
    if not isinstance(policy, dict):
        return None
    tools = policy.get("tools") if isinstance(policy.get("tools"), dict) else {}

    def strs(v: Any) -> List[str]:
        return [str(x).strip() for x in v if str(x).strip()] if isinstance(v, list) else []

    return ToolPolicy(allow=strs(tools.get("allow")), deny=strs(tools.get("deny")), ask=strs(tools.get("ask")))


def _external_directory_rule(extra_dirs: Iterable[str]) -> Any:
    """`external_directory` with a policy: denied, except the job's other repositories (multi-repository jobs)."""
    dirs = [d.rstrip("/") for d in extra_dirs if d]
    if not dirs:
        return "deny"
    rule: Dict[str, str] = {"*": "deny"}
    for d in dirs:
        rule[d] = "allow"
        rule[d + "/**"] = "allow"
    return rule


def opencode_permission(policy: Optional[ToolPolicy], extra_dirs: Iterable[str] = ()) -> Dict[str, Any]:
    """OpenCode `permission` block (https://opencode.ai/docs/permissions/, last matching rule wins).

    With an allowlist, unmatched shell commands are denied; deny (and ask) patterns come last so they win.
    `extra_dirs` (the other repositories of a multi-repository job) stay reachable when external directories are
    otherwise denied.
    """
    if policy is None:
        return {"*": "allow", "edit": "allow", "bash": "allow", "webfetch": "allow", "external_directory": "allow", "doom_loop": "allow"}
    bash: Dict[str, str] = {"*": "deny" if policy.allow else "allow"}
    for pattern in policy.allow:
        bash[pattern] = "allow"
    for pattern in policy.effective_deny:
        bash[pattern] = "deny"
    return {
        "*": "allow",
        "read": "allow",
        "edit": "allow",
        "glob": "allow",
        "grep": "allow",
        "bash": bash,
        "webfetch": "deny",
        "websearch": "deny",
        "external_directory": _external_directory_rule(extra_dirs),
        "doom_loop": "deny",
        "question": "deny",
    }


def claude_rule(pattern: str) -> str:
    """`git *` -> `Bash(git *)`; rules that already name a tool (`WebFetch(...)`, `Edit`) are kept."""
    if re.match(r"^[A-Z][A-Za-z]+(\(.*\))?$", pattern):
        return pattern
    return f"Bash({pattern})"


def claude_settings(policy: ToolPolicy) -> Dict[str, Any]:
    """Claude Code settings for `--permission-mode dontAsk`: everything not allowed is denied without asking."""
    allow = list(CLAUDE_BASE_TOOLS)
    allow += [claude_rule(p) for p in policy.allow] if policy.allow else ["Bash"]
    return {
        "permissions": {
            "allow": list(dict.fromkeys(allow)),
            "deny": list(dict.fromkeys(claude_rule(p) for p in policy.effective_deny)),
            "defaultMode": "dontAsk",
        },
        "env": {"DISABLE_AUTOUPDATER": "1", "DISABLE_TELEMETRY": "1"},
    }


def command_matches(patterns: Iterable[str], command: str) -> Optional[str]:
    """First pattern (`*` wildcard) the command matches, for reporting."""
    for p in patterns:
        if fnmatch.fnmatchcase(command.strip(), p) or command.strip() == p.rstrip(" *"):
            return p
    return None


# --------------------------------------------------------------------------------------------
# Prompt construction
# --------------------------------------------------------------------------------------------


def speckit_command(agent: str, phase: str, repo_dir: Optional[Path]) -> str:
    """Return the slash command for a Spec Kit phase.

    OpenCode: `/speckit.<phase>` (markdown commands in .opencode/commands).
    Claude Code: recent Spec Kit releases install skills as `/speckit-<phase>`; older ones used
    `/speckit.<phase>` commands. We look at what `specify init` actually produced.
    """
    phase = phase.lower()
    if agent == "CLAUDE_CODE" and repo_dir is not None:
        claude_dir = repo_dir / CLAUDE_DIR
        if (claude_dir / "skills" / f"speckit-{phase}").exists():
            return f"/speckit-{phase}"
        if (claude_dir / "commands" / f"speckit.{phase}.md").exists():
            return f"/speckit.{phase}"
        if (claude_dir / "skills").is_dir():
            return f"/speckit-{phase}"
    return f"/speckit.{phase}"


def has_tasks_md(repo_dir: Path) -> bool:
    return any((repo_dir / "specs").glob("**/tasks.md")) if (repo_dir / "specs").is_dir() else False


def build_agent_prompt(spec: JobSpec, repo_dir: Optional[Path]) -> str:
    if spec.mode == "SPEC_KIT":
        phase = spec.spec_kit_phase or "SPECIFY"
        return f"{speckit_command(spec.agent, phase, repo_dir)} {spec.prompt}".strip()
    if spec.mode == "IMPLEMENT":
        if spec.spec_kit_allowed and repo_dir is not None and has_tasks_md(repo_dir):
            cmd = speckit_command(spec.agent, "implement", repo_dir)
            return f"{cmd}\n\n{spec.prompt}".strip() if spec.prompt else cmd
        return spec.prompt
    return spec.prompt


ACCESS_DESCRIPTIONS = {
    GIT_ACCESS_PUSH_MR: "changes are committed and pushed to branch {branch} (merge request)",
    GIT_ACCESS_READ: "read-only: changes here are not pushed",
    GIT_ACCESS_NONE: "read-only, no credential: changes here are not pushed",
}


def repositories_prompt_section(spec: JobSpec, workspace: Path) -> str:
    """Short `## Repositories` context for multi-repository jobs: key, absolute path, access and purpose. The main
    prompt comes from Back Office; this only tells the agent where each repository is and what may change."""
    if not spec.multi_repo:
        return ""
    if not spec.repositories:
        return f"## Repositories\n\nNo repository is checked out; the working directory is {workspace.as_posix()}."
    lines = ["## Repositories", "", "Repositories checked out for this job (the working directory is the primary one):"]
    for r in spec.repositories:
        what = ACCESS_DESCRIPTIONS[r.access].format(branch=r.push_branch or "-")
        purpose = f" - {' '.join(r.purpose.split())[:300]}" if r.purpose else ""
        primary = ", primary" if r.primary else ""
        lines.append(f"- `{r.key}` at {(workspace / r.path).as_posix()} ({r.access}{primary}; {what}){purpose}")
    return "\n".join(lines)


def with_repositories_section(prompt: str, spec: JobSpec, workspace: Path) -> str:
    section = repositories_prompt_section(spec, workspace)
    return f"{prompt}\n\n{section}".strip() if section else prompt


# --------------------------------------------------------------------------------------------
# Agent configuration
# --------------------------------------------------------------------------------------------


def _with_v1(base_url: str) -> str:
    base_url = base_url.rstrip("/")
    return base_url if base_url.endswith("/v1") else base_url + "/v1"


def opencode_model_options(model: ModelRef) -> Dict[str, Any]:
    """Best-effort mapping of ModelOptions.reasoning to OpenCode per-model options."""
    if not model.reasoning or model.reasoning == "NONE":
        return {}
    if model.provider == "ANTHROPIC":
        return {"thinking": {"type": "enabled", "budgetTokens": REASONING_TO_BUDGET[model.reasoning]}}
    if model.provider in ("OPENAI", "OPENAI_COMPATIBLE", "MISTRAL"):
        return {"reasoningEffort": REASONING_TO_EFFORT[model.reasoning]}
    if model.provider == "GOOGLE":
        return {"thinkingConfig": {"thinkingBudget": REASONING_TO_BUDGET[model.reasoning]}}
    return {}


def _without_v1(base_url: str) -> str:
    base_url = base_url.rstrip("/")
    return base_url[: -len("/v1")] if base_url.endswith("/v1") else base_url


JOB_TOKEN_ENV = "BACKOFFICE_JOB_TOKEN"
GATEWAY_PROVIDER_ID = "backoffice"


def require_gateway(gateway: Optional[Gateway], need: str) -> str:
    url = (gateway.anthropic_base_url if need == "anthropic" else gateway.openai_base_url) if gateway else None
    if not url:
        raise ValueError(
            f"spec.gateway.{need}BaseUrl is required: agents reach models only through the Back Office LLM gateway (ADR 0024)"
        )
    return url


def opencode_model_ref(model: ModelRef) -> str:
    """`provider/model` for OpenCode: Anthropic and OpenAI natively, every other provider via the gateway's OpenAI endpoint."""
    if model.provider in ("ANTHROPIC", "OPENAI"):
        return f"{PROVIDER_TO_OPENCODE[model.provider]}/{model.model_id}"
    return f"{GATEWAY_PROVIDER_ID}/{model.model_id}"


def build_opencode_config(
    model: ModelRef, gateway: Optional[Gateway], tools: Optional[ToolPolicy] = None, extra_dirs: Iterable[str] = ()
) -> Dict[str, Any]:
    """opencode.json for a non-interactive run (https://opencode.ai/docs/config/, /docs/providers/).

    Every provider points at the LLM gateway with the job token as API key (`{env:BACKOFFICE_JOB_TOKEN}`, so the
    token is not written to disk). Providers other than Anthropic and OpenAI use the gateway's OpenAI-compatible
    endpoint through `@ai-sdk/openai-compatible` (provider id `backoffice`).
    """
    api_key = "{env:%s}" % JOB_TOKEN_ENV
    providers: Dict[str, Any] = {}
    if model.provider == "ANTHROPIC":
        providers["anthropic"] = {"options": {"baseURL": _with_v1(require_gateway(gateway, "anthropic")), "apiKey": api_key}}
        provider_id = "anthropic"
    elif model.provider == "OPENAI":
        providers["openai"] = {"options": {"baseURL": _with_v1(require_gateway(gateway, "openai")), "apiKey": api_key}}
        provider_id = "openai"
    else:
        providers[GATEWAY_PROVIDER_ID] = {
            "npm": "@ai-sdk/openai-compatible",
            "name": "Back Office gateway",
            "options": {"baseURL": _with_v1(require_gateway(gateway, "openai")), "apiKey": api_key},
            "models": {model.model_id: {"name": model.model_id}},
        }
        provider_id = GATEWAY_PROVIDER_ID
    options = opencode_model_options(model)
    if options:
        providers[provider_id].setdefault("models", {}).setdefault(model.model_id, {})["options"] = options
    return {
        "$schema": "https://opencode.ai/config.json",
        "model": opencode_model_ref(model),
        "autoupdate": False,
        "share": "disabled",
        "provider": providers,
        "permission": opencode_permission(tools, extra_dirs),
    }


@dataclass
class AgentSession:
    """ADR 0044: the coding agent's session of one role in a run workspace. Claude Code: a fixed id derived from the
    workspace and the role (`--session-id` the first time, `--resume` after); OpenCode: its own data directory per
    role (`--continue` once it holds a session)."""

    role: str
    session_id: str
    resume: bool
    data_dir: Optional[Path] = None


def session_id_for(workspace_name: str, role: str) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"https://backoffice.invalid/workspaces/{workspace_name}/sessions/{role}"))


def agent_session(spec: JobSpec, workspace: Path, agent_env: Dict[str, str]) -> Optional[AgentSession]:
    """The session to start or continue, or None (no workspace, no role: a new session as before ADR 0044)."""
    ws = spec.workspace
    if ws is None or not ws.session_role or ws.prepare_only or spec.agent not in ("CLAUDE_CODE", "OPENCODE"):
        return None
    sid = session_id_for(ws.name, ws.session_role)
    if spec.agent == "CLAUDE_CODE":
        config_dir = Path(agent_env.get("CLAUDE_CONFIG_DIR") or workspace / ".claude-config")
        return AgentSession(ws.session_role, sid, any(config_dir.glob(f"projects/*/{sid}.jsonl")))
    data_dir = workspace / WORKSPACE_STATE_DIR / "opencode" / ws.session_role
    return AgentSession(ws.session_role, sid, data_dir.is_dir() and any(data_dir.iterdir()), data_dir)


def build_opencode_command(model: ModelRef, prompt: str, continue_session: bool = False) -> List[str]:
    cmd = ["opencode", "run", "--model", opencode_model_ref(model), "--format", "json"]
    if continue_session:
        cmd.append("--continue")
    return cmd + [prompt]


def build_claude_command(
    model: ModelRef, prompt: str, permission_mode: str, help_text: str = "", settings_file: Optional[Path] = None,
    extra_dirs: Iterable[str] = (), session: Optional[AgentSession] = None,
) -> List[str]:
    cmd = ["claude", "-p", prompt, "--output-format", "stream-json", "--verbose", "--model", model.model_id]
    if permission_mode == "bypassPermissions":
        cmd.append("--dangerously-skip-permissions")
    else:
        cmd += ["--permission-mode", permission_mode]
    if settings_file is not None:
        cmd += ["--settings", str(settings_file)]
    if "--add-dir" in help_text:
        # the other repositories of a multi-repository job (the working directory is the primary one)
        for d in extra_dirs:
            cmd += ["--add-dir", str(d)]
    if session is not None and "--session-id" in help_text:
        # ADR 0044: the role's session in the run workspace, kept for the next step of that role
        cmd += ["--resume", session.session_id] if session.resume else ["--session-id", session.session_id]
    elif "--no-session-persistence" in help_text:
        cmd.append("--no-session-persistence")
    if model.reasoning and model.reasoning != "NONE" and "--effort" in help_text:
        cmd += ["--effort", REASONING_TO_CLAUDE_EFFORT[model.reasoning]]
    return cmd


def agent_environment(
    spec: JobSpec, base_env: Dict[str, str], workspace: Path, agent_token: str = "", proxy_url: Optional[str] = None,
    local_login_token: str = "",
) -> Dict[str, str]:
    """Environment of the agent process: the gateway-only agent token is its only credential (the job token only
    as fallback for an older Back Office). Runner credentials (git, callback) never reach it. LOCAL_LOGIN jobs
    (ADR 0040): Claude Code gets the owner's own login instead of the gateway."""
    env = {k: v for k, v in base_env.items() if k not in PROVIDER_ENV_NAMES and k not in RUNNER_ONLY_ENV_NAMES}
    env.setdefault("CI", "true")
    env["TERM"] = "dumb"
    env["NO_COLOR"] = "1"
    env["FORCE_COLOR"] = "0"
    apply_proxy_env(env, proxy_url)
    if agent_token:
        env[JOB_TOKEN_ENV] = agent_token
    if spec.agent == "OPENCODE":
        env["OPENCODE_CONFIG"] = str(workspace / "opencode.json")
        env.setdefault("OPENCODE_DISABLE_AUTOUPDATE", "1")
        env.setdefault("OPENCODE_DISABLE_MODELS_FETCH", "1")
    elif spec.agent == "CLAUDE_CODE":
        env.setdefault("CLAUDE_CONFIG_DIR", str(workspace / ".claude-config"))
        env.setdefault("DISABLE_AUTOUPDATER", "1")
        env.setdefault("DISABLE_TELEMETRY", "1")
        env.setdefault("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1")
        if spec.local_login:
            # Anthropic directly with the owner's subscription; a gateway token would take precedence over the login
            if local_login_token:
                env["CLAUDE_CODE_OAUTH_TOKEN"] = local_login_token
        else:
            if spec.gateway and spec.gateway.anthropic_base_url:
                # Claude Code appends /v1/messages itself.
                env["ANTHROPIC_BASE_URL"] = _without_v1(spec.gateway.anthropic_base_url)
            if agent_token:
                env["ANTHROPIC_AUTH_TOKEN"] = agent_token
        if spec.model.reasoning and spec.model.reasoning != "NONE":
            env.setdefault("MAX_THINKING_TOKENS", str(REASONING_TO_BUDGET[spec.model.reasoning]))
    return env


# --------------------------------------------------------------------------------------------
# Usage accumulation from agent event streams
# --------------------------------------------------------------------------------------------


@dataclass
class Usage:
    input_tokens: int = 0
    output_tokens: int = 0
    cache_read_tokens: int = 0
    cache_write_tokens: int = 0
    cost_usd: Optional[float] = None
    seen: bool = False

    def add(self, inp: int = 0, out: int = 0, cache_read: int = 0, cache_write: int = 0, cost: Optional[float] = None) -> None:
        self.input_tokens += int(inp or 0)
        self.output_tokens += int(out or 0)
        self.cache_read_tokens += int(cache_read or 0)
        self.cache_write_tokens += int(cache_write or 0)
        if cost is not None:
            self.cost_usd = (self.cost_usd or 0.0) + float(cost)
        self.seen = True

    def to_json(self) -> Dict[str, Any]:
        d: Dict[str, Any] = {
            "inputTokens": self.input_tokens,
            "outputTokens": self.output_tokens,
            "cacheReadTokens": self.cache_read_tokens,
            "cacheWriteTokens": self.cache_write_tokens,
        }
        d["costUsd"] = round(self.cost_usd, 6) if self.cost_usd is not None else None
        return d


@dataclass
class AgentOutcome:
    usage: Usage = field(default_factory=Usage)
    texts: List[str] = field(default_factory=list)
    final_result: Optional[str] = None
    errors: List[str] = field(default_factory=list)
    is_error: bool = False
    unknown_lines: int = 0
    json_lines: int = 0
    # Tool calls the permission policy refused (Claude Code `permission_denials`, OpenCode rejected tools).
    denials: List[Dict[str, str]] = field(default_factory=list)

    def summary(self) -> str:
        if self.final_result:
            return self.final_result
        return "\n\n".join(t for t in self.texts if t.strip())


def _as_int(v: Any) -> int:
    try:
        return int(v or 0)
    except (TypeError, ValueError):
        return 0


TokenCounts = Tuple[int, int, int, int]  # input, output, cache read, cache write


def _dict_field(obj: Dict[str, Any], key: str) -> Dict[str, Any]:
    """`obj[key]` when it is a dict, else an empty dict."""
    value = obj.get(key)
    return value if isinstance(value, dict) else {}


def _tokens_from_usage(u: Dict[str, Any]) -> Optional[TokenCounts]:
    """Anthropic style (`input_tokens`, ...) or camelCase (`inputTokens`, ...) usage dictionaries."""
    if "input_tokens" in u or "output_tokens" in u:
        return (
            _as_int(u.get("input_tokens")),
            _as_int(u.get("output_tokens")),
            _as_int(u.get("cache_read_input_tokens")),
            _as_int(u.get("cache_creation_input_tokens")),
        )
    if "inputTokens" in u or "outputTokens" in u:
        return (
            _as_int(u.get("inputTokens")),
            _as_int(u.get("outputTokens")),
            _as_int(u.get("cacheReadTokens") or u.get("cacheReadInputTokens")),
            _as_int(u.get("cacheWriteTokens") or u.get("cacheCreationInputTokens")),
        )
    return None


def _tokens_from_generic(obj: Dict[str, Any]) -> Optional[TokenCounts]:
    """Recognise a few common token dictionaries (OpenCode `tokens`, Anthropic `usage`)."""
    if isinstance(obj.get("tokens"), dict):
        t = obj["tokens"]
        cache = _dict_field(t, "cache")
        return (_as_int(t.get("input")), _as_int(t.get("output")), _as_int(cache.get("read")), _as_int(cache.get("write")))
    if isinstance(obj.get("usage"), dict):
        return _tokens_from_usage(obj["usage"])
    return None


def _numeric_cost(cost: Any) -> Optional[float]:
    return float(cost) if isinstance(cost, (int, float)) else None


def _decode_event_line(line: str, outcome: AgentOutcome) -> Tuple[Optional[Dict[str, Any]], Optional[str]]:
    """(event, None) for a JSON object line; (None, log line or None) otherwise (counted as unknown)."""
    line = line.strip()
    if not line:
        return None, None
    if not line.startswith("{"):
        outcome.unknown_lines += 1
        return None, line
    try:
        ev = json.loads(line)
    except json.JSONDecodeError:
        outcome.unknown_lines += 1
        return None, line
    if not isinstance(ev, dict):
        outcome.unknown_lines += 1
        return None, None
    outcome.json_lines += 1
    return ev, None


def _opencode_step_finish(ev: Dict[str, Any], part: Dict[str, Any], outcome: AgentOutcome) -> str:
    tokens = _tokens_from_generic(part) or _tokens_from_generic(ev)
    cost = part.get("cost", ev.get("cost"))
    if tokens:
        outcome.usage.add(*tokens, cost=_numeric_cost(cost))
    elif isinstance(cost, (int, float)):
        outcome.usage.add(cost=float(cost))
    tokens_in, tokens_out = (tokens[0], tokens[1]) if tokens else ("?", "?")
    return f"[step] finished reason={part.get('reason', '?')} tokens_in={tokens_in} tokens_out={tokens_out} cost={cost if cost is not None else '?'}"


def _opencode_text(ev: Dict[str, Any], part: Dict[str, Any], outcome: AgentOutcome) -> Optional[str]:
    text = str(part.get("text") or ev.get("text") or "")
    if not text.strip():
        return None
    outcome.texts.append(text)
    return text


def _opencode_tool(part: Dict[str, Any], outcome: AgentOutcome) -> str:
    state = _dict_field(part, "state")
    title = state.get("title") or part.get("tool") or "tool"
    status = state.get("status") or ""
    if status == "error":
        _record_opencode_tool_error(part, state, str(state.get("error") or title), outcome)
    return f"[tool] {part.get('tool', '')} {title} {status}".strip()


def _record_opencode_tool_error(part: Dict[str, Any], state: Dict[str, Any], err_text: str, outcome: AgentOutcome) -> None:
    outcome.errors.append(err_text)
    lowered = err_text.lower()
    if "permission" in lowered or "rejected" in lowered:
        inp = _dict_field(state, "input")
        outcome.denials.append({"tool": str(part.get("tool") or ""), "input": str(inp.get("command") or inp.get("filePath") or "")[:500]})


def _opencode_error_message(err: Any) -> str:
    if not isinstance(err, dict):
        return str(err)
    msg = _dict_field(err, "data").get("message")
    return msg or err.get("message") or err.get("name") or str(err)


def _opencode_error(ev: Dict[str, Any], outcome: AgentOutcome) -> str:
    msg = _opencode_error_message(ev.get("error"))
    outcome.errors.append(str(msg))
    outcome.is_error = True
    return f"[error] {msg}"


def _harvest_tokens(part: Dict[str, Any], ev: Dict[str, Any], outcome: AgentOutcome) -> None:
    """Unknown event: still try to harvest tokens defensively."""
    tokens = _tokens_from_generic(part) or _tokens_from_generic(ev)
    if tokens:
        outcome.usage.add(*tokens)


def parse_opencode_line(line: str, outcome: AgentOutcome) -> Optional[str]:
    """Parse one `opencode run --format json` line. Returns a human readable log line (or None).

    Event shape (packages/opencode/src/cli/cmd/run.ts): {"type": "<event>", "timestamp": .., "sessionID": ..,
    "part": {...}} with events step_start | step_finish | text | tool_use | reasoning | error. The
    `step-finish` part carries `cost` and `tokens: {input, output, reasoning, cache: {read, write}}`.
    """
    ev, passthrough = _decode_event_line(line, outcome)
    if ev is None:
        return passthrough
    ev_type = str(ev.get("type") or "").replace("-", "_")
    part = _dict_field(ev, "part")
    part_type = str(part.get("type") or "").replace("-", "_")
    types = (ev_type, part_type)

    if "step_finish" in types:
        return _opencode_step_finish(ev, part, outcome)
    if "text" in types:
        return _opencode_text(ev, part, outcome)
    if ev_type == "tool_use" or part_type == "tool":
        return _opencode_tool(part, outcome)
    if ev_type == "reasoning":
        return None
    if ev_type == "error" or ("error" in ev and not part):
        return _opencode_error(ev, outcome)
    if ev_type != "step_start":
        _harvest_tokens(part, ev, outcome)
    return None


def parse_claude_line(line: str, outcome: AgentOutcome) -> Optional[str]:
    """Parse one `claude -p --output-format stream-json` line.

    Final event: {"type": "result", "subtype": "success"|"error_*", "is_error": bool, "result": str,
    "total_cost_usd": float, "usage": {"input_tokens", "output_tokens", "cache_read_input_tokens",
    "cache_creation_input_tokens"}, "num_turns", "duration_ms", ...}
    """
    ev, passthrough = _decode_event_line(line, outcome)
    if ev is None:
        return passthrough
    ev_type = ev.get("type")
    if ev_type == "result":
        return _claude_result(ev, outcome)
    if ev_type == "assistant":
        return _claude_assistant(ev, outcome)
    if ev_type == "system":
        return f"[system] {ev.get('subtype', '')} model={ev.get('model', '')}".rstrip()
    return None  # "user" and unknown events


def _claude_result(ev: Dict[str, Any], outcome: AgentOutcome) -> str:
    usage = _dict_field(ev, "usage")
    cost = ev.get("total_cost_usd")
    # `result` is authoritative and cumulative: replace instead of adding.
    outcome.usage = Usage()
    outcome.usage.add(
        _as_int(usage.get("input_tokens")),
        _as_int(usage.get("output_tokens")),
        _as_int(usage.get("cache_read_input_tokens")),
        _as_int(usage.get("cache_creation_input_tokens")),
        cost=_numeric_cost(cost),
    )
    if ev.get("result"):
        outcome.final_result = str(ev["result"])
    _claude_denials(ev, outcome)
    _claude_errors(ev, outcome)
    return f"[result] subtype={ev.get('subtype')} turns={ev.get('num_turns')} cost_usd={cost} tokens_in={outcome.usage.input_tokens} tokens_out={outcome.usage.output_tokens}"


def _claude_denials(ev: Dict[str, Any], outcome: AgentOutcome) -> None:
    for d in ev.get("permission_denials") or []:
        if not isinstance(d, dict):
            continue
        inp = _dict_field(d, "tool_input")
        outcome.denials.append({"tool": str(d.get("tool_name") or ""), "input": str(inp.get("command") or inp.get("file_path") or inp.get("url") or "")[:500]})


def _claude_errors(ev: Dict[str, Any], outcome: AgentOutcome) -> None:
    if not (ev.get("is_error") or str(ev.get("subtype", "")).startswith("error")):
        return
    outcome.is_error = True
    errs = ev.get("errors") if isinstance(ev.get("errors"), list) else []
    outcome.errors.extend(str(e) for e in errs)
    if not errs:
        outcome.errors.append(str(ev.get("subtype") or "error"))


def _claude_assistant(ev: Dict[str, Any], outcome: AgentOutcome) -> Optional[str]:
    content = _dict_field(ev, "message").get("content")
    out = [human for human in (_claude_block(b, outcome) for b in (content if isinstance(content, list) else [])) if human is not None]
    return "\n".join(out) if out else None


def _claude_block(block: Any, outcome: AgentOutcome) -> Optional[str]:
    """Log line of one assistant content block (text is also collected for the summary)."""
    if not isinstance(block, dict):
        return None
    if block.get("type") == "text" and block.get("text"):
        outcome.texts.append(str(block["text"]))
        return str(block["text"])
    if block.get("type") == "tool_use":
        inp = _dict_field(block, "input")
        hint = inp.get("command") or inp.get("file_path") or inp.get("pattern") or ""
        return f"[tool] {block.get('name')} {str(hint)[:200]}".rstrip()
    return None


# --------------------------------------------------------------------------------------------
# Process execution
# --------------------------------------------------------------------------------------------


@dataclass
class ProcessResult:
    exit_code: int
    timed_out: bool
    tail: str
    duration_seconds: float


def run_process(
    cmd: List[str],
    cwd: Path,
    env: Dict[str, str],
    timeout: Optional[float],
    on_line: Callable[[str], None],
    shell: bool = False,
    tail_bytes: int = VERIFY_TAIL_BYTES,
) -> ProcessResult:
    """Run a command in its own process group, stream stdout+stderr lines, kill the group on timeout."""
    start = time.monotonic()
    tail: List[str] = []
    tail_size = 0
    popen_kwargs: Dict[str, Any] = dict(
        cwd=str(cwd),
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        stdin=subprocess.DEVNULL,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    )
    if os.name == "posix":
        popen_kwargs["start_new_session"] = True
    proc = subprocess.Popen(cmd if not shell else cmd[0], shell=shell, **popen_kwargs)  # noqa: S603
    timed_out = False

    def _kill() -> None:
        nonlocal timed_out
        timed_out = True
        _terminate(proc)

    timer = threading.Timer(timeout, _kill) if timeout and timeout > 0 else None
    if timer:
        timer.daemon = True
        timer.start()
    try:
        assert proc.stdout is not None
        for raw in proc.stdout:
            line = raw.rstrip("\n")
            tail.append(line)
            tail_size += len(line) + 1
            while tail_size > tail_bytes and len(tail) > 1:
                tail_size -= len(tail[0]) + 1
                tail.pop(0)
            try:
                on_line(line)
            except Exception as e:  # noqa: BLE001 - logging must never kill the job
                print(f"[runner] WARN line handler failed: {e}", file=sys.stderr)
        proc.wait()
    finally:
        if timer:
            timer.cancel()
    return ProcessResult(proc.returncode, timed_out, "\n".join(tail)[-tail_bytes:], time.monotonic() - start)


def _terminate(proc: subprocess.Popen) -> None:
    try:
        if os.name == "posix":
            os.killpg(proc.pid, signal.SIGTERM)
        else:
            proc.terminate()
    except (ProcessLookupError, PermissionError, OSError):
        return
    deadline = time.monotonic() + 15
    while proc.poll() is None and time.monotonic() < deadline:
        time.sleep(0.2)
    if proc.poll() is None:
        try:
            if os.name == "posix":
                os.killpg(proc.pid, signal.SIGKILL)
            else:
                proc.kill()
        except (ProcessLookupError, PermissionError, OSError):
            pass


# --------------------------------------------------------------------------------------------
# Git
# --------------------------------------------------------------------------------------------


def credential_host(url: str) -> str:
    """`host[:port]` git sends to credential helpers for an http(s) URL ("" for ssh, local paths, ...)."""
    try:
        parts = urllib.parse.urlsplit(url or "")
    except ValueError:
        return ""
    if parts.scheme not in ("http", "https"):
        return ""
    return parts.netloc.rsplit("@", 1)[-1]


def credential_path(url: str) -> str:
    """Repository path git sends to credential helpers with `credential.useHttpPath` (http(s) only), normalised
    like the helper normalises it: no leading/trailing slash, no `.git` suffix."""
    try:
        parts = urllib.parse.urlsplit(url or "")
    except ValueError:
        return ""
    if parts.scheme not in ("http", "https"):
        return ""
    path = parts.path.strip("/")
    path = path[: -len(".git")] if path.endswith(".git") else path
    return path.strip("/")


class Git:
    """Thin git wrapper hardened against a prompt-injected agent working in the same repository.

    * The token lives only in entrypoint memory (`token`, read once by `load_git_token`); it is never in
      `self.env`, `.git/config`, a file or a log. Only invocations with `with_creds=True` (clone, fetch,
      ls-remote, push) get it, as BO_GIT_PASSWORD plus BO_GIT_HOST (host of the spec repository URL) in their own
      environment, read by an inline credential helper (`GIT_CREDENTIAL_HELPER`, after a `credential.helper=`
      reset) that answers only `get` for exactly that host.
    * Every invocation runs with hooks and fsmonitor disabled (`GIT_SAFETY_ARGS`), ignores the global git config
      ($HOME is writable by the agent) and replace refs; diffs use --no-ext-diff --no-textconv.
    * `snapshot_config` (after clone and branch checkout) and `restore_after_agent` (after the agent and
      verifyCommand) put `.git/config` back and empty `.git/hooks`; `pin_base` freezes the base commit so a moved
      `origin/<base>` ref cannot hide changes from the diff.
    * `push` goes to the explicit spec URL with the refspec HEAD:refs/heads/<branch> (never `origin`, whose URL
      the agent could change, never forced), after `validate_push_branch`.
    * Least privilege (ADR 0031): `access` READ (the default) and NONE never push (`push` raises, the clone gets
      `remote.origin.pushurl = disabled://...`); NONE also never hands out the token. PUSH_MR pushes with
      whitelisted push options only (`validate_push_options`) and `push.pushOption` from config reset.
    * Multi-repository jobs (`bind_path=True`): one `Git` per repository with that repository's own token; the
      helper also checks the repository path (BO_GIT_PATH, `credential.useHttpPath=true`).
    """

    def __init__(
        self,
        repo_dir: Path,
        env: Dict[str, str],
        log: Callable[[str], None],
        token: Optional[str] = None,
        repo_url: Optional[str] = None,
        access: str = GIT_ACCESS_READ,
        bind_path: bool = False,
    ):
        if access not in GIT_ACCESS_LEVELS:
            raise ValueError(f"unknown git access {access!r}")
        self.access = access
        self.repo_dir = repo_dir
        self.env = {k: v for k, v in env.items() if k not in SECRET_ENV_NAMES and k not in RUNNER_ONLY_ENV_NAMES}
        self.env.setdefault("GIT_TERMINAL_PROMPT", "0")
        # The agent can write ~/.gitconfig (url.insteadOf, filters, credential helpers): ignore global config.
        self.env["GIT_CONFIG_GLOBAL"] = os.devnull
        self.env["GIT_NO_REPLACE_OBJECTS"] = "1"
        self.log = log
        # NONE: whatever was mounted, the job does not use a credential.
        self.token: Optional[str] = (token or None) if access != GIT_ACCESS_NONE else None
        self.has_token = bool(self.token)
        self.remote_host = credential_host(repo_url) if repo_url else ""
        self.bind_path = bind_path
        self.remote_path = credential_path(repo_url) if (repo_url and bind_path) else ""
        # Resolved now, before the agent runs, so a `git` planted later on PATH is never picked up.
        self.git_bin = shutil.which("git", path=self.env.get("PATH")) or "git"
        self._config_snapshot: Optional[bytes] = None
        self._pinned_bases: Dict[str, str] = {}

    def _cred_args(self) -> List[str]:
        if not self.has_token:
            return []
        args = ["-c", "credential.helper=", "-c", f"credential.helper={GIT_CREDENTIAL_HELPER}"]
        if self.remote_path:
            args += ["-c", "credential.useHttpPath=true"]
        return args

    def _cred_env(self, with_creds: bool) -> Dict[str, str]:
        """Environment of one git invocation: the password only when this invocation needs it."""
        if not (with_creds and self.has_token):
            return self.env
        env = dict(self.env)
        env[GIT_PASSWORD_ENV] = self.token or ""
        env[GIT_HOST_ENV] = self.remote_host
        if self.remote_path:
            env[GIT_PATH_ENV] = self.remote_path
        return env

    def _cmd(self, args: List[str], with_creds: bool = False) -> List[str]:
        return [self.git_bin, *GIT_SAFETY_ARGS] + (self._cred_args() if with_creds else []) + list(args)

    def run(self, args: List[str], check: bool = True, cwd: Optional[Path] = None, with_creds: bool = False) -> subprocess.CompletedProcess:
        cp = subprocess.run(  # noqa: S603
            self._cmd(args, with_creds), cwd=str(cwd or self.repo_dir), env=self._cred_env(with_creds),
            capture_output=True, text=True, encoding="utf-8", errors="replace",
        )
        if check and cp.returncode != 0:
            raise RuntimeError(f"git {' '.join(args[:2])} failed ({cp.returncode}): {cp.stderr.strip()[-2000:]}")
        return cp

    @staticmethod
    def url_with_token(url: str, token: str) -> str:
        """https URL with oauth2:<token>@ credentials (used only when a caller insists on URL auth)."""
        if not token or not url.startswith(HTTPS_PREFIX) or "@" in url.split("//", 1)[1].split("/", 1)[0]:
            return url
        return url.replace(HTTPS_PREFIX, f"{HTTPS_PREFIX}oauth2:{token}@", 1)

    def clone(self, url: str, base_ref: str) -> None:
        self.repo_dir.parent.mkdir(parents=True, exist_ok=True)
        if not self.remote_host:
            self.remote_host = credential_host(url)
        if self.bind_path and not self.remote_path:
            self.remote_path = credential_path(url)
        self.run(["clone", "--no-tags", url, str(self.repo_dir)], cwd=self.repo_dir.parent, with_creds=True)
        if not self.can_push:
            self.disable_push()
        # baseRef may be a branch, a tag or a SHA.
        cp = self.run(["checkout", "--quiet", base_ref], check=False)
        if cp.returncode != 0:
            self.run(["fetch", "--quiet", "origin", base_ref], with_creds=True)
            self.run(["checkout", "--quiet", "FETCH_HEAD"])

    def reattach(self, url: str, base_ref: str) -> None:
        """ADR 0044: reuse a clone an earlier job of the run left in the workspace. Untrusted code ran there, so before
        any git call `.git` must be a plain directory, hooks and grafts go and `.git/config` is written anew (only
        origin = the spec URL); then fetch, the access of this job (push disabled for READ / NONE) and the base ref.
        The working tree, local branches and uncommitted changes are kept."""
        git_dir = self._git_dir()
        _empty_hooks_dir(git_dir / "hooks")
        grafts = git_dir / "info" / "grafts"
        if grafts.exists() or grafts.is_symlink():
            _remove_entry(grafts)
        _replace_file(git_dir / "config", trusted_git_config(url).encode("utf-8"))
        if not self.remote_host:
            self.remote_host = credential_host(url)
        if self.bind_path and not self.remote_path:
            self.remote_path = credential_path(url)
        if not self.can_push:
            self.disable_push()
        self.run(["fetch", "--quiet", "--no-tags", "origin"], with_creds=True)
        if self.run(["rev-parse", "--verify", "--quiet", f"origin/{base_ref}"], check=False).returncode != 0:
            # baseRef may be a tag or a SHA
            self.run(["fetch", "--quiet", "origin", base_ref], with_creds=True, check=False)

    def current_branch(self) -> Optional[str]:
        cp = self.run(["symbolic-ref", "--quiet", "--short", "HEAD"], check=False)
        return cp.stdout.strip() or None if cp.returncode == 0 else None

    def has_local_branch(self, branch: str) -> bool:
        return self.run(["rev-parse", "--verify", "--quiet", f"refs/heads/{branch}"], check=False).returncode == 0

    @property
    def can_push(self) -> bool:
        return self.access == GIT_ACCESS_PUSH_MR

    def disable_push(self) -> None:
        """READ / NONE: `git push` to origin fails before contacting any host (part of the `.git/config` snapshot,
        so it is restored after the agent too)."""
        self.run(["config", "remote.origin.pushurl", DISABLED_PUSH_URL])

    def configure_identity(self, name: str, email: str) -> None:
        self.run(["config", "user.name", name])
        self.run(["config", "user.email", email])
        self.run(["config", "commit.gpgsign", "false"])

    def checkout_branch(self, branch: str) -> bool:
        """Create or re-attach the run branch. Returns True when it already existed on the remote."""
        remote = self.run(["ls-remote", "--heads", "origin", branch], check=False, with_creds=True)
        exists = bool(remote.stdout.strip())
        if exists:
            self.run(["fetch", "--quiet", "origin", f"{branch}:refs/remotes/origin/{branch}"], with_creds=True)
            self.run(["checkout", "--quiet", "-B", branch, f"origin/{branch}"])
        else:
            self.run(["checkout", "--quiet", "-B", branch])
        return exists

    def has_changes(self) -> bool:
        return bool(self.run(["status", "--porcelain"]).stdout.strip())

    def commit_all(self, message: str, run_id: Optional[str] = None) -> Optional[str]:
        """Stage everything and commit with the `Back-Office-Run: <runId>` trailer (ADR 0013)."""
        self.run(["add", "-A"])
        if not self.run(["diff", *GIT_DIFF_SAFETY_ARGS, "--cached", "--quiet"], check=False).returncode == 0:
            self.run(["commit", "--quiet", "-m", commit_message_with_trailer(message, run_id)])
            return self.head()
        return None

    def head(self) -> Optional[str]:
        cp = self.run(["rev-parse", "HEAD"], check=False)
        return cp.stdout.strip() or None

    def push(self, branch: str, repo_url: str, base_ref: str, merge_request: Optional[MergeRequest] = None) -> Optional[str]:
        """Push HEAD to refs/heads/<branch> of the spec repository URL; with `merge_request` GitLab push options
        open (or update) the MR.

        The URL is the one from the spec, never `origin` (the agent could have changed its URL), the refspec is
        explicit and there is no --force/--mirror/--all/--tags, so only the job branch can be written. Raises
        ValueError for a branch `validate_push_branch` refuses (base branch, MR target, odd ref names).
        Returns the merge request URL GitLab prints in the push output, if any. Hosts that ignore
        push options (GitHub, Gitea, plain git) just push. Raises ValueError for READ / NONE access (ADR 0031)
        and for push options outside `ALLOWED_PUSH_OPTIONS` (nothing that merges).
        """
        if not self.can_push:
            raise ValueError(f"push disabled: git access {self.access} (only PUSH_MR jobs push)")
        branch = validate_push_branch(branch, base_ref)
        if merge_request is not None:
            validate_push_branch(branch, merge_request.target_branch)
        if not repo_url:
            raise ValueError("no repository URL to push to")
        options = validate_push_options(push_options(merge_request))
        # `push.pushOption` (from any config the agent could have touched) is reset: only our -o options are sent
        args = ["-c", "push.pushOption=", "push"] + options + [repo_url, f"HEAD:refs/heads/{branch}"]
        cp = self.run(args, with_creds=True)
        output = (cp.stdout or "") + "\n" + (cp.stderr or "")
        for line in output.splitlines():
            line = line.strip()
            if line.startswith("remote:") and len(line) > len("remote:"):
                self.log(f"[git] {self.redact_url(line)}")
        return parse_merge_request_url(output)

    @staticmethod
    def redact_url(text: str) -> str:
        return re.sub(r"(https?://)([^/@\s]+)@", r"\1***@", text)

    def diff_bytes(self, base_ref: str) -> Optional[bytes]:
        """Full `git diff <baseRef>...HEAD` (merge base), uncapped."""
        base = self.resolve_base(base_ref)
        safe = ["diff", *GIT_DIFF_SAFETY_ARGS]
        for args in (safe + ["--merge-base", base, "HEAD"], safe + [f"{base}...HEAD"], safe + [base, "HEAD"]):
            cp = subprocess.run(self._cmd(args), cwd=str(self.repo_dir), env=self.env, capture_output=True)  # noqa: S603
            if cp.returncode == 0:
                return cp.stdout
        return None

    def diff_text(self, base_ref: str, max_bytes: int = DIFF_MAX_BYTES) -> Optional[str]:
        """`git diff <baseRef>...HEAD` (merge base) capped at `max_bytes` with a marker."""
        data = self.diff_bytes(base_ref)
        return cap_diff(data, max_bytes) if data is not None else None

    def resolve_base(self, base_ref: str) -> str:
        if base_ref in self._pinned_bases:
            return self._pinned_bases[base_ref]
        for candidate in (f"origin/{base_ref}", base_ref):
            if self.run(["rev-parse", "--verify", "--quiet", candidate], check=False).returncode == 0:
                return candidate
        return base_ref

    def pin_base(self, base_ref: str) -> Optional[str]:
        """Freeze the base commit before the agent runs: it could move `origin/<base>` to HEAD (git update-ref)
        and so make the reviewed diff empty while the push still carries its changes."""
        candidate = self.resolve_base(base_ref)
        cp = self.run(["rev-parse", "--verify", "--quiet", f"{candidate}^{{commit}}"], check=False)
        sha = cp.stdout.strip()
        if cp.returncode == 0 and sha:
            self._pinned_bases[base_ref] = sha
            return sha
        return None

    def diff_stat(self, base_ref: str) -> Optional[Dict[str, int]]:
        base = self.resolve_base(base_ref)
        cp = self.run(["diff", *GIT_DIFF_SAFETY_ARGS, "--shortstat", f"{base}...HEAD"], check=False)
        if cp.returncode != 0:
            cp = self.run(["diff", *GIT_DIFF_SAFETY_ARGS, "--shortstat", base, "HEAD"], check=False)
        if cp.returncode != 0:
            return None
        return parse_shortstat(cp.stdout)

    def _git_dir(self) -> Path:
        git_dir = self.repo_dir / ".git"
        if git_dir.is_symlink() or not git_dir.is_dir():
            # A `.git` file or symlink would point our next git calls (and the snapshot write) elsewhere.
            raise RuntimeError(".git is not a plain directory any more (replaced by untrusted code); refusing to continue")
        return git_dir

    def snapshot_config(self) -> bytes:
        """Remember `.git/config` as the runner left it (after clone, identity and branch checkout)."""
        self._config_snapshot = (self._git_dir() / "config").read_bytes()
        return self._config_snapshot

    def restore_after_agent(self) -> bool:
        """Undo what untrusted code may have planted in `.git` before the runner commits, diffs and pushes:
        `.git/config` back to the snapshot (url.insteadOf, core.fsmonitor, diff/filter drivers, remote URLs,
        includes, ...), `.git/hooks` emptied, `.git/info/grafts` removed. Returns True when the agent had changed
        `.git/config`."""
        git_dir = self._git_dir()
        if self._config_snapshot is None:
            self.log("[git] WARN no .git/config snapshot; config not restored")
            return False
        changed = _replace_file(git_dir / "config", self._config_snapshot)
        self.log("[git] restored .git/config (agent changed it)" if changed else "[git] .git/config unchanged by the agent")
        removed = _empty_hooks_dir(git_dir / "hooks")
        if removed:
            planted = [n for n in removed if not n.endswith(".sample")]
            planted_note = f"; not from git templates: {', '.join(planted)[:500]}" if planted else ""
            self.log(f"[git] emptied .git/hooks ({len(removed)} entries" + planted_note + ")")
        grafts = git_dir / "info" / "grafts"
        if grafts.exists() or grafts.is_symlink():
            grafts.unlink()
            self.log("[git] removed .git/info/grafts (planted by untrusted code)")
        return changed


def trusted_git_config(url: str) -> str:
    """The `.git/config` a reused clone gets (ADR 0044): nothing an agent could have planted survives."""
    quoted = url.replace("\\", "\\\\").replace('"', '\\"')
    return (
        "[core]\n\trepositoryformatversion = 0\n\tfilemode = true\n\tbare = false\n\tlogallrefupdates = true\n"
        f'[remote "origin"]\n\turl = "{quoted}"\n\tfetch = +refs/heads/*:refs/remotes/origin/*\n'
    )


def _read_regular_file(path: Path) -> Optional[bytes]:
    """Content of a regular file (never through a symlink); None otherwise or when unreadable."""
    try:
        return path.read_bytes() if path.is_file() and not path.is_symlink() else None
    except OSError:
        return None


def _remove_entry(path: Path) -> None:
    """Remove a file, a symlink (never its target) or a directory tree."""
    if path.is_symlink() or not path.is_dir():
        path.unlink()
    else:
        shutil.rmtree(path)


def _replace_file(path: Path, content: bytes) -> bool:
    """Write `content` to a fresh `path` (whatever is there - file, symlink, directory - is removed first, so we
    never write through a symlink the agent placed). Returns True when the previous content differed."""
    changed = _read_regular_file(path) != content
    if path.is_symlink() or path.exists():
        _remove_entry(path)
    path.write_bytes(content)
    return changed


def _empty_hooks_dir(hooks: Path) -> List[str]:
    """Empty `.git/hooks` (a symlink or file in its place is removed) and recreate it; returns removed names."""
    removed: List[str] = []
    if hooks.is_symlink() or (hooks.exists() and not hooks.is_dir()):
        hooks.unlink()
        removed.append("hooks")
    elif hooks.is_dir():
        for entry in sorted(hooks.iterdir()):
            _remove_entry(entry)
            removed.append(entry.name)
    hooks.mkdir(exist_ok=True)
    return removed


def parse_shortstat(text: str) -> Dict[str, int]:
    files = insertions = deletions = 0
    m = re.search(r"(\d+) files? changed", text)
    if m:
        files = int(m.group(1))
    m = re.search(r"(\d+) insertions?\(\+\)", text)
    if m:
        insertions = int(m.group(1))
    m = re.search(r"(\d+) deletions?\(-\)", text)
    if m:
        deletions = int(m.group(1))
    return {"files": files, "insertions": insertions, "deletions": deletions}


UNTRUSTED_OPEN_RE = re.compile(r"<untrusted-data[^>]*>")
UNTRUSTED_CLOSE_RE = re.compile(r"</untrusted-data[^>]*>")


def commit_message(mode: str, prompt: str) -> str:
    """`<mode>: <first prompt line>`. Back Office wraps untrusted values in `<untrusted-data ...>` blocks: the
    tags are stripped, and when the first line opens such a block and has nothing useful after its colon
    (`Implement SHOP-1: <untrusted-data ...>`), the first content line of the block completes the subject."""
    lines = [l.strip() for l in prompt.splitlines() if l.strip()]
    first = lines[0] if lines else "agent changes"
    opens_block = bool(UNTRUSTED_OPEN_RE.search(first)) and not UNTRUSTED_CLOSE_RE.search(first)
    first = " ".join(UNTRUSTED_CLOSE_RE.sub(" ", UNTRUSTED_OPEN_RE.sub(" ", first)).split())
    first = re.sub(r"^/speckit[.-]\w+\s*", "", first)
    if opens_block and (not first or first.endswith(":")) and len(lines) > 1:
        nxt = " ".join(UNTRUSTED_CLOSE_RE.sub(" ", UNTRUSTED_OPEN_RE.sub(" ", lines[1])).split())
        if nxt and not nxt.startswith("#"):  # never the "## Untrusted data" notice
            first = f"{first} {nxt}".strip()
    first = first or "agent changes"
    if len(first) > 72:
        first = first[:69].rstrip() + "..."
    return f"{mode}: {first}"


_REF_FORBIDDEN_CHARS = set("~^:?*[\\")


def validate_push_branch(branch: str, base_ref: str) -> str:
    """Refuse anything but a plain, new job branch (git check-ref-format rules plus: not the base branch, not
    HEAD, no `refs/` prefix, no option-like name). Raises ValueError; returns the branch otherwise."""
    b = branch or ""
    _check_not_base_branch(b, base_ref)
    _check_ref_format(b)
    return b


def _check_not_base_branch(b: str, base_ref: str) -> None:
    base = (base_ref or "").strip()
    base_short = base[len("refs/heads/"):] if base.startswith("refs/heads/") else base
    if not b.strip():
        raise ValueError("empty branch name")
    if b in (base, base_short):
        raise ValueError(f"branch {b!r} is the base branch {base!r}")
    if b in ("HEAD", "@"):
        raise ValueError(f"branch {b!r} is not a branch name")


def _is_forbidden_ref_char(c: str) -> bool:
    return c.isspace() or ord(c) < 32 or ord(c) == 127 or c in _REF_FORBIDDEN_CHARS


def _check_ref_format(b: str) -> None:
    """git check-ref-format rules (plus no `refs/` prefix and no option-like name) for a non-empty branch."""
    if b.startswith(("refs/", "-", "/")):
        raise ValueError(f"branch {b!r} must be a plain branch name (no refs/ prefix, no leading - or /)")
    if any(s in b for s in ("..", "@{", "//")):
        raise ValueError(f"branch {b!r} contains '..', '@{{' or '//'")
    if any(_is_forbidden_ref_char(c) for c in b):
        raise ValueError(f"branch {b!r} contains whitespace, control or forbidden characters")
    if b.endswith((".lock", "/", ".")):
        raise ValueError(f"branch {b!r} ends with .lock, / or .")
    if any(part.startswith(".") or part.endswith(".lock") for part in b.split("/")):
        raise ValueError(f"branch {b!r} has a component starting with . or ending with .lock")


def commit_message_with_trailer(message: str, run_id: Optional[str]) -> str:
    """Append the `Back-Office-Run: <runId>` trailer (blank line separated, so git treats it as a trailer)."""
    if not run_id:
        return message
    body = message.rstrip("\n")
    if re.search(rf"^{RUN_TRAILER}:\s*{re.escape(run_id)}\s*$", body, re.MULTILINE):
        return body + "\n"
    return f"{body}\n\n{RUN_TRAILER}: {run_id}\n"


def _push_option_value(value: str, max_len: int = 1000) -> str:
    """Push options must be single-line (git refuses newlines); collapse and cap them."""
    flat = " ".join(value.split())
    return flat[: max_len - 3].rstrip() + "..." if len(flat) > max_len else flat


def push_options(merge_request: Optional[MergeRequest]) -> List[str]:
    """`-o merge_request.create -o merge_request.target=<t> -o merge_request.title=<title> [...]` (GitLab push options)."""
    if merge_request is None or not merge_request.create:
        return []
    opts = [
        "-o", "merge_request.create",
        "-o", f"merge_request.target={merge_request.target_branch}",
        "-o", f"merge_request.title={_push_option_value(merge_request.title, 255)}",
    ]
    if merge_request.description:
        opts += ["-o", f"merge_request.description={_push_option_value(merge_request.description)}"]
    if merge_request.draft:
        opts += ["-o", "merge_request.draft"]
    return opts


def validate_push_options(args: List[str]) -> List[str]:
    """Refuse any `-o <option>` outside `ALLOWED_PUSH_OPTIONS` (ADR 0031: open a merge request, never merge,
    never skip CI). Returns `args` unchanged; raises ValueError otherwise."""
    if len(args) % 2:
        raise ValueError("malformed push options")
    for flag, value in zip(args[::2], args[1::2]):
        key = value.split("=", 1)[0].strip()
        if flag != "-o" or key not in ALLOWED_PUSH_OPTIONS:
            raise ValueError(f"push option {key!r} is not allowed (allowed: {', '.join(ALLOWED_PUSH_OPTIONS)})")
    return args


MR_URL_RE = re.compile(r"https?://[^\s'\"<>]+/(?:-/)?merge_requests/\d+")


def parse_merge_request_url(push_output: str) -> Optional[str]:
    """First merge request URL GitLab prints on push (`remote:   https://host/group/repo/-/merge_requests/N`)."""
    for line in push_output.splitlines():
        m = MR_URL_RE.search(line)
        if m:
            return m.group(0).rstrip(".,;)")
    return None


def cap_diff(data: bytes, max_bytes: int = DIFF_MAX_BYTES) -> str:
    """Decode a diff and truncate it at `max_bytes` on a line boundary with a marker."""
    if len(data) <= max_bytes:
        return data.decode("utf-8", errors="replace")
    head = data[:max_bytes]
    cut = head.rfind(b"\n")
    if cut > 0:
        head = head[:cut]
    return head.decode("utf-8", errors="replace") + f"\n\n[... diff truncated by runner at {max_bytes} bytes ({len(data)} total) ...]\n"


# --------------------------------------------------------------------------------------------
# Spec Kit
# --------------------------------------------------------------------------------------------


def specify_init_command(agent: str, help_text: str) -> List[str]:
    """Build `specify init` for the installed Spec Kit version.

    Spec Kit >= 1.0 uses `--integration <claude|opencode>` and `--non-interactive`; older releases
    used `--ai <agent>` and `--no-git`.
    """
    agent_key = "claude" if agent == "CLAUDE_CODE" else "opencode"
    cmd = ["specify", "init", "--here", "--force", "--script", "sh"]
    if "--integration" in help_text:
        cmd += ["--integration", agent_key]
    else:
        cmd += ["--ai", agent_key]
    if "--non-interactive" in help_text:
        cmd.append("--non-interactive")
    if "--no-git" in help_text:
        cmd.append("--no-git")
    if "--ignore-agent-tools" in help_text:
        cmd.append("--ignore-agent-tools")
    return cmd


def ensure_spec_kit(repo_dir: Path, agent: str, env: Dict[str, str], log: Callable[[str], None]) -> bool:
    if (repo_dir / SPECIFY_DIR).is_dir():
        log(f"[spec-kit] {SPECIFY_DIR}/ already present")
        return True
    if shutil.which("specify") is None:
        log("[spec-kit] WARN specify CLI not installed; skipping init")
        return False
    help_cp = subprocess.run(["specify", "init", "--help"], cwd=str(repo_dir), env=env, capture_output=True, text=True, errors="replace")  # noqa: S603
    help_text = (help_cp.stdout or "") + (help_cp.stderr or "")
    cmd = specify_init_command(agent, help_text)
    log(f"[spec-kit] {' '.join(shlex.quote(c) for c in cmd)}")
    res = run_process(cmd, repo_dir, env, timeout=600, on_line=lambda l: log(f"[spec-kit] {l}"))
    if res.exit_code != 0 or not (repo_dir / SPECIFY_DIR).is_dir():
        log(f"[spec-kit] WARN specify init exited {res.exit_code}; continuing without it")
        return False
    return True


def write_constitution(repo_dir: Path, constitution: str, log: Callable[[str], None]) -> None:
    target = repo_dir / SPECIFY_DIR / "memory" / "constitution.md"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(constitution if constitution.endswith("\n") else constitution + "\n", encoding="utf-8")
    log(f"[spec-kit] wrote constitution ({len(constitution)} chars) to {target.relative_to(repo_dir)}")


# --------------------------------------------------------------------------------------------
# Skills (Task Library, ADR 0015)
# --------------------------------------------------------------------------------------------


def skill_dir_name(key: str) -> str:
    name = re.sub(r"[^a-z0-9._-]+", "-", key.strip().lower()).strip("-.")
    return name or "skill"


def _yaml_scalar(value: str) -> str:
    return json.dumps(" ".join(value.split()))


def skill_markdown(skill: Skill) -> str:
    """SKILL.md content: the skill body, with a name/description frontmatter added when it has none."""
    content = skill.content.replace("\r\n", "\n")
    if content.lstrip().startswith("---\n") or content.lstrip().startswith("---\r\n"):
        return content if content.endswith("\n") else content + "\n"
    description = next((l.strip().lstrip("#").strip() for l in content.splitlines() if l.strip()), skill.name)
    front = f"---\nname: {_yaml_scalar(skill.name)}\ndescription: {_yaml_scalar(description[:200])}\n---\n\n"
    return front + content + ("" if content.endswith("\n") else "\n")


def append_git_excludes(exclude_file: Path, patterns: List[str], header: Optional[str] = None) -> List[str]:
    """Append the `patterns` not yet listed in `exclude_file` (a `.git/info/exclude`), after an optional comment
    line, starting on a fresh line. Returns the patterns added; raises OSError."""
    existing = exclude_file.read_text(encoding="utf-8") if exclude_file.exists() else ""
    present = existing.splitlines()
    new = [p for p in patterns if p not in present]
    if new:
        separator = "\n" if existing and not existing.endswith("\n") else ""
        block = ([header] if header else []) + new
        with exclude_file.open("a", encoding="utf-8") as fh:
            fh.write(separator + "\n".join(block) + "\n")
    return new


def _write_skill(repo_dir: Path, skill: Skill) -> List[Path]:
    """SKILL.md of one skill in every agent's skill directory."""
    dir_name = skill_dir_name(skill.key)
    text = skill_markdown(skill)
    written: List[Path] = []
    for base in SKILL_DIRS:
        target = repo_dir / base / dir_name / "SKILL.md"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")
        written.append(target)
    return written


def write_skills(repo_dir: Path, skills: List[Skill], log: Callable[[str], None]) -> List[Path]:
    """Write `spec.skills[]` to .claude/skills/<key>/SKILL.md and .opencode/skills/<key>/SKILL.md.

    The directories are added to `.git/info/exclude` so they never end up in the agent's commit.
    """
    written: List[Path] = []
    if not skills:
        return written
    excludes: List[str] = []
    for skill in skills:
        written += _write_skill(repo_dir, skill)
        excludes += ["/" + (base / skill_dir_name(skill.key)).as_posix() + "/" for base in SKILL_DIRS]
    git_dir = repo_dir / ".git"
    if git_dir.is_dir():
        exclude_file = git_dir / "info" / "exclude"
        try:
            exclude_file.parent.mkdir(parents=True, exist_ok=True)
            append_git_excludes(exclude_file, excludes, "# back-office runner skills (not part of the change)")
        except OSError as e:
            log(f"[skills] WARN cannot update .git/info/exclude: {e}")
    log(f"[skills] wrote {len(skills)} skill(s): {', '.join(skill_dir_name(s.key) for s in skills)} -> {', '.join(str(d) for d in SKILL_DIRS)}")
    return written


# --------------------------------------------------------------------------------------------
# Artefacts
# --------------------------------------------------------------------------------------------


def media_type_for(path: str) -> str:
    lower = path.lower()
    if lower.endswith(".md") or lower.endswith(".markdown"):
        return "text/markdown"
    if lower.endswith(".json"):
        return JSON_MEDIA_TYPE
    return "text/plain"


def artifact_name_for(rel_path: str, taken: set) -> str:
    base = Path(rel_path).name.lower()
    name = ARTIFACT_NAME_BY_FILE.get(base)
    if name is None and base.startswith("analysis") and base.endswith(".md"):
        name = "analysis"
    if name is None or name in taken:
        name = rel_path.replace("\\", "/")
    return name


ARTIFACT_TRUNCATED_NOTE = "[... truncated by runner at 2 MB ...]\n"
ARTIFACT_TRUNCATED_MARKER = "\n\n" + ARTIFACT_TRUNCATED_NOTE


def _glob_files(repo_dir: Path, pattern: str, log: Callable[[str], None]) -> List[Path]:
    """Sorted regular files matching `pattern` (relative to the repository), outside `.git`."""
    try:
        matches = sorted(p for p in repo_dir.glob(pattern) if p.is_file())
    except (ValueError, NotImplementedError) as e:
        log(f"[artifacts] WARN bad glob {pattern!r}: {e}")
        return []
    return [p for p in matches if ".git" not in p.relative_to(repo_dir).parts]


def _artifact_files(repo_dir: Path, patterns: List[str], log: Callable[[str], None]) -> List[Path]:
    """Files matching any pattern, in pattern order, each once."""
    files: List[Path] = []
    for pattern in (p.strip().lstrip("/") for p in patterns):
        if pattern:
            files += [p for p in _glob_files(repo_dir, pattern, log) if p not in files]
    return files


def _read_artifact_content(path: Path) -> str:
    """File content capped at ARTIFACT_MAX_BYTES (with a marker); raises OSError."""
    data = path.read_bytes()
    content = data[:ARTIFACT_MAX_BYTES].decode("utf-8", errors="replace")
    return content + ARTIFACT_TRUNCATED_MARKER if len(data) > ARTIFACT_MAX_BYTES else content


def collect_artifacts(repo_dir: Path, patterns: List[str], log: Callable[[str], None]) -> List[Dict[str, Any]]:
    artifacts: List[Dict[str, Any]] = []
    taken: set = set()
    for p in _artifact_files(repo_dir, patterns, log):
        rel = p.relative_to(repo_dir).as_posix()
        try:
            content = _read_artifact_content(p)
        except OSError as e:
            log(f"[artifacts] WARN cannot read {rel}: {e}")
            continue
        name = artifact_name_for(rel, taken)
        taken.add(name)
        artifacts.append({"name": name, "path": rel, "mediaType": media_type_for(rel), "content": content})
    log(f"[artifacts] collected {len(artifacts)} file(s): {', '.join(a['name'] for a in artifacts) or '-'}")
    return artifacts


# --------------------------------------------------------------------------------------------
# Result
# --------------------------------------------------------------------------------------------


def truncate_utf8(text: str, max_bytes: int) -> str:
    data = text.encode("utf-8")
    if len(data) <= max_bytes:
        return text
    return data[:max_bytes].decode("utf-8", errors="ignore") + "\n[... truncated ...]"


def _agent_failure(agent_exit: Optional[int], outcome: AgentOutcome) -> Optional[str]:
    """Error message when the agent exited non-zero or reported an error (with its last errors), else None."""
    if agent_exit == 0 and not outcome.is_error:
        return None
    error = f"agent exited with code {agent_exit}" if agent_exit != 0 else "agent reported an error"
    if outcome.errors:
        error += ": " + "; ".join(outcome.errors[-3:])[:1500]
    return error


def _verify_failure(verify: Optional[ProcessResult]) -> Optional[str]:
    if verify is None:
        return None
    if verify.timed_out:
        return "verifyCommand timed out"
    if verify.exit_code != 0:
        return f"verifyCommand exited with code {verify.exit_code}"
    return None


def _result_status(
    agent_exit: Optional[int], timed_out: bool, outcome: AgentOutcome, verify: Optional[ProcessResult], error: Optional[str]
) -> Tuple[str, Optional[str]]:
    """(RunnerResult.status, error): timeout, then a runner error, then the agent, then verifyCommand."""
    if timed_out:
        return "TIMED_OUT", error or "agent exceeded timeoutSeconds and was killed"
    if error:
        return "FAILED", error
    failure = _agent_failure(agent_exit, outcome) or _verify_failure(verify)
    if failure:
        return "FAILED", failure
    return "SUCCEEDED", error


def _verification_json(verify_command: Optional[str], verify: Optional[ProcessResult]) -> Optional[Dict[str, Any]]:
    if verify is None or not verify_command:
        return None
    return {"command": verify_command, "exitCode": verify.exit_code if not verify.timed_out else -1, "outputTail": verify.tail}


def build_result(
    *,
    agent_exit: Optional[int],
    timed_out: bool,
    outcome: AgentOutcome,
    verify_command: Optional[str],
    verify: Optional[ProcessResult],
    branch: Optional[str],
    commit_sha: Optional[str],
    diff_stat: Optional[Dict[str, int]],
    artifacts: List[Dict[str, Any]],
    error: Optional[str] = None,
    diff: Optional[str] = None,
    merge_request_url: Optional[str] = None,
) -> Dict[str, Any]:
    status, error = _result_status(agent_exit, timed_out, outcome, verify, error)
    result: Dict[str, Any] = {
        "status": status,
        "summary": truncate_utf8(outcome.summary(), SUMMARY_MAX_BYTES) or None,
        "branch": branch,
        "commitSha": commit_sha,
        "diffStat": diff_stat,
        "diff": diff,
        "mergeRequestUrl": merge_request_url,
        "verification": _verification_json(verify_command, verify),
        "artifacts": artifacts,
        "usage": outcome.usage.to_json(),
        "error": error,
    }
    return result


# --------------------------------------------------------------------------------------------
# Uploads (large artefacts and logs) and digests
# --------------------------------------------------------------------------------------------


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class Uploader:
    """`POST /api/v1/runner/v2/jobs/{id}/uploads` (job token) -> presigned PUT target -> upload."""

    def __init__(self, client: CallbackClient, log: Callable[[str], None]):
        self.client = client
        self.log = log
        self.enabled = client.enabled

    def upload(self, name: str, content_type: str, data: bytes) -> Optional[Dict[str, Any]]:
        """Returns `{name, blobKey, sha256, sizeBytes, mediaType}` or None when uploads are not available."""
        if not self.enabled:
            return None
        digest = sha256_hex(data)
        url = f"{self.client.base_url}/api/v1/runner/v2/jobs/{self.client.job_id}/uploads"
        try:
            target = self.client.request_json(url, {"name": name, "contentType": content_type, "sizeBytes": len(data), "sha256": digest})
            if not isinstance(target, dict) or not target.get("url"):
                raise ValueError("upload target without url")
            method = str(target.get("method") or "PUT").upper()
            if method != "PUT":
                raise ValueError(f"unsupported upload method {method}")
            headers = {str(k): str(v) for k, v in (target.get("headers") or {}).items()}
            headers.setdefault("Content-Type", content_type)
            status = self.client.put_bytes(str(target["url"]), data, headers)
            if not 200 <= status < 300:
                raise ValueError(f"upload answered HTTP {status}")
        except (urllib.error.URLError, OSError, ValueError) as e:
            self.log(f"[upload] WARN {name} ({len(data)} bytes) not uploaded: {self.client.redact(str(e))}")
            return None
        self.log(f"[upload] {name}: {len(data)} bytes sha256={digest[:12]} -> {target.get('blobKey')}")
        return {"name": name, "blobKey": target.get("blobKey"), "sha256": digest, "sizeBytes": len(data), "mediaType": content_type}


BLOB_REF_MEDIA_TYPE = "application/vnd.backoffice.blob-ref+json"


def blob_ref_artifact(ref: Dict[str, Any], path: Optional[str]) -> Dict[str, Any]:
    """Artefact entry pointing at an uploaded blob (content = JSON reference)."""
    return {"name": ref["name"], "path": path, "mediaType": BLOB_REF_MEDIA_TYPE, "content": json.dumps({k: ref[k] for k in ("blobKey", "sha256", "sizeBytes", "mediaType")})}


def private_log_path(env: Dict[str, str], job_id: str) -> Optional[Path]:
    """Path of the runner log inside a fresh directory only this user can enter (mode 0700).

    Never a predictable name in a shared, world-writable temp directory: another process there could
    pre-create or symlink the file and read or redirect the (redacted) log. `TMPDIR` of the job
    environment is honoured; `None` (no log file, events still stream) when no directory can be made.
    """
    try:
        directory = tempfile.mkdtemp(prefix="runner-log-", dir=env.get("TMPDIR") or None)
    except OSError:
        return None
    safe_id = re.sub(r"[^A-Za-z0-9_.-]", "_", job_id or "offline")
    return Path(directory) / f"runner-{safe_id}.log"


class LogFile:
    """Keeps the complete (redacted) runner log for the final upload."""

    def __init__(self, path: Optional[Path]):
        self.path = path
        self._fh = None
        if path is not None:
            try:
                path.parent.mkdir(parents=True, exist_ok=True)
                self._fh = path.open("a", encoding="utf-8")
            except OSError:
                self._fh = None

    def write(self, line: str) -> None:
        if self._fh is not None:
            try:
                self._fh.write(line + "\n")
            except (OSError, ValueError):
                pass

    def read_bytes(self) -> bytes:
        if self._fh is not None:
            try:
                self._fh.flush()
            except (OSError, ValueError):
                pass
        try:
            return self.path.read_bytes() if self.path else b""
        except OSError:
            return b""


# --------------------------------------------------------------------------------------------
# STUB agent (tests only, RUNNER_ENABLE_STUB_AGENT=1)
# --------------------------------------------------------------------------------------------


def parse_stub_line(line: str, outcome: AgentOutcome) -> Optional[str]:
    if line.strip():
        outcome.texts.append(line)
    return line


# --------------------------------------------------------------------------------------------
# Main flow
# --------------------------------------------------------------------------------------------


def _prepare_home(workspace: Path, env: Dict[str, str], log: Callable[[str], None]) -> None:
    """Make sure HOME is writable (root filesystem may be read-only inside the Job)."""
    home = Path(env.get("HOME") or "/nonexistent")
    try:
        home.mkdir(parents=True, exist_ok=True)
        probe = home / ".runner-write-test"
        probe.write_text("ok")
        probe.unlink()
        return
    except OSError:
        pass
    new_home = workspace / ".home"
    new_home.mkdir(parents=True, exist_ok=True)
    env["HOME"] = str(new_home)
    env["XDG_CONFIG_HOME"] = str(new_home / ".config")
    env["XDG_DATA_HOME"] = str(new_home / ".local" / "share")
    env["XDG_CACHE_HOME"] = str(new_home / ".cache")
    env["XDG_STATE_HOME"] = str(new_home / ".local" / "state")
    env.setdefault("npm_config_cache", str(new_home / ".npm"))
    env.setdefault("GRADLE_USER_HOME", str(new_home / ".gradle"))
    env.setdefault("UV_CACHE_DIR", str(new_home / ".uv-cache"))
    log(f"[runner] HOME was not writable, relocated to {new_home}")


def _tool_version(cmd: List[str], env: Dict[str, str]) -> str:
    try:
        cp = subprocess.run(cmd, capture_output=True, text=True, timeout=30, env=env, errors="replace")  # noqa: S603
        out = (cp.stdout or cp.stderr or "").strip().splitlines()
        return out[0][:120] if out else f"exit {cp.returncode}"
    except Exception as e:  # noqa: BLE001
        return f"unavailable ({e.__class__.__name__})"


def _write_policy_files(spec: JobSpec, tools: Optional[ToolPolicy], workspace: Path, repo_dir: Path, log: Callable[[str], None]) -> Optional[Path]:
    """Claude Code settings rendered from the tool policy; returns the file passed with `--settings`."""
    if spec.agent != "CLAUDE_CODE" or tools is None:
        return None
    settings = claude_settings(tools)
    settings_file = workspace / ".runner" / "claude-settings.json"
    settings_file.parent.mkdir(parents=True, exist_ok=True)
    settings_file.write_text(json.dumps(settings, indent=2), encoding="utf-8")
    # Also as .claude/settings.local.json (git-excluded) so the rules apply at the local scope.
    local = repo_dir / CLAUDE_DIR / "settings.local.json"
    try:
        local.parent.mkdir(parents=True, exist_ok=True)
        local.write_text(json.dumps(settings, indent=2), encoding="utf-8")
        exclude = repo_dir / ".git" / "info" / "exclude"
        if exclude.parent.is_dir():
            append_git_excludes(exclude, [f"/{CLAUDE_DIR}/settings.local.json"])
    except OSError as e:
        log(f"[policy] WARN cannot write {local}: {e}")
    project = repo_dir / CLAUDE_DIR / "settings.json"
    if project.is_file() and "permissions" in (_read_text(project) or ""):
        log("[policy] WARN the repository has .claude/settings.json with permissions; its allow rules add to the job policy")
    return settings_file


@dataclass
class _Run:
    """State of one job run: what the phases below need and what they accumulate for the RunnerResult."""

    env: Dict[str, str]
    workspace: Path
    started: float
    sandboxed: bool
    proxy_url: Optional[str]
    redact: Redactor
    sink: EventSink
    uploader: Uploader
    security: Dict[str, Any]
    agent_credential: str
    tools: Optional[ToolPolicy] = None
    outcome: AgentOutcome = field(default_factory=AgentOutcome)
    verify_res: Optional[ProcessResult] = None
    agent_exit: Optional[int] = None
    timed_out: bool = False
    branch: Optional[str] = None
    commit_sha: Optional[str] = None
    diff_stat: Optional[Dict[str, int]] = None
    artifacts: List[Dict[str, Any]] = field(default_factory=list)
    fatal_error: Optional[str] = None
    verify_command: Optional[str] = None
    diff_text: Optional[str] = None
    merge_request_url: Optional[str] = None
    digests: Dict[str, str] = field(default_factory=dict)
    # Multi-repository jobs: per-repository tokens, the other repositories' directories (reachable by the agent)
    # and `result.repositories` (None for legacy single-repo specs, whose result keeps its old shape).
    repo_tokens: Dict[str, str] = field(default_factory=dict)
    extra_dirs: List[str] = field(default_factory=list)
    repo_results: Optional[List[Dict[str, Any]]] = None
    # ADR 0040: the owner's Claude Code login (LOCAL_LOGIN jobs); subscription = Claude Code ran with it
    local_login_token: str = ""
    subscription: bool = False

    def log(self, message: str) -> None:
        self.sink.log(message)


def _try_load_job_context(env: Dict[str, str]) -> Tuple[Optional[JobContext], Optional[str]]:
    try:
        return load_job_context(env), None
    except (ValueError, OSError) as e:
        return None, f"{e.__class__.__name__}: {e}"


def _connection_settings(env: Dict[str, str], ctx: Optional[JobContext]) -> Tuple[str, str, str, Optional[str]]:
    """(job id, job token, callback URL, proxy URL) from the bundle, else from the legacy environment."""
    if ctx is None:
        return (
            env.get("RUNNER_JOB_ID", ""),
            env.get("RUNNER_CALLBACK_TOKEN", ""),
            env.get("RUNNER_CALLBACK_URL", ""),
            env.get("RUNNER_PROXY_URL"),
        )
    return ctx.job_id, ctx.token, ctx.callback_url, ctx.proxy_url


def proxy_password(proxy_url: Optional[str]) -> str:
    """Password of `scheme://user:password@host` proxy URLs (redacted from every log), "" without credentials."""
    if not proxy_url or "@" not in proxy_url:
        return ""
    return proxy_url.split("//", 1)[-1].split("@", 1)[0].split(":", 1)[-1]


def _security_report(
    non_dumpable: bool, git_token_removed: Optional[bool], ctx: Optional[JobContext], log: Callable[[str], None]
) -> Dict[str, Any]:
    """`result.security`: evidence of the prompt-injection hardening in effect (updated while the job runs)."""
    job_token_removed = ctx.token_file_removed if ctx else None
    security: Dict[str, Any] = {
        "gitAccess": ctx.spec.git_access if ctx else None,
        "processNonDumpable": non_dumpable,
        "gitTokenFileRemoved": git_token_removed,
        "jobTokenFileRemoved": job_token_removed,
        "agentTokenIsolated": False,
        "strayProcessesKilled": 0,
        "gitConfigRestored": False,
    }
    if git_token_removed is False:
        log("[runner] WARN the git token file could not be deleted (read-only mount): it stays readable by the agent;"
            " the runner-controller should deliver it into a writable memory volume")
    if job_token_removed is False:
        log("[runner] WARN the job-token file could not be deleted (read-only mount): it stays readable by the agent;"
            " the runner-controller should deliver the bundle into a writable memory volume")
    return security


def _isolate_agent_token(
    client: CallbackClient, ctx: Optional[JobContext], redact: Redactor, security: Dict[str, Any], log: Callable[[str], None]
) -> Optional[str]:
    """The agent gets a gateway-only token; the job token (result/events/uploads) stays with the entrypoint."""
    agent_token = exchange_agent_token(client, log) if ctx is not None else None
    if agent_token:
        redact.add(agent_token)
        security["agentTokenIsolated"] = True
        log("[runner] agent gets a gateway-only agent token (cannot post results, events or uploads)")
    return agent_token


def _untrusted_env(run: _Run, spec: JobSpec) -> Dict[str, str]:
    """Environment of setupCommand, verifyCommand and Spec Kit: the agent environment without any token."""
    return agent_environment(spec, run.env, run.workspace, "", run.proxy_url)


def _log_job_header(run: _Run, spec: JobSpec, job_id: str, source: str) -> None:
    run.log(
        f"[runner] job={job_id or '-'} mode={spec.mode} agent={spec.agent} model={spec.model.provider}/{spec.model.model_id}"
        f" reasoning={spec.model.reasoning or '-'} timeout={spec.timeout_seconds}s phase={spec.spec_kit_phase or '-'}"
        f" spec={source} proxy={'yes' if run.proxy_url else 'no'} gateway={'yes' if spec.gateway else 'no'}"
        f" git={spec.git_access}"
        + (f" repositories={','.join(f'{r.key}:{r.access}' for r in spec.repositories) or '-'}" if spec.multi_repo else "")
    )


def _log_tool_policy(run: _Run) -> None:
    tools = run.tools
    if tools is None:
        return
    run.log(f"[policy] tools allow={tools.allow or ['*']} deny={tools.deny} ask={tools.ask}")
    if tools.ask:
        run.sink.progress(f"policy: unattended job, 'ask' treated as deny: {', '.join(tools.ask)}")


def _log_tool_versions(run: _Run) -> None:
    env = run.env
    run.log(f"[runner] tools: git={_tool_version(['git', '--version'], env)} | node={_tool_version(['node', '--version'], env)}"
            f" | java={_tool_version(['java', '-version'], env)} | opencode={_tool_version(['opencode', '--version'], env)}"
            f" | claude={_tool_version(['claude', '--version'], env)} | specify={_tool_version(['specify', '--version'], env)}")


def _validate_push_target(repository: Repository) -> None:
    """Fail before any untrusted code runs; Git.push checks again right before pushing."""
    if not (repository.branch and repository.push):
        return
    try:
        validate_push_branch(repository.branch, repository.base_ref)
        if repository.merge_request is not None:
            validate_push_branch(repository.branch, repository.merge_request.target_branch)
    except ValueError as e:
        raise RuntimeError(f"push refused: {e}") from e


def _prepare_repository(run: _Run, spec: JobSpec, repo_dir: Path, git_token: str) -> Optional[Git]:
    """Clone, identity, push target check, run branch; then the trusted `.git` state is recorded."""
    repository = spec.repository
    if not repository:
        repo_dir.mkdir(parents=True, exist_ok=True)
        run.log("[runner] no repository in spec; using empty workspace")
        return None
    if repo_dir.exists():
        shutil.rmtree(repo_dir, ignore_errors=True)
    git = Git(repo_dir, run.env, run.log, token=git_token, repo_url=repository.url, access=spec.git_access)
    if spec.git_access != GIT_ACCESS_PUSH_MR:
        run.log(f"[git] access {spec.git_access}: clone only, pushing is disabled (ADR 0031)")
    if not git.has_token and repository.url.startswith(HTTPS_PREFIX):
        run.log("[git] WARN no git token; cloning anonymously")
    run.sink.progress(f"cloning {run.redact(repository.url)} @ {repository.base_ref}")
    git.clone(repository.url, repository.base_ref)
    git.configure_identity(run.env.get("GIT_USER_NAME") or "Back Office Agent", run.env.get("GIT_USER_EMAIL") or "agent@backoffice.local")
    _validate_push_target(repository)
    if repository.branch:
        run.branch = repository.branch
        existed = git.checkout_branch(run.branch)
        run.log(f"[git] branch {run.branch} {'re-attached from origin' if existed else 'created'} (base {repository.base_ref})")
    run.log(f"[git] HEAD {git.head()}")
    # Trusted state before any untrusted code (setupCommand, agent, verifyCommand) runs in the repo.
    git.snapshot_config()
    git.pin_base(repository.base_ref)
    return git


def _run_workspace_setup(run: _Run, spec: JobSpec, work_dir: Path) -> None:
    """ADR 0044: the workspace's own setup, once per workspace (a marker under /workspace/.bo remembers the command)."""
    ws = spec.workspace
    if ws is None or not ws.setup_command:
        return
    marker = run.workspace / WORKSPACE_STATE_DIR / "setup.json"
    digest = sha256_hex(ws.setup_command.encode("utf-8"))
    previous = _read_regular_file(marker)
    try:
        done = previous is not None and json.loads(previous.decode("utf-8")).get("sha256") == digest
    except (ValueError, AttributeError):
        done = False
    if done:
        run.log(f"[workspace] setup of {ws.key} already ran in this workspace; skipped")
        return
    run.sink.progress(f"running the setup of workspace {ws.key}: {ws.setup_command}")
    res = run_process([ws.setup_command], work_dir, _untrusted_env(run, spec), timeout=max(60, spec.timeout_seconds),
                      on_line=lambda l: run.log(f"[workspace setup] {l}"), shell=True)
    if res.exit_code != 0:
        raise RuntimeError(f"workspace setup exited with code {res.exit_code}" + (" (timed out)" if res.timed_out else ""))
    marker.parent.mkdir(parents=True, exist_ok=True)
    _replace_file(marker, json.dumps({"sha256": digest}).encode("utf-8"))


def _run_setup(run: _Run, spec: JobSpec, repo_dir: Path) -> None:
    if not spec.setup_command:
        return
    run.sink.progress(f"running setupCommand: {spec.setup_command}")
    res = run_process([spec.setup_command], repo_dir, _untrusted_env(run, spec), timeout=max(60, spec.timeout_seconds), on_line=lambda l: run.log(f"[setup] {l}"), shell=True)
    if res.exit_code != 0:
        raise RuntimeError(f"setupCommand exited with code {res.exit_code}" + (" (timed out)" if res.timed_out else ""))


def needs_spec_kit(spec: JobSpec, repo_dir: Path) -> bool:
    """Spec Kit is initialised for SPEC_KIT jobs, jobs with a constitution and IMPLEMENT jobs on a tasks.md - never when
    the spec says `specKit: false` (ADR 0036: the project does not use Spec Kit)."""
    if spec.agent == STUB_AGENT or not spec.spec_kit_allowed:
        return False
    return spec.mode == "SPEC_KIT" or bool(spec.constitution) or (spec.mode == "IMPLEMENT" and has_tasks_md(repo_dir))


def _prepare_spec_kit(run: _Run, spec: JobSpec, repo_dir: Path) -> None:
    if not spec.spec_kit_allowed:
        run.log("[spec-kit] off for this project (specKit: false): no specify init, no constitution")
        return
    if needs_spec_kit(spec, repo_dir):
        ensure_spec_kit(repo_dir, spec.agent, _untrusted_env(run, spec), run.log)
    if spec.constitution:
        write_constitution(repo_dir, spec.constitution, run.log)


def _claude_help(agent_env: Dict[str, str]) -> str:
    """`claude --help` output (flags differ between versions); "" when the CLI is missing or does not answer."""
    if not shutil.which("claude"):
        return ""
    try:
        help_cp = subprocess.run(["claude", "--help"], capture_output=True, text=True, errors="replace", env=agent_env, timeout=60)  # noqa: S603
    except (OSError, subprocess.TimeoutExpired):
        return ""
    return (help_cp.stdout or "") + (help_cp.stderr or "")


def _claude_invocation(
    run: _Run, spec: JobSpec, repo_dir: Path, prompt: str, agent_env: Dict[str, str], session: Optional[AgentSession] = None,
) -> List[str]:
    if spec.local_login:
        if not run.local_login_token:
            raise ValueError(f"llmCredentials LOCAL_LOGIN but no {LOCAL_LOGIN_FILE} in the job bundle: the runner-controller"
                             " needs CLAUDE_CODE_OAUTH_TOKEN (claude setup-token) - ADR 0040")
        run.subscription = True
        run.log("[agent] Claude Code uses the personal runner owner's own login (ADR 0040): not metered by Back Office")
    else:
        require_gateway(spec.gateway, "anthropic")
    if spec.model.provider != "ANTHROPIC":
        run.log(f"[agent] WARN Claude Code with provider {spec.model.provider}: the gateway's Anthropic endpoint must serve this model id")
    settings_file = _write_policy_files(spec, run.tools, run.workspace, repo_dir, run.log)
    permission_mode = run.env.get("RUNNER_CLAUDE_PERMISSION_MODE") or ("dontAsk" if run.tools is not None else "bypassPermissions")
    help_text = _claude_help(agent_env)
    if run.extra_dirs and "--add-dir" not in help_text:
        run.log("[agent] WARN this Claude Code has no --add-dir: the other repositories may be outside its allowed directories")
    if session is not None and "--session-id" not in help_text:
        run.log("[agent] WARN this Claude Code cannot keep a named session (no --session-id): a new session starts")
    return build_claude_command(spec.model, prompt, permission_mode, help_text, settings_file, run.extra_dirs, session)


LineParser = Callable[[str, AgentOutcome], Optional[str]]


def _agent_invocation(
    run: _Run, spec: JobSpec, repo_dir: Path, prompt: str, agent_env: Dict[str, str], session: Optional[AgentSession] = None,
) -> Tuple[List[str], LineParser, bool]:
    """(command, output line parser, run through the shell) for the spec's agent."""
    if spec.agent == "OPENCODE":
        config = build_opencode_config(spec.model, spec.gateway, run.tools, run.extra_dirs)
        (run.workspace / "opencode.json").write_text(json.dumps(config, indent=2), encoding="utf-8")
        return build_opencode_command(spec.model, prompt, bool(session and session.resume)), parse_opencode_line, False
    if spec.agent == "CLAUDE_CODE":
        return _claude_invocation(run, spec, repo_dir, prompt, agent_env, session), parse_claude_line, False
    # STUB (tests only): the prompt is a shell script run with the agent's environment
    return [prompt], parse_stub_line, True


def _warn_missing_usage(run: _Run) -> None:
    if run.outcome.json_lines == 0:
        run.log("[agent] WARN no JSON events recognised in agent output; token usage will be reported as zero")
    elif not run.outcome.usage.seen:
        run.log("[agent] WARN agent output contained no usage information; token usage reported as zero")


def _kill_strays(run: _Run) -> None:
    run.security["strayProcessesKilled"] += kill_stray_processes(run.log, only_descendants=not run.sandboxed)


def _run_agent(run: _Run, spec: JobSpec, repo_dir: Path) -> None:
    prompt = with_repositories_section(build_agent_prompt(spec, repo_dir), spec, run.workspace) if spec.agent != STUB_AGENT else spec.prompt
    agent_env = agent_environment(spec, run.env, run.workspace, run.agent_credential, run.proxy_url, run.local_login_token)
    session = agent_session(spec, run.workspace, agent_env)
    if session is not None:
        if session.data_dir is not None:
            session.data_dir.mkdir(parents=True, exist_ok=True)
            agent_env["XDG_DATA_HOME"] = str(session.data_dir)
        run.log(f"[agent] session of role {session.role}: {'continuing' if session.resume else 'starting'} {session.session_id} (run workspace)")
    cmd, parser, shell = _agent_invocation(run, spec, repo_dir, prompt, agent_env, session)
    elapsed = time.monotonic() - run.started
    agent_timeout = max(60.0, spec.timeout_seconds - elapsed)
    run.log(f"[agent] prompt ({len(prompt)} chars): {prompt.splitlines()[0][:200] if prompt else ''}")
    run.log(f"[agent] command: {' '.join(shlex.quote(c) if c is not prompt else '<prompt>' for c in cmd)} (timeout {int(agent_timeout)}s)")
    run.sink.progress("agent running")
    outcome = run.outcome

    def on_agent_line(line: str) -> None:
        human = parser(line, outcome)
        if human:
            run.log(human)

    res = run_process(cmd, repo_dir, agent_env, timeout=agent_timeout, on_line=on_agent_line, shell=shell, tail_bytes=VERIFY_TAIL_BYTES)
    run.agent_exit, run.timed_out = res.exit_code, res.timed_out
    if spec.agent != STUB_AGENT:
        _warn_missing_usage(run)
    run.log(f"[agent] exited {run.agent_exit} after {int(res.duration_seconds)}s timed_out={run.timed_out} usage={outcome.usage.to_json()}")
    for d in outcome.denials:
        run.log(f"[policy] denied {d.get('tool')}: {d.get('input')}")
    _kill_strays(run)


def _run_verify(run: _Run, spec: JobSpec, repo_dir: Path) -> None:
    if not spec.verify_command or run.timed_out:
        return
    run.sink.progress(f"running verifyCommand: {spec.verify_command}")
    verify_timeout = float(run.env.get("RUNNER_VERIFY_TIMEOUT") or 900)
    run.verify_res = run_process([spec.verify_command], repo_dir, _untrusted_env(run, spec), timeout=verify_timeout, on_line=lambda l: run.log(f"[verify] {l}"), shell=True)
    run.log(f"[verify] exited {run.verify_res.exit_code} timed_out={run.verify_res.timed_out}")
    # verifyCommand is repository code, i.e. untrusted as well.
    _kill_strays(run)


def _collect_run_artifacts(run: _Run, spec: JobSpec, repo_dir: Path) -> None:
    """Collect `spec.collectArtifacts`; files over the inline limit are also uploaded in full (blob reference)."""
    run.artifacts = collect_artifacts(repo_dir, spec.collect_artifacts, run.log)
    for a in list(run.artifacts):
        if a["content"].endswith(ARTIFACT_TRUNCATED_NOTE) and a.get("path"):
            data = (repo_dir / a["path"]).read_bytes()
            ref = run.uploader.upload(f"artifact:{a['path']}", a["mediaType"], data)
            if ref:
                run.artifacts.append(blob_ref_artifact(ref, a["path"]))


def _commit_changes(run: _Run, spec: JobSpec, git: Git) -> None:
    if not git.has_changes():
        run.log("[git] no changes to commit")
        return
    sha = git.commit_all(commit_message(spec.mode, spec.prompt), run_id=spec.run_id)
    if sha:
        trailer = f" (trailer {RUN_TRAILER}: {spec.run_id})" if spec.run_id else ""
        run.log(f"[git] committed {sha[:12]}" + trailer)


def _collect_diff(run: _Run, git: Git, base_ref: str) -> None:
    """Digest and capped text of the full diff; a diff over the cap is also uploaded in full (redacted)."""
    _record_diff(run, git.diff_bytes(base_ref), base_ref)


def _record_diff(run: _Run, full_diff: Optional[bytes], base_ref: str) -> None:
    """`result.diff` (capped) and `digests.diffSha256` from the full diff of the (primary) repository."""
    if full_diff is not None:
        run.digests["diffSha256"] = sha256_hex(full_diff)
        run.diff_text = cap_diff(full_diff)
        if len(full_diff) > DIFF_MAX_BYTES:
            ref = run.uploader.upload("diff", "text/x-diff", run.redact(full_diff.decode("utf-8", errors="replace")).encode("utf-8"))
            if ref:
                run.artifacts.append(blob_ref_artifact(ref, None))
    run.log(f"[git] diff against {base_ref}: {len(full_diff) if full_diff else 0} bytes")


def _has_commits_to_push(run: _Run, git: Git, base_ref: str) -> bool:
    return _commits_ahead_of_base(git, base_ref, run.diff_stat)


def _commits_ahead_of_base(git: Git, base_ref: str, diff_stat: Optional[Dict[str, int]]) -> bool:
    if (diff_stat or {}).get("files", 0) > 0:
        return True
    count = git.run(["rev-list", "--count", f"{git.resolve_base(base_ref)}..HEAD"], check=False).stdout.strip()
    return count not in ("", "0")


def _log_merge_request(run: _Run, mr: Optional[MergeRequest]) -> None:
    if mr is None:
        return
    if run.merge_request_url:
        run.log(f"[git] merge request: {run.merge_request_url}")
    else:
        run.log("[git] WARN push options sent but no merge request URL found in the push output (host ignores merge_request.* options, or the MR already existed?)")


def _push_branch(run: _Run, repository: Repository, git: Git, branch: str) -> None:
    """Push the run branch (merge request push options when requested); a refused or failed push fails the job."""
    if not _has_commits_to_push(run, git, repository.base_ref):
        run.log("[git] nothing to push")
        return
    mr = repository.merge_request
    run.sink.progress(f"pushing {branch}" + (f" (merge request -> {mr.target_branch})" if mr else ""))
    try:
        run.merge_request_url = git.push(branch, repository.url, repository.base_ref, mr)
    except ValueError as e:
        run.fatal_error = f"push refused: {run.redact(str(e))}"
        run.log(f"[git] ERROR {run.fatal_error}")
        return
    except RuntimeError as e:
        run.fatal_error = f"push failed: {run.redact(str(e))}"
        run.log(f"[git] ERROR {run.fatal_error}")
        return
    run.log(f"[git] pushed {branch}")
    _log_merge_request(run, mr)


def _commit_and_push(run: _Run, spec: JobSpec, repository: Repository, git: Git) -> None:
    _commit_changes(run, spec, git)
    run.commit_sha = git.head()
    run.diff_stat = git.diff_stat(repository.base_ref)
    if spec.collect_diff:
        _collect_diff(run, git, repository.base_ref)
    if repository.push and run.branch and git.can_push:
        _push_branch(run, repository, git, run.branch)
    elif run.branch and not git.can_push:
        run.log(f"[git] access {git.access}: nothing is pushed")


# --------------------------------------------------------------------------------------------
# Multi-repository jobs (`spec.repositories`)
# --------------------------------------------------------------------------------------------


@dataclass
class _RepoState:
    """One repository of a multi-repository job: its own `Git` (own token, own access level) and its result entry."""

    repo: RepoSpec
    directory: Path
    result: Dict[str, Any]
    git: Optional[Git] = None
    diff: Optional[bytes] = None
    integrity_ok: bool = True

    @property
    def tag(self) -> str:
        return f"[git:{self.repo.key}]"


def _initial_repo_result(repo: RepoSpec) -> Dict[str, Any]:
    """`result.repositories[]` entry (filled while the job runs)."""
    return {"key": repo.key, "branch": None, "commitSha": None, "diffStat": None, "mergeRequestUrl": None, "pushed": False, "error": None}


def _repo_log(run: _Run, state: _RepoState) -> Callable[[str], None]:
    """Log of one repository's `Git`: its `[git]` lines are tagged with the repository key."""
    return lambda m: run.log(m.replace("[git]", state.tag, 1) if m.startswith("[git]") else f"{state.tag} {m}")


def _validate_repo_push_targets(spec: JobSpec) -> None:
    """Every PUSH_MR repository's branch is checked before anything is cloned (Git.push checks again)."""
    for repo in spec.repositories:
        if not repo.can_push:
            continue
        try:
            validate_push_branch(repo.push_branch or "", repo.base_ref)
            if repo.merge_request is not None:
                validate_push_branch(repo.push_branch or "", repo.merge_request.target_branch)
        except ValueError as e:
            raise RuntimeError(f"push refused: repository {repo.key}: {e}") from e


def _clone_marker(run: _Run, repo: RepoSpec) -> Path:
    return run.workspace / WORKSPACE_STATE_DIR / "repos" / f"{repo.path.replace('/', '__')}.json"


def _reusable_clone(run: _Run, spec: JobSpec, state: _RepoState) -> bool:
    """ADR 0044: a repository directory of the run workspace that an earlier job's runner cloned from the same URL."""
    if spec.workspace is None or not (state.directory / ".git").is_dir() or (state.directory / ".git").is_symlink():
        return False
    marker = _read_regular_file(_clone_marker(run, state.repo))
    try:
        return marker is not None and json.loads(marker.decode("utf-8")).get("url") == state.repo.url
    except (ValueError, AttributeError):
        return False


def _remember_clone(run: _Run, spec: JobSpec, repo: RepoSpec) -> None:
    if spec.workspace is None:
        return
    marker = _clone_marker(run, repo)
    marker.parent.mkdir(parents=True, exist_ok=True)
    _replace_file(marker, json.dumps({"url": repo.url, "key": repo.key}).encode("utf-8"))


def _reattach_repository(run: _Run, state: _RepoState, git: Git) -> None:
    """ADR 0044: continue in the clone an earlier step of the run left (working tree and local commits kept)."""
    repo = state.repo
    run.sink.progress(f"reusing {repo.key} from the run workspace: {run.redact(repo.url)} -> {repo.path}")
    git.reattach(repo.url, repo.base_ref)
    git.configure_identity(run.env.get("GIT_USER_NAME") or "Back Office Agent", run.env.get("GIT_USER_EMAIL") or "agent@backoffice.local")
    if repo.can_push and repo.push_branch:
        current = git.current_branch()
        if current == repo.push_branch:
            run.log(f"{state.tag} staying on branch {repo.push_branch} (run workspace)")
        elif git.has_local_branch(repo.push_branch):
            git.run(["checkout", "--quiet", repo.push_branch])
            run.log(f"{state.tag} switched to the local branch {repo.push_branch} (run workspace)")
        else:
            existed = git.checkout_branch(repo.push_branch)
            run.log(f"{state.tag} branch {repo.push_branch} {'re-attached from origin' if existed else 'created'} (run workspace)")
        state.result["branch"] = repo.push_branch
    dirty = git.has_changes()
    run.log(f"{state.tag} reused from an earlier step of the run: HEAD {git.head()}" + (", with uncommitted changes" if dirty else ""))


def _clone_repository(run: _Run, state: _RepoState, spec: Optional[JobSpec] = None) -> None:
    """Clone into <workspace>/<path> with the repository's own token; READ / NONE clones get the push URL
    disabled, PUSH_MR clones the job branch. In a run workspace (ADR 0044) a clone of an earlier step is reused.
    Then the trusted `.git` state is recorded."""
    repo = state.repo
    token = run.repo_tokens.get(repo.key) if repo.access != GIT_ACCESS_NONE else None
    git = Git(state.directory, run.env, _repo_log(run, state), token=token, repo_url=repo.url, access=repo.access, bind_path=True)
    state.git = git
    if spec is not None and _reusable_clone(run, spec, state):
        if not repo.can_push:
            run.log(f"{state.tag} access {repo.access}: pushing is disabled (ADR 0031)")
        _reattach_repository(run, state, git)
        git.snapshot_config()
        git.pin_base(repo.base_ref)
        return
    if state.directory.exists():
        shutil.rmtree(state.directory, ignore_errors=True)
    if not repo.can_push:
        run.log(f"{state.tag} access {repo.access}: clone only, pushing is disabled (ADR 0031)")
    if not git.has_token and repo.url.startswith(HTTPS_PREFIX):
        run.log(f"{state.tag} {'no credential (access NONE)' if repo.access == GIT_ACCESS_NONE else 'WARN no git token'}; cloning anonymously")
    run.sink.progress(f"cloning {repo.key}: {run.redact(repo.url)} @ {repo.base_ref} -> {repo.path}")
    git.clone(repo.url, repo.base_ref)
    git.configure_identity(run.env.get("GIT_USER_NAME") or "Back Office Agent", run.env.get("GIT_USER_EMAIL") or "agent@backoffice.local")
    if repo.can_push and repo.push_branch:
        existed = git.checkout_branch(repo.push_branch)
        state.result["branch"] = repo.push_branch
        run.log(f"{state.tag} branch {repo.push_branch} {'re-attached from origin' if existed else 'created'} (base {repo.base_ref})")
    run.log(f"{state.tag} HEAD {git.head()} in {repo.path}")
    git.snapshot_config()
    git.pin_base(repo.base_ref)
    if spec is not None:
        _remember_clone(run, spec, repo)


def _prepare_repositories(run: _Run, spec: JobSpec) -> List[_RepoState]:
    states = [_RepoState(r, run.workspace / r.path, _initial_repo_result(r)) for r in spec.repositories]
    run.repo_results = [s.result for s in states]
    run.security["gitAccessByRepository"] = {r.key: r.access for r in spec.repositories}
    _validate_repo_push_targets(spec)
    for state in states:
        try:
            _clone_repository(run, state, spec)
        except (RuntimeError, OSError, ValueError) as e:
            state.result["error"] = f"clone failed: {run.redact(str(e))}"
            raise RuntimeError(f"repository {state.repo.key}: {state.result['error']}") from e
    return states


def _restore_repositories(run: _Run, states: List[_RepoState]) -> None:
    """Git integrity of every repository after untrusted code ran; a repository whose `.git` was replaced is
    neither committed nor pushed (its error is reported, the others continue)."""
    restored = False
    for state in states:
        try:
            restored = state.git.restore_after_agent() or restored  # type: ignore[union-attr]
        except (RuntimeError, OSError) as e:
            state.integrity_ok = False
            state.result["error"] = run.redact(str(e))
            run.log(f"{state.tag} ERROR {state.result['error']}")
    run.security["gitConfigRestored"] = restored


def _commit_repository(run: _Run, spec: JobSpec, state: _RepoState) -> None:
    git = state.git
    assert git is not None
    if spec.workspace is not None and not state.repo.can_push:
        # ADR 0044: in a run workspace a read-only step leaves its changes uncommitted for the next step
        if git.has_changes():
            run.log(f"{state.tag} changes left uncommitted in the run workspace (access {state.repo.access})")
    elif git.has_changes():
        sha = git.commit_all(commit_message(spec.mode, spec.prompt), run_id=spec.run_id)
        if sha:
            run.log(f"{state.tag} committed {sha[:12]}" + (f" (trailer {RUN_TRAILER}: {spec.run_id})" if spec.run_id else ""))
    else:
        run.log(f"{state.tag} no changes to commit")
    state.result["commitSha"] = git.head()
    state.result["diffStat"] = git.diff_stat(state.repo.base_ref)
    if spec.collect_diff:
        state.diff = git.diff_bytes(state.repo.base_ref)


def _push_repository(run: _Run, state: _RepoState) -> None:
    repo, git = state.repo, state.git
    assert git is not None
    if not _commits_ahead_of_base(git, repo.base_ref, state.result["diffStat"]):
        run.log(f"{state.tag} nothing to push")
        return
    mr = repo.merge_request
    run.sink.progress(f"pushing {repo.key}: {repo.push_branch}" + (f" (merge request -> {mr.target_branch})" if mr else ""))
    try:
        url = git.push(repo.push_branch or "", repo.url, repo.base_ref, mr)
    except ValueError as e:
        state.result["error"] = f"push refused: {run.redact(str(e))}"
    except RuntimeError as e:
        state.result["error"] = f"push failed: {run.redact(str(e))}"
    else:
        state.result["pushed"] = True
        state.result["mergeRequestUrl"] = url
        run.log(f"{state.tag} pushed {repo.push_branch}" + (f"; merge request: {url}" if url else ""))
        if mr is not None and not url:
            run.log(f"{state.tag} WARN push options sent but no merge request URL found in the push output")
        return
    run.log(f"{state.tag} ERROR {state.result['error']}")


def _finish_repository(run: _Run, spec: JobSpec, state: _RepoState) -> None:
    """Commit, diff and (PUSH_MR only) push one repository; any failure stays in its own result entry."""
    if not state.integrity_ok:
        return
    try:
        _commit_repository(run, spec, state)
        if state.repo.can_push:
            _push_repository(run, state)
        elif (state.result["diffStat"] or {}).get("files", 0) > 0:
            run.log(f"{state.tag} access {state.repo.access}: local changes are reported, nothing is pushed")
    except (RuntimeError, OSError, ValueError) as e:
        state.result["error"] = run.redact(f"{e.__class__.__name__}: {e}")
        run.log(f"{state.tag} ERROR {state.result['error']}")


def _report_repositories(run: _Run, states: List[_RepoState]) -> None:
    """Legacy top-level fields from the primary repository; other repositories' diffs as `diff:<key>` artefacts;
    the job fails when a PUSH_MR repository failed (every repository's own result stays visible)."""
    for state in states:
        if state.repo.primary:
            run.branch = state.result["branch"]
            run.commit_sha = state.result["commitSha"]
            run.diff_stat = state.result["diffStat"]
            run.merge_request_url = state.result["mergeRequestUrl"]
            if state.diff is not None:
                _record_diff(run, state.diff, state.repo.base_ref)
        elif state.diff:
            run.artifacts.append({"name": f"diff:{state.repo.key}", "path": None, "mediaType": "text/x-diff", "content": cap_diff(state.diff)})
    failed = [f"repository {s.repo.key}: {s.result['error']}" for s in states if s.repo.can_push and s.result["error"]]
    if failed and not run.fatal_error:
        run.fatal_error = "; ".join(failed)[:4000]


def _execute_multi_repo(run: _Run, spec: JobSpec) -> None:
    """Workspace with several repositories (or none): clone each into <workspace>/<path>, run everything in the
    primary repository (the workspace when there is none), then commit / diff / push per repository."""
    states = _prepare_repositories(run, spec)
    primary = next((s for s in states if s.repo.primary), None)
    work_dir = primary.directory if primary else run.workspace
    work_dir.mkdir(parents=True, exist_ok=True)
    run.extra_dirs = [str(s.directory) for s in states if s is not primary]
    if not states:
        run.log(f"[runner] no repositories in spec; working directory {work_dir}")
    if spec.skills:
        write_skills(work_dir, spec.skills, run.log)
    _run_workspace_setup(run, spec, work_dir)
    _run_setup(run, spec, work_dir)
    if spec.workspace is not None and spec.workspace.prepare_only:
        _finish_prepare_only(run, spec, states)
        return
    _prepare_spec_kit(run, spec, work_dir)
    _run_agent(run, spec, work_dir)
    _run_verify(run, spec, work_dir)
    _restore_repositories(run, states)
    if spec.collect_artifacts:
        _collect_run_artifacts(run, spec, work_dir)
    for state in states:
        _finish_repository(run, spec, state)
    _report_repositories(run, states)


def _finish_prepare_only(run: _Run, spec: JobSpec, states: List[_RepoState]) -> None:
    """ADR 0044: a prepare step: the repositories are in the run workspace and setupCommand ran; no agent, no commit,
    no push. setupCommand is repository code, so `.git` is restored as after an agent."""
    _restore_repositories(run, states)
    run.agent_exit = 0
    names = ", ".join(f"{s.repo.key} ({s.repo.path})" for s in states) or "no repositories"
    run.outcome.final_result = (
        f"Workspace {spec.workspace.key if spec.workspace else ''} prepared: {names}"
        + (f"; setupCommand: {spec.setup_command}" if spec.setup_command else "")
        + ". No agent ran."
    )
    for state in states:
        state.result["commitSha"] = state.git.head() if state.git else None
    _report_repositories(run, states)
    run.log(f"[workspace] prepared {names}; later steps of the run reuse them")


def _execute_job(run: _Run, ctx: Optional[JobContext], ctx_error: Optional[str], job_id: str, git_token: str) -> None:
    """Everything between reading the bundle and building the result; raises on fatal errors."""
    spec = ctx.spec if ctx else None
    if spec is None:
        raise ValueError(ctx_error or "no job spec")
    run.verify_command = spec.verify_command
    _log_job_header(run, spec, job_id, ctx.source)
    apply_process_limit(run.env, run.log)
    run.workspace.mkdir(parents=True, exist_ok=True)
    _prepare_home(run.workspace, run.env, run.log)
    _log_tool_policy(run)
    if spec.agent != STUB_AGENT:
        _log_tool_versions(run)
    if spec.multi_repo:
        if git_token:
            run.log("[git] WARN a single-repository git token was delivered for a multi-repository job; it is not used")
        _execute_multi_repo(run, spec)
        return

    repo_dir = run.workspace / "repo"
    git = _prepare_repository(run, spec, repo_dir, git_token)
    if spec.skills:
        write_skills(repo_dir, spec.skills, run.log)
    _run_setup(run, spec, repo_dir)
    _prepare_spec_kit(run, spec, repo_dir)
    _run_agent(run, spec, repo_dir)
    _run_verify(run, spec, repo_dir)
    # git integrity: nothing the agent planted in .git runs during commit/diff/push
    if git is not None:
        run.security["gitConfigRestored"] = git.restore_after_agent()
    if spec.collect_artifacts:
        _collect_run_artifacts(run, spec, repo_dir)
    if git is not None and spec.repository is not None:
        _commit_and_push(run, spec, spec.repository, git)


def _redact_result(result: Dict[str, Any], redact: Redactor) -> None:
    for key in ("summary", "error", "diff"):
        if result.get(key):
            result[key] = redact(result[key])
    for a in result["artifacts"]:
        a["content"] = redact(a["content"])


def _attach_runner_log(run: _Run, result: Dict[str, Any], log_file: LogFile) -> None:
    log_bytes = log_file.read_bytes()
    if not log_bytes:
        return
    run.digests["logSha256"] = sha256_hex(log_bytes)
    ref = run.uploader.upload("runner.log", "text/plain", log_bytes)
    if ref:
        result["artifacts"].append(blob_ref_artifact(ref, None))


def _finalize_result(run: _Run, log_file: LogFile) -> Dict[str, Any]:
    result = build_result(
        agent_exit=run.agent_exit,
        timed_out=run.timed_out,
        outcome=run.outcome,
        verify_command=run.verify_command,
        verify=run.verify_res,
        branch=run.branch,
        commit_sha=run.commit_sha,
        diff_stat=run.diff_stat,
        artifacts=run.artifacts,
        error=run.fatal_error,
        diff=run.diff_text,
        merge_request_url=run.merge_request_url,
    )
    if run.subscription:
        # ADR 0040: costUsd stays Claude Code's estimate; Back Office does not count it as spend
        result["usage"]["billing"] = BILLING_SUBSCRIPTION
    _redact_result(result, run.redact)
    if run.repo_results is not None:
        # multi-repository jobs; the top-level branch/commit/diff/MR fields above are the primary repository's
        result["repositories"] = [
            {k: (run.redact(v) if k == "error" and v else v) for k, v in entry.items()} for entry in run.repo_results
        ]
    if run.tools is not None:
        result["policy"] = {"askTreatedAsDeny": run.tools.ask, "denials": run.outcome.denials}
    # Evidence of the prompt-injection hardening that was in effect (Back Office stores the result as is).
    result["security"] = run.security
    run.log(f"[runner] result status={result['status']} commit={result.get('commitSha') or '-'} artefacts={len(result['artifacts'])}"
            f" diff={'yes' if result.get('diff') else 'no'} mr={result.get('mergeRequestUrl') or '-'} error={result.get('error') or '-'}")
    _attach_runner_log(run, result, log_file)
    result["digests"] = run.digests
    return result


def _write_result_file(result_file: Optional[str], result: Dict[str, Any], log: Callable[[str], None]) -> None:
    if not result_file:
        return
    try:
        Path(result_file).write_text(json.dumps(result, indent=2), encoding="utf-8")
        log(f"[runner] result written to {result_file}")
    except OSError as e:
        log(f"[runner] WARN cannot write result file: {e}")


def _deliver_result(client: CallbackClient, result: Dict[str, Any]) -> int:
    ok, err = client.post_json("result", result, attempts=RESULT_MAX_ATTEMPTS, base_delay=2.0)
    if ok:
        print(f"[runner] result delivered ({result['status']})", flush=True)
        return 0
    print(f"[runner] FATAL could not deliver result: {err}", file=sys.stderr, flush=True)
    return 3


def main() -> int:
    # Before any secret is read: a same-UID agent must not be able to read our memory later.
    early_log: List[str] = []
    non_dumpable = harden_process(early_log.append)
    started = time.monotonic()
    env = dict(os.environ)
    workspace = Path(env.get("RUNNER_WORKSPACE") or "/workspace").resolve()
    result_file = env.get("RUNNER_RESULT_FILE")
    # Inside the runner image (ENV RUNNER_SANDBOX=1) every other process of our UID is untrusted; elsewhere (local
    # debugging, tests) only our own process tree is swept.
    sandboxed = env.get("RUNNER_SANDBOX") == "1"

    # The bundle is read before anything else so the token and proxy are known for every callback.
    ctx, ctx_error = _try_load_job_context(env)
    # Read once into memory (the file is deleted when possible); never exported to any environment.
    git_token, git_token_removed = load_git_token(env)
    # Multi-repository jobs: one token per repository (<token dir>/<key>/token), each read once the same way.
    repo_tokens, repo_tokens_removed = load_repo_tokens(env, [r.key for r in ctx.spec.repositories] if ctx else [])
    git_token_removed = _combine_removed(git_token_removed, repo_tokens_removed)
    # ADR 0040: the owner's Claude Code login of a LOCAL_LOGIN job, read once the same way
    local_login_token, local_login_removed = load_local_login_token(env, ctx)
    job_id, callback_token, callback_url, proxy_url = _connection_settings(env, ctx)
    apply_proxy_env(env, proxy_url)

    redact = Redactor([env.get(n, "") for n in SECRET_ENV_NAMES] + [callback_token, proxy_password(proxy_url), git_token]
                      + list(repo_tokens.values()) + [local_login_token])
    client = CallbackClient(callback_url, job_id, callback_token, redact, proxy_url)
    log_file = LogFile(private_log_path(env, job_id))
    sink = EventSink(client, redact, log_file)
    sink.start()
    log = sink.log
    uploader = Uploader(client, log)
    for line in early_log:
        log(line)

    if not client.enabled:
        log("[runner] WARN no callback URL or job id: running in offline mode (events -> stdout only)")

    security = _security_report(non_dumpable, git_token_removed, ctx, log)
    if ctx is not None and ctx.spec.local_login:
        security["llmCredentials"] = LLM_CREDENTIALS_LOCAL_LOGIN
        security["localLoginFileRemoved"] = local_login_removed
    agent_token = _isolate_agent_token(client, ctx, redact, security, log)
    run = _Run(
        env=env, workspace=workspace, started=started, sandboxed=sandboxed, proxy_url=proxy_url, redact=redact, sink=sink,
        uploader=uploader, security=security, agent_credential=agent_token or callback_token,
        tools=tool_policy_from(ctx.policy) if ctx else None, fatal_error=ctx_error, repo_tokens=repo_tokens,
        local_login_token=local_login_token,
    )

    if ctx_error:
        log(f"[runner] ERROR {redact(ctx_error)}")

    try:
        _execute_job(run, ctx, ctx_error, job_id, git_token)
    except Exception as e:  # noqa: BLE001
        run.fatal_error = redact(f"{e.__class__.__name__}: {e}")
        log(f"[runner] ERROR {run.fatal_error}")

    result = _finalize_result(run, log_file)
    _write_result_file(result_file, result, log)
    sink.close()
    return _deliver_result(client, result)


if __name__ == "__main__":
    sys.exit(main())
