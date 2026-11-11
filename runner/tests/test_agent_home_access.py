"""ADR 0060 in the runner: the agent home (/workspace/.agents/<key>/ as Claude Code CLAUDE_CONFIG_DIR, OpenCode
OPENCODE_CONFIG_DIR + data directory per session role, skills never in a repository), the agent's access (snapshot of
every repository before the agent, revert of changes outside the access after it, harness tool rules as guidance) and
the agent's questions (`agent-questions.json`). Run with: python3 -m unittest discover -s runner/tests"""

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
WS_NAME = "bo-ws-11111111222243338444555555555555-run"
RUN_ID = "11111111-2222-4333-8444-555555555555"
DOC = "# Back Office\n\nYou are the documentation writer.\n"
SKILLS = [{"key": "adr-format", "name": "ADR format", "content": "# ADR format\n\nWrite MADR."},
          {"key": "spec-writing", "name": "Spec writing", "content": "# Spec writing\n"}]
SERVERS = [{"name": "docs", "url": "https://mcp.example.com/mcp"}]


def spec(**kw) -> dict:
    s = {"mode": "IMPLEMENT", "agent": "OPENCODE", "model": {"provider": "ANTHROPIC", "modelId": "m"}, "prompt": "p",
         "timeoutSeconds": 600, "gateway": GATEWAY_JSON}
    s.update(kw)
    return s


class ParsingTests(unittest.TestCase):
    def test_older_specs_have_no_home_and_full_access(self):
        parsed = ep.parse_spec(spec())
        self.assertIsNone(parsed.agent_home)
        self.assertEqual(parsed.access, ep.AgentAccess("FULL", []))
        self.assertFalse(parsed.access.restricted)
        nulls = ep.parse_spec(spec(agentHome=None, access=None))
        self.assertIsNone(nulls.agent_home)
        self.assertEqual(nulls.access.mode, "FULL")

    def test_agent_home_is_validated(self):
        self.assertEqual(ep.parse_spec(spec(agentHome="doc-writer")).agent_home, "doc-writer")
        for bad in ("../etc", "Doc", "a", "x/y", ".agents", 7, "a" * 65):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                ep.parse_spec(spec(agentHome=bad))

    def test_access_modes_and_paths(self):
        self.assertEqual(ep.parse_spec(spec(access={"mode": "READ_ONLY", "paths": ["docs/**"]})).access,
                         ep.AgentAccess("READ_ONLY", []))
        paths = ep.parse_spec(spec(access={"mode": "paths", "paths": ["docs/**", "./README.md", "/adr/", " ", "docs/**", 3]})).access
        self.assertEqual(paths, ep.AgentAccess("PATHS", ["docs/**", "README.md", "adr/**"]))
        self.assertTrue(paths.restricted)
        self.assertEqual(ep.parse_spec(spec(access={})).access.mode, "FULL")
        for bad in ({"mode": "WRITE"}, "READ_ONLY", {"mode": "PATHS", "paths": "docs/**"}):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                ep.parse_spec(spec(access=bad))


class GlobTests(unittest.TestCase):
    def check(self, pattern: str, yes: list, no: list):
        for p in yes:
            self.assertTrue(ep.path_glob_matches(pattern, p), f"{pattern} should match {p}")
        for p in no:
            self.assertFalse(ep.path_glob_matches(pattern, p), f"{pattern} should not match {p}")

    def test_double_star(self):
        self.check("docs/**", ["docs/a.md", "docs/x/y/z.md"], ["docs", "src/docs/a.md", "docsx/a.md", "README.md"])
        self.check("**/*.md", ["a.md", "x/y/a.md"], ["a.mdx", "x/a.txt"])
        self.check("src/**/test/*.kt", ["src/test/A.kt", "src/a/b/test/A.kt"], ["src/a/test/x/A.kt", "test/A.kt"])

    def test_single_star_question_mark_and_class(self):
        self.check("*.md", ["README.md"], ["docs/README.md"])
        self.check("docs/?.md", ["docs/a.md"], ["docs/ab.md", "docs//.md"])
        self.check("docs/[ab].md", ["docs/a.md", "docs/b.md"], ["docs/c.md"])
        self.check("docs/[!a].md", ["docs/c.md"], ["docs/a.md", "docs//.md"])
        self.check("docs/[x", ["docs/[x"], ["docs/x"])

    def test_plain_paths_cover_what_is_below(self):
        self.check("docs", ["docs", "docs/a.md", "docs/x/y"], ["docsx", "src/docs"])
        self.check("README.md", ["README.md"], ["README.md.bak", "x/README.md"])

    def test_access_allows(self):
        self.assertTrue(ep.AgentAccess().allows("src/A.kt"))
        self.assertFalse(ep.AgentAccess("READ_ONLY").allows("docs/a.md"))
        paths = ep.AgentAccess("PATHS", ["docs/**", "CHANGELOG.md"])
        self.assertTrue(paths.allows("docs/a.md"))
        self.assertTrue(paths.allows("CHANGELOG.md"))
        self.assertFalse(paths.allows("src/A.kt"))
        self.assertFalse(ep.AgentAccess("PATHS", []).allows("docs/a.md"))


