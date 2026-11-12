"""Multi-repository jobs (`spec.repositories`): parsing, per-repository tokens and credentials, agent jobs that never
push (ADR 0063), and DELIVER jobs: commit, push and merge request of the run directory, per-repository results and
failure isolation. Run with: python3 -m unittest discover -s runner/tests"""

from __future__ import annotations

import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import entrypoint as ep  # noqa: E402
from test_entrypoint import b64, git, make_git_env  # noqa: E402  (functions only, no test classes)

HAS_GIT = shutil.which("git") is not None
HAS_SH = shutil.which("sh") is not None
RUN_ID = "11111111-2222-4333-8444-555555555555"
DOCS_TOKEN = "glpat-docs-read-0123456789"
SRC_TOKEN = "glpat-src-push-0123456789"
TOOLS_TOKEN = "glpat-tools-none-0123456789"  # trufflehog:ignore (test fixture)
RUN_WORKSPACE = {"name": "bo-ws-11111111222243338444555555555555-run", "key": "run"}


def base_spec(**kw) -> dict:
    spec = {
        "mode": "IMPLEMENT", "agent": "OPENCODE", "model": {"provider": "ANTHROPIC", "modelId": "m"},
        "prompt": "Implement SHOP-1", "timeoutSeconds": 600,
    }
    spec.update(kw)
    return spec


def two_repos(docs_url: str = "https://gitlab.example.com/shop/docs.git", src_url: str = "https://gitlab.example.com/shop/api.git") -> list:
    return [
        {"key": "docs", "url": docs_url, "baseBranch": "main", "access": "READ", "path": "docs", "primary": False,
         "purpose": "Product documentation", "mergeRequest": {"create": True, "title": "never"}},
        {"key": "src", "url": src_url, "baseBranch": "main", "access": "PUSH_MR", "path": "src", "pushBranch": "feature/shop-1",
         "mergeRequest": {"create": True, "title": "SHOP-1", "description": "d", "draft": True}, "primary": True},
    ]


