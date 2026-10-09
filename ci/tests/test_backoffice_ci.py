"""Tests for ci/backoffice-ci.sh.

Starts a fake Back Office API (http.server) that records requests and returns canned CiExecution /
CiSignalResult documents, prepares a temporary git repository with a merge-request-like history and
runs the script through `sh`. Run with:

    python3 -m unittest discover -s ci/tests -v

On Windows the tests use Git Bash (`sh.exe`); override the shell with BACKOFFICE_CI_SH.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Dict, List, Optional

SCRIPT = Path(__file__).resolve().parents[1] / "backoffice-ci.sh"
TOKEN = "bo_ci_" + "t" * 40
RUN_ID = "11111111-2222-4333-8444-555555555555"
TRAILER_RUN_ID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"

TRICKY_CONTENT = (
    'He said "hello" \\ back\\slash\ttab here\n'
    "path C:\\Users\\x\\file.txt and unicode: zażółć gęślą jaźń ✓\n"
    "carriage\rreturn and a /slash/ and {braces} [brackets]\n"
    "  leading spaces and trailing spaces   \n"
    "last line without newline"
)


def find_sh() -> str:
    override = os.environ.get("BACKOFFICE_CI_SH")
    if override:
        return override
    if os.name == "nt":
        for candidate in (r"C:\Program Files\Git\usr\bin\sh.exe", r"C:\Program Files\Git\bin\sh.exe"):
            if Path(candidate).exists():
                return candidate
    found = shutil.which("sh")
    if not found:
        raise unittest.SkipTest("no POSIX sh available")
    return found


SH = find_sh()


# --------------------------------------------------------------------------------------------
# Fake API
# --------------------------------------------------------------------------------------------


def execution_doc(exec_id: str, entrypoint: str, status: str, gate: str, mode: str = "BLOCKING", **extra: Any) -> Dict[str, Any]:
    doc: Dict[str, Any] = {
        "id": exec_id,
        "projectId": str(uuid.uuid5(uuid.NAMESPACE_DNS, "project")),
        "projectKey": "SHOP",
        "entrypoint": entrypoint,
        "mode": mode,
        "status": status,
        "gateResult": gate,
        "runId": RUN_ID if status != "QUEUED" else None,
        "context": {"provider": "GITLAB"},
        "cost": {"amount": 0.42, "currency": "USD"},
        "createdAt": "2026-09-26T10:00:00Z",
        "finishedAt": None,
        "summary": None,
        "report": None,
        "changedFiles": [],
        "variables": {},
        "error": None,
    }
    doc.update(extra)
    return doc


REPORT = {
    "verdict": "REQUEST_CHANGES",
    "score": 62,
    "summary": 'Summary line 1 "quoted" with a back\\slash\nsecond line of the summary',
    "findings": [
        {"severity": "BLOCKER", "file": "src/cart/CartController.kt", "line": 88, "title": "Price computed client-side", "detail": "d1", "suggestion": None},
        {"severity": "MAJOR", "file": "src/cart/CartService.kt", "line": None, "title": "Missing transaction boundary", "detail": "d2"},
        {"severity": "NIT", "file": "README.md", "line": 3, "title": 'Typo in "heading"', "detail": "d3"},
    ],
}


class FakeApiHandler(BaseHTTPRequestHandler):
    server: "FakeApi"

    def log_message(self, fmt: str, *args: Any) -> None:  # silence
        return

    def _json(self, code: int, doc: Any) -> None:
        data = json.dumps(doc).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _record(self, body: Optional[Dict[str, Any]]) -> Dict[str, Any]:
        rec = {"method": self.command, "path": self.path, "headers": {k.lower(): v for k, v in self.headers.items()}, "body": body}
        with self.server.lock:
            self.server.records.append(rec)
        return rec

    def _authorized(self) -> bool:
        return self.headers.get("Authorization") == f"Bearer {TOKEN}"

    def do_GET(self) -> None:  # noqa: N802
        self._record(None)
        if not self._authorized():
            return self._json(401, {"type": "about:blank", "title": "Unauthorized", "status": 401, "detail": "invalid CI token"})
        prefix = "/api/v1/ci/executions/"
        if self.path.startswith(prefix):
            exec_id = self.path[len(prefix):]
            entrypoint = self.server.executions.get(exec_id)
            if entrypoint is None:
                return self._json(404, {"title": "Not Found", "status": 404, "detail": f"execution {exec_id} not found"})
            with self.server.lock:
                self.server.polls[exec_id] = self.server.polls.get(exec_id, 0) + 1
                n = self.server.polls[exec_id]
            return self._json(200, self.server.execution_state(exec_id, entrypoint, n))
        return self._json(404, {"title": "Not Found", "status": 404})

    def do_POST(self) -> None:  # noqa: N802
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        try:
            body = json.loads(raw.decode("utf-8")) if raw else None
        except (ValueError, UnicodeDecodeError) as e:
            self._record({"__invalid__": raw.decode("utf-8", "replace"), "__error__": str(e)})
            return self._json(400, {"title": "Bad Request", "status": 400, "detail": f"invalid JSON: {e}"})
        self._record(body)
        if not self._authorized():
            return self._json(401, {"type": "about:blank", "title": "Unauthorized", "status": 401, "detail": "invalid CI token"})
        if self.path == "/api/v1/ci/executions":
            entrypoint = str(body.get("entrypoint"))
            if entrypoint == "unknown":
                return self._json(404, {"title": "Not Found", "status": 404, "detail": "no entrypoint unknown"})
            exec_id = str(uuid.uuid5(uuid.NAMESPACE_URL, entrypoint + str(len(self.server.records))))
            with self.server.lock:
                self.server.executions[exec_id] = entrypoint
                self.server.polls[exec_id] = 0
            return self._json(202, self.server.execution_state(exec_id, entrypoint, 0))
        if self.path == "/api/v1/ci/signals":
            signal = body.get("signal")
            if signal == "nothing":
                return self._json(200, {"runId": None, "resumed": False, "message": "no run is waiting for signal nothing"})
            run_id = body.get("runId") or RUN_ID
            return self._json(200, {"runId": run_id, "resumed": True, "message": f"run {run_id} resumed with {body.get('status')}"})
        return self._json(404, {"title": "Not Found", "status": 404})


class FakeApi(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self) -> None:
        super().__init__(("127.0.0.1", 0), FakeApiHandler)
        self.lock = threading.Lock()
        self.records: List[Dict[str, Any]] = []
        self.executions: Dict[str, str] = {}
        self.polls: Dict[str, int] = {}

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.server_address[1]}"

    def reset(self) -> None:
        with self.lock:
            self.records.clear()

    def execution_state(self, exec_id: str, entrypoint: str, poll: int) -> Dict[str, Any]:
        """QUEUED on creation, RUNNING on the first poll, terminal afterwards (except `slow`)."""
        if poll == 0:
            return execution_doc(exec_id, entrypoint, "QUEUED", "PENDING")
        if poll == 1 or entrypoint == "slow":
            return execution_doc(exec_id, entrypoint, "RUNNING", "PENDING")
        if entrypoint in ("code-review", "review-pass"):
            return execution_doc(exec_id, entrypoint, "SUCCEEDED", "PASS", summary="All good.", report=dict(REPORT, verdict="APPROVE", score=91))
        if entrypoint == "fail-review":
            return execution_doc(exec_id, entrypoint, "SUCCEEDED", "FAIL", report=REPORT)
        if entrypoint == "advisory-review":
            return execution_doc(exec_id, entrypoint, "SUCCEEDED", "FAIL", mode="ADVISORY", report=REPORT)
        if entrypoint == "broken":
            return execution_doc(exec_id, entrypoint, "FAILED", "FAIL", error="provider quota exceeded")
        return execution_doc(exec_id, entrypoint, "SUCCEEDED", "PASS")


# --------------------------------------------------------------------------------------------
# Git fixture
# --------------------------------------------------------------------------------------------


def git(cwd: Path, *args: str, check: bool = True) -> str:
    cp = subprocess.run(
        ["git", "-c", "user.name=Test", "-c", "user.email=test@example.com", "-c", "core.autocrlf=false", "-c", "commit.gpgsign=false", *args],
        cwd=str(cwd), capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if check and cp.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {cp.stderr}")
    return cp.stdout


def make_repo(root: Path) -> Path:
    """origin (bare) with `main`; clone `work` on branch feature/cart with two commits, the last carrying the trailer."""
    origin = root / "origin.git"
    origin.mkdir()
    git(origin, "init", "--bare", "-b", "main")
    work = root / "work"
    git(root, "clone", "-q", str(origin), str(work))
    git(work, "checkout", "-q", "-b", "main")
    git(work, "config", "core.autocrlf", "false")
    # bytes everywhere: text mode would write CRLF on Windows and the diffs must be byte-exact
    (work / "README.md").write_bytes(b"# Demo\n\nline\n")
    (work / "src").mkdir()
    (work / "src" / "app.kt").write_bytes(b"fun main() {}\n")
    git(work, "add", "-A")
    git(work, "commit", "-q", "-m", "init")
    git(work, "push", "-q", "-u", "origin", "main")
    # base moves on after the branch point so that merge-base differs from origin/main
    git(work, "checkout", "-q", "-b", "feature/cart")
    (work / "src" / "app.kt").write_bytes(b'fun main() { println("cart") }\n')
    (work / "tricky.txt").write_bytes(TRICKY_CONTENT.encode("utf-8"))
    git(work, "add", "-A")
    git(work, "commit", "-q", "-m", "IMPLEMENT: add cart")
    (work / "README.md").write_bytes(b"# Demo\n\nline\nmore\n")
    git(work, "add", "-A")
    git(work, "commit", "-q", "-m", f"IMPLEMENT: docs\n\nBack-Office-Run: {TRAILER_RUN_ID}")
    git(work, "push", "-q", "-u", "origin", "feature/cart")
    # advance main on the remote to make the merge-base meaningful
    git(work, "checkout", "-q", "main")
    (work / "CHANGELOG.md").write_bytes(b"unrelated change on main\n")
    git(work, "add", "-A")
    git(work, "commit", "-q", "-m", "main moves on")
    git(work, "push", "-q", "origin", "main")
    git(work, "checkout", "-q", "feature/cart")
    return work


# --------------------------------------------------------------------------------------------
# Test base
# --------------------------------------------------------------------------------------------


class ScriptTestCase(unittest.TestCase):
    api: FakeApi
    tmp: tempfile.TemporaryDirectory
    repo: Path

    @classmethod
    def setUpClass(cls) -> None:
        cls.api = FakeApi()
        threading.Thread(target=cls.api.serve_forever, name="fake-api", daemon=True).start()
        cls.tmp = tempfile.TemporaryDirectory(prefix="bo-ci-")
        cls.repo = make_repo(Path(cls.tmp.name))

    @classmethod
    def tearDownClass(cls) -> None:
        cls.api.shutdown()
        cls.api.server_close()
        try:
            cls.tmp.cleanup()
        except (OSError, PermissionError):
            pass

    def setUp(self) -> None:
        self.api.reset()

    def base_env(self, **overrides: str) -> Dict[str, str]:
        env = {k: v for k, v in os.environ.items() if not k.startswith(("CI_", "GITHUB_", "GITLAB_", "BACKOFFICE_", "CI"))}
        env.update({
            "BACKOFFICE_URL": self.api.url + "/",
            "BACKOFFICE_CI_TOKEN": TOKEN,
            "BACKOFFICE_POLL_INTERVAL": "0",
            "BACKOFFICE_REVIEW_TIMEOUT": "60",
            "LC_ALL": "C.UTF-8",
        })
        env.update(overrides)
        return env

    def gitlab_env(self, **overrides: str) -> Dict[str, str]:
        head = git(self.repo, "rev-parse", "HEAD").strip()
        env = self.base_env(
            GITLAB_CI="true",
            CI_PROJECT_PATH="group/shop",
            CI_PROJECT_URL="https://gitlab.example.com/group/shop",
            CI_PIPELINE_ID="1001",
            CI_JOB_ID="2002",
            CI_JOB_URL="https://gitlab.example.com/group/shop/-/jobs/2002",
            CI_COMMIT_REF_NAME="feature/cart",
            CI_COMMIT_SHA=head,
            CI_DEFAULT_BRANCH="main",
            GITLAB_USER_LOGIN="alice",
            CI_MERGE_REQUEST_IID="42",
            CI_MERGE_REQUEST_TITLE="Add cart",
            CI_MERGE_REQUEST_DESCRIPTION="Adds the \"cart\"\nendpoint",
            CI_MERGE_REQUEST_SOURCE_BRANCH_NAME="feature/cart",
            CI_MERGE_REQUEST_TARGET_BRANCH_NAME="main",
            CI_MERGE_REQUEST_PROJECT_URL="https://gitlab.example.com/group/shop",
        )
        env.update(overrides)
        return env

    def run_script(self, *args: str, env: Optional[Dict[str, str]] = None, cwd: Optional[Path] = None) -> subprocess.CompletedProcess:
        return subprocess.run(
            [SH, str(SCRIPT), *args],
            cwd=str(cwd or self.repo), env=env or self.base_env(), stdin=subprocess.DEVNULL, capture_output=True,
            text=True, encoding="utf-8", errors="replace", timeout=120,
        )

    def posts(self, path: str) -> List[Dict[str, Any]]:
        return [r for r in self.api.records if r["method"] == "POST" and r["path"] == path]

    def gets(self) -> List[Dict[str, Any]]:
        return [r for r in self.api.records if r["method"] == "GET"]

    def expected_diff(self) -> str:
        # bytes: text mode would translate the lone CR inside tricky.txt into a newline
        cp = subprocess.run(["git", "-c", "core.autocrlf=false", "diff", "--merge-base", "origin/main", "HEAD"], cwd=str(self.repo), capture_output=True, check=True)
        return cp.stdout.decode("utf-8")


# --------------------------------------------------------------------------------------------
# Tests
# --------------------------------------------------------------------------------------------


class RunCommandTests(ScriptTestCase):
    def test_run_wait_pass_sends_context_and_diff(self):
        cp = self.run_script("run", "code-review", "--wait", "--diff", "auto", env=self.gitlab_env())
        self.assertEqual(cp.returncode, 0, cp.stdout + cp.stderr)
        posts = self.posts("/api/v1/ci/executions")
        self.assertEqual(len(posts), 1, self.api.records)
        body = posts[0]["body"]
        self.assertNotIn("__invalid__", body, body)
        self.assertEqual(posts[0]["headers"]["authorization"], f"Bearer {TOKEN}")
        self.assertEqual(posts[0]["headers"]["content-type"], "application/json")
        self.assertEqual(body["entrypoint"], "code-review")
        ctx = body["context"]
        self.assertEqual(ctx["provider"], "GITLAB")
        self.assertEqual(ctx["projectPath"], "group/shop")
        self.assertEqual(ctx["pipelineId"], "1001")
        self.assertEqual(ctx["jobId"], "2002")
        self.assertEqual(ctx["jobUrl"], "https://gitlab.example.com/group/shop/-/jobs/2002")
        self.assertEqual(ctx["ref"], "feature/cart")
        self.assertEqual(ctx["commitSha"], git(self.repo, "rev-parse", "HEAD").strip())
        self.assertEqual(ctx["defaultBranch"], "main")
        self.assertEqual(ctx["actor"], "alice")
        mr = ctx["mergeRequest"]
        self.assertEqual(mr["iid"], "42")
        self.assertEqual(mr["title"], "Add cart")
        self.assertEqual(mr["description"], "Adds the \"cart\"\nendpoint")
        self.assertEqual(mr["sourceBranch"], "feature/cart")
        self.assertEqual(mr["targetBranch"], "main")
        self.assertEqual(mr["url"], "https://gitlab.example.com/group/shop/-/merge_requests/42")
        self.assertEqual(mr["author"], "alice")
        # diff: byte-exact after JSON decoding, including quotes, backslashes, tabs, CR and unicode
        self.assertEqual(body["diff"], self.expected_diff())
        self.assertIn(TRICKY_CONTENT.splitlines()[0], body["diff"])
        self.assertEqual(sorted(body["changedFiles"]), ["README.md", "src/app.kt", "tricky.txt"])
        self.assertEqual(body["variables"], {})
        # polling: QUEUED (POST) -> RUNNING -> SUCCEEDED = 2 GETs
        self.assertEqual(len(self.gets()), 2, self.api.records)
        # readable summary
        self.assertIn("Status:    SUCCEEDED", cp.stdout)
        self.assertIn("Gate:      PASS", cp.stdout)
        self.assertIn("Verdict:   APPROVE (score 91)", cp.stdout)
        self.assertIn(f"Run:       {self.api.url}/runs/{RUN_ID}", cp.stdout)
        self.assertIn("  All good.", cp.stdout)
        self.assertIn("[BLOCKER] src/cart/CartController.kt:88 - Price computed client-side", cp.stdout)
        self.assertIn("[MAJOR] src/cart/CartService.kt - Missing transaction boundary", cp.stdout)
        self.assertIn('[NIT] README.md:3 - Typo in "heading"', cp.stdout)
        self.assertIn("Gate PASS", cp.stdout)
        self.assertNotIn(TOKEN, cp.stdout + cp.stderr)

    def test_review_alias(self):
        cp = self.run_script("review", env=self.gitlab_env())
        self.assertEqual(cp.returncode, 0, cp.stdout + cp.stderr)
        body = self.posts("/api/v1/ci/executions")[0]["body"]
        self.assertEqual(body["entrypoint"], "code-review")
        self.assertTrue(body["diff"])
        self.assertEqual(len(self.gets()), 2)

    def test_run_wait_fail_exits_1(self):
        cp = self.run_script("run", "fail-review", "--wait", env=self.gitlab_env())
        self.assertEqual(cp.returncode, 1, cp.stdout + cp.stderr)
        self.assertIn("Gate:      FAIL (mode BLOCKING)", cp.stdout)
        self.assertIn("Verdict:   REQUEST_CHANGES (score 62)", cp.stdout)
        self.assertIn('  Summary line 1 "quoted" with a back\\slash', cp.stdout)
        self.assertIn("  second line of the summary", cp.stdout)
        self.assertIn("Gate FAIL", cp.stdout)

    def test_execution_failed_exits_1(self):
        cp = self.run_script("run", "broken", "--wait", env=self.gitlab_env())
        self.assertEqual(cp.returncode, 1, cp.stdout + cp.stderr)
        self.assertIn("Error:     provider quota exceeded", cp.stdout)

    def test_advisory_env_never_fails(self):
        cp = self.run_script("run", "fail-review", "--wait", env=self.gitlab_env(BACKOFFICE_ADVISORY="true"))
        self.assertEqual(cp.returncode, 0, cp.stdout + cp.stderr)
        self.assertIn("advisory mode", cp.stdout)

    def test_advisory_entrypoint_mode_never_fails(self):
        cp = self.run_script("run", "advisory-review", "--wait", env=self.gitlab_env())
        self.assertEqual(cp.returncode, 0, cp.stdout + cp.stderr)
        self.assertIn("entrypoint mode is ADVISORY", cp.stdout)

    def test_timeout_exits_3(self):
        cp = self.run_script("run", "slow", "--wait", "--diff", "none", env=self.gitlab_env(BACKOFFICE_REVIEW_TIMEOUT="1"))
        self.assertEqual(cp.returncode, 3, cp.stdout + cp.stderr)
        self.assertIn("timed out after 1s", cp.stderr)
        self.assertGreaterEqual(len(self.gets()), 1)

    def test_timeout_in_advisory_mode_exits_0(self):
        cp = self.run_script("run", "slow", "--wait", "--diff", "none", env=self.gitlab_env(BACKOFFICE_REVIEW_TIMEOUT="1", BACKOFFICE_ADVISORY="true"))
        self.assertEqual(cp.returncode, 0, cp.stdout + cp.stderr)

    def test_unauthorized_exits_2_without_leaking_token(self):
        bad = "bo_ci_" + "x" * 40
        cp = self.run_script("run", "code-review", "--wait", "--diff", "none", env=self.gitlab_env(BACKOFFICE_CI_TOKEN=bad))
        self.assertEqual(cp.returncode, 2, cp.stdout + cp.stderr)
        self.assertIn("HTTP 401", cp.stderr)
        self.assertIn("invalid CI token", cp.stderr)
        self.assertNotIn(bad, cp.stdout + cp.stderr)

    def test_unknown_entrypoint_exits_2(self):
        cp = self.run_script("run", "unknown", "--diff", "none", env=self.gitlab_env())
        self.assertEqual(cp.returncode, 2, cp.stdout + cp.stderr)
        self.assertIn("HTTP 404", cp.stderr)

    def test_unreachable_server_exits_2(self):
        cp = self.run_script("run", "code-review", "--diff", "none", env=self.gitlab_env(BACKOFFICE_URL="http://127.0.0.1:9", BACKOFFICE_HTTP_TIMEOUT="5"))
        self.assertEqual(cp.returncode, 2, cp.stdout + cp.stderr)
        self.assertIn("transport error", cp.stderr)

    def test_no_wait_only_posts(self):
        cp = self.run_script("run", "code-review", "--diff", "none", env=self.gitlab_env())
        self.assertEqual(cp.returncode, 0, cp.stdout + cp.stderr)
        self.assertEqual(len(self.posts("/api/v1/ci/executions")), 1)
        self.assertEqual(self.gets(), [])
        body = self.posts("/api/v1/ci/executions")[0]["body"]
        self.assertIsNone(body["diff"])
        self.assertEqual(body["changedFiles"], [])
        self.assertIn("status=QUEUED", cp.stdout)
        self.assertIn("backoffice-ci status", cp.stdout)

    def test_variables_are_sent(self):
        cp = self.run_script("run", "code-review", "--diff", "none", "--var", "force=true", "--var", "name=a=b \"q\"", "--var=flag", env=self.gitlab_env())
        self.assertEqual(cp.returncode, 0, cp.stdout + cp.stderr)
        body = self.posts("/api/v1/ci/executions")[0]["body"]
        self.assertEqual(body["variables"], {"force": "true", "name": "a=b \"q\"", "flag": ""})

    def test_diff_is_truncated_at_cap_with_marker(self):
        cp = self.run_script("run", "code-review", "--diff", "auto", env=self.gitlab_env(BACKOFFICE_DIFF_MAX_BYTES="300"))
        self.assertEqual(cp.returncode, 0, cp.stdout + cp.stderr)
        body = self.posts("/api/v1/ci/executions")[0]["body"]
        diff = body["diff"]
        self.assertIn("[... diff truncated by backoffice-ci: 300 of", diff)
        head, _marker = diff.rsplit("\n[... diff truncated", 1)
        self.assertLessEqual(len(head.encode("utf-8")), 300)
        self.assertTrue(head.endswith("}") or head.endswith("\n"), repr(head[-30:]))
        # cut on a line boundary: the kept part is a prefix of the full diff made of whole lines
        self.assertTrue(self.expected_diff().startswith(head.rstrip("\n") + "\n"), head)
        self.assertEqual(sorted(body["changedFiles"]), ["README.md", "src/app.kt", "tricky.txt"])
        self.assertIn("(truncated to 300)", cp.stdout)

    def test_github_context_from_env_and_event(self):
        event = Path(self.tmp.name) / "event.json"
        event.write_text(json.dumps({
            "action": "synchronize",
            "repository": {"default_branch": "main", "full_name": "acme/shop"},
            "pull_request": {
                "number": 7,
                "title": "Add cart (GH)",
                "body": "Body with \"quotes\"\nand two lines",
                "html_url": "https://github.com/acme/shop/pull/7",
                "user": {"login": "bob"},
                "head": {"ref": "feature/cart", "sha": "abc"},
                "base": {"ref": "main", "sha": git(self.repo, "rev-parse", "origin/main").strip()},
            },
        }), encoding="utf-8")
        head = git(self.repo, "rev-parse", "HEAD").strip()
        env = self.base_env(
            GITHUB_ACTIONS="true", GITHUB_REPOSITORY="acme/shop", GITHUB_RUN_ID="555", GITHUB_JOB="review",
            GITHUB_SHA=head, GITHUB_REF="refs/pull/7/merge", GITHUB_REF_NAME="7/merge", GITHUB_HEAD_REF="feature/cart",
            GITHUB_BASE_REF="main", GITHUB_ACTOR="bob", GITHUB_SERVER_URL="https://github.com", GITHUB_EVENT_PATH=str(event),
        )
        cp = self.run_script("run", "code-review", "--diff", "auto", env=env)
        self.assertEqual(cp.returncode, 0, cp.stdout + cp.stderr)
        body = self.posts("/api/v1/ci/executions")[0]["body"]
        ctx = body["context"]
        self.assertEqual(ctx["provider"], "GITHUB")
        self.assertEqual(ctx["projectPath"], "acme/shop")
        self.assertEqual(ctx["pipelineId"], "555")
        self.assertEqual(ctx["jobId"], "review")
        self.assertEqual(ctx["jobUrl"], "https://github.com/acme/shop/actions/runs/555")
        self.assertEqual(ctx["ref"], "feature/cart")
        self.assertEqual(ctx["commitSha"], head)
        self.assertEqual(ctx["defaultBranch"], "main")
        self.assertEqual(ctx["actor"], "bob")
        mr = ctx["mergeRequest"]
        self.assertEqual(mr["iid"], "7")
        self.assertEqual(mr["title"], "Add cart (GH)")
        self.assertEqual(mr["description"], "Body with \"quotes\"\nand two lines")
        self.assertEqual(mr["sourceBranch"], "feature/cart")
        self.assertEqual(mr["targetBranch"], "main")
        self.assertEqual(mr["url"], "https://github.com/acme/shop/pull/7")
        self.assertEqual(mr["author"], "bob")
        self.assertEqual(body["diff"], self.expected_diff())

    def test_other_context_uses_git(self):
        head = git(self.repo, "rev-parse", "HEAD").strip()
        cp = self.run_script("run", "code-review", "--diff", "auto", env=self.base_env())
        self.assertEqual(cp.returncode, 0, cp.stdout + cp.stderr)
        ctx = self.posts("/api/v1/ci/executions")[0]["body"]["context"]
        self.assertEqual(ctx["provider"], "OTHER")
        self.assertIsNone(ctx["projectPath"])
        self.assertIsNone(ctx["mergeRequest"])
        self.assertEqual(ctx["commitSha"], head)
        self.assertEqual(ctx["ref"], "feature/cart")
        self.assertEqual(ctx["defaultBranch"], "main")
        # branch diff against the default branch (merge-base), not against the moved-on main
        self.assertEqual(self.posts("/api/v1/ci/executions")[0]["body"]["diff"], self.expected_diff())

    def test_run_outside_git_repo_sends_no_diff(self):
        with tempfile.TemporaryDirectory() as tmp:
            cp = self.run_script("run", "code-review", "--diff", "auto", env=self.gitlab_env(), cwd=Path(tmp))
        self.assertEqual(cp.returncode, 0, cp.stdout + cp.stderr)
        body = self.posts("/api/v1/ci/executions")[0]["body"]
        self.assertIsNone(body["diff"])
        self.assertIn("not inside a git repository", cp.stderr)


class SignalCommandTests(ScriptTestCase):
    def test_signal_uses_trailer_of_head(self):
        cp = self.run_script("signal", "tests", "--status", "success", env=self.gitlab_env())
        self.assertEqual(cp.returncode, 0, cp.stdout + cp.stderr)
        posts = self.posts("/api/v1/ci/signals")
        self.assertEqual(len(posts), 1, self.api.records)
        body = posts[0]["body"]
        self.assertEqual(body["signal"], "tests")
        self.assertEqual(body["status"], "SUCCESS")
        self.assertEqual(body["runId"], TRAILER_RUN_ID)
        self.assertEqual(body["branch"], "feature/cart")
        self.assertEqual(body["commitSha"], git(self.repo, "rev-parse", "HEAD").strip())
        self.assertIsNone(body["message"])
        self.assertEqual(body["context"]["provider"], "GITLAB")
        self.assertIn(f"trailer of HEAD: {TRAILER_RUN_ID}", cp.stdout)
        self.assertIn(f"run {TRAILER_RUN_ID} resumed with SUCCESS", cp.stdout)

    def test_signal_with_explicit_run_and_message(self):
        cp = self.run_script("signal", "tests", "--status", "fail", "--run", RUN_ID, "--message", "3 tests failed: \"CartTest\"", env=self.gitlab_env())
        self.assertEqual(cp.returncode, 0, cp.stdout + cp.stderr)
        body = self.posts("/api/v1/ci/signals")[0]["body"]
        self.assertEqual(body["status"], "FAIL")
        self.assertEqual(body["runId"], RUN_ID)
        self.assertEqual(body["message"], "3 tests failed: \"CartTest\"")
        self.assertIn(f"run {RUN_ID} resumed with FAIL", cp.stdout)

    def test_signal_without_trailer_falls_back_to_branch(self):
        with tempfile.TemporaryDirectory() as tmp:
            cp = self.run_script("signal", "nothing", "--status", "success", env=self.gitlab_env(), cwd=Path(tmp))
        self.assertEqual(cp.returncode, 0, cp.stdout + cp.stderr)
        body = self.posts("/api/v1/ci/signals")[0]["body"]
        self.assertIsNone(body["runId"])
        self.assertEqual(body["branch"], "feature/cart")
        self.assertIn("matched by branch", cp.stdout)
        self.assertIn("no run is waiting for signal nothing", cp.stdout)

    def test_signal_requires_status(self):
        cp = self.run_script("signal", "tests", env=self.gitlab_env())
        self.assertEqual(cp.returncode, 2)
        self.assertIn("--status must be success or fail", cp.stderr)
        self.assertEqual(self.api.records, [])

    def test_signal_unauthorized_exits_2(self):
        cp = self.run_script("signal", "tests", "--status", "success", env=self.gitlab_env(BACKOFFICE_CI_TOKEN="bo_ci_bad"))
        self.assertEqual(cp.returncode, 2, cp.stdout + cp.stderr)
        self.assertIn("HTTP 401", cp.stderr)


class StatusAndUsageTests(ScriptTestCase):
    def test_status_prints_execution(self):
        self.run_script("run", "code-review", "--diff", "none", env=self.gitlab_env())
        exec_id = next(iter(self.api.executions))
        cp = self.run_script("status", exec_id, env=self.gitlab_env())
        self.assertEqual(cp.returncode, 0, cp.stdout + cp.stderr)
        self.assertIn(f"Execution: {exec_id}", cp.stdout)
        self.assertIn("Status:    RUNNING", cp.stdout)

    def test_status_unknown_id_exits_2(self):
        cp = self.run_script("status", str(uuid.uuid4()), env=self.gitlab_env())
        self.assertEqual(cp.returncode, 2, cp.stdout + cp.stderr)
        self.assertIn("HTTP 404", cp.stderr)

    def test_version_and_help_need_no_config(self):
        env = self.base_env()
        env.pop("BACKOFFICE_URL")
        env.pop("BACKOFFICE_CI_TOKEN")
        cp = self.run_script("version", env=env)
        self.assertEqual(cp.returncode, 0)
        self.assertRegex(cp.stdout, r"^backoffice-ci \d+\.\d+\.\d+")
        cp = self.run_script("help", env=env)
        self.assertEqual(cp.returncode, 0)
        self.assertIn("Usage:", cp.stdout)

    def test_missing_config_and_unknown_command(self):
        env = self.base_env()
        env.pop("BACKOFFICE_CI_TOKEN")
        cp = self.run_script("run", "code-review", env=env)
        self.assertEqual(cp.returncode, 2)
        self.assertIn("BACKOFFICE_CI_TOKEN is required", cp.stderr)
        self.assertIn("not Protected", cp.stderr)
        # an empty value (GitLab passes protected variables empty to unprotected branches) is the same error
        env["BACKOFFICE_CI_TOKEN"] = ""
        cp = self.run_script("review", env=env)
        self.assertEqual(cp.returncode, 2)
        self.assertIn("Masked, not Protected", cp.stderr)
        cp = self.run_script("frobnicate", env=self.base_env())
        self.assertEqual(cp.returncode, 2)
        self.assertIn("unknown command", cp.stderr)
        cp = self.run_script(env=self.base_env())
        self.assertEqual(cp.returncode, 2)

    def test_script_is_posix_sh_without_bashisms(self):
        text = SCRIPT.read_text(encoding="utf-8")
        self.assertTrue(text.startswith("#!/bin/sh\n"))
        self.assertIn("set -eu", text)
        shell_only = text.split("cat > \"$AWK_PROG\" <<\"EOF\"", 1)[0] + text.split("\nEOF\n", 1)[1]
        for bashism in ("[[ ", "function ", "local ", "${BASH", "$(( ${", " == ", " source ", "declare ", "pipefail", "&>", "$'"):
            self.assertNotIn(bashism, shell_only, f"bashism {bashism!r} found")


if __name__ == "__main__":
    unittest.main()
