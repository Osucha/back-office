"""The agent is the environment (ADR 0057): `spec.instructions` and `spec.mcpServers` parsed and written in each
harness's own form outside every repository, and the coding agent's structured answer collected but never committed.
Run with: python3 -m unittest discover -s runner/tests"""

from __future__ import annotations

import io
import json
import os
import shutil
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import entrypoint as ep  # noqa: E402
from test_entrypoint import GATEWAY_JSON, b64, git, make_git_env  # noqa: E402  (constants and functions only)
from test_multi_repo import make_origin  # noqa: E402  (function only)

HAS_GIT = shutil.which("git") is not None
WS_NAME = "bo-ws-11111111222243338444555555555555-main"
DOC = "# Back Office\n\nYou are the implementer.\n\n## Rules\n\n- Never weaken a test.\n"
SERVERS = [{"name": "docs", "url": "https://mcp.example.com/mcp"}, {"name": "jira-read", "url": "http://jira.internal:8080/mcp"}]


def spec(**kw) -> dict:
    s = {"mode": "IMPLEMENT", "agent": "OPENCODE", "model": {"provider": "ANTHROPIC", "modelId": "m"}, "prompt": "p",
         "timeoutSeconds": 600, "gateway": GATEWAY_JSON}
    s.update(kw)
    return s


class ParsingTests(unittest.TestCase):
    def test_older_specs_have_no_environment(self):
        parsed = ep.parse_spec(spec())
        self.assertIsNone(parsed.instructions)
        self.assertEqual(parsed.mcp_servers, [])
        self.assertEqual(parsed.warnings, [])
        nulls = ep.parse_spec(spec(instructions=None, mcpServers=None))
        self.assertIsNone(nulls.instructions)
        self.assertEqual(nulls.mcp_servers, [])

    def test_instructions_are_kept_verbatim_and_blank_means_none(self):
        self.assertEqual(ep.parse_spec(spec(instructions=DOC)).instructions, DOC)
        self.assertIsNone(ep.parse_spec(spec(instructions=" \n ")).instructions)
        with self.assertRaises(ValueError):
            ep.parse_spec(spec(instructions=["not", "a", "string"]))

    def test_mcp_servers_are_parsed(self):
        parsed = ep.parse_spec(spec(mcpServers=SERVERS))
        self.assertEqual(parsed.mcp_servers, [ep.McpServer("docs", "https://mcp.example.com/mcp"),
                                              ep.McpServer("jira-read", "http://jira.internal:8080/mcp")])
        self.assertEqual(parsed.warnings, [])

    def test_invalid_mcp_servers_are_skipped_with_a_warning(self):
        bad = [
            "docs",
            {"name": "Docs", "url": "https://a.example/mcp"},
            {"name": "1docs", "url": "https://a.example/mcp"},
            {"name": "x" * 41, "url": "https://a.example/mcp"},
            {"name": "files", "url": "file:///etc/passwd"},
            {"name": "ws", "url": "wss://a.example/mcp"},
            {"name": "nourl"},
            {"name": "space", "url": "https://a.example/m cp"},
            {"name": "long", "url": "https://a.example/" + "x" * 500},
            {"name": "docs", "url": "https://first.example/mcp"},
            {"name": "docs", "url": "https://second.example/mcp"},
        ]
        parsed = ep.parse_spec(spec(mcpServers=bad))
        self.assertEqual(parsed.mcp_servers, [ep.McpServer("docs", "https://first.example/mcp")])
        self.assertEqual(len(parsed.warnings), len(bad) - 1)
        self.assertTrue(all(w.startswith("mcpServers[") and " ignored: " in w for w in parsed.warnings), parsed.warnings)
        self.assertIn("repeated", parsed.warnings[-1])
        self.assertEqual(ep.parse_spec(spec(mcpServers={"docs": "x"})).warnings, ["mcpServers ignored: not a list"])