class MultiRepoSpecParsingTests(unittest.TestCase):
    def test_parses_repositories_with_per_repository_access(self):
        spec = ep.parse_spec(base_spec(mode="DELIVER", repositories=two_repos()))
        self.assertTrue(spec.multi_repo)
        self.assertIsNone(spec.repository)
        self.assertEqual(spec.git_access, "PUSH_MR")  # highest level, for logs and result.security only
        docs, src = spec.repositories
        self.assertEqual((docs.key, docs.access, docs.path, docs.push_branch, docs.merge_request), ("docs", "READ", "docs", None, None))
        self.assertEqual(docs.purpose, "Product documentation")
        self.assertEqual((src.access, src.push_branch, src.primary), ("PUSH_MR", "feature/shop-1", True))
        self.assertEqual(src.merge_request, ep.MergeRequest(title="SHOP-1", target_branch="main", description="d", draft=True))
        self.assertEqual(spec.primary_repository, src)
        self.assertEqual(spec.warnings, [])

    def test_an_agent_job_uses_push_entries_as_read(self):
        """ADR 0063, defence in depth: whatever the spec says, an agent job never pushes."""
        for mode in ("IMPLEMENT", "EVAL_CODING", "PROMPT"):
            spec = ep.parse_spec(base_spec(mode=mode, repositories=two_repos()))
            docs, src = spec.repositories
            self.assertEqual((src.access, src.push_branch, src.merge_request, src.primary), ("READ", None, None, True), mode)
            self.assertEqual(docs.access, "READ")
            self.assertEqual(spec.git_access, "READ")
            self.assertEqual(spec.warnings, ["repository src: an agent job never commits or pushes (ADR 0063): access PUSH_MR"
                                             " and pushBranch feature/shop-1 ignored, access READ"])

    def test_deliver_spec(self):
        """ADR 0063: a DELIVER job runs no agent: agent, model and prompt are optional; it needs `repositories`."""
        minimal = ep.parse_spec({"mode": "deliver", "repositories": two_repos()})
        self.assertTrue(minimal.delivers)
        self.assertEqual((minimal.agent, minimal.model.provider, minimal.prompt, minimal.timeout_seconds), ("NONE", "NONE", "", 600))
        self.assertEqual(minimal.repositories[1].push_branch, "feature/shop-1")
        from_back_office = ep.parse_spec(base_spec(mode="DELIVER", agent="SOMETHING", repositories=two_repos(), commitSubject="SHOP-1: cart",
                                                   workspace=RUN_WORKSPACE))
        self.assertEqual((from_back_office.agent, from_back_office.commit_subject), ("SOMETHING", "SHOP-1: cart"))
        self.assertEqual(from_back_office.workspace.key, "run")
        with self.assertRaisesRegex(ValueError, "DELIVER job needs spec.repositories"):
            ep.parse_spec(base_spec(mode="DELIVER", repository={"url": "https://h/a.git", "branch": "f", "push": True}))
        with self.assertRaisesRegex(ValueError, "RunnerJobSpec.agent is required"):
            ep.parse_spec({"mode": "IMPLEMENT", "repositories": []})

    def test_defaults_primary_path_and_access(self):
        repos = [{"key": "a", "url": "https://h/a.git"}, {"key": "b", "url": "https://h/b.git", "access": "push_mr", "pushBranch": "x"}]
        spec = ep.parse_spec(base_spec(repositories=repos))
        a, b = spec.repositories
        self.assertEqual((a.access, a.path, a.base_ref), ("READ", "a", "main"))  # absent access = READ
        self.assertTrue(b.primary)  # no explicit primary: first PUSH_MR repository
        self.assertFalse(a.primary)
        only_read = ep.parse_spec(base_spec(repositories=[{"key": "a", "url": "u"}, {"key": "b", "url": "v", "access": "NONE"}]))
        self.assertTrue(only_read.repositories[0].primary)
        self.assertEqual(only_read.git_access, "READ")

    def test_invalid_repository_lists_are_refused(self):
        ok = {"key": "a", "url": "https://h/a.git"}
        cases = {
            "key": [dict(ok, key="../x")],
            "duplicate": [ok, dict(ok, path="other")],
            "inside": [ok, dict(ok, key="b", path="a/b")],
            "two primaries": [dict(ok, primary=True), dict(ok, key="b", primary=True)],
            "pushBranch": [dict(ok, access="PUSH_MR")],
            "unknown": [dict(ok, access="ADMIN")],
            "url": [{"key": "a"}],
        }
        for name, repos in cases.items():
            with self.assertRaises(ValueError, msg=name):
                ep.parse_spec(base_spec(repositories=repos))
        for bad_path in ("../x", "/abs", "a/../b", ".home", "x/.git", "C:/x", "opencode.json", "a b"):
            with self.assertRaises(ValueError, msg=bad_path):
                ep.normalise_repo_path(bad_path, "k")
        self.assertEqual(ep.normalise_repo_path("libs\\core/", "k"), "libs/core")
        self.assertEqual(ep.normalise_repo_path(None, "k"), "k")

    def test_legacy_spec_when_repositories_absent_or_empty_next_to_repository(self):
        legacy_repo = {"url": "https://h/a.git", "baseRef": "main", "branch": "f", "push": True}
        for raw in (base_spec(repository=legacy_repo), base_spec(repository=legacy_repo, repositories=[])):
            spec = ep.parse_spec(raw)
            self.assertFalse(spec.multi_repo)
            self.assertEqual(spec.repository.url, "https://h/a.git")
            self.assertEqual(spec.git_access, "READ")  # ADR 0063: an agent job never pushes
        empty = ep.parse_spec(base_spec(repositories=[]))
        self.assertTrue(empty.multi_repo)
        self.assertEqual((empty.repositories, empty.git_access), ([], "NONE"))

    def test_repositories_prompt_section(self):
        spec = ep.parse_spec(base_spec(repositories=two_repos()))
        section = ep.repositories_prompt_section(spec, Path("/workspace"))
        self.assertTrue(section.startswith("## Repositories"))
        self.assertIn("`docs` at /workspace/docs (READ; changes stay in the work tree; the runner does not commit or push)"
                      " - Product documentation", section)
        self.assertIn("`src` at /workspace/src (READ, primary; changes stay in the work tree", section)
        prompt = ep.with_repositories_section("Do it", spec, Path("/workspace"))
        self.assertTrue(prompt.startswith("Do it\n\n## Repositories"))
        legacy = ep.parse_spec(base_spec())
        self.assertEqual(ep.with_repositories_section("Do it", legacy, Path("/workspace")), "Do it")
        self.assertIn("No repository", ep.repositories_prompt_section(ep.parse_spec(base_spec(repositories=[])), Path("/workspace")))

    def test_agent_can_reach_the_other_repositories(self):
        policy = ep.ToolPolicy(allow=["git *"])
        perm = ep.opencode_permission(policy, ["/workspace/docs"])
        self.assertEqual(perm["external_directory"], {"*": "deny", "/workspace/docs": "allow", "/workspace/docs/**": "allow"})
        self.assertEqual(ep.opencode_permission(policy)["external_directory"], "deny")
        model = ep.ModelRef("ANTHROPIC", "m")
        cmd = ep.build_claude_command(model, "p", "dontAsk", "--add-dir <directories...>", None, ["/workspace/docs"])
        self.assertEqual(cmd[cmd.index("--add-dir"):cmd.index("--add-dir") + 2], ["--add-dir", "/workspace/docs"])
        self.assertNotIn("--add-dir", ep.build_claude_command(model, "p", "dontAsk", "", None, ["/workspace/docs"]))


