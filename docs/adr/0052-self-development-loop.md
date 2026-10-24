# 0052 — Back Office develops itself: pipeline in the loop, workflows that start workflows, a watcher and a session

- Status: accepted (steps 1 to 4 implemented)
- Date: 2026-10-23
- Deciders: owner
- Related: ADR 0029 (prompt injection), 0033 (approval before write), 0044 (run workspaces), 0049 (MCP), 0050
  (model availability), 0051 (SCM connections, agent tools); docs/manual/workflows.md

## Context

The first self-development run ("fix the CI pipeline on main") succeeded by its own measure and left CI red. Its
definition of done was a green work log and an approving AI review; nothing looked at the pipeline after the push.
Fixing CI peels an onion (each fix uncovers the next failure) and its target moves (a new advisory appeared during the
run), so it needs the pipeline in the loop. The plan also asked for checks the sandbox cannot run (Testcontainers
without Docker), which cost attempts, and the owner had to come to a chat outside Back Office to understand what was
going on and to ask questions.

## Decision

1. **The pipeline is part of the loop.** A `WAIT_FOR_CI` node with `watchPipeline` is resumed by Back Office itself: a
   sweep (every minute, one worker, ShedLock `pipeline-watch-sweep`) reads the latest pipeline of the run's branch
   (the last pushed branch and commit of the watched repository) with the project's CI_READ token (ADR 0051) and
   delivers the node's signal when it finished. A failure carries the failed jobs that may not fail and the last
   lines of their logs; the node passes them to the next step as feedback, so the implementer fixes what CI says. A
   pipeline still running, none yet, a missing token or an unreachable SCM keep the run waiting until the node's
   timeout. Pipelines run on the agent's own branch (its merge request, a draft until green), never on `develop` or
   `main`.
2. **Honest results from the sandbox.** The implementer's work log may end with `RESULT: PARTIAL`: every check the
   sandbox can run passed and the rest (tests that need Docker, image builds, the pipeline) is left to CI, listed under
   "Left to CI". The step succeeds and the workflow continues with the pipeline watch. Every coding prompt carries a
   "Sandbox" section (Docker or not, the hosts the proxy allows, TLS stays on), and the planner writes test commands the
   sandbox can run.
3. **Workflows start workflows (step 2).** A task executor `START_WORKFLOW` (template "Start workflow") starts another
   workflow of the same project, one run per entry of a JSON artefact (an object or an array of objects whose fields
   become run parameters), never itself. It runs only right after a human gate; with `selection: DECISION` only the
   entries whose numbers the owner wrote in the gate's comment are started ("all" starts every one), on behalf of the
   person who decided at the gate (`StartRunOnBehalf`: their `run.start`, their personal model profiles). An executor and
   not a new node kind, so the designer renders it from its config schema.
4. **A watcher of Back Office's own development (step 3).** A trigger `SCHEDULE` (`intervalMinutes`, at least 5)
   starts a workflow every N minutes after its latest run began, never while one is active; the run gets `since` and is started on behalf of whoever last saved the workflow.
   A task executor `PROJECT_DIGEST` writes deterministically what happened since then (runs of the other workflows,
   repeated and failed steps with errors, gate decisions with comments, the latest pipelines of configured branches
   with failed jobs) - the agent gets this artefact, not access to Back Office. The workflow "Back Office: watch the
   development" lets a coding agent (repository read-only, `gitlab-read`) write `retro.md` and, when worthwhile,
   `proposals.json`; a gate offers them and "Start workflow" starts the change workflow for the picked ones. For now
   only the project Back Office itself.
5. **A side session (step 4).** The workflow "Back Office: ask the agent" loops "question in a gate's comment ->
   answer by a coding agent (repository, `gitlab-read`, the project digest) in `answer.md` -> next question"; the run
   workspace keeps one Claude Code session for the whole conversation. Built from existing nodes only.

## Consequences

- A change is done when its pipeline is green, not when the agent says so; the owner's merge gate sees the result.
- The watch polls GitLab once a minute per waiting run (read-only); the token needs CI_READ.
- `RESULT: PARTIAL` must only follow by a pipeline watch; without one a workflow would accept unverified work.
