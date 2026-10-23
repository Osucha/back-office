# 0052 — Back Office develops itself: pipeline in the loop, workflows that start workflows, a watcher and a session

- Status: accepted (step 1 implemented; 2 to 4 planned)
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
3. **Workflows start workflows (step 2, planned).** A `START_WORKFLOW` node starts another workflow of the same
   project with parameters taken from an artefact of an earlier step (for example a proposal: title and idea). It is
   always placed behind a human gate: an agent proposes, the owner accepts, Back Office starts.
4. **A watcher of Back Office's own development (step 3, planned).** A scheduled workflow collects what happened since
   its last run - runs and their failures, gate decisions and retries, pipelines and failed jobs - lets an agent write
   a retrospective with concrete improvement proposals, and offers each proposal at a gate whose "do it" option
   starts the change workflow through point 3. For now only the project Back Office itself.
5. **A side session (step 4, planned).** A workflow loop "question at a gate -> answer by an agent with the repository,
   CI (glab) and the run history -> next question" keeps one Claude Code session for the whole conversation.

## Consequences

- A change is done when its pipeline is green, not when the agent says so; the owner's merge gate sees the result.
- The watch polls GitLab once a minute per waiting run (read-only); the token needs CI_READ.
- `RESULT: PARTIAL` must only follow by a pipeline watch; without one a workflow would accept unverified work.
