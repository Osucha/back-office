"""Run workspaces and agent sessions (ADR 0044): parsing, reuse of clones between jobs of one run, prepare-only jobs,
uncommitted work handed on, `.git` rewritten on reuse, session ids per role. Run with:
python3 -m unittest discover -s runner/tests"""

from __future__ import annotations

import io
import json
import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import entrypoint as ep  # noqa: E402
from test_entrypoint import b64, git, make_git_env  # noqa: E402  (functions only)
from test_multi_repo import make_origin  # noqa: E402  (function only)

HAS_GIT = shutil.which("git") is not None
RUN_ID = "11111111-2222-4333-8444-555555555555"
WS_NAME = "bo-ws-11111111222243338444555555555555-main"


def workspace(**kw) -> dict:
    return dict({"name": WS_NAME, "key": "main"}, **kw)


def spec(**kw) -> dict:
    s = {"mode": "IMPLEMENT", "agent": "OPENCODE", "model": {"provider": "ANTHROPIC", "modelId": "m"}, "prompt": "p", "timeoutSeconds": 600}
    s.update(kw)
    return s


class WorkspaceSpecTests(unittest.TestCase):
    def test_parses_workspace_and_takes_the_multi_repository_path_without_repositories(self):
        parsed = ep.parse_spec(spec(workspace=workspace(sessionRole="implementer")))
        self.assertEqual(parsed.workspace, ep.WorkspaceSpec(WS_NAME, "main", "implementer", False))
        self.assertTrue(parsed.multi_repo)
        self.assertEqual(parsed.repositories, [])
        self.assertTrue(ep.parse_spec(spec(workspace=workspace(prepareOnly=True))).workspace.prepare_only)
        self.assertIsNone(ep.parse_spec(spec()).workspace)

    def test_invalid_workspaces_are_refused(self):
        for bad in ({"name": "../etc", "key": "main"}, {"name": WS_NAME, "key": "Main"}, workspace(sessionRole="Role X"), "main"):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                ep.parse_spec(spec(workspace=bad))


class AgentSessionTests(unittest.TestCase):
    def test_claude_session_id_per_workspace_and_role(self):
        a = ep.session_id_for(WS_NAME, "implementer")
        self.assertEqual(a, ep.session_id_for(WS_NAME, "implementer"))
        self.assertNotEqual(a, ep.session_id_for(WS_NAME, "reviewer"))
        self.assertNotEqual(a, ep.session_id_for(WS_NAME.replace("-main", "-docs"), "implementer"))

    def test_claude_starts_then_resumes_its_session(self):
        with tempfile.TemporaryDirectory() as tmp:
            ws = Path(tmp)
            job = ep.parse_spec(spec(agent="CLAUDE_CODE", workspace=workspace(sessionRole="reviewer")))
            env = {"CLAUDE_CONFIG_DIR": str(ws / ".claude-config")}
            first = ep.agent_session(job, ws, env)
            self.assertFalse(first.resume)
            help_text = "--session-id --resume --no-session-persistence"
            cmd = ep.build_claude_command(job.model, "p", "dontAsk", help_text, session=first)
            self.assertEqual(cmd[cmd.index("--session-id") + 1], first.session_id)
            self.assertNotIn("--no-session-persistence", cmd)
            transcript = ws / ".claude-config" / "projects" / "-workspace-src" / f"{first.session_id}.jsonl"
            transcript.parent.mkdir(parents=True)
            transcript.write_text("{}\n")
            again = ep.agent_session(job, ws, env)
            self.assertTrue(again.resume)
            cmd = ep.build_claude_command(job.model, "p", "dontAsk", help_text, session=again)
            self.assertEqual(cmd[cmd.index("--resume") + 1], first.session_id)
            # without a role (or a workspace) every step starts a fresh, unsaved session as before
            plain = ep.parse_spec(spec(agent="CLAUDE_CODE", workspace=workspace()))
            self.assertIsNone(ep.agent_session(plain, ws, env))
            self.assertIn("--no-session-persistence", ep.build_claude_command(job.model, "p", "dontAsk", help_text))

    def test_opencode_continues_in_its_own_data_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            ws = Path(tmp)
            job = ep.parse_spec(spec(workspace=workspace(sessionRole="implementer")))
            first = ep.agent_session(job, ws, {})
            self.assertFalse(first.resume)
            self.assertEqual(first.data_dir, ws / ".bo" / "opencode" / "implementer")
            self.assertNotIn("--continue", ep.build_opencode_command(job.model, "p", first.resume))
            first.data_dir.mkdir(parents=True)
            (first.data_dir / "opencode.db").write_text("x")
            again = ep.agent_session(job, ws, {})
            self.assertTrue(again.resume)
            self.assertEqual(ep.build_opencode_command(job.model, "p", True)[-2:], ["--continue", "p"])
            # a prepare step starts no agent, so it has no session
            self.assertIsNone(ep.agent_session(ep.parse_spec(spec(workspace=workspace(sessionRole="x", prepareOnly=True))), ws, {}))