class RepoTokenTests(unittest.TestCase):
    def test_per_repository_tokens_are_consumed_into_memory(self):
        with tempfile.TemporaryDirectory() as tmp:
            token_dir = Path(tmp) / "git"
            for key, token in (("docs", DOCS_TOKEN), ("src", SRC_TOKEN)):
                (token_dir / key).mkdir(parents=True)
                (token_dir / key / "token").write_text(token + "\n")
            (token_dir / "other").mkdir()
            (token_dir / "other" / "token").write_text("not-this-job")
            env = {"RUNNER_GIT_TOKEN_DIR": str(token_dir)}
            tokens, removed = ep.load_repo_tokens(env, ["docs", "src", "missing", "../other"])
            self.assertEqual(tokens, {"docs": DOCS_TOKEN, "src": SRC_TOKEN})
            self.assertTrue(removed)
            self.assertFalse((token_dir / "docs" / "token").exists())
            self.assertFalse((token_dir / "src" / "token").exists())
            self.assertTrue((token_dir / "other" / "token").exists())  # never a key outside REPO_KEY_RE
            self.assertEqual(ep.load_repo_tokens(env, ["docs"]), ({}, None))

    def test_token_dir_resolution(self):
        self.assertEqual(ep.git_token_dir({"RUNNER_GIT_TOKEN_DIR": "/x"}), Path("/x"))
        self.assertEqual(ep.git_token_dir({}), Path(ep.DEFAULT_GIT_TOKEN_DIR))
        self.assertEqual(ep.git_token_dir({"RUNNER_GIT_TOKEN_FILE": ep.DEFAULT_GIT_TOKEN_FILE}), Path(ep.DEFAULT_GIT_TOKEN_DIR))
        self.assertIsNone(ep.git_token_dir({"RUNNER_GIT_TOKEN_FILE": "/home/me/token"}))  # never guess a directory
        self.assertEqual(ep._combine_removed(None, True), True)
        self.assertEqual(ep._combine_removed(True, False), False)
        self.assertIsNone(ep._combine_removed(None, None))
        self.assertIn("RUNNER_GIT_TOKEN_DIR", ep.RUNNER_ONLY_ENV_NAMES)
        self.assertIn("BO_GIT_PATH", ep.RUNNER_ONLY_ENV_NAMES)

    def test_credentials_are_bound_to_host_and_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            g = ep.Git(Path(tmp) / "src", make_git_env(), lambda m: None, token=SRC_TOKEN,
                       repo_url="https://gitlab.example.com/shop/api.git", access="PUSH_MR", bind_path=True)
            self.assertIn("credential.useHttpPath=true", g._cred_args())
            env = g._cred_env(True)
            self.assertEqual((env["BO_GIT_HOST"], env["BO_GIT_PATH"], env["BO_GIT_PASSWORD"]), ("gitlab.example.com", "shop/api", SRC_TOKEN))
            legacy = ep.Git(Path(tmp) / "repo", make_git_env(), lambda m: None, token=SRC_TOKEN, repo_url="https://gitlab.example.com/shop/api.git")
            self.assertNotIn("credential.useHttpPath=true", legacy._cred_args())
            self.assertNotIn("BO_GIT_PATH", legacy._cred_env(True))
        self.assertEqual(ep.credential_path("https://h/group/sub/proj.git/"), "group/sub/proj")
        self.assertEqual(ep.credential_path("git@h:group/proj.git"), "")

    @unittest.skipUnless(HAS_SH, "sh not installed")
    def test_credential_helper_checks_the_path(self):
        body = ep.GIT_CREDENTIAL_HELPER[1:]
        env = dict(os.environ, BO_GIT_PASSWORD=SRC_TOKEN, BO_GIT_HOST="h.example", BO_GIT_PATH="shop/api")

        def helper(host: str, path: str = None) -> str:
            data = f"protocol=https\nhost={host}\n" + (f"path={path}\n" if path is not None else "")
            return subprocess.run(["sh", "-c", f"{body} get"], input=data.encode(), env=env, capture_output=True,
                                  timeout=30).stdout.decode().replace("\r\n", "\n")

        expected = f"username=oauth2\npassword={SRC_TOKEN}\n"
        self.assertEqual(helper("h.example", "shop/api.git"), expected)
        self.assertEqual(helper("h.example", "shop/api"), expected)
        self.assertEqual(helper("h.example", "shop/docs.git"), "")   # sibling repository on the same host
        self.assertEqual(helper("h.example"), "")                     # no path sent
        self.assertEqual(helper("evil.example", "shop/api.git"), "")

    @unittest.skipUnless(HAS_GIT, "git not installed")
    def test_credential_helper_via_git_answers_only_its_repository(self):
        with tempfile.TemporaryDirectory() as tmp:
            g = ep.Git(Path(tmp) / "src", make_git_env(), lambda m: None, token=SRC_TOKEN,
                       repo_url="https://good.example/shop/api.git", access="READ", bind_path=True)
            env = g._cred_env(True)
            env["GIT_CONFIG_NOSYSTEM"] = "1"
            for name in ("GIT_ASKPASS", "SSH_ASKPASS"):
                env.pop(name, None)
            cmd = g._cmd(["credential", "fill"], with_creds=True)

            def fill(url: str) -> subprocess.CompletedProcess:
                return subprocess.run(cmd, input=f"url={url}\n\n", env=env, cwd=tmp, capture_output=True, text=True, timeout=60)

            self.assertIn(f"password={SRC_TOKEN}", fill("https://good.example/shop/api.git").stdout)
            other = fill("https://good.example/shop/docs.git")
            self.assertNotIn(SRC_TOKEN, other.stdout + other.stderr)


