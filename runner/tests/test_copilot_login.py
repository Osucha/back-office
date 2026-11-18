"""ADR 0069: GitHub Copilot models in OpenCode with the personal runner owner's own Copilot login."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import entrypoint as ep  # noqa: E402

# obviously fake: never a real token
FAKE_TOKEN = "gho_FAKE_PLACEHOLDER_TOKEN"
FAKE_AUTH = {"github-copilot": {"type": "oauth", "refresh": FAKE_TOKEN, "access": FAKE_TOKEN, "expires": 0}}

COPILOT_SPEC = {
    "mode": "EVAL_CODING",
    "agent": "OPENCODE",
    "model": {"provider": "GITHUB_COPILOT", "modelId": "gpt-5.3-codex"},
    "repository": {"url": "https://gitlab.example.com/group/shop-api.git", "baseRef": "main"},
    "prompt": "Fix the failing test",
    "timeoutSeconds": 1800,
    "llmCredentials": "LOCAL_LOGIN",
}


def bundle(d: Path, spec: dict) -> None:
    (d / "spec.json").write_text(json.dumps(spec), encoding="utf-8")
    (d / "policy.json").write_text("{}", encoding="utf-8")
    (d / "job-id").write_text("11111111-0000-4000-8000-000000000069\n", encoding="utf-8")
    (d / "job-token").write_text("bo_job_secret\n", encoding="utf-8")


class CopilotSpecTests(unittest.TestCase):
    def test_spec_accepts_the_copilot_provider(self):
        spec = ep.parse_spec(dict(COPILOT_SPEC))
        self.assertTrue(spec.copilot_model)
        self.assertTrue(spec.local_login)
        self.assertEqual(ep.opencode_model_ref(spec.model), "github-copilot/gpt-5.3-codex")
        self.assertEqual(ep.build_opencode_command(spec.model, "x")[:4], ["opencode", "run", "--model", "github-copilot/gpt-5.3-codex"])

    def test_config_uses_the_built_in_provider_and_no_gateway(self):
        config = ep.build_opencode_config(ep.ModelRef("GITHUB_COPILOT", "claude-sonnet-4.6"), None)
        self.assertEqual(config["model"], "github-copilot/claude-sonnet-4.6")
        self.assertEqual(config["small_model"], "github-copilot/claude-sonnet-4.6")
        self.assertNotIn("provider", config)
        self.assertNotIn(ep.JOB_TOKEN_ENV, json.dumps(config))

    def test_prompt_config_has_an_agent_without_tools_and_the_system_prompt(self):
        config = ep.build_opencode_prompt_config(ep.ModelRef("GITHUB_COPILOT", "gpt-5-mini"), None, "You are terse.")
        agent = config["agent"][ep.PROMPT_OPENCODE_AGENT]
        self.assertEqual((agent["mode"], agent["prompt"], agent["steps"]), ("primary", "You are terse.", 1))
        self.assertEqual(agent["permission"], {"*": "deny"})
        self.assertEqual(config["permission"], {"*": "deny"})
        cmd = ep.build_opencode_prompt_command(ep.ModelRef("GITHUB_COPILOT", "gpt-5-mini"))
        self.assertEqual(cmd[cmd.index("--agent") + 1], ep.PROMPT_OPENCODE_AGENT)
        self.assertNotIn("You are terse.", cmd)

    def test_prompt_config_without_system_prompt_has_a_default(self):
        config = ep.build_opencode_prompt_config(ep.ModelRef("GITHUB_COPILOT", "gpt-5-mini"), None, None)
        self.assertEqual(config["agent"][ep.PROMPT_OPENCODE_AGENT]["prompt"], ep.PROMPT_OPENCODE_DEFAULT_SYSTEM)


class CopilotLoginDeliveryTests(unittest.TestCase):
    def test_login_read_once_and_deleted(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            bundle(d, COPILOT_SPEC)
            (d / ep.COPILOT_LOGIN_FILE).write_text(json.dumps(FAKE_AUTH), encoding="utf-8")
            env = {"RUNNER_JOB_DIR": tmp}
            login, removed = ep.load_copilot_login(env, ep.load_job_context(env))
            self.assertEqual(json.loads(login), FAKE_AUTH)
            self.assertTrue(removed)
            self.assertFalse((d / ep.COPILOT_LOGIN_FILE).exists())

    def test_login_ignored_for_other_jobs(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            bundle(d, dict(COPILOT_SPEC, model={"provider": "ANTHROPIC", "modelId": "claude-opus-5"}))
            (d / ep.COPILOT_LOGIN_FILE).write_text(json.dumps(FAKE_AUTH), encoding="utf-8")
            env = {"RUNNER_JOB_DIR": tmp}
            self.assertEqual(ep.load_copilot_login(env, ep.load_job_context(env)), ("", None))
            self.assertEqual(ep.load_copilot_login(env, None), ("", None))

    def test_only_the_copilot_entry_is_kept(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            bundle(d, COPILOT_SPEC)
            (d / ep.COPILOT_LOGIN_FILE).write_text(json.dumps(dict(FAKE_AUTH, openai={"type": "api", "key": "sk-fake"})), encoding="utf-8")
            env = {"RUNNER_JOB_DIR": tmp}
            login, _ = ep.load_copilot_login(env, ep.load_job_context(env))
            self.assertEqual(list(json.loads(login)), ["github-copilot"])

    def test_a_malformed_file_is_deleted_and_ignored(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            bundle(d, COPILOT_SPEC)
            (d / ep.COPILOT_LOGIN_FILE).write_text("not json", encoding="utf-8")
            env = {"RUNNER_JOB_DIR": tmp}
            self.assertEqual(ep.load_copilot_login(env, ep.load_job_context(env)), ("", True))
            self.assertFalse((d / ep.COPILOT_LOGIN_FILE).exists())

    def test_the_agent_gets_the_login_only_as_opencode_auth_content(self):
        spec = ep.parse_spec(dict(COPILOT_SPEC))
        login = json.dumps(FAKE_AUTH)
        env = ep.agent_environment(spec, {"HOME": "/tmp", "OPENCODE_AUTH_CONTENT": "inherited"}, Path("/ws"), "bo_job_abc",
                                   copilot_login=login)
        self.assertEqual(env[ep.COPILOT_AUTH_ENV], login)

    def test_no_auth_content_without_a_login_or_for_gateway_models(self):
        spec = ep.parse_spec(dict(COPILOT_SPEC))
        env = ep.agent_environment(spec, {"HOME": "/tmp", "OPENCODE_AUTH_CONTENT": "inherited"}, Path("/ws"), "bo_job_abc")
        self.assertNotIn(ep.COPILOT_AUTH_ENV, env)
        gateway = ep.parse_spec(dict(COPILOT_SPEC, model={"provider": "OPENAI", "modelId": "gpt-5"}))
        env = ep.agent_environment(gateway, {"HOME": "/tmp"}, Path("/ws"), "bo_job_abc", copilot_login=json.dumps(FAKE_AUTH))
        self.assertNotIn(ep.COPILOT_AUTH_ENV, env)

    def test_tokens_of_the_login_are_redacted(self):
        secrets = ep.copilot_secrets(json.dumps(FAKE_AUTH))
        self.assertIn(FAKE_TOKEN, secrets)
        redact = ep.Redactor(secrets)
        self.assertNotIn(FAKE_TOKEN, redact(f"Authorization: Bearer {FAKE_TOKEN}"))
        self.assertEqual(ep.copilot_secrets(""), [])


class CopilotRunTests(unittest.TestCase):
    class FakeRun:
        def __init__(self, login: str):
            self.copilot_login = login
            self.subscription = False
            self.copilot = False
            self.lines = []

        def log(self, message: str) -> None:
            self.lines.append(message)

    def test_a_copilot_model_needs_the_login(self):
        spec = ep.parse_spec(dict(COPILOT_SPEC))
        with self.assertRaises(ValueError) as e:
            ep.use_copilot_login(self.FakeRun(""), spec)
        self.assertIn(ep.COPILOT_LOGIN_FILE, str(e.exception))
        self.assertNotIn(FAKE_TOKEN, str(e.exception))

    def test_a_copilot_model_needs_a_personal_runner_with_own_login(self):
        spec = ep.parse_spec(dict(COPILOT_SPEC, llmCredentials="GATEWAY"))
        with self.assertRaises(ValueError) as e:
            ep.use_copilot_login(self.FakeRun(json.dumps(FAKE_AUTH)), spec)
        self.assertIn("LOCAL_LOGIN", str(e.exception))

    def test_with_the_login_the_run_is_a_subscription(self):
        run = self.FakeRun(json.dumps(FAKE_AUTH))
        ep.use_copilot_login(run, ep.parse_spec(dict(COPILOT_SPEC)))
        self.assertTrue(run.subscription and run.copilot)
        self.assertNotIn(FAKE_TOKEN, "".join(run.lines))

    def test_result_drops_the_cost_and_hints_at_the_copilot_settings(self):
        result = {"usage": {"inputTokens": 10, "outputTokens": 5, "costUsd": 0.0}, "error": "agent reported an error: model not supported"}
        ep.copilot_result(result)
        self.assertIsNone(result["usage"]["costUsd"])
        self.assertIn("Copilot settings", result["error"])
        ok = {"usage": {"costUsd": 1.5}, "error": None}
        ep.copilot_result(ok)
        self.assertEqual(ok, {"usage": {"costUsd": None}, "error": None})
        other = {"usage": {}, "error": "verifyCommand exited with code 1"}
        ep.copilot_result(other)
        self.assertEqual(other["error"], "verifyCommand exited with code 1")


if __name__ == "__main__":
    unittest.main()