class HarnessRuleTests(unittest.TestCase):
    def test_opencode_edit_rule(self):
        self.assertEqual(ep.opencode_edit_rule(None), "allow")
        self.assertEqual(ep.opencode_edit_rule(ep.AgentAccess()), "allow")
        self.assertEqual(ep.opencode_edit_rule(ep.AgentAccess("READ_ONLY")), "deny")
        self.assertEqual(ep.opencode_edit_rule(ep.AgentAccess("PATHS", [])), "deny")
        rule = ep.opencode_edit_rule(ep.AgentAccess("PATHS", ["docs/**"]), Path("/workspace/app"),
                                     [Path("/workspace/app"), Path("/workspace/lib")])
        # OpenCode matches paths relative to its worktree (the working repository): another repository is ../<path>
        self.assertEqual(rule, {"*": "deny", "docs/**": "allow", "../lib/docs/**": "allow"})
        self.assertEqual(list(rule)[0], "*")  # last matching rule wins: the deny-all comes first

    def test_opencode_permission_carries_the_edit_rule_with_and_without_policy(self):
        self.assertEqual(ep.opencode_permission(None, (), "deny")["edit"], "deny")
        self.assertEqual(ep.opencode_permission(ep.ToolPolicy(allow=["git *"]), (), "deny")["edit"], "deny")
        self.assertEqual(ep.opencode_permission(None)["edit"], "allow")
        config = ep.build_opencode_config(ep.ModelRef("ANTHROPIC", "m"), ep.parse_gateway(GATEWAY_JSON), edit="deny")
        self.assertEqual(config["permission"]["edit"], "deny")

    def test_claude_read_only_denies_the_editing_tools(self):
        settings = ep.claude_settings(ep.ToolPolicy(), access=ep.AgentAccess("READ_ONLY"))
        perms = settings["permissions"]
        self.assertEqual(perms["deny"], ["Edit", "Write", "MultiEdit", "NotebookEdit"])
        for tool in ("Edit", "Write", "MultiEdit", "NotebookEdit"):
            self.assertNotIn(tool, perms["allow"])
        self.assertIn("Read", perms["allow"])
        self.assertIn("Bash", perms["allow"])
        self.assertEqual(perms["defaultMode"], "dontAsk")

    def test_claude_paths_allow_editing_only_on_the_globs(self):
        settings = ep.claude_settings(ep.ToolPolicy(allow=["git *"], deny=["rm *"]), access=ep.AgentAccess("PATHS", ["docs/**"]),
                                      work_dir=Path("/workspace/app"), repo_dirs=[Path("/workspace/app")])
        perms = settings["permissions"]
        self.assertIn("Edit(docs/**)", perms["allow"])
        self.assertIn("Write(docs/**)", perms["allow"])
        self.assertIn("Edit(//workspace/app/docs/**)", perms["allow"])
        self.assertNotIn("Edit", perms["allow"])
        self.assertNotIn("NotebookEdit", perms["allow"])
        self.assertEqual(perms["deny"], ["Bash(rm *)"])
        full = ep.claude_settings(ep.ToolPolicy(), access=ep.AgentAccess())["permissions"]
        self.assertIn("Edit", full["allow"])
        self.assertEqual(full["deny"], [])


class HomeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.ws = Path(self.tmp.name)
        self.logs = []
        self.repo = self.ws / "app"
        self.repo.mkdir()
        (self.repo / "AGENTS.md").write_text("repo agents\n")

    def tearDown(self):
        self.tmp.cleanup()

    def run_state(self, tools=None):
        return types.SimpleNamespace(workspace=self.ws, tools=tools, extra_dirs=[], env={}, local_login_token="",
                                     subscription=False, access_repos=[], log=self.logs.append)

    def assert_repository_untouched(self):
        self.assertEqual(sorted(p.name for p in self.repo.iterdir()), ["AGENTS.md"])

    def test_opencode_home_layout(self):
        job = ep.parse_spec(spec(agentHome="doc-writer", instructions=DOC, skills=SKILLS, mcpServers=SERVERS))
        home = self.ws / ".agents" / "doc-writer"
        env = ep.agent_environment(job, {}, self.ws)
        self.assertEqual(env["OPENCODE_CONFIG"], str(home / "opencode.json"))
        self.assertEqual(env["OPENCODE_CONFIG_DIR"], str(home))
        files = ep.prepare_agent_files(job, self.ws, env, self.logs.append)
        self.assertEqual(files.home, home)
        self.assertEqual(files.instructions, home / "AGENTS.md")
        self.assertEqual(files.instructions.read_text(encoding="utf-8"), DOC)
        self.assertIsNone(files.mcp_config)
        self.assertTrue((home / "skills" / "adr-format" / "SKILL.md").read_text(encoding="utf-8").startswith("---\nname: \"ADR format\""))
        self.assertTrue((home / "skills" / "spec-writing" / "SKILL.md").is_file())
        ep._agent_invocation(self.run_state(), job, self.repo, "p", env, None, files)
        config = json.loads((home / "opencode.json").read_text(encoding="utf-8"))
        self.assertEqual(config["instructions"], [(home / "AGENTS.md").as_posix()])
        self.assertEqual(sorted(config["mcp"]), ["docs"])
        self.assertEqual(config["permission"]["edit"], "allow")
        self.assertFalse((self.ws / "opencode.json").exists())
        self.assertFalse((self.ws / ".backoffice-agent").exists())
        self.assert_repository_untouched()

    def test_claude_code_home_layout(self):
        job = ep.parse_spec(spec(agent="CLAUDE_CODE", agentHome="reviewer", instructions=DOC, skills=SKILLS, mcpServers=SERVERS))
        home = self.ws / ".agents" / "reviewer"
        env = ep.agent_environment(job, {"CLAUDE_CONFIG_DIR": str(self.ws / "elsewhere")}, self.ws)
        self.assertEqual(env["CLAUDE_CONFIG_DIR"], str(home))  # the home wins over an inherited configuration
        files = ep.prepare_agent_files(job, self.ws, env, self.logs.append)
        self.assertEqual(files.instructions, home / "CLAUDE.md")
        self.assertEqual(files.mcp_config, home / "mcp.json")
        self.assertTrue((home / "skills" / "adr-format" / "SKILL.md").is_file())
        with mock.patch.object(ep, "_claude_help", return_value="--mcp-config --settings --session-id"):
            cmd, _, _ = ep._agent_invocation(self.run_state(ep.ToolPolicy(allow=["git *"])), job, self.repo, "p", env, None, files)
        self.assertEqual(cmd[cmd.index("--settings") + 1], str(home / "settings.json"))
        self.assertEqual(cmd[cmd.index("--mcp-config") + 1], str(home / "mcp.json"))
        self.assertEqual(sorted(p.name for p in home.iterdir()), ["CLAUDE.md", "mcp.json", "settings.json", "skills"])
        self.assertFalse((self.ws / ".backoffice-agent").exists())
        self.assertFalse((self.ws / ".runner").exists())
        self.assert_repository_untouched()  # no .claude/settings.local.json in the repository

    def test_claude_settings_without_policy_only_for_a_restricted_access(self):
        home = self.ws / ".agents" / "reviewer"
        full = ep.parse_spec(spec(agent="CLAUDE_CODE", agentHome="reviewer"))
        ep.prepare_agent_files(full, self.ws, ep.agent_environment(full, {}, self.ws), self.logs.append)
        (home / "settings.json").write_text("{}")
        self.assertIsNone(ep._write_policy_files(full, None, self.ws, self.repo, self.logs.append, home))
        self.assertFalse((home / "settings.json").exists())  # a stale one does not restrict a FULL agent
        ro = ep.parse_spec(spec(agent="CLAUDE_CODE", agentHome="reviewer", access={"mode": "READ_ONLY"}))
        env = ep.agent_environment(ro, {}, self.ws)
        files = ep.prepare_agent_files(ro, self.ws, env, self.logs.append)
        with mock.patch.object(ep, "_claude_help", return_value="--settings"):
            cmd, _, _ = ep._agent_invocation(self.run_state(), ro, self.repo, "p", env, None, files)
        self.assertEqual(cmd[cmd.index("--permission-mode") + 1], "dontAsk")
        perms = json.loads((home / "settings.json").read_text(encoding="utf-8"))["permissions"]
        self.assertIn("Edit", perms["deny"])
        self.assertIn("WebFetch", perms["allow"])
        self.assertIn("Bash", perms["allow"])
        with mock.patch.object(ep, "_claude_help", return_value=""):
            cmd, _, _ = ep._agent_invocation(self.run_state(), full, self.repo, "p", ep.agent_environment(full, {}, self.ws), None, files)
        self.assertIn("--dangerously-skip-permissions", cmd)

    def test_skills_are_rewritten_per_job_and_homes_are_separate(self):
        first = ep.parse_spec(spec(agentHome="writer", skills=SKILLS))
        ep.prepare_agent_files(first, self.ws, {}, self.logs.append)
        second = ep.parse_spec(spec(agentHome="writer", skills=SKILLS[1:]))
        ep.prepare_agent_files(second, self.ws, {}, self.logs.append)
        skills = self.ws / ".agents" / "writer" / "skills"
        self.assertEqual(sorted(p.name for p in skills.iterdir()), ["spec-writing"])
        other = ep.parse_spec(spec(agentHome="reviewer", agent="CLAUDE_CODE"))
        ep.prepare_agent_files(other, self.ws, ep.agent_environment(other, {}, self.ws), self.logs.append)
        self.assertFalse((self.ws / ".agents" / "reviewer" / "skills").exists())
        self.assertTrue(skills.is_dir())  # another agent's home is not touched
        ep.prepare_agent_files(ep.parse_spec(spec(agentHome="writer")), self.ws, {}, self.logs.append)
        self.assertFalse(skills.exists())

    def test_prompt_jobs_have_no_home(self):
        job = ep.parse_spec(spec(mode="PROMPT", agent="CLAUDE_CODE", agentHome="reviewer"))
        self.assertIsNone(ep.agent_home_path(job, self.ws))
        self.assertEqual(ep.agent_environment(job, {}, self.ws)["CLAUDE_CONFIG_DIR"], str(self.ws / ".claude-config"))

    @unittest.skipIf(os.name == "nt", "symlinks need privileges on Windows")
    def test_never_writes_through_a_planted_symlink(self):
        outside = self.ws / "outside"
        outside.mkdir()
        (self.ws / ".agents").symlink_to(outside, target_is_directory=True)
        job = ep.parse_spec(spec(agentHome="writer", instructions=DOC, skills=SKILLS))
        ep.prepare_agent_files(job, self.ws, {}, self.logs.append)
        self.assertFalse((self.ws / ".agents").is_symlink())
        self.assertEqual(list(outside.iterdir()), [])
        home = self.ws / ".agents" / "writer"
        shutil.rmtree(home / "skills")
        (home / "skills").symlink_to(outside, target_is_directory=True)
        victim = self.ws / "victim.md"
        victim.write_text("keep")
        (home / "AGENTS.md").unlink()
        (home / "AGENTS.md").symlink_to(victim)
        ep.prepare_agent_files(job, self.ws, {}, self.logs.append)
        self.assertEqual(victim.read_text(), "keep")
        self.assertEqual(list(outside.iterdir()), [])
        self.assertFalse((home / "skills").is_symlink())