def make_origin(root: Path, name: str) -> Path:
    """Bare repository `<name>.git` with one commit on main."""
    origin = root / f"{name}.git"
    origin.mkdir()
    git(origin, "init", "-q", "--bare", "-b", "main")
    git(origin, "config", "receive.advertisePushOptions", "true")
    work = root / f"{name}-seed"
    git(root, "clone", "-q", str(origin), str(work))
    git(work, "checkout", "-q", "-b", "main")
    (work / "README.md").write_text(f"# {name}\n")
    git(work, "add", "-A")
    git(work, "commit", "-q", "-m", "init")
    git(work, "push", "-q", "-u", "origin", "main")
    return origin


class _MainFlow:
    """In-process main() with the STUB agent against local bare repositories (helpers, not a test case)."""

    def stub(self, code: str) -> str:
        return f'"{sys.executable}" -c "{code}"'

    def run_main(self, tmp: Path, spec: dict, tokens: dict = None) -> tuple:
        token_dir = tmp / "git-tokens"
        for key, token in (tokens or {}).items():
            (token_dir / key).mkdir(parents=True, exist_ok=True)
            (token_dir / key / "token").write_text(token)
        env = make_git_env()
        for name in ("RUNNER_CALLBACK_URL", "RUNNER_CALLBACK_TOKEN", "RUNNER_JOB_ID", "RUNNER_SANDBOX", "RUNNER_POLICY",
                     "RUNNER_PROXY_URL", "RUNNER_MAX_PROCS", "RUNNER_WAIT_FOR_JOB", "RUNNER_SPEC_FILE"):
            env.pop(name, None)
        env.update({
            "RUNNER_ENABLE_STUB_AGENT": "1", "RUNNER_WORKSPACE": str(tmp / "ws"), "RUNNER_RESULT_FILE": str(tmp / "result.json"),
            "RUNNER_JOB_DIR": str(tmp / "no-bundle"), "RUNNER_GIT_TOKEN_FILE": str(tmp / "no-git-token"),
            "RUNNER_GIT_TOKEN_DIR": str(token_dir), "HOME": str(tmp / "home"), "TMPDIR": str(tmp), "RUNNER_SPEC": b64(spec),
        })
        created: list = []
        self.pushes: list = []
        pushes = self.pushes
        real_git = ep.Git

        class RecordingGit(real_git):  # type: ignore[misc, valid-type]
            def __init__(self, *args, **kwargs):
                super().__init__(*args, **kwargs)
                # plain data only: a Git keeps the run's log (and its open log file) alive
                created.append({"dir": Path(self.repo_dir).name, "token": self.token, "access": self.access, "bind_path": self.bind_path})

            def run(self, args, *a, **kw):
                if "push" in args:
                    pushes.append({"dir": Path(self.repo_dir).name, "args": list(args)})
                return super().run(args, *a, **kw)

        out = io.StringIO()
        with mock.patch.dict(os.environ, env, clear=True), mock.patch.object(ep.sys, "stdout", out), \
                mock.patch.object(ep, "Git", RecordingGit):
            code = ep.main()
        self.assertEqual(code, 0, out.getvalue())
        return json.loads((tmp / "result.json").read_text(encoding="utf-8")), created, out.getvalue()

    def spec(self, repos: list, prompt: str, **kw) -> dict:
        return dict({"mode": "IMPLEMENT", "agent": "STUB", "model": {"provider": "ANTHROPIC", "modelId": "m"}, "prompt": prompt,
                     "timeoutSeconds": 600, "runId": RUN_ID, "repositories": repos}, **kw)

    def deliver(self, repos: list, **kw) -> dict:
        """A DELIVER job as Back Office sends it (agent and model present but unused) in the run directory."""
        return dict({"mode": "DELIVER", "agent": "CLAUDE_CODE", "model": {"provider": "ANTHROPIC", "modelId": "m"},
                     "prompt": "Deliver the changes of the run", "timeoutSeconds": 600, "runId": RUN_ID, "repositories": repos,
                     "workspace": RUN_WORKSPACE}, **kw)


