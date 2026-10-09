# 0036 — Spec Kit is optional, per project, with a platform switch

- Status: accepted
- Date: 2026-10-09
- Deciders: owner
- Related: ADR 0005 (Spec Kit for specification artefacts), 0024 (sandboxed runner fleet), 0034 (optional
  multi-repository projects); docs/manual/projects.md#spec-kit, docs/manual/workflows.md#nodes,
  docs/architecture/10-agent-execution.md

## Context

ADR 0005 made GitHub Spec Kit the way Back Office writes specifications, plans and task lists: `SPEC_KIT` steps run
`specify init` in the runner and call `/speckit.*` commands; every project with a repository got the seeded workflow
**Issue to reviewed MR**, which is built on Spec Kit; the project form always showed a Spec Kit constitution.

Not every team works that way: many projects (and every project without code, ADR 0034) never use Spec Kit, yet the
designer offered its templates, the constitution field suggested it was needed, and the runner initialised Spec Kit
more often than expected. Checking `runner/entrypoint.py` showed that `specify init` ran not only for `SPEC_KIT`
jobs but also for **any job with a constitution** (Back Office sent the project's constitution with every runner job)
and for `IMPLEMENT` jobs on a repository that contained a `specs/**/tasks.md`, whose prompt was then rewritten to
`/speckit.implement`. A project that had never chosen Spec Kit could therefore get a `.specify/` directory in its
workspace.

## Decision

1. **Project setting `specKit`** (`Project.specKit`, column `project.spec_kit_enabled`), **off by default** for new
   projects; changed with the project form (`project.manage`, audited in `PROJECT_UPDATE`).
2. **Platform switch** `PlatformSettings.specKitAllowed` (identity context, column
   `identity_platform_settings.spec_kit_allowed`, default true, `admin.settings.manage`). Other contexts read it
   through the new shared-kernel port `PlatformFeatures`. Spec Kit is **effective** for a project when both are on;
   turning the project setting on while the platform disallows it is refused (`400`, field `specKit`).
3. **When Spec Kit is not effective**:
   - the designer palette hides every template whose resolved executor is `SPEC_KIT` (built-in `spec-kit-*` and
     templates that extend them);
   - the workflow validator reports each `SPEC_KIT` step as an **error** (field `task.templateKey`, pointing to
     `projects#spec-kit`), so such a workflow cannot be saved;
   - starting a run is refused (`409`) when the workflow has a `SPEC_KIT` step; a `SPEC_KIT` step reached anyway
     fails before submitting a job;
   - the constitution field is hidden in the form and not sent to runner jobs; it stays in the database and comes
     back when Spec Kit is turned on again;
   - runner jobs carry **`specKit: false`** (`RunnerJobSpec.specKit`, signed with the spec): the runner then does no
     Spec Kit work at all: no `specify init`, no constitution file, no `/speckit.implement` rewrite even when a
     `tasks.md` exists; a `SPEC_KIT` job with `specKit: false` is refused. `specKit: true` keeps the ADR 0005
     behaviour. Jobs without the field (coding benchmarks, specs stored before this change, older Back Office) keep
     the earlier behaviour, and their canonical JSON and spec digest do not change (null members are omitted).
4. **Seeding**: the SDLC workflows that use Spec Kit (today **Issue to reviewed MR**) are seeded only when Spec Kit is
   effective; the others (**CI code review**, **Change request (delta)**) as before. When a project turns Spec Kit on
   later, the Settings tab shows the missing Spec Kit workflows with **Add Spec Kit workflows**
   (`GET /projects/{id}/spec-kit`, `POST /projects/{id}/spec-kit/workflows`, `workflow.edit`, audited
   `PROJECT_SPEC_KIT_SEEDED`); it never seeds on its own and never overwrites a workflow with the same name.
5. **Migration** (projects `V110`): Spec Kit stays **on** for every existing project that uses it: a workflow node
   whose template is a built-in `spec-kit-*` template, or a library template whose own or inherited executor is
   `SPEC_KIT` (checked only when the task library table exists). Other existing projects get it off; their stored
   constitutions are kept.

## Alternatives considered

- **Keep Spec Kit always on, hide it only in the UI.** Rejected: the runner would still initialise it for jobs with a
  constitution, and validation would accept steps the project does not want.
- **Decide per workflow.** Rejected: the constitution, the `.specify/` directory and the review conventions are per
  repository/project; mixing Spec Kit and non-Spec-Kit workflows in one project gives inconsistent artefacts.
- **Remove Spec Kit.** Rejected: teams that use it keep their workflows; ADR 0005 still holds where it is on.
- **Auto-seed the Spec Kit workflows when the setting is turned on.** Rejected: a project may have its own
  workflows under the same names or not want the seeded one; an explicit button is visible and idempotent.

## Consequences

- New projects start without the Spec Kit workflow and without the constitution field; turning Spec Kit on is one
  switch plus **Add Spec Kit workflows**.
- Existing projects using Spec Kit are unchanged. Existing projects that did not use it stop sending the
  constitution to runner jobs (it is kept and returns with the setting).
- Runner images must be updated to honour `specKit: false`; older runners ignore the field and behave as before
  (Back Office no longer sends a constitution to them for such projects, so only an `IMPLEMENT` job on a repository
  with `tasks.md` could still trigger `specify init` there).
- Contract: `ProjectInput.specKit`, `PlatformSettings.specKitAllowed`, `RunnerJobSpec.specKit`, schema
  `SpecKitStatus` and the two `spec-kit` endpoints.