class SessionTests(unittest.TestCase):
    def test_opencode_data_directory_per_role_in_the_home(self):
        with tempfile.TemporaryDirectory() as tmp:
            ws = Path(tmp)
            job = ep.parse_spec(spec(agentHome="writer", workspace={"name": WS_NAME, "key": "run", "sessionRole": "writer"}))
            session = ep.agent_session(job, ws, ep.agent_environment(job, {}, ws))
            self.assertEqual(session.data_dir, ws / ".agents" / "writer" / "data" / "writer")
            self.assertFalse(session.resume)
            (session.data_dir / "opencode").mkdir(parents=True)
            self.assertTrue(ep.agent_session(job, ws, {}).resume)
            old = ep.parse_spec(spec(workspace={"name": WS_NAME, "key": "run", "sessionRole": "writer"}))
            self.assertEqual(ep.agent_session(old, ws, {}).data_dir, ws / ".bo" / "opencode" / "writer")

    def test_claude_session_found_in_the_home(self):
        with tempfile.TemporaryDirectory() as tmp:
            ws = Path(tmp)
            job = ep.parse_spec(spec(agent="CLAUDE_CODE", agentHome="writer",
                                     workspace={"name": WS_NAME, "key": "run", "sessionRole": "writer"}))
            env = ep.agent_environment(job, {}, ws)
            session = ep.agent_session(job, ws, env)
            self.assertFalse(session.resume)
            transcript = ws / ".agents" / "writer" / "projects" / "-workspace-app" / f"{session.session_id}.jsonl"
            transcript.parent.mkdir(parents=True)
            transcript.write_text("{}\n")
            self.assertTrue(ep.agent_session(job, ws, env).resume)
            other = ep.parse_spec(spec(agent="CLAUDE_CODE", agentHome="reviewer",
                                       workspace={"name": WS_NAME, "key": "run", "sessionRole": "writer"}))
            self.assertFalse(ep.agent_session(other, ws, ep.agent_environment(other, {}, ws)).resume)


