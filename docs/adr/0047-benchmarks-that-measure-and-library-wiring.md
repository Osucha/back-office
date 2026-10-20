# 0047 — Benchmarks that measure the work, and a library whose steps fit together

- Status: accepted
- Date: 2026-10-20
- Deciders: owner
- Related: ADR 0019 (evaluation), 0024 (runner fleet), 0036 (Spec Kit per project), 0046 (benchmark execution);
  docs/manual/evaluation.md, docs/manual/task-library.md

## Context

A review of the starter suite and the task library after the first real benchmarks found that the numbers did not
measure what they claimed:

- **Coding tasks passed without doing anything.** The verification was the repository's own test suite, which is
  green before any change; with 60 % weight a no-op scored about 62 and counted as passed. The judge never saw the
  code: it read the agent's own summary, a diff statistic and the repository's README (collected as `*.md`).
- **Prompt-task rubrics hit the ceiling.** They checked presence ("covers X"), only two tasks had a reference answer,
  the judge wrote the 0-100 total itself and ignored its own `passed`.
- **Library steps did not fit together.** The rework loop after an AI review never received the findings; commit
  subjects were the first line of the system prompt; Spec Kit got the whole role and security preamble as the feature
  description (and may write it into `spec.md`); produced artifact names did not match the schema fields
  (`userStory`/`user-story`), so later steps got JSON; feedback from a gate leaked into every later step; built-in
  skills and interfaces were never upgraded.

## Decision

1. **Acceptance checks for coding tasks.** `TaskRepository.acceptance` (evaluation V205) carries hidden `files`
   (acceptance tests, golden files), `protectedPaths` (git pathspecs, e.g. the existing tests) and `requireChanges`.
   They travel in `RunnerJobSpec.acceptance`. After the agent the runner fails the verification for an untouched work
   tree or a changed protected file, writes the hidden files, runs `verifyCommand` and removes them again, so they
   reach neither the agent, the commit nor the diff the judge reads.
2. **The judge reads the change.** For coding tasks the judge message carries the diff (source and tests first,
   capped at 80 000 characters, the changed-file list always), not collected `*.md` files.
3. **Scores computed in code.** The judge writes, per rubric criterion, the evidence first and then 0-10; the overall
   score is the mean × 10. The reference answer is authoritative on facts. Passed = overall ≥ 70 for prompt tasks;
   for coding tasks the verification must pass and the combined score must reach 70 as well. The judge answers within
   3000 output tokens. Temperature is left to the judge profile (reasoning models refuse a fixed one).
4. **Starter content.** Every prompt task has a reference answer with ground truth and a "Penalise" list and
   discriminating rubrics with caps. The five coding tasks run against Spring PetClinic pinned to a commit, with
   precise Java contracts, hidden acceptance tests and golden files. Starter tasks the owner did not edit (content
   hash of the earlier version) are replaced at startup; edited ones stay.
5. **Runner job spec.** `commitSubject` (rendered like the merge request title, `{{issue.key}}: {{issue.summary}}`)
   and `featureDescription` (the step's user prompt only, for Spec Kit) are chosen by Back Office. The newest Spec Kit
   feature directory names the `spec`/`plan`/`tasks` artifacts; `work-log.md` is collected as `work-log` and kept
   out of the commit.
6. **Library wiring.** Template sections `{{#x}}…{{/x}}` / `{{^x}}…{{/x}}`; gate feedback is scoped to the step it
   was given for (`{{feedback}}`, history in `{{feedbackHistory}}`); implement prompts carry the latest review
   findings; produced names are checked against schemas at build time; `review-against-spec` keeps its coverage
   matrix and a MISSING requirement requests changes; built-ins are upgraded by a checksum over all fields when the
   owner did not edit them; the "Change request" default workflow fetches the issue first.

## Consequences

- Benchmark scores of earlier runs are not comparable with new ones (different scoring and tasks).
- Hidden tests must be validated against the base (fail) and a reference solution (pass) when a task is written;
  the PetClinic tasks were.
- Existing projects keep their stored workflows; only new projects get the corrected defaults.
- An acceptance file is visible to anyone who can read the suite (it is hidden from the agent, not from people).
