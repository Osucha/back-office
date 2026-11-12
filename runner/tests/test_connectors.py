"""Connectors (ADR 0064): `spec.connectors` parsed (and the former `mcpServers` / `agentTools` of an older Back Office),
MCP secrets read once from the bundle into the agent process's environment only, referenced (never written) in each
harness's MCP configuration and redacted; GITLAB_READ keeps the glab path of ADR 0051.
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
from test_entrypoint import GATEWAY_JSON, b64, make_git_env  # noqa: E402  (constants and functions only)

HAS_GIT = shutil.which("git") is not None
SECRET = "s3cret-docs-bearer-value"
HEADER_SECRET = "s3cret-jira-header-value"
CONNECTORS = [
    {"key": "docs", "kind": "MCP_HTTP", "url": "https://mcp.example.com/mcp", "auth": "BEARER"},
    {"key": "jira-read", "kind": "MCP_HTTP", "url": "http://jira.internal:8080/mcp", "auth": "HEADER", "headerName": "X-Api-Key"},
    {"key": "public", "kind": "MCP_HTTP", "url": "https://public.example/mcp", "auth": "NONE"},
    {"key": "gitlab-read", "kind": "GITLAB_READ", "repository": "main"},
]
SECRETS = {"docs": SECRET, "jira-read": HEADER_SECRET}
REPOS = [{"key": "main", "url": "https://gitlab.example.com/group/app.git", "baseBranch": "main", "access": "READ", "primary": True}]


def spec(**kw) -> dict:
    s = {"mode": "IMPLEMENT", "agent": "OPENCODE", "model": {"provider": "ANTHROPIC", "modelId": "m"}, "prompt": "p",
         "timeoutSeconds": 600, "gateway": GATEWAY_JSON}
    s.update(kw)
    return s


def files_containing(root: Path, needle: str) -> list:
    found = []
    for path in root.rglob("*"):
        if path.is_file() and not path.is_symlink():
            try:
                if needle in path.read_text(encoding="utf-8", errors="ignore"):
                    found.append(str(path))
            except OSError:
                pass
    return found


class ParsingTests(unittest.TestCase):
    def test_connectors_are_parsed(self):
        parsed = ep.parse_spec(spec(connectors=CONNECTORS))
        self.assertEqual(parsed.connectors, [
            ep.Connector("docs", "MCP_HTTP", "https://mcp.example.com/mcp", "BEARER"),
            ep.Connector("jira-read", "MCP_HTTP", "http://jira.internal:8080/mcp", "HEADER", "X-Api-Key"),
            ep.Connector("public", "MCP_HTTP", "https://public.example/mcp", "NONE"),
            ep.Connector("gitlab-read", "GITLAB_READ", repository="main"),
        ])
        self.assertEqual(parsed.warnings, [])
        self.assertEqual([c.key for c in parsed.mcp_connectors], ["docs", "jira-read", "public"])
        self.assertEqual([c.key for c in parsed.gitlab_read_connectors], ["gitlab-read"])
        self.assertEqual(parsed.connectors[1].env_name, "BO_CONNECTOR_JIRA_READ")
        # auth omitted or null = NONE; a GITLAB_READ connector without repository = the primary / legacy repository
        lean = ep.parse_spec(spec(connectors=[{"key": "a", "kind": "MCP_HTTP", "url": "https://a.example/mcp", "auth": None},
                                              {"key": "g", "kind": "GITLAB_READ", "repository": None}]))
        self.assertEqual(lean.connectors, [ep.Connector("a", "MCP_HTTP", "https://a.example/mcp"), ep.Connector("g", "GITLAB_READ")])
        self.assertFalse(lean.connectors[0].needs_secret)

    def test_invalid_connectors_are_skipped_with_a_warning(self):
        bad = [
            "docs",
            {"key": "Docs", "kind": "MCP_HTTP", "url": "https://a.example/mcp"},
            {"key": "x" * 41, "kind": "MCP_HTTP", "url": "https://a.example/mcp"},
            {"key": "sql", "kind": "MCP_STDIO", "url": "https://a.example/mcp"},
            {"key": "files", "kind": "MCP_HTTP", "url": "file:///etc/passwd"},
            {"key": "nourl", "kind": "MCP_HTTP"},
            {"key": "basic", "kind": "MCP_HTTP", "url": "https://a.example/mcp", "auth": "BASIC"},
            {"key": "hdr", "kind": "MCP_HTTP", "url": "https://a.example/mcp", "auth": "HEADER"},
            {"key": "crlf", "kind": "MCP_HTTP", "url": "https://a.example/mcp", "auth": "HEADER", "headerName": "X-A\r\nX-B"},
            {"key": "repo", "kind": "GITLAB_READ", "repository": "../etc"},
            {"key": "docs", "kind": "MCP_HTTP", "url": "https://first.example/mcp"},
            {"key": "docs", "kind": "MCP_HTTP", "url": "https://second.example/mcp"},
        ]
        parsed = ep.parse_spec(spec(connectors=bad))
        self.assertEqual(parsed.connectors, [ep.Connector("docs", "MCP_HTTP", "https://first.example/mcp")])
        self.assertEqual(len(parsed.warnings), len(bad) - 1, parsed.warnings)
        self.assertTrue(all(w.startswith("connectors[") and " ignored: " in w for w in parsed.warnings), parsed.warnings)
        self.assertIn("repeated", parsed.warnings[-1])
        self.assertEqual(ep.parse_spec(spec(connectors={"docs": "x"})).warnings, ["connectors ignored: not a list"])

    def test_legacy_mcp_servers_and_agent_tools_become_connectors(self):
        legacy = ep.parse_spec(spec(
            mcpServers=[{"name": "docs", "url": "https://mcp.example.com/mcp"}, {"name": "Bad", "url": "x"}],
            agentTools=[{"name": "gitlab-read", "repository": "main"}, {"name": "gitlab-read", "repository": "main"},
                        {"name": "gitlab-read", "repository": "lib"}, {"name": "shell-root", "repository": "main"}],
        ))
        self.assertEqual(legacy.connectors, [
            ep.Connector("docs", "MCP_HTTP", "https://mcp.example.com/mcp", "NONE"),
            ep.Connector("gitlab-read", "GITLAB_READ", repository="main"),
            ep.Connector("gitlab-read-2", "GITLAB_READ", repository="lib"),
        ])
        self.assertEqual(len(legacy.warnings), 1)
        self.assertTrue(legacy.warnings[0].startswith("mcpServers[1] ignored: name 'Bad'"), legacy.warnings)

    def test_connectors_win_over_the_legacy_fields(self):
        parsed = ep.parse_spec(spec(connectors=[], mcpServers=[{"name": "docs", "url": "https://mcp.example.com/mcp"}],
                                    agentTools=[{"name": "gitlab-read", "repository": ""}]))
        self.assertEqual(parsed.connectors, [])


class SecretFileTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.job_dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_secrets_are_read_once_and_every_connector_file_is_removed(self):
        for name, value in (("docs", SECRET), ("jira-read", HEADER_SECRET), ("public", "unused-secret"), ("ghost", "other-secret")):
            (self.job_dir / f"connector-token-{name}").write_text(value + "\n")
        (self.job_dir / "tool-token-main").write_text("tkn-ci-reader")
        ctx = types.SimpleNamespace(spec=ep.parse_spec(spec(connectors=CONNECTORS, repositories=REPOS)))
        env = {"RUNNER_JOB_DIR": str(self.job_dir)}
        self.assertEqual(ep.load_connector_secrets(env, ctx), SECRETS)
        self.assertEqual(sorted(p.name for p in self.job_dir.iterdir()), ["tool-token-main"])
        self.assertEqual(ep.load_tool_tokens(env, ctx), {"main": "tkn-ci-reader"})
        self.assertEqual(list(self.job_dir.iterdir()), [])

    def test_files_are_removed_even_when_the_job_has_no_use_for_them(self):
        (self.job_dir / "connector-token-docs").write_text(SECRET)
        prompt = types.SimpleNamespace(spec=ep.parse_spec(spec(mode="PROMPT", agent="CLAUDE_CODE")))
        self.assertEqual(ep.load_connector_secrets({"RUNNER_JOB_DIR": str(self.job_dir)}, prompt), {})
        self.assertFalse((self.job_dir / "connector-token-docs").exists())
        self.assertEqual(ep.load_connector_secrets({"RUNNER_JOB_DIR": str(self.job_dir / "missing")}, None), {})

    @unittest.skipIf(os.name == "nt", "symlinks need privileges on Windows")
    def test_a_symlink_is_never_followed(self):
        victim = self.job_dir / "victim"
        victim.write_text("not a secret")
        (self.job_dir / "connector-token-docs").symlink_to(victim)
        ctx = types.SimpleNamespace(spec=ep.parse_spec(spec(connectors=CONNECTORS)))
        self.assertEqual(ep.load_connector_secrets({"RUNNER_JOB_DIR": str(self.job_dir)}, ctx), {})
        self.assertTrue(victim.exists())


class EnvironmentTests(unittest.TestCase):
    def test_secrets_reach_only_the_agent_environment(self):
        job = ep.parse_spec(spec(agent="CLAUDE_CODE", connectors=CONNECTORS))
        base = {"HOME": "/tmp", "BO_CONNECTOR_STALE": "inherited-value"}
        env = ep.agent_environment(job, base, Path("/ws"), "bo_job_abc", None, "", {}, SECRETS)
        self.assertEqual(env["BO_CONNECTOR_DOCS"], SECRET)
        self.assertEqual(env["BO_CONNECTOR_JIRA_READ"], HEADER_SECRET)
        self.assertNotIn("BO_CONNECTOR_PUBLIC", env)  # auth NONE: no variable
        self.assertNotIn("BO_CONNECTOR_STALE", env)  # never inherited
        # setupCommand / verifyCommand and the runner's own processes get none of them
        untrusted = ep.agent_environment(job, base, Path("/ws"), "", None)
        self.assertFalse(any(k.startswith("BO_CONNECTOR_") for k in untrusted), untrusted)
        self.assertNotIn(SECRET, json.dumps(untrusted))

    def test_a_prompt_job_gets_no_connector(self):
        job = ep.parse_spec(spec(mode="PROMPT", agent="CLAUDE_CODE", connectors=CONNECTORS, repositories=REPOS))
        env = ep.agent_environment(job, {}, Path("/ws"), "bo_job_abc", None, "", {"main": "tkn-ci-reader"}, SECRETS)
        self.assertFalse(any(k.startswith("BO_CONNECTOR_") for k in env))
        self.assertNotIn("GITLAB_TOKEN", env)

    def test_a_connector_whose_secret_did_not_arrive_is_skipped_with_a_warning(self):
        job = ep.parse_spec(spec(agent="CLAUDE_CODE", connectors=CONNECTORS))
        logs = []
        ep.drop_connectors_without_secret(job, {"docs": SECRET}, logs.append)
        self.assertEqual([c.key for c in job.connectors], ["docs", "public", "gitlab-read"])
        self.assertEqual(logs, ["[connector] WARN jira-read: no secret arrived (connector-token-jira-read), connector skipped"])
        self.assertNotIn("jira-read", ep.claude_mcp_config(job.connectors)["mcpServers"])
        allow = ep.claude_settings(ep.ToolPolicy(allow=["git *"]), job.connectors)["permissions"]["allow"]
        self.assertIn("mcp__docs", allow)
        self.assertIn("mcp__public", allow)
        self.assertNotIn("mcp__jira-read", allow)
        self.assertNotIn("mcp__gitlab-read", allow)

    def test_secrets_are_redacted(self):
        redact = ep.Redactor(list(SECRETS.values()))
        self.assertEqual(redact(f"Authorization: Bearer {SECRET} / X-Api-Key: {HEADER_SECRET}"), "Authorization: Bearer *** / X-Api-Key: ***")


class HarnessConfigTests(unittest.TestCase):
    def setUp(self):
        self.connectors = ep.parse_spec(spec(connectors=CONNECTORS)).connectors

    def test_claude_mcp_config_refers_to_the_environment(self):
        self.assertEqual(ep.claude_mcp_config(self.connectors), {"mcpServers": {
            "docs": {"type": "http", "url": "https://mcp.example.com/mcp", "headers": {"Authorization": "Bearer ${BO_CONNECTOR_DOCS}"}},
            "jira-read": {"type": "http", "url": "http://jira.internal:8080/mcp", "headers": {"X-Api-Key": "${BO_CONNECTOR_JIRA_READ}"}},
            "public": {"type": "http", "url": "https://public.example/mcp"},
        }})

    def test_opencode_mcp_refers_to_the_environment(self):
        config = ep.build_opencode_config(ep.ModelRef("ANTHROPIC", "m"), ep.parse_gateway(GATEWAY_JSON), connectors=self.connectors)
        self.assertEqual(config["mcp"], {
            "docs": {"type": "remote", "url": "https://mcp.example.com/mcp", "enabled": True,
                     "headers": {"Authorization": "Bearer {env:BO_CONNECTOR_DOCS}"}},
            "jira-read": {"type": "remote", "url": "http://jira.internal:8080/mcp", "enabled": True,
                          "headers": {"X-Api-Key": "{env:BO_CONNECTOR_JIRA_READ}"}},
            "public": {"type": "remote", "url": "https://public.example/mcp", "enabled": True},
        })

    def run_state(self, ws: Path, tools=None):
        return types.SimpleNamespace(workspace=ws, tools=tools, extra_dirs=[], env={}, local_login_token="", subscription=False,
                                     access_repos=[], log=lambda m: None)

    def test_no_secret_on_disk_for_either_harness(self):
        for agent, home in (("CLAUDE_CODE", None), ("CLAUDE_CODE", "reviewer"), ("OPENCODE", None), ("OPENCODE", "writer")):
            with self.subTest(agent=agent, home=home), tempfile.TemporaryDirectory() as tmp:
                ws = Path(tmp)
                repo = ws / "src"
                repo.mkdir()
                job = ep.parse_spec(spec(agent=agent, agentHome=home, connectors=CONNECTORS))
                env = ep.agent_environment(job, {}, ws, "bo_job_abc", None, "", {}, SECRETS)
                files = ep.prepare_agent_files(job, ws, env, lambda m: None)
                with mock.patch.object(ep, "_claude_help", return_value="--mcp-config --settings"):
                    cmd, _, _ = ep._agent_invocation(self.run_state(ws, ep.ToolPolicy(allow=["git *"])), job, repo, "p", env, None, files)
                if agent == "CLAUDE_CODE":
                    mcp = json.loads(Path(cmd[cmd.index("--mcp-config") + 1]).read_text(encoding="utf-8"))
                    self.assertEqual(mcp["mcpServers"]["docs"]["headers"], {"Authorization": "Bearer ${BO_CONNECTOR_DOCS}"})
                    settings = json.loads(Path(cmd[cmd.index("--settings") + 1]).read_text(encoding="utf-8"))
                    self.assertIn("mcp__jira-read", settings["permissions"]["allow"])
                else:
                    config = json.loads(Path(env["OPENCODE_CONFIG"]).read_text(encoding="utf-8"))
                    self.assertEqual(config["mcp"]["jira-read"]["headers"], {"X-Api-Key": "{env:BO_CONNECTOR_JIRA_READ}"})
                self.assertNotIn(SECRET, " ".join(cmd))
                for secret in SECRETS.values():
                    self.assertEqual(files_containing(ws, secret), [])


class GitlabReadTests(unittest.TestCase):
    """ADR 0064 §1: GITLAB_READ is the former gitlab-read agent tool (ADR 0051), driven by connectors."""

    def test_glab_settings_from_the_connector(self):
        job = ep.parse_spec(spec(agent="CLAUDE_CODE", repositories=REPOS, connectors=CONNECTORS))
        env = ep.agent_environment(job, {"GITLAB_TOKEN": "inherited"}, Path("/ws"), "bo_job_abc", None, "", {"main": "tkn-ci-reader"})
        self.assertEqual(env["GITLAB_TOKEN"], "tkn-ci-reader")
        self.assertEqual(env["GITLAB_HOST"], "https://gitlab.example.com")
        self.assertIn("group/app", ep.tools_prompt_section(job, {"main": "tkn-ci-reader"}))
        without = ep.agent_environment(job, {"GITLAB_TOKEN": "inherited"}, Path("/ws"), "bo_job_abc")
        self.assertNotIn("GITLAB_TOKEN", without)
        self.assertEqual(ep.tools_prompt_section(job, {}), "")

    def test_one_glab_token_and_each_connector_is_logged(self):
        repos = REPOS + [{"key": "lib", "url": "https://gitlab.example.com/group/lib.git", "baseBranch": "main", "access": "READ", "path": "lib"}]
        job = ep.parse_spec(spec(agent="CLAUDE_CODE", repositories=repos, connectors=[
            {"key": "ci-lib", "kind": "GITLAB_READ", "repository": "lib"},
            {"key": "ci-main", "kind": "GITLAB_READ", "repository": "main"},
            {"key": "ci-gone", "kind": "GITLAB_READ", "repository": "gone"},
        ]))
        tokens = {"lib": "tkn-lib-reader", "main": "tkn-main-reader"}
        self.assertEqual(ep.tool_environment(job, tokens, Path("/ws"))["GITLAB_TOKEN"], "tkn-lib-reader")
        logs = []
        ep._log_gitlab_read(types.SimpleNamespace(tool_tokens=tokens, log=logs.append), job)
        self.assertEqual(logs, [
            "[connector] connector ci-lib (glab) for repository 'lib': read-only token delivered",
            "[connector] WARN connector ci-main (glab) for repository 'main': not available (glab works with one token: ci-lib)",
            "[connector] WARN connector ci-gone (glab) for repository 'gone': no token arrived (tool-token), connector not available",
        ])


@unittest.skipUnless(HAS_GIT, "git not installed")
class MainFlowTests(unittest.TestCase):
    """In-process main() with the STUB agent and a job bundle that carries connector secrets."""

    def run_main(self, tmp: Path, job: dict, bundle: dict) -> tuple:
        job_dir = tmp / "job"
        job_dir.mkdir(exist_ok=True)
        for name, value in bundle.items():
            (job_dir / name).write_text(value)
        env = make_git_env()
        for name in ("RUNNER_CALLBACK_URL", "RUNNER_CALLBACK_TOKEN", "RUNNER_JOB_ID", "RUNNER_SANDBOX", "RUNNER_POLICY",
                     "RUNNER_PROXY_URL", "RUNNER_MAX_PROCS", "RUNNER_WAIT_FOR_JOB", "RUNNER_SPEC_FILE"):
            env.pop(name, None)
        env.update({
            "RUNNER_ENABLE_STUB_AGENT": "1", "RUNNER_WORKSPACE": str(tmp / "ws"), "RUNNER_RESULT_FILE": str(tmp / "result.json"),
            "RUNNER_JOB_DIR": str(job_dir), "RUNNER_GIT_TOKEN_FILE": str(tmp / "no-git-token"),
            "RUNNER_GIT_TOKEN_DIR": str(tmp / "git-tokens"), "HOME": str(tmp / "home"), "TMPDIR": str(tmp), "RUNNER_SPEC": b64(job),
        })
        out = io.StringIO()
        with mock.patch.dict(os.environ, env, clear=True), mock.patch.object(ep.sys, "stdout", out):
            code = ep.main()
        self.assertEqual(code, 0, out.getvalue())
        return json.loads((tmp / "result.json").read_text(encoding="utf-8")), out.getvalue()

    def test_the_agent_sees_the_secret_the_log_and_the_disk_do_not(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
            tmp_path = Path(tmp)
            check = ("import os; v = os.environ.get('BO_CONNECTOR_DOCS'); print('agent sees', v); "
                     "print('match', v == '" + SECRET + "'); print('jira', os.environ.get('BO_CONNECTOR_JIRA_READ'))")
            verify = ("import os, sys; sys.exit(1 if any(k.startswith('BO_CONNECTOR_') for k in os.environ) else 0)")
            job = {"mode": "IMPLEMENT", "agent": "STUB", "model": {"provider": "ANTHROPIC", "modelId": "m"},
                   "prompt": f'"{sys.executable}" -c "{check}"', "verifyCommand": f'"{sys.executable}" -c "{verify}"',
                   "timeoutSeconds": 600, "connectors": CONNECTORS}
            result, stdout = self.run_main(tmp_path, job, {"connector-token-docs": SECRET, "connector-token-ghost": "ghost-secret"})
            self.assertEqual(result["status"], "SUCCEEDED", result)
            self.assertIn("match True", stdout)
            self.assertIn("agent sees ***", stdout)
            self.assertIn("jira None", stdout)
            self.assertIn("[connector] WARN jira-read: no secret arrived (connector-token-jira-read), connector skipped", stdout)
            self.assertNotIn(SECRET, stdout)
            self.assertNotIn(SECRET, json.dumps(result))
            self.assertEqual(list((tmp_path / "job").iterdir()), [])
            self.assertEqual(files_containing(tmp_path / "ws", SECRET), [])

    def test_a_prompt_job_ignores_connectors(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
            tmp_path = Path(tmp)
            check = "import os; print('vars', sorted(k for k in os.environ if k.startswith('BO_CONNECTOR_')))"
            job = {"mode": "PROMPT", "agent": "STUB", "model": {"provider": "ANTHROPIC", "modelId": "m"},
                   "prompt": f'"{sys.executable}" -c "{check}"', "timeoutSeconds": 600, "connectors": CONNECTORS}
            result, stdout = self.run_main(tmp_path, job, {"connector-token-docs": SECRET})
            self.assertEqual(result["status"], "SUCCEEDED", result)
            self.assertIn("vars []", stdout)
            self.assertIn("[connector] PROMPT job: 4 connector(s) ignored", stdout)
            self.assertFalse((tmp_path / "job" / "connector-token-docs").exists())


if __name__ == "__main__":
    unittest.main()
