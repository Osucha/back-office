#!/usr/bin/env python3
"""Fails when a Trivy suppression is expired, has no expiry date or no statement (guardrail GR-S2).

Usage: python3 ci/check-trivy-ignore.py [ci/trivy-ignore.yaml] [--today YYYY-MM-DD]

Dependency-free on purpose (runs on python:3.12-slim without PyYAML): it understands the flat list
format documented at the top of ci/trivy-ignore.yaml - entries start with `- id:` under a top-level
section (vulnerabilities, misconfigurations, secrets, licenses) and carry `statement:` and `expired_at:`.
Exit codes: 0 all entries valid, 1 at least one invalid entry, 2 usage or parse error.
"""
import datetime
import re
import sys

SECTIONS = ("vulnerabilities", "misconfigurations", "secrets", "licenses")
BLOCK_SCALAR_MARKERS = (">", ">-", "|", "|-", "")
# Linear patterns (no `\s*` directly before `.*`): the value is stripped by _field_value.
TOP_LEVEL_RE = re.compile(r"^([a-z_]+):")
ITEM_RE = re.compile(r"^\s*-\s+id:\s*(\S+)")
FIELD_RE = re.compile(r"^\s+(statement|expired_at):(.*)$")


def _strip_comment(raw):
    """The line without a trailing ` # comment`; "" for a full-line comment."""
    if raw.lstrip().startswith("#"):
        return ""
    return raw.split(" #", 1)[0].rstrip()


def _field_value(value):
    # folded / literal block scalars (">-", "|") count as present when followed by text
    value = value.strip()
    return value if value not in BLOCK_SCALAR_MARKERS else "<block>"


def _apply_entry_line(line, number, section, current, entries):
    """`- id:` starts a new entry of `section`; `statement:` / `expired_at:` fill the current entry.
    Returns the (possibly new) current entry."""
    item = ITEM_RE.match(line)
    if item and section:
        current = {"id": item.group(1).strip("\"'"), "section": section, "line": number}
        entries.append(current)
        return current
    field = FIELD_RE.match(line)
    if field and current is not None:
        current[field.group(1)] = _field_value(field.group(2))
    return current


def parse(text):
    entries, section, current = [], None, None
    for number, raw in enumerate(text.splitlines(), start=1):
        line = _strip_comment(raw)
        if not line.strip():
            continue
        top = TOP_LEVEL_RE.match(line)
        if top:
            section = top.group(1) if top.group(1) in SECTIONS else None
            current = None
            continue
        current = _apply_entry_line(line, number, section, current, entries)
    return entries


def _parse_args(argv):
    """(path, today) from `[path] [--today YYYY-MM-DD]`."""
    path, today = "ci/trivy-ignore.yaml", datetime.date.today()
    args = list(argv)
    if "--today" in args:
        i = args.index("--today")
        today = datetime.date.fromisoformat(args[i + 1])
        del args[i:i + 2]
    if args:
        path = args[0]
    return path, today


def entry_problems(entry, path, today):
    """Problems of one suppression entry (missing statement, missing/invalid/past expiry)."""
    where = f"{path}:{entry['line']} {entry['section']} {entry['id']}"
    problems = []
    if not entry.get("statement"):
        problems.append(f"{where}: missing statement (why it is suppressed, who decided)")
    expiry = entry.get("expired_at")
    if not expiry:
        problems.append(f"{where}: missing expired_at (every suppression must expire)")
        return problems
    try:
        date = datetime.date.fromisoformat(expiry.strip("\"'"))
    except ValueError:
        problems.append(f"{where}: expired_at '{expiry}' is not YYYY-MM-DD")
        return problems
    if date < today:
        problems.append(f"{where}: expired on {date} - fix the finding or renew the entry with a new decision")
    return problems


def main(argv):
    path, today = _parse_args(argv)
    try:
        entries = parse(open(path, encoding="utf-8").read())
    except (OSError, ValueError) as error:
        print(f"ERROR cannot read {path}: {error}")
        return 2
    problems = []
    for e in entries:
        problems.extend(entry_problems(e, path, today))
    for p in problems:
        print("FAIL " + p)
    print(f"{len(entries)} suppression(s) checked against {today}, {len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