@unittest.skipUnless(HAS_GIT, "git not installed")
class WorkspaceMainFlowTests(unittest.TestCase):
    """Three jobs of one run in one workspace directory: prepare, a read-only step, a pushing step."""

    def stub(self, code: str) -> str:
        return f'"{sys.executable}" -c "{code}"'

    def run_main(self, tmp: Path, job: dict) -> tuple:
        env = make_git_env()
        for name in ("RUNNER_CALLBACK_URL", "RUNNER_CALLBACK_TOKEN", "RUNNER_JOB_ID", "RUNNER_SANDBOX", "RUNNER_POLICY",
                     "RUNNER_PROXY_URL", "RUNNER_MAX_PROCS", "RUNNER_WAIT_FOR_JOB", "RUNNER_SPEC_FILE"):
            env.pop(name, None)
        env.update({
            "RUNNER_ENABLE_STUB_AGENT": "1", "RUNNER_WORKSPACE": str(tmp / "ws"), "RUNNER_RESULT_FILE": str(tmp / "result.json"),
            "RUNNER_JOB_DIR": str(tmp / "no-bundle"), "RUNNER_GIT_TOKEN_FILE": str(tmp / "no-git-token"),
            "RUNNER_GIT_TOKEN_DIR": str(tmp / "git-tokens"), "HOME": str(tmp / "home"), "TMPDIR": str(tmp), "RUNNER_SPEC": b64(job),
        })
        out = io.StringIO()
        with mock.patch.dict(os.environ, env, clear=True), mock.patch.object(ep.sys, "stdout", out):
            code = ep.main()
        self.assertEqual(code, 0, out.getvalue())
        return json.loads((tmp / "result.json").read_text(encoding="utf-8")), out.getvalue()

    def job(self, access: str, prompt: str, **kw) -> dict:
        repo = {"key": "src", "url": str(self.origin), "baseBranch": "main", "access": access, "path": "src", "primary": True}
        if access == "PUSH_MR":
            repo["pushBranch"] = "feature/ws-1"
        return dict({"mode": "IMPLEMENT", "agent": "STUB", "model": {"provider": "ANTHROPIC", "modelId": "m"}, "prompt": prompt,
                     "timeoutSeconds": 600, "runId": RUN_ID, "repositories": [repo]}, **kw)

    def test_steps_of_a_run_share_clones_setup_and_uncommitted_work(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
            tmp_path = Path(tmp)
            self.origin = make_origin(tmp_path, "src")
            src = tmp_path / "ws" / "src"

            # 1. prepare: clone + the workspace setup, no agent (the stub would fail the job if it ran)
            setup = self.stub("import pathlib; p = pathlib.Path('prepared.txt'); p.write_text(p.read_text() + 'x' if p.exists() else 'x')")
            result, _ = self.run_main(tmp_path, self.job("READ", self.stub("raise SystemExit(3)"),
                                                         workspace=workspace(prepareOnly=True, setupCommand=setup)))
            self.assertEqual(result["status"], "SUCCEEDED", result)
            self.assertIn("prepared", result["summary"])
            self.assertTrue((src / "prepared.txt").exists())
            self.assertTrue((tmp_path / "ws" / ".bo" / "repos" / "src.json").exists())

            # 2. a read-only step sees what was prepared; its notes stay uncommitted for the next step
            notes = self.stub("import pathlib; print('prepared', pathlib.Path('prepared.txt').exists()); pathlib.Path('NOTES.md').write_text('check cart')")
            result, stdout = self.run_main(tmp_path, self.job("READ", notes, workspace=workspace(sessionRole="reviewer", setupCommand=setup)))
            self.assertEqual(result["status"], "SUCCEEDED", result)
            self.assertIn("prepared True", result["summary"])
            self.assertIn("already ran in this workspace", stdout)
            self.assertEqual((src / "prepared.txt").read_text(), "x")  # the workspace setup ran once
            self.assertIn("reused from an earlier step", stdout)
            self.assertIn("NOTES.md", git(src, "status", "--porcelain"))
            self.assertEqual(git(src, "config", "remote.origin.pushurl").strip(), ep.DISABLED_PUSH_URL)

            # something planted in .git between the steps does not survive the reuse
            git(src, "config", "alias.st", "status")
            hook = src / ".git" / "hooks" / "post-checkout"
            hook.write_text("#!/bin/sh\necho planted\n")

            # 3. a pushing step continues on the same tree and pushes everything to the run branch
            code = self.stub("import pathlib; print('notes', pathlib.Path('NOTES.md').read_text()); pathlib.Path('cart.kt').write_text('fun cart() = 2')")
            result, _ = self.run_main(tmp_path, self.job("PUSH_MR", code, workspace=workspace(sessionRole="implementer")))
            self.assertEqual(result["status"], "SUCCEEDED", result)
            self.assertIn("notes check cart", result["summary"])
            pushed = {r["key"]: r for r in result["repositories"]}["src"]
            self.assertTrue(pushed["pushed"], result)
            files = git(self.origin, "ls-tree", "--name-only", "feature/ws-1").split()
            self.assertIn("cart.kt", files)
            self.assertIn("NOTES.md", files)
            self.assertNotIn("alias.st", git(src, "config", "--list"))
            self.assertFalse(hook.exists())
            self.assertNotIn("pushurl", git(src, "config", "--list"))

    @unittest.skipIf(os.name == "nt", "Windows cannot rmtree the read-only git objects of the earlier clone (Linux sandboxes can)")
    def test_without_a_workspace_a_job_clones_afresh(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
            tmp_path = Path(tmp)
            self.origin = make_origin(tmp_path, "src")
            self.run_main(tmp_path, self.job("READ", self.stub("import pathlib; pathlib.Path('left.txt').write_text('x')")))
            result, stdout = self.run_main(tmp_path, self.job("READ", self.stub("import pathlib; print('left', pathlib.Path('left.txt').exists())")))
            self.assertEqual(result["status"], "SUCCEEDED", result)
            self.assertIn("left False", result["summary"])
            self.assertNotIn("reused from an earlier step", stdout)


if __name__ == "__main__":
    unittest.main()