class QuestionsTests(unittest.TestCase):
    def test_named_ephemeral_artefact(self):
        self.assertEqual(ep.artifact_name_for("agent-questions.json", set()), "agent-questions")
        self.assertEqual(ep.media_type_for("agent-questions.json"), ep.JSON_MEDIA_TYPE)
        self.assertIn("agent-questions.json", ep.EPHEMERAL_ARTIFACT_FILES)

    def test_stale_questions_are_removed_only_when_collected(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            logs = []
            (work / "agent-questions.json").write_text("{}")
            (work / "structured-answer.json").write_text("{}")
            ep.remove_stale_answers(work, ["spec.md"], logs.append)
            self.assertTrue((work / "agent-questions.json").exists())
            ep.remove_stale_answers(work, ["/agent-questions.json"], logs.append)
            self.assertFalse((work / "agent-questions.json").exists())
            self.assertTrue((work / "structured-answer.json").exists())
            self.assertEqual(logs, ["[agent] removed agent-questions.json of an earlier step"])

    def test_questions_are_counted_only_when_collected_and_well_formed(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            logs = []
            collect = ["agent-questions.json"]
            self.assertEqual(ep.count_agent_questions(work, collect, logs.append), 0)  # missing
            target = work / "agent-questions.json"
            target.write_text('{"questions": [{"question": "Which API?", "why": "two exist"}, {"question": " "}, "x", {"question": "Scope?"}]}')
            self.assertEqual(ep.count_agent_questions(work, collect, logs.append), 2)
            self.assertEqual(ep.count_agent_questions(work, ["spec.md"], logs.append), 0)  # not collected
            target.write_text('{"questions": []}')
            self.assertEqual(ep.count_agent_questions(work, collect, logs.append), 0)
            target.write_text("not json")
            self.assertEqual(ep.count_agent_questions(work, collect, logs.append), 0)
            self.assertTrue(any("is not a JSON object" in l for l in logs), logs)

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
            (work / "agent-questions.json").write_text('{"questions": []}')
            g = ep.Git(work, make_git_env(), lambda m: None)
            g.configure_identity("Back Office Agent", "agent@backoffice.local")
            g.commit_all("SHOP-1: feature", keep_out=ep.EPHEMERAL_ARTIFACT_FILES)
            self.assertEqual(git(work, "show", "--name-only", "--format=", "HEAD").split(), ["Feature.kt"])


@unittest.skipUnless(HAS_GIT, "git not installed")
class SnapshotRevertTests(unittest.TestCase):
    """Snapshots and reverts on real temporary repositories."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.repo = Path(self.tmp.name) / "repo"
        self.repo.mkdir()
        git(self.repo, "init", "-q", "-b", "main")
        (self.repo / "README.md").write_text("readme\n")
        (self.repo / "src").mkdir()
        (self.repo / "src" / "A.kt").write_text("class A\n")
        (self.repo / "docs").mkdir()
        (self.repo / "docs" / "guide.md").write_text("guide\n")
        (self.repo / ".gitignore").write_text("build/\n")
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-q", "-m", "init")
        self.logs = []
        self.git = ep.Git(self.repo, make_git_env(), self.logs.append)

    def tearDown(self):
        self.tmp.cleanup()

    def snapshot(self, access: ep.AgentAccess, key=None) -> ep.AccessRepo:
        r = ep.AccessRepo(key, self.git)
        ep.snapshot_access([r], access, self.logs.append)
        return r

    def read(self, rel: str) -> str:
        return (self.repo / rel).read_text()

    def test_snapshot_does_not_touch_the_real_index_and_is_stable(self):
        (self.repo / "src" / "A.kt").write_text("class A2\n")  # uncommitted work of an earlier step
        (self.repo / "notes.txt").write_text("untracked\n")
        index = (self.repo / ".git" / "index").read_bytes()
        status = git(self.repo, "status", "--porcelain")
        first = self.git.snapshot_tree()
        self.assertEqual(first, self.git.snapshot_tree())
        self.assertEqual((self.repo / ".git" / "index").read_bytes(), index)
        self.assertEqual(git(self.repo, "status", "--porcelain"), status)
        self.assertIn("notes.txt", git(self.repo, "ls-tree", "-r", "--name-only", first).split())
        self.assertNotEqual(first, git(self.repo, "rev-parse", "HEAD^{tree}").strip())

    def test_read_only_reverts_every_kind_of_change_to_the_snapshot(self):
        (self.repo / "src" / "A.kt").write_text("class A2\n")  # earlier step's uncommitted work: the baseline
        (self.repo / "earlier.txt").write_text("earlier\n")  # untracked, from an earlier step
        (self.repo / "build").mkdir()
        (self.repo / "build" / "out.bin").write_text("old")
        r = self.snapshot(ep.AgentAccess("READ_ONLY"))
        self.assertTrue(any(l.startswith("[access] repository: work tree before the agent = tree ") for l in self.logs), self.logs)
        # the agent (any tool, shell included)
        (self.repo / "src" / "A.kt").write_text("class Hacked\n")
        (self.repo / "README.md").unlink()
        (self.repo / "earlier.txt").unlink()
        (self.repo / "new" / "deep").mkdir(parents=True)
        (self.repo / "new" / "deep" / "x.kt").write_text("x\n")
        (self.repo / "docs" / "guide.md").write_text("changed\n")
        (self.repo / "build" / "out.bin").write_text("new")  # ignored: not part of any change
        reverted = ep.enforce_access([r], ep.AgentAccess("READ_ONLY"), self.logs.append)
        self.assertEqual(self.read("src/A.kt"), "class A2\n")
        self.assertEqual(self.read("README.md"), "readme\n")
        self.assertEqual(self.read("earlier.txt"), "earlier\n")
        self.assertEqual(self.read("docs/guide.md"), "guide\n")
        self.assertFalse((self.repo / "new").exists())
        self.assertEqual(self.read("build/out.bin"), "new")
        self.assertEqual(sorted((e["path"], e["change"]) for e in reverted), [
            ("README.md", "deleted"), ("docs/guide.md", "modified"), ("earlier.txt", "deleted"),
            ("new/deep/x.kt", "added"), ("src/A.kt", "modified")])
        self.assertTrue(all(e["repository"] is None for e in reverted))
        self.assertIn("[access] reverted repository:new/deep/x.kt (added; outside the agent's access)", self.logs)
        self.assertEqual(self.git.snapshot_tree(), r.tree)
        # the real index still knows the earlier step's state: nothing staged
        self.assertEqual(git(self.repo, "diff", "--cached", "--name-only"), "")

    def test_paths_keep_allowed_changes(self):
        access = ep.AgentAccess("PATHS", ["docs/**", "CHANGELOG.md"])
        r = self.snapshot(access, "app")
        (self.repo / "docs" / "guide.md").write_text("better guide\n")
        (self.repo / "docs" / "adr").mkdir()
        (self.repo / "docs" / "adr" / "0001.md").write_text("adr\n")
        (self.repo / "CHANGELOG.md").write_text("changes\n")
        (self.repo / "src" / "A.kt").write_text("class Hacked\n")
        (self.repo / "src" / "B.kt").write_text("class B\n")
        reverted = ep.enforce_access([r], access, self.logs.append)
        self.assertEqual(self.read("docs/guide.md"), "better guide\n")
        self.assertEqual(self.read("docs/adr/0001.md"), "adr\n")
        self.assertEqual(self.read("CHANGELOG.md"), "changes\n")
        self.assertEqual(self.read("src/A.kt"), "class A\n")
        self.assertFalse((self.repo / "src" / "B.kt").exists())
        self.assertTrue((self.repo / "src").is_dir())  # still holds A.kt
        self.assertEqual(sorted(e["path"] for e in reverted), ["src/A.kt", "src/B.kt"])
        self.assertEqual({e["repository"] for e in reverted}, {"app"})

    def test_no_change_outside_the_access(self):
        access = ep.AgentAccess("PATHS", ["docs/**"])
        r = self.snapshot(access)
        (self.repo / "docs" / "guide.md").write_text("better guide\n")
        self.assertEqual(ep.enforce_access([r], access, self.logs.append), [])
        self.assertIn("[access] repository: no change outside the agent's access", self.logs)

    def test_a_gitignore_entry_cannot_hide_a_new_file(self):
        access = ep.AgentAccess("READ_ONLY")
        r = self.snapshot(access)
        (self.repo / "evil.txt").write_text("evil\n")
        with (self.repo / ".gitignore").open("a") as fh:
            fh.write("evil.txt\n")
        reverted = ep.enforce_access([r], access, self.logs.append)
        self.assertFalse((self.repo / "evil.txt").exists())
        self.assertEqual(self.read(".gitignore"), "build/\n")
        self.assertEqual(sorted(e["path"] for e in reverted), [".gitignore", "evil.txt"])

    def test_info_exclude_and_the_users_ignore_file_cannot_hide_a_new_file(self):
        access = ep.AgentAccess("READ_ONLY")
        xdg = Path(self.tmp.name) / "xdg"
        (xdg / "git").mkdir(parents=True)
        self.git.env["XDG_CONFIG_HOME"] = str(xdg)
        r = self.snapshot(access)
        (self.repo / ".git" / "info").mkdir(exist_ok=True)
        (self.repo / ".git" / "info" / "exclude").write_text("hidden-by-exclude.txt\n")
        (self.repo / "hidden-by-exclude.txt").write_text("x\n")
        (xdg / "git" / "ignore").write_text("hidden-by-user.txt\n")
        (self.repo / "hidden-by-user.txt").write_text("x\n")
        reverted = ep.enforce_access([r], access, self.logs.append)
        self.assertFalse((self.repo / "hidden-by-exclude.txt").exists())
        self.assertFalse((self.repo / "hidden-by-user.txt").exists())
        self.assertEqual(sorted(e["path"] for e in reverted), ["hidden-by-exclude.txt", "hidden-by-user.txt"])
        self.assertIn("[access] repository: restored .git/info/exclude (the agent changed it)", self.logs)

    @unittest.skipIf(os.name == "nt", "file modes and symlinks need a POSIX file system")
    def test_mode_changes_and_symlinks_are_reverted(self):
        access = ep.AgentAccess("READ_ONLY")
        r = self.snapshot(access)
        os.chmod(self.repo / "src" / "A.kt", 0o755)
        shutil.rmtree(self.repo / "docs")
        (self.repo / "docs").symlink_to("/etc", target_is_directory=True)
        ep.enforce_access([r], access, self.logs.append)
        self.assertFalse(os.access(self.repo / "src" / "A.kt", os.X_OK))
        self.assertFalse((self.repo / "docs").is_symlink())
        self.assertEqual(self.read("docs/guide.md"), "guide\n")

    def test_snapshot_failure_fails_before_the_agent(self):
        broken = ep.AccessRepo("x", ep.Git(Path(self.tmp.name) / "not-a-repo", make_git_env(), self.logs.append))
        (Path(self.tmp.name) / "not-a-repo").mkdir()
        with mock.patch.dict(os.environ, {"GIT_CEILING_DIRECTORIES": self.tmp.name}):
            broken.git.env["GIT_CEILING_DIRECTORIES"] = self.tmp.name
            with self.assertRaises(RuntimeError):
                ep.snapshot_access([broken], ep.AgentAccess("READ_ONLY"), self.logs.append)

    def test_summary_line(self):
        self.assertIsNone(ep.access_summary_line({"mode": "READ_ONLY", "reverted": []}))
        line = ep.access_summary_line({"mode": "PATHS", "reverted": [{"repository": "app", "path": "src/A.kt", "change": "modified"},
                                                                     {"repository": None, "path": "x", "change": "added"}]})
        self.assertEqual(line, "[Back Office runner] reverted 2 change(s) outside the agent's access (PATHS): app:src/A.kt, x")


@unittest.skipUnless(HAS_GIT, "git not installed")
class MainFlowTests(unittest.TestCase):
    """In-process main() with the STUB agent: homes, no skills in repositories, access enforced end to end, questions."""

    def stub(self, code: str) -> str:
        return f'"{sys.executable}" -c "{code}"'

    def run_main(self, tmp: Path, job: dict, tokens: dict = None) -> tuple:
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
            "RUNNER_GIT_TOKEN_DIR": str(token_dir), "HOME": str(tmp / "home"), "TMPDIR": str(tmp), "RUNNER_SPEC": b64(job),
        })
        out = io.StringIO()
        with mock.patch.dict(os.environ, env, clear=True), mock.patch.object(ep.sys, "stdout", out):
            code = ep.main()
        self.assertEqual(code, 0, out.getvalue())
        return json.loads((tmp / "result.json").read_text(encoding="utf-8")), out.getvalue()

    def job(self, prompt: str, **kw) -> dict:
        return dict({"mode": "IMPLEMENT", "agent": "STUB", "model": {"provider": "ANTHROPIC", "modelId": "m"}, "prompt": prompt,
                     "timeoutSeconds": 600}, **kw)

    def repo(self, origin: Path, access: str = "PUSH_MR", **kw) -> dict:
        return dict({"key": "app", "url": str(origin), "baseBranch": "main", "access": access, "path": "app", "primary": True,
                     "pushBranch": "feature/docs"}, **kw)

    def test_skills_go_to_the_home_not_into_the_repository(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
            tmp_path = Path(tmp)
            origin = make_origin(tmp_path, "app")
            job = self.job(self.stub("print(1)"), repositories=[self.repo(origin, "READ")], skills=SKILLS, agentHome="writer",
                           workspace={"name": WS_NAME, "key": "run"})
            result, stdout = self.run_main(tmp_path, job)
            self.assertEqual(result["status"], "SUCCEEDED", result)
            app = tmp_path / "ws" / "app"
            self.assertFalse((app / ".claude").exists())
            self.assertFalse((app / ".opencode").exists())
            self.assertNotIn("skills", (app / ".git" / "info" / "exclude").read_text())
            self.assertTrue((tmp_path / "ws" / ".agents" / "writer" / "skills" / "adr-format" / "SKILL.md").is_file())
            self.assertIn("[skills] 2 skill(s) go to the agent home, not into the repository", stdout)
            self.assertNotIn("access", result)  # FULL: no access report

    def test_older_back_office_still_gets_skills_in_the_repository(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
            tmp_path = Path(tmp)
            origin = make_origin(tmp_path, "app")
            result, _ = self.run_main(tmp_path, self.job(self.stub("print(1)"), repositories=[self.repo(origin, "READ")], skills=SKILLS))
            self.assertEqual(result["status"], "SUCCEEDED", result)
            self.assertTrue((tmp_path / "ws" / "app" / ".claude" / "skills" / "adr-format" / "SKILL.md").is_file())
            self.assertFalse((tmp_path / "ws" / ".agents").exists())

    def test_read_only_agent_changes_are_reverted_and_reported_and_questions_collected(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
            tmp_path = Path(tmp)
            origin = make_origin(tmp_path, "app")
            code = ("import pathlib, os; pathlib.Path('README.md').write_text('hacked'); pathlib.Path('evil.sh').write_text('x');"
                    " pathlib.Path('agent-questions.json').write_text('{\\\"questions\\\": [{\\\"question\\\": \\\"Which API?\\\"}]}')")
            job = self.job(self.stub(code), repositories=[self.repo(origin, "READ")], agentHome="reviewer",
                           access={"mode": "READ_ONLY"}, collectArtifacts=["agent-questions.json"],
                           workspace={"name": WS_NAME, "key": "run"})
            result, stdout = self.run_main(tmp_path, job)
            self.assertEqual(result["status"], "SUCCEEDED", result)
            app = tmp_path / "ws" / "app"
            self.assertEqual((app / "README.md").read_text(), "# app\n")
            self.assertFalse((app / "evil.sh").exists())
            self.assertFalse((app / "agent-questions.json").exists())  # reverted after it was collected
            questions = [a for a in result["artifacts"] if a["name"] == "agent-questions"]
            self.assertEqual(len(questions), 1)
            self.assertEqual(json.loads(questions[0]["content"])["questions"][0]["question"], "Which API?")
            self.assertEqual(result["access"]["mode"], "READ_ONLY")
            self.assertEqual(sorted((e["repository"], e["path"], e["change"]) for e in result["access"]["reverted"]), [
                ("app", "README.md", "modified"), ("app", "agent-questions.json", "added"), ("app", "evil.sh", "added")])
            self.assertIn("[Back Office runner] reverted 3 change(s) outside the agent's access (READ_ONLY)", result["summary"])
            self.assertIn("[access] reverted app:evil.sh (added; outside the agent's access)", stdout)
            self.assertEqual(git(app, "status", "--porcelain"), "")

    def test_paths_agent_pushes_only_what_it_may_change(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
            tmp_path = Path(tmp)
            origin = make_origin(tmp_path, "app")
            code = ("import pathlib; pathlib.Path('docs').mkdir(); pathlib.Path('docs/guide.md').write_text('guide');"
                    " pathlib.Path('README.md').write_text('hacked'); pathlib.Path('Main.kt').write_text('x')")
            job = self.job(self.stub(code), repositories=[self.repo(origin)], agentHome="doc-writer",
                           access={"mode": "PATHS", "paths": ["docs/**"]}, runId=RUN_ID)
            result, _ = self.run_main(tmp_path, job, {"app": "tok"})
            self.assertEqual(result["status"], "SUCCEEDED", result)
            files = git(origin, "ls-tree", "-r", "--name-only", "feature/docs").split()
            self.assertEqual(sorted(files), ["README.md", "docs/guide.md"])
            self.assertEqual(git(origin, "show", "feature/docs:README.md"), "# app\n")
            self.assertEqual(sorted(e["path"] for e in result["access"]["reverted"]), ["Main.kt", "README.md"])
            self.assertEqual(result["access"]["paths"], ["docs/**"])

    def test_legacy_single_repository_spec_is_held_to_the_access(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
            tmp_path = Path(tmp)
            origin = make_origin(tmp_path, "app")
            code = "import pathlib; pathlib.Path('README.md').write_text('hacked')"
            job = self.job(self.stub(code), repository={"url": str(origin), "baseRef": "main"}, access={"mode": "READ_ONLY"})
            result, _ = self.run_main(tmp_path, job)
            self.assertEqual(result["status"], "SUCCEEDED", result)
            self.assertEqual((tmp_path / "ws" / "repo" / "README.md").read_text(), "# app\n")
            self.assertEqual([(e["repository"], e["path"]) for e in result["access"]["reverted"]], [(None, "README.md")])

    QUESTIONS_CODE = ("import pathlib; pathlib.Path('Main.kt').write_text('half done');"
                      " pathlib.Path('agent-questions.json').write_text('{\\\"questions\\\": [{\\\"question\\\": \\\"Which API?\\\"},"
                      " {\\\"question\\\": \\\"Scope?\\\"}]}')")

    def test_questions_skip_verification_commit_and_push(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
            tmp_path = Path(tmp)
            origin = make_origin(tmp_path, "app")
            repo = self.repo(origin, mergeRequest={"create": True, "title": "t", "targetBranch": "main"})
            job = self.job(self.stub(self.QUESTIONS_CODE), repositories=[repo], verifyCommand="exit 1", runId=RUN_ID,
                           collectArtifacts=["agent-questions.json"], workspace={"name": WS_NAME, "key": "run"})
            result, stdout = self.run_main(tmp_path, job, {"app": "tok"})
            self.assertEqual(result["status"], "SUCCEEDED", result)
            self.assertIsNone(result["verification"])
            self.assertIsNone(result["mergeRequestUrl"])
            self.assertFalse(result["repositories"][0]["pushed"])
            self.assertEqual(git(origin, "branch", "--list", "feature/docs"), "")
            app = tmp_path / "ws" / "app"
            self.assertEqual((app / "Main.kt").read_text(), "half done")  # left for the continued session
            self.assertEqual(git(app, "log", "--format=%s"), "init\n")
            self.assertEqual(result["repositories"][0]["commitSha"], git(app, "rev-parse", "HEAD").strip())
            self.assertEqual(len([a for a in result["artifacts"] if a["name"] == "agent-questions"]), 1)
            self.assertIn("[Back Office runner] the agent asked 2 question(s); verification, commit and push skipped", result["summary"])
            self.assertNotIn("[verify]", stdout)

    def test_empty_questions_do_not_stop_the_step(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
            tmp_path = Path(tmp)
            origin = make_origin(tmp_path, "app")
            code = ("import pathlib; pathlib.Path('Main.kt').write_text('x');"
                    " pathlib.Path('agent-questions.json').write_text('{\\\"questions\\\": []}')")
            job = self.job(self.stub(code), repositories=[self.repo(origin)], runId=RUN_ID, collectArtifacts=["agent-questions.json"])
            result, _ = self.run_main(tmp_path, job, {"app": "tok"})
            self.assertEqual(result["status"], "SUCCEEDED", result)
            self.assertTrue(result["repositories"][0]["pushed"])
            self.assertNotIn("question(s)", result["summary"] or "")
            self.assertNotIn("agent-questions.json", git(origin, "ls-tree", "--name-only", "feature/docs").split())

    def test_questions_on_a_legacy_spec_with_access_still_revert(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
            tmp_path = Path(tmp)
            origin = make_origin(tmp_path, "app")
            code = self.QUESTIONS_CODE + "; pathlib.Path('docs').mkdir(); pathlib.Path('docs/a.md').write_text('a')"
            job = self.job(self.stub(code), repository={"url": str(origin), "baseRef": "main", "branch": "feature/q", "push": True},
                           gitAccess="PUSH_MR", access={"mode": "PATHS", "paths": ["docs/**"]}, verifyCommand="exit 1",
                           collectArtifacts=["agent-questions.json"])
            result, _ = self.run_main(tmp_path, job)
            self.assertEqual(result["status"], "SUCCEEDED", result)
            self.assertIsNone(result["verification"])
            self.assertEqual(git(origin, "branch", "--list", "feature/q"), "")
            repo = tmp_path / "ws" / "repo"
            self.assertFalse((repo / "Main.kt").exists())  # outside the access: reverted even when asking
            self.assertEqual((repo / "docs" / "a.md").read_text(), "a")
            self.assertEqual(sorted(e["path"] for e in result["access"]["reverted"]), ["Main.kt", "agent-questions.json"])
            self.assertIn("asked 2 question(s)", result["summary"])
            self.assertEqual(len([a for a in result["artifacts"] if a["name"] == "agent-questions"]), 1)

    def test_restricted_access_without_repositories(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
            tmp_path = Path(tmp)
            result, stdout = self.run_main(tmp_path, self.job(self.stub("print(1)"), access={"mode": "READ_ONLY"},
                                                               workspace={"name": WS_NAME, "key": "run"}))
            self.assertEqual(result["status"], "SUCCEEDED", result)
            self.assertEqual(result["access"], {"mode": "READ_ONLY", "paths": [], "reverted": []})
            self.assertIn("[access] READ_ONLY: no repository in this job, nothing to hold to the access", stdout)


if __name__ == "__main__":
    unittest.main()