class HarnessConfigTests(unittest.TestCase):
    def setUp(self):
        self.servers = ep.parse_spec(spec(mcpServers=SERVERS)).mcp_servers

    def test_opencode_config_lists_the_document_and_the_servers(self):
        model = ep.ModelRef("ANTHROPIC", "m")
        config = ep.build_opencode_config(model, ep.parse_gateway(GATEWAY_JSON), None, (),
                                          Path("/workspace/.backoffice-agent/AGENTS.md"), self.servers)
        self.assertEqual(config["instructions"], ["/workspace/.backoffice-agent/AGENTS.md"])
        self.assertEqual(config["mcp"], {
            "docs": {"type": "remote", "url": "https://mcp.example.com/mcp", "enabled": True},
            "jira-read": {"type": "remote", "url": "http://jira.internal:8080/mcp", "enabled": True},
        })
        plain = ep.build_opencode_config(model, ep.parse_gateway(GATEWAY_JSON))
        self.assertNotIn("instructions", plain)
        self.assertNotIn("mcp", plain)

    def test_claude_mcp_config_shape(self):
        self.assertEqual(ep.claude_mcp_config(self.servers), {"mcpServers": {
            "docs": {"type": "http", "url": "https://mcp.example.com/mcp"},
            "jira-read": {"type": "http", "url": "http://jira.internal:8080/mcp"},
        }})

    def test_claude_command_passes_the_mcp_config_when_the_cli_has_the_flag(self):
        model = ep.ModelRef("ANTHROPIC", "m")
        path = Path("/workspace/.backoffice-agent/mcp.json")
        cmd = ep.build_claude_command(model, "p", "dontAsk", "--mcp-config <configs...>", mcp_config=path)
        self.assertEqual(cmd[cmd.index("--mcp-config") + 1], str(path))
        self.assertEqual(cmd[2], "p")  # the prompt comes before the variadic --mcp-config
        self.assertNotIn("--mcp-config", ep.build_claude_command(model, "p", "dontAsk", "", mcp_config=path))
        self.assertNotIn("--mcp-config", ep.build_claude_command(model, "p", "dontAsk", "--mcp-config"))

    def test_claude_settings_allow_the_tools_of_the_agents_servers(self):
        policy = ep.ToolPolicy(allow=["git *"], deny=[], ask=[])
        allow = ep.claude_settings(policy, self.servers)["permissions"]["allow"]
        self.assertIn("mcp__docs", allow)
        self.assertIn("mcp__jira-read", allow)
        self.assertIn("Bash(git *)", allow)
        self.assertFalse(any(a.startswith("mcp__") for a in ep.claude_settings(policy)["permissions"]["allow"]))


class EnvironmentFilesTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.ws = Path(self.tmp.name)
        self.logs = []
        # a repository with its own instruction files: they are never touched
        self.repo = self.ws / "src"
        self.repo.mkdir()
        (self.repo / "AGENTS.md").write_text("repo agents\n")
        (self.repo / "CLAUDE.md").write_text("repo claude\n")

    def tearDown(self):
        self.tmp.cleanup()

    def assert_repository_untouched(self):
        self.assertEqual((self.repo / "AGENTS.md").read_text(), "repo agents\n")
        self.assertEqual((self.repo / "CLAUDE.md").read_text(), "repo claude\n")
        self.assertEqual(sorted(p.name for p in self.repo.iterdir()), ["AGENTS.md", "CLAUDE.md"])

    def test_opencode_gets_agents_md_in_the_workspace(self):
        job = ep.parse_spec(spec(instructions=DOC, mcpServers=SERVERS))
        files = ep.prepare_agent_files(job, self.ws, {}, self.logs.append)
        target = self.ws / ".backoffice-agent" / "AGENTS.md"
        self.assertEqual(files.instructions, target)
        self.assertIsNone(files.mcp_config)  # OpenCode gets its servers in opencode.json
        self.assertEqual(target.read_text(encoding="utf-8"), DOC)
        self.assertFalse((self.ws / ".backoffice-agent" / "mcp.json").exists())
        self.assertIn(f"[environment] wrote {target} ({len(DOC)} chars)", self.logs)
        self.assertTrue(any(line.startswith("[mcp] server docs: https://mcp.example.com/mcp") for line in self.logs), self.logs)
        self.assert_repository_untouched()

    def test_claude_code_gets_user_memory_and_mcp_config(self):
        job = ep.parse_spec(spec(agent="CLAUDE_CODE", instructions=DOC, mcpServers=SERVERS))
        env = ep.agent_environment(job, {}, self.ws)
        config_dir = Path(env["CLAUDE_CONFIG_DIR"])
        self.assertFalse(config_dir.exists())
        files = ep.prepare_agent_files(job, self.ws, env, self.logs.append)
        self.assertEqual(files.instructions, self.ws / ".claude-config" / "CLAUDE.md")
        self.assertEqual(files.instructions.read_text(encoding="utf-8"), DOC)
        self.assertEqual(files.mcp_config, self.ws / ".backoffice-agent" / "mcp.json")
        self.assertEqual(json.loads(files.mcp_config.read_text(encoding="utf-8")), ep.claude_mcp_config(job.mcp_servers))
        self.assertFalse((self.ws / ".backoffice-agent" / "AGENTS.md").exists())
        self.assert_repository_untouched()

    def test_claude_code_honours_a_given_config_dir(self):
        job = ep.parse_spec(spec(agent="CLAUDE_CODE", instructions=DOC))
        env = ep.agent_environment(job, {"CLAUDE_CONFIG_DIR": str(self.ws / "home" / ".claude")}, self.ws)
        files = ep.prepare_agent_files(job, self.ws, env, self.logs.append)
        self.assertEqual(files.instructions, self.ws / "home" / ".claude" / "CLAUDE.md")
        self.assertIsNone(files.mcp_config)

    def test_every_step_overwrites_and_a_step_without_environment_removes_it(self):
        env = {"CLAUDE_CONFIG_DIR": str(self.ws / ".claude-config")}
        first = ep.parse_spec(spec(agent="CLAUDE_CODE", instructions=DOC, mcpServers=SERVERS))
        ep.prepare_agent_files(first, self.ws, env, self.logs.append)
        second = ep.parse_spec(spec(agent="CLAUDE_CODE", instructions="# Reviewer\n"))
        files = ep.prepare_agent_files(second, self.ws, env, self.logs.append)
        self.assertEqual(files.instructions.read_text(encoding="utf-8"), "# Reviewer\n")
        self.assertIsNone(files.mcp_config)
        self.assertFalse((self.ws / ".backoffice-agent" / "mcp.json").exists())
        third = ep.parse_spec(spec(agent="CLAUDE_CODE"))
        self.assertEqual(ep.prepare_agent_files(third, self.ws, env, self.logs.append), ep.AgentFiles())
        self.assertFalse((self.ws / ".claude-config" / "CLAUDE.md").exists())
        ep.prepare_agent_files(ep.parse_spec(spec(instructions=DOC)), self.ws, {}, self.logs.append)
        ep.prepare_agent_files(ep.parse_spec(spec()), self.ws, {}, self.logs.append)
        self.assertFalse((self.ws / ".backoffice-agent" / "AGENTS.md").exists())
        self.assert_repository_untouched()

    @unittest.skipIf(os.name == "nt", "symlinks need privileges on Windows")
    def test_never_writes_through_a_planted_symlink(self):
        outside = self.ws / "outside"
        outside.mkdir()
        (self.ws / ".backoffice-agent").symlink_to(outside, target_is_directory=True)
        ep.prepare_agent_files(ep.parse_spec(spec(instructions=DOC)), self.ws, {}, self.logs.append)
        self.assertFalse((self.ws / ".backoffice-agent").is_symlink())
        self.assertEqual(list(outside.iterdir()), [])
        victim = self.ws / "victim.md"
        victim.write_text("keep")
        (self.ws / ".backoffice-agent" / "AGENTS.md").unlink()
        (self.ws / ".backoffice-agent" / "AGENTS.md").symlink_to(victim)
        ep.prepare_agent_files(ep.parse_spec(spec(instructions=DOC)), self.ws, {}, self.logs.append)
        self.assertEqual(victim.read_text(), "keep")

    def test_only_coding_modes_of_a_harness_get_files(self):
        for job in (ep.parse_spec(spec(mode="PROMPT", agent="CLAUDE_CODE", instructions=DOC, mcpServers=SERVERS)),):
            self.assertEqual(ep.prepare_agent_files(job, self.ws, {}, self.logs.append), ep.AgentFiles())
        for mode in ("SPEC_KIT", "EVAL_CODING"):
            files = ep.prepare_agent_files(ep.parse_spec(spec(mode=mode, instructions=DOC)), self.ws, {}, self.logs.append)
            self.assertIsNotNone(files.instructions, mode)
        self.assertFalse((self.ws / ".claude-config").exists())