def entry(key: str, origin: Path, access: str = "READ", **kw) -> dict:
    e = {"key": key, "url": str(origin), "baseBranch": "main", "access": access, "path": key}
    e.update(kw)
    return e


def origin_branches(origin: Path) -> list:
    return git(origin, "for-each-ref", "--format=%(refname)", "refs/heads").split()


@unittest.skipUnless(HAS_GIT, "git not installed")
class MultiRepoMainFlowTests(_MainFlow, unittest.TestCase):
    def test_agent_job_never_pushes_even_with_push_entries(self):
        """ADR 0063: an IMPLEMENT job never commits or pushes; a PUSH_MR entry is used as READ with a warning."""
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
            tmp_path = Path(tmp)
            docs_origin, src_origin, tools_origin = (make_origin(tmp_path, n) for n in ("docs", "src", "tools"))
            repos = two_repos(str(docs_origin), str(src_origin)) + [
                {"key": "tools", "url": str(tools_origin), "baseBranch": "main", "access": "NONE", "path": "vendor/tools"}]
            # the agent runs in the primary repository (src), edits the docs sibling and tries to push both itself
            prompt = self.stub(
                "import pathlib, subprocess, os; print('cwd', pathlib.Path.cwd().name);"
                " pathlib.Path('cart.kt').write_text('fun cart() = 1');"
                " pathlib.Path('../docs/cart.md').write_text('# Cart');"
                " r = subprocess.run(['git', '-C', '../docs', 'push', 'origin', 'HEAD:refs/heads/agent-push'], capture_output=True);"
                " s = subprocess.run(['git', 'push', 'origin', 'HEAD:refs/heads/agent-push'], capture_output=True);"
                " print('agent pushes exit', r.returncode != 0, s.returncode != 0)"
            )
            tokens = {"docs": DOCS_TOKEN, "src": SRC_TOKEN, "tools": TOOLS_TOKEN}
            result, created, stdout = self.run_main(tmp_path, self.spec(repos, prompt, collectArtifacts=["cart.kt"]), tokens)

            self.assertEqual(result["status"], "SUCCEEDED", result)
            self.assertIn("cwd src", result["summary"])
            self.assertIn("agent pushes exit True True", result["summary"])
            self.assertIn("[spec] WARN repository src: an agent job never commits or pushes (ADR 0063): access PUSH_MR and"
                          " pushBranch feature/shop-1 ignored, access READ", stdout)
            by_key = {r["key"]: r for r in result["repositories"]}
            self.assertEqual(set(by_key), {"docs", "src", "tools"})
            for entry_ in result["repositories"]:
                self.assertEqual(set(entry_), {"key", "branch", "commitSha", "diffStat", "mergeRequestUrl", "pushed", "error"})
                self.assertFalse(entry_["pushed"])
                self.assertIsNone(entry_["branch"])
                self.assertIsNone(entry_["mergeRequestUrl"])
                self.assertIsNone(entry_["error"])
            self.assertEqual(self.pushes, [])
            for origin in (docs_origin, src_origin, tools_origin):
                self.assertEqual(origin_branches(origin), ["refs/heads/main"])
            # nothing committed: HEAD is the base, the work is in the work tree and in the diff
            ws = tmp_path / "ws"
            src, docs = by_key["src"], by_key["docs"]
            self.assertEqual(src["commitSha"], git(src_origin, "rev-parse", "main").strip())
            self.assertEqual(git(ws / "src", "status", "--porcelain").strip(), "?? cart.kt")
            self.assertEqual((src["diffStat"]["files"], docs["diffStat"]["files"]), (1, 1))
            self.assertIn("+fun cart() = 1", result["diff"])
            self.assertNotIn("# Cart", result["diff"])
            artifacts = {a["name"]: a for a in result["artifacts"]}
            self.assertIn("+# Cart", artifacts["diff:docs"]["content"])
            self.assertEqual(artifacts["cart.kt"]["content"], "fun cart() = 1")
            # every clone of an agent job has its push URL disabled, the downgraded one too
            for path in ("docs", "src", "vendor/tools"):
                self.assertEqual(git(ws / path, "config", "remote.origin.pushurl").strip(), ep.DISABLED_PUSH_URL, path)
            tokens_by_dir = {g["dir"]: (g["token"], g["access"]) for g in created}
            self.assertEqual(tokens_by_dir, {"docs": (DOCS_TOKEN, "READ"), "src": (SRC_TOKEN, "READ"), "tools": (None, "NONE")})
            self.assertTrue(all(g["bind_path"] for g in created))
            for key in tokens:
                self.assertFalse((tmp_path / "git-tokens" / key / "token").exists(), key)
            self.assertEqual(result["security"]["gitAccess"], "READ")
            self.assertEqual(result["security"]["gitAccessByRepository"], {"docs": "READ", "src": "READ", "tools": "NONE"})
            for token in tokens.values():
                self.assertNotIn(token, json.dumps(result))
                self.assertNotIn(token, stdout)

    def test_clone_failure_names_the_repository(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
            tmp_path = Path(tmp)
            origin = make_origin(tmp_path, "src")
            repos = [{"key": "src", "url": str(origin), "access": "READ", "path": "src"},
                     {"key": "gone", "url": str(tmp_path / "missing.git"), "access": "READ", "path": "gone"}]
            result, _, _ = self.run_main(tmp_path, self.spec(repos, self.stub("print(1)")))
            self.assertEqual(result["status"], "FAILED")
            self.assertIn("repository gone: clone failed", result["error"])
            self.assertIn("clone failed", {r["key"]: r for r in result["repositories"]}["gone"]["error"])

    def test_without_repositories_the_agent_works_in_the_workspace(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
            tmp_path = Path(tmp)
            prompt = self.stub("import pathlib; print('cwd', pathlib.Path.cwd().name)")
            result, created, _ = self.run_main(tmp_path, self.spec([], prompt))
            self.assertEqual(result["status"], "SUCCEEDED", result)
            self.assertEqual(result["summary"], "cwd ws")
            self.assertEqual(result["repositories"], [])
            self.assertEqual(created, [])
            self.assertIsNone(result["branch"])

    def test_legacy_single_repository_spec_keeps_its_shape_and_never_pushes(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
            tmp_path = Path(tmp)
            origin = make_origin(tmp_path, "src")
            spec = self.spec([], self.stub("import pathlib; pathlib.Path('out.txt').write_text('x'); print(pathlib.Path.cwd().name)"),
                             repository={"url": str(origin), "baseRef": "main", "branch": "feature/legacy", "push": True})
            result, created, stdout = self.run_main(tmp_path, spec)
            self.assertEqual(result["status"], "SUCCEEDED", result)
            self.assertNotIn("repositories", result)
            self.assertEqual(result["summary"], "repo")  # /workspace/repo as before
            self.assertEqual(result["branch"], "feature/legacy")
            self.assertEqual(result["commitSha"], git(origin, "rev-parse", "main").strip())
            self.assertIn("+x", result["diff"])
            self.assertEqual(origin_branches(origin), ["refs/heads/main"])
            self.assertEqual(self.pushes, [])
            self.assertIn("[spec] WARN an agent job never commits or pushes (ADR 0063)", stdout)
            self.assertEqual(len(created), 1)
            self.assertFalse(created[0]["bind_path"])


@unittest.skipUnless(HAS_GIT, "git not installed")
class DeliverTests(_MainFlow, unittest.TestCase):
    """ADR 0063 `DELIVER`: no agent; the run directory an agent job left is committed, pushed and the merge request
    opened through push options."""

    def implement(self, tmp_path: Path, repos: list, code: str, **kw) -> dict:
        result, _, _ = self.run_main(tmp_path, self.spec(repos, self.stub(code), workspace=RUN_WORKSPACE, **kw))
        self.assertEqual(result["status"], "SUCCEEDED", result)
        self.assertEqual(self.pushes, [])
        return result

    def test_delivers_the_run_directory_with_a_merge_request(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
            tmp_path = Path(tmp)
            origin = make_origin(tmp_path, "src")
            code = ("import pathlib; pathlib.Path('cart.kt').write_text('fun cart() = 1');"
                    " pathlib.Path('README.md').write_text('# src\\nchanged');"
                    " pathlib.Path('.gitignore').write_text('build/');"
                    " pathlib.Path('build').mkdir(); pathlib.Path('build/out.bin').write_text('bin');"
                    " pathlib.Path('work-log.md').write_text('log');"
                    " pathlib.Path('structured-answer.json').write_text('{}');"
                    " pathlib.Path('agent-questions.json').write_text('{}')")
            # an older Back Office: skills in the repository (git-excluded)
            self.implement(tmp_path, [entry("src", origin, primary=True)], code,
                           skills=[{"key": "kotlin", "name": "Kotlin", "content": "# Kotlin"}])
            self.assertEqual(origin_branches(origin), ["refs/heads/main"])

            mr = {"create": True, "title": "SHOP-1: cart", "description": "line 1\nline 2", "draft": True}
            job = self.deliver([entry("src", origin, "PUSH_MR", pushBranch="feature/shop-1", mergeRequest=mr)],
                               commitSubject="SHOP-1: add the cart", verifyCommand="exit 1", setupCommand="exit 1")
            result, created, stdout = self.run_main(tmp_path, job, {"src": SRC_TOKEN})

            self.assertEqual(result["status"], "SUCCEEDED", result)
            src = result["repositories"][0]
            sha = git(origin, "rev-parse", "refs/heads/feature/shop-1").strip()
            self.assertEqual((src["pushed"], src["branch"], src["commitSha"], src["error"]), (True, "feature/shop-1", sha, None))
            self.assertIsNone(src["mergeRequestUrl"])  # a bare repository prints none
            self.assertEqual((result["branch"], result["commitSha"]), ("feature/shop-1", sha))
            self.assertEqual(git(origin, "log", "-1", "--format=%s", "feature/shop-1").strip(), "SHOP-1: add the cart")
            trailer = git(origin, "log", "-1", "--format=%(trailers:key=Back-Office-Run,valueonly)", "feature/shop-1").strip()
            self.assertEqual(trailer, RUN_ID)
            # tracked and untracked changes; ignored files, skills and the runner's ephemeral files stay out
            files = git(origin, "ls-tree", "-r", "--name-only", "feature/shop-1").split()
            self.assertEqual(sorted(files), [".gitignore", "README.md", "cart.kt"])
            self.assertIn("+fun cart() = 1", result["diff"])
            self.assertIn("+changed", result["diff"])
            self.assertEqual(src["diffStat"]["files"], 3)
            self.assertIn("- src: pushed feature/shop-1", result["summary"])
            # the merge request through whitelisted push options to the explicit URL and refspec
            self.assertEqual(len(self.pushes), 1)
            args = self.pushes[0]["args"]
            options = [args[i + 1] for i, a in enumerate(args) if a == "-o"]
            self.assertEqual(options, ["merge_request.create", "merge_request.target=main", "merge_request.title=SHOP-1: cart",
                                       "merge_request.description=line 1 line 2", "merge_request.draft"])
            self.assertEqual(args[-2:], [str(origin), "HEAD:refs/heads/feature/shop-1"])
            self.assertNotIn("--force", args)
            # no agent, no command
            self.assertEqual(created[0]["token"], SRC_TOKEN)
            self.assertIn("setupCommand, verifyCommand ignored: a delivery runs no command", stdout)
            self.assertIsNone(result["verification"])
            self.assertNotIn("agent running", stdout)
            self.assertFalse(result["security"]["agentTokenIsolated"])
            self.assertEqual(result["security"]["gitAccess"], "PUSH_MR")
            # the ephemeral files stay in the run directory, untracked
            self.assertTrue((tmp_path / "ws" / "src" / "work-log.md").exists())

    def test_a_repository_without_changes_is_not_pushed(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
            tmp_path = Path(tmp)
            origin = make_origin(tmp_path, "src")
            result, _, stdout = self.run_main(tmp_path, self.deliver([entry("src", origin, "PUSH_MR", pushBranch="feature/x")]))
            self.assertEqual(result["status"], "SUCCEEDED", result)
            src = result["repositories"][0]
            self.assertFalse(src["pushed"])
            self.assertIsNone(src["error"])
            self.assertEqual(src["commitSha"], git(origin, "rev-parse", "main").strip())
            self.assertEqual(origin_branches(origin), ["refs/heads/main"])
            self.assertEqual(self.pushes, [])
            self.assertIn("- src: no changes, nothing pushed", result["summary"])
            self.assertIn("no changes to commit", stdout)

    def test_branch_safety_refuses_before_anything_is_cloned(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
            tmp_path = Path(tmp)
            origin = make_origin(tmp_path, "src")
            cases = {"main": None, "release": {"targetBranch": "release"}, "refs/heads/x": None, "-x": None, "a..b": None}
            for branch, mr in cases.items():
                with self.subTest(branch=branch):
                    extra = {"mergeRequest": mr} if mr else {}
                    result, created, _ = self.run_main(tmp_path, self.deliver([entry("src", origin, "PUSH_MR", pushBranch=branch, **extra)]))
                    self.assertEqual(result["status"], "FAILED")
                    self.assertIn("push refused: repository src", result["error"])
                    self.assertEqual(created, [])
            self.assertEqual(origin_branches(origin), ["refs/heads/main"])

    def test_several_repositories(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
            tmp_path = Path(tmp)
            a_origin, b_origin, docs_origin = (make_origin(tmp_path, n) for n in ("a", "b", "docs"))
            agent_repos = [entry("a", a_origin, primary=True), entry("b", b_origin), entry("docs", docs_origin)]
            self.implement(tmp_path, agent_repos, "import pathlib; pathlib.Path('x.txt').write_text('a'); pathlib.Path('../docs/d.md').write_text('d')")
            repos = [entry("a", a_origin, "PUSH_MR", pushBranch="feature/x", primary=True),
                     entry("b", b_origin, "PUSH_MR", pushBranch="feature/x"), entry("docs", docs_origin)]
            result, created, _ = self.run_main(tmp_path, self.deliver(repos), {"a": "tok-a", "b": "tok-b", "docs": DOCS_TOKEN})
            self.assertEqual(result["status"], "SUCCEEDED", result)
            a, b, docs = result["repositories"]
            self.assertTrue(a["pushed"])
            self.assertEqual(git(a_origin, "ls-tree", "-r", "--name-only", "feature/x").split(), ["README.md", "x.txt"])
            self.assertEqual((b["pushed"], b["error"]), (False, None))  # no changes
            self.assertEqual(origin_branches(b_origin), ["refs/heads/main"])
            self.assertEqual((docs["pushed"], docs["branch"]), (False, None))  # READ: not delivered
            self.assertEqual(origin_branches(docs_origin), ["refs/heads/main"])
            self.assertIn("?? d.md", git(tmp_path / "ws" / "docs", "status", "--porcelain"))
            self.assertEqual(result["commitSha"], a["commitSha"])
            self.assertEqual(result["security"]["gitAccessByRepository"], {"a": "PUSH_MR", "b": "PUSH_MR", "docs": "READ"})
            self.assertEqual({g["dir"]: g["token"] for g in created}, {"a": "tok-a", "b": "tok-b", "docs": DOCS_TOKEN})
            self.assertEqual([p["dir"] for p in self.pushes], ["a"])
            self.assertIn("- b: no changes, nothing pushed", result["summary"])
            self.assertIn("- docs: not delivered (access READ)", result["summary"])

    def test_one_failing_push_does_not_hide_the_others(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
            tmp_path = Path(tmp)
            a_origin, b_origin = make_origin(tmp_path, "a"), make_origin(tmp_path, "b")
            # b's remote cannot take refs/heads/feature/x (a branch feature/x/blocker exists there)
            git(b_origin, "branch", "feature/x/blocker", "main")
            self.implement(tmp_path, [entry("a", a_origin, primary=True), entry("b", b_origin)],
                           "import pathlib; pathlib.Path('x.txt').write_text('a'); pathlib.Path('../b/y.txt').write_text('b')")
            repos = [entry("a", a_origin, "PUSH_MR", pushBranch="feature/x", primary=True),
                     entry("b", b_origin, "PUSH_MR", pushBranch="feature/x")]
            result, _, _ = self.run_main(tmp_path, self.deliver(repos))
            self.assertEqual(result["status"], "FAILED")
            self.assertIn("repository b: push failed", result["error"])
            a, b = result["repositories"]
            self.assertTrue(a["pushed"])
            self.assertIsNone(a["error"])
            self.assertEqual(git(a_origin, "rev-parse", "refs/heads/feature/x").strip(), a["commitSha"])
            self.assertFalse(b["pushed"])
            self.assertIn("push failed", b["error"])
            self.assertIsNotNone(b["commitSha"])  # committed in the run directory, so a retry can push it
            self.assertEqual(result["commitSha"], a["commitSha"])  # primary still reported
            self.assertIn("- b: failed: push failed", result["summary"])


if __name__ == "__main__":
    unittest.main()
