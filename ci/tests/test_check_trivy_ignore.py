"""Tests for ci/check-trivy-ignore.py (guardrail GR-S2). Run with:

    python3 -m unittest discover -s ci/tests -v
"""

from __future__ import annotations

import contextlib
import datetime
import importlib.util
import io
import os
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "check-trivy-ignore.py"
_spec = importlib.util.spec_from_file_location("check_trivy_ignore", SCRIPT)
cti = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cti)

TODAY = datetime.date(2026, 1, 15)

SAMPLE = """\
# Trivy suppressions
vulnerabilities:
  - id: CVE-2025-0001  # comment after the id
    statement: not reachable, decided by owner
    expired_at: 2026-06-30
  - id: "CVE-2025-0002"
    statement: >-
      folded text
    expired_at: "2025-12-31"
other:
  - id: IGNORED-1
    statement: outside a known section
misconfigurations:
  - id: AVD-KSV-0001
    expired_at: tomorrow
secrets:
  - id: generic-token
"""


class ParseTest(unittest.TestCase):
    def test_parses_entries_of_known_sections_only(self):
        entries = cti.parse(SAMPLE)
        self.assertEqual(["CVE-2025-0001", "CVE-2025-0002", "AVD-KSV-0001", "generic-token"], [e["id"] for e in entries])
        self.assertEqual("vulnerabilities", entries[0]["section"])
        self.assertEqual(3, entries[0]["line"])
        self.assertEqual("not reachable, decided by owner", entries[0]["statement"])
        self.assertEqual("2026-06-30", entries[0]["expired_at"])
        self.assertEqual("<block>", entries[1]["statement"])
        self.assertNotIn("statement", entries[2])

    def test_strip_comment(self):
        self.assertEqual("", cti._strip_comment("  # whole line"))
        self.assertEqual("  - id: X", cti._strip_comment("  - id: X  # why"))
        self.assertEqual("a#b", cti._strip_comment("a#b"))

    def test_field_value_block_scalars(self):
        for marker in (">", ">-", "|", "|-", "   "):
            self.assertEqual("<block>", cti._field_value(marker))
        self.assertEqual("text", cti._field_value("  text "))


class ProblemsTest(unittest.TestCase):
    def entry(self, **kw):
        return {"id": "X", "section": "secrets", "line": 7, **kw}

    def test_valid_entry_has_no_problems(self):
        self.assertEqual([], cti.entry_problems(self.entry(statement="s", expired_at="2026-02-01"), "f.yaml", TODAY))

    def test_missing_statement_and_expiry(self):
        problems = cti.entry_problems(self.entry(), "f.yaml", TODAY)
        self.assertEqual(2, len(problems))
        self.assertTrue(problems[0].startswith("f.yaml:7 secrets X: missing statement"))
        self.assertIn("missing expired_at", problems[1])

    def test_invalid_and_expired_dates(self):
        self.assertIn("is not YYYY-MM-DD", cti.entry_problems(self.entry(statement="s", expired_at="soon"), "f", TODAY)[0])
        self.assertIn("expired on 2026-01-14", cti.entry_problems(self.entry(statement="s", expired_at="'2026-01-14'"), "f", TODAY)[0])

    def test_parse_args(self):
        self.assertEqual(("x.yaml", TODAY), cti._parse_args(["x.yaml", "--today", "2026-01-15"]))
        self.assertEqual(("ci/trivy-ignore.yaml", TODAY), cti._parse_args(["--today", "2026-01-15"]))


class MainTest(unittest.TestCase):
    def run_main(self, text, *args):
        fd, path = tempfile.mkstemp(suffix=".yaml")
        os.close(fd)
        try:
            Path(path).write_text(text, encoding="utf-8")
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = cti.main([path, *args])
            return code, out.getvalue()
        finally:
            os.remove(path)

    def test_exit_codes(self):
        code, out = self.run_main(SAMPLE, "--today", "2026-01-15")
        self.assertEqual(1, code)
        self.assertIn("4 suppression(s) checked against 2026-01-15, 5 problem(s)", out)
        code, out = self.run_main("secrets:\n  - id: a\n    statement: s\n    expired_at: 2030-01-01\n", "--today", "2026-01-15")
        self.assertEqual(0, code)
        self.assertNotIn("FAIL", out)

    def test_unreadable_file(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(2, cti.main([str(Path(tempfile.gettempdir()) / "does-not-exist-trivy.yaml")]))
        self.assertIn("ERROR cannot read", out.getvalue())

    def test_repository_file_is_valid_format(self):
        repo_file = SCRIPT.parent / "trivy-ignore.yaml"
        entries = cti.parse(repo_file.read_text(encoding="utf-8"))
        for e in entries:
            self.assertIn(e["section"], cti.SECTIONS)


if __name__ == "__main__":
    unittest.main()