class InvocationTests(unittest.TestCase):
    """`_agent_invocation` points each harness at the files."""

    def run_state(self, ws: Path, tools=None):
        return types.SimpleNamespace(workspace=ws, tools=tools, extra_dirs=[], env={}, local_login_token="", subscription=False,
                                     log=lambda m: self.logs.append(m))

    def setUp(self):
        self.logs = []

    def test_opencode_config_on_disk(self):
        with tempfile.TemporaryDirectory() as tmp:
            ws = Path(tmp)
            job = ep.parse_spec(spec(instructions=DOC, mcpServers=SERVERS))
            files = ep.prepare_agent_files(job, ws, {}, self.logs.append)
            ep._agent_invocation(self.run_state(ws), job, ws, "p", {}, None, files)
            config = json.loads((ws / "opencode.json").read_text(encoding="utf-8"))
            self.assertEqual(config["instructions"], [(ws / ".backoffice-agent" / "AGENTS.md").as_posix()])
            self.assertEqual(sorted(config["mcp"]), ["docs", "jira-read"])

    def test_claude_command_and_settings(self):
        with tempfile.TemporaryDirectory() as tmp:
            ws = Path(tmp)
            repo = ws / "src"
            repo.mkdir()
            job = ep.parse_spec(spec(agent="CLAUDE_CODE", instructions=DOC, mcpServers=SERVERS))
            env = ep.agent_environment(job, {}, ws)
            files = ep.prepare_agent_files(job, ws, env, self.logs.append)
            policy = ep.ToolPolicy(allow=["git *"], deny=[], ask=[])
            with mock.patch.object(ep, "_claude_help", return_value="--mcp-config --settings"):
                cmd, _, _ = ep._agent_invocation(self.run_state(ws, policy), job, repo, "p", env, None, files)
            self.assertEqual(cmd[cmd.index("--mcp-config") + 1], str(ws / ".backoffice-agent" / "mcp.json"))
            settings = json.loads(Path(cmd[cmd.index("--settings") + 1]).read_text(encoding="utf-8"))
            self.assertIn("mcp__docs", settings["permissions"]["allow"])
            with mock.patch.object(ep, "_claude_help", return_value=""):
                cmd, _, _ = ep._agent_invocation(self.run_state(ws, policy), job, repo, "p", env, None, files)
            self.assertNotIn("--mcp-config", cmd)
            self.assertIn("[mcp] WARN this Claude Code has no --mcp-config: the agent's MCP servers are not available", self.logs)


