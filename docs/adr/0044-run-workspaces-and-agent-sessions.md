# 0044 — Run workspaces and resumed agent sessions

- Status: accepted
- Date: 2026-10-17
- Deciders: owner
- Related: ADR 0024 (runner fleet), 0031 (git least privilege), 0034 (step repositories), 0040 (personal runners),
  0043 (sources); docs/architecture/10-agent-execution.md, docs/manual/workflows.md#workspaces

## Context

Every runner step starts from nothing: a new container, a `/workspace` on tmpfs (Docker) or an emptyDir
(Kubernetes), fresh clones of its repositories and a new coding-agent session (`--no-session-persistence`). The only
continuity between steps is the pushed run branch and the artefacts. That has three costs:

- every step clones and installs again (`setupCommand`, dependency caches);
- work that is not committed and pushed (a draft, notes, generated files, a half-done refactor) is lost between steps;
- an agent that comes back to its own work (implement, verify, implement again) re-reads everything, which costs
  tokens and loses what it had understood.

The owner wants several agents to take turns on the same material, for example one implements and another verifies,
each continuing its own session, and wants to prepare that material once.

## Decision

1. **Workspace of a run.** A runner step may name a workspace (`config.workspace`, a key such as `main`). All steps
   of one run that name the same key work in one persistent directory mounted at `/workspace`: a Docker named volume
   (DOCKER backend, also personal runners) or a PersistentVolumeClaim (Kubernetes backends). Its name is derived from
   the run and the key (`bo-ws-<run id>-<key>`). Steps without `workspace` keep today's throwaway workspace.
2. **Pinning and serialisation.** A workspace lives on one runner. The fleet records it (`runner_workspace`, V54) with
   its pool, the runner that first leased one of its jobs, and the job currently using it. A job of a workspace is
   routed to the workspace's pool, leased only by its runner, and only while no other job uses it (claimed with one
   conditional `UPDATE` on the workspace row). Parallel branches that name the same workspace therefore run one after
   the other.
3. **Reuse in the runner.** In a workspace, a repository that the runner itself cloned earlier (a marker under
   `/workspace/.bo`, not the repository's own config) is reused: `.git` is checked, hooks and grafts are removed,
   `.git/config` is rewritten from the spec, then the runner fetches and sets the step's access (push disabled for
   READ, the run branch for PUSH_MR). The working tree is kept: later steps see uncommitted work of earlier ones.
   Other repositories are cloned as before. Commits and pushes stay per step, as in ADR 0031 and ADR 0034.
4. **Prepare node.** The built-in template `prepare-workspace` (executor IMPLEMENT, `config.prepareOnly`) clones the
   step's repositories and sources and runs its `setupCommand` without starting an agent. It is optional; a workspace
   is also created by the first step that names it.
5. **Sessions per role.** A step in a workspace may name a session role (`config.sessionRole`, for example
   `implementer`, `reviewer`). Claude Code then keeps its session in the workspace (`CLAUDE_CONFIG_DIR` under
   `/workspace/.bo`) with a session id derived from the workspace and the role: the first step of the role starts it
   with `--session-id`, later steps continue it with `--resume`. OpenCode keeps one data directory per role and
   continues with `--continue`. Steps without a role, or outside a workspace, start a new session as before. A session
   started by one harness is never resumed by another.
6. **Release.** When a run succeeds or is cancelled its workspaces are released at once. When it fails they are kept
   for 24 hours so a retry continues in them, then released. Each runner-controller reports the workspaces it holds
   every minute (`POST /runner/v2/workspaces/sync`) and deletes the ones Back Office no longer knows or has released.
   A workspace used again after its release starts empty.

## Consequences

- One run's steps share files, caches and agent memory; the prepared state and resumed sessions save time and tokens.
- A workspace is one trust domain: every step of the run, and every role, can read and change what the others left
  there, including the other roles' session transcripts. The runner still restores `.git` before each commit and
  push, and credentials never reach the workspace. For an independent check, use a step without the workspace.
- A workspace job waits for its runner. If that runner is gone, its jobs fail when their queue deadline passes; the
  run can be retried after the workspace is released (it then starts empty, the pushed branch is kept).
- Docker volumes have no size limit of their own; PVCs get the step's `ephemeralStorage` as their size.
- Warm Agent Sandbox pods are not used for workspace jobs (their volume is fixed in the template).