class StructuredAnswerTests(unittest.TestCase):
    def test_named_structured_answer_and_ephemeral(self):
        self.assertEqual(ep.artifact_name_for("structured-answer.json", set()), "structured-answer")
        self.assertEqual(ep.media_type_for("structured-answer.json"), ep.JSON_MEDIA_TYPE)
        self.assertIn("structured-answer.json", ep.EPHEMERAL_ARTIFACT_FILES)
        self.assertIn("work-log.md", ep.EPHEMERAL_ARTIFACT_FILES)

    def test_collected_from_the_working_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            (work / "structured-answer.json").write_text('{"verdict": "ok"}')
            arts = ep.collect_artifacts(work, ["structured-answer.json"], lambda m: None)
            self.assertEqual(arts, [{"name": "structured-answer", "path": "structured-answer.json",
                                     "mediaType": "application/json", "content": '{"verdict": "ok"}'}])

    def test_stale_answer_is_removed(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            logs = []
            ep.remove_stale_structured_answer(work, logs.append)
            self.assertEqual(logs, [])
            (work / "structured-answer.json").write_text("{}")
            ep.remove_stale_structured_answer(work, logs.append)
            self.assertFalse((work / "structured-answer.json").exists())
            self.assertEqual(len(logs), 1)

    @unittest.skipUnless(HAS_GIT, "git not installed")
    def test_kept_out_of_the_commit(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp) / "work"
            work.mkdir()
            git(work, "init", "-q", "-b", "main")
            (work / "README.md").write_text("x\n")
            git(work, "add", "-A")
            git(work, "commit", "-q", "-m", "init")
            (work / "Feature.kt").write_text("feature\n")
            (work / "structured-answer.json").write_text("{}")
            g = ep.Git(work, make_git_env(), lambda m: None)
            g.configure_identity("Back Office Agent", "agent@backoffice.local")
            g.commit_all("SHOP-1: feature", keep_out=ep.EPHEMERAL_ARTIFACT_FILES)
            committed = git(work, "show", "--name-only", "--format=", "HEAD").split()
            self.assertEqual(committed, ["Feature.kt"])
            self.assertTrue((work / "structured-answer.json").exists())


@unittest.skipUnless(HAS_GIT, "git not installed")
class MainFlowTests(unittest.TestCase):
    """In-process main() with the STUB agent: the answer is collected with and without a repository, never pushed,
    and a PROMPT job ignores the environment."""

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

    def job(self, prompt: str, **kw) -> dict:
        return dict({"mode": "IMPLEMENT", "agent": "STUB", "model": {"provider": "ANTHROPIC", "modelId": "m"}, "prompt": prompt,
                     "timeoutSeconds": 600, "collectArtifacts": ["structured-answer.json"]}, **kw)

    def answers(self, result: dict) -> list:
        return [a for a in result["artifacts"] if a["name"] == "structured-answer"]

    def test_collected_in_an_empty_workspace_and_a_stale_answer_does_not_count(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
            tmp_path = Path(tmp)
            write = self.stub("import pathlib; pathlib.Path('structured-answer.json').write_text('{}')")
            workspace = {"name": WS_NAME, "key": "main"}
            result, _ = self.run_main(tmp_path, self.job(write, workspace=workspace, mcpServers=[{"name": "Bad", "url": "x"}]))
            self.assertEqual(result["status"], "SUCCEEDED", result)
            self.assertEqual([a["path"] for a in self.answers(result)], ["structured-answer.json"])
            result, stdout = self.run_main(tmp_path, self.job(self.stub("print(1)"), workspace=workspace))
            self.assertEqual(self.answers(result), [])
            self.assertIn("removed structured-answer.json of an earlier step", stdout)

    def test_collected_without_repository_or_workspace(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
            write = self.stub("import pathlib; pathlib.Path('structured-answer.json').write_text('{}')")
            result, stdout = self.run_main(Path(tmp), self.job(write, mcpServers=[{"name": "Bad", "url": "x"}]))
            self.assertEqual(result["status"], "SUCCEEDED", result)
            self.assertEqual(len(self.answers(result)), 1)
            self.assertIn("[spec] WARN mcpServers[0] ignored", stdout)

    def test_collected_but_never_pushed(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
            tmp_path = Path(tmp)
            origin = make_origin(tmp_path, "src")
            repo = {"key": "src", "url": str(origin), "baseBranch": "main", "access": "PUSH_MR", "path": "src",
                    "primary": True, "pushBranch": "feature/answer"}
            write = self.stub("import pathlib; pathlib.Path('structured-answer.json').write_text('{}'); pathlib.Path('a.kt').write_text('x')")
            result, _ = self.run_main(tmp_path, self.job(write, repositories=[repo], runId="11111111-2222-4333-8444-555555555555"))
            self.assertEqual(result["status"], "SUCCEEDED", result)
            self.assertEqual(len(self.answers(result)), 1)
            files = git(origin, "ls-tree", "--name-only", "feature/answer").split()
            self.assertIn("a.kt", files)
            self.assertNotIn("structured-answer.json", files)

    def test_prompt_job_ignores_the_environment(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
            tmp_path = Path(tmp)
            memory = tmp_path / "ws" / ".claude-config" / "CLAUDE.md"
            memory.parent.mkdir(parents=True)
            memory.write_text("left by a coding step")
            job = self.job(self.stub("print(1)"), mode="PROMPT", instructions=DOC, mcpServers=SERVERS, collectArtifacts=[])
            # the STUB prompt job keeps the agent CLAUDE_CODE paths out; check with CLAUDE_CODE paths via the helper
            result, stdout = self.run_main(tmp_path, job)
            self.assertEqual(result["status"], "SUCCEEDED", result)
            self.assertIn(f"[environment] PROMPT job: instructions ({len(DOC)} chars) not written", stdout)
            self.assertIn("[mcp] PROMPT job: 2 MCP server(s) ignored", stdout)
            self.assertFalse((tmp_path / "ws" / ".backoffice-agent").exists())
            # the stub is not Claude Code: its user memory path does not apply
            self.assertTrue(memory.exists())
            claude = ep.parse_spec(spec(mode="PROMPT", agent="CLAUDE_CODE"))
            self.assertEqual(ep.environment_document_path(claude, tmp_path / "ws", {}), memory)


if __name__ == "__main__":
    unittest.main()
