# 0043 — Sources: attachable material in the library and on the project

- Status: accepted
- Date: 2026-10-17
- Deciders: owner
- Related: ADR 0034 (project repositories, step repositories), 0035 (task library), 0037 (workflow library);
  docs/manual/task-library.md#sources, docs/manual/projects.md#sources, docs/manual/workflows.md#step-repositories

## Context

Agents need more than the code they change: company standards to follow, templates to start from, reference
documentation. Until now the only way to give them such material was to add its repository to every project that
needs it (ADR 0034) and keep these copies in sync by hand. The material is the same for all projects, so it belongs
in the library, next to templates, interfaces, skills and workflows. On the project, the repositories sat in the
Settings form, among unrelated fields, and nothing showed what else a step can attach.

Later steps (not part of this decision) add more kinds of sources and a shared working directory per run in which
several agents take turns; the model here leaves room for them.

## Decision

1. A new library entity **source** (`library_source`, migration V116): key (`^[a-z][a-z0-9-]{1,26}$`, immutable),
   name, description, kind, URL, branch, purpose. The only kind for now is `GIT`. `library.view` reads,
   `library.manage` writes; changes are audited (`LIBRARY_SOURCE_CREATE/UPDATE/DELETE`). REST
   `/api/v1/library/sources` (tag `library-sources`).
2. Project repositories are the project's own sources; we do not introduce a second project-level model. The project
   gets a tab **Sources**: its repositories (moved out of Settings) and, read only, the library sources.
3. A step attaches a library source as an entry of the existing `config.repositories` list:
   `{key, access: READ, library: true}`. Library entries are always read only, never primary unless the step has no
   project repository, and are cloned to `/workspace/lib-<key>` (repository key `lib-<key>`, which cannot clash with
   a project key: the plan reports a clash as an error). The prompt tells the agent to follow them, not change them.
   An unknown library key fails validation, so a workflow that names a deleted source is kept as a draft (ADR 0041).
4. `RepositoryPurpose` gains `STANDARDS` and `TEMPLATES` (used by both project repositories and sources).
5. Publishing a workflow to the workflow library keeps the library entries of its steps; updating an adopted copy
   merges the project's own entries with the published library entries (`LibraryPortability`).
6. In the designer, the step section Repositories lists library sources as checkboxes. Ticking one while the step
   uses the default (the primary repository) switches it to an explicit list starting with the primary, because an
   explicit list replaces the default.

## Consequences

- Shared standards and templates are maintained once and reach every project's agents read only.
- Runners need read credentials for the source repositories, exactly as for project repositories.
- The key is the contract between steps and the source; renaming means a new source and editing the steps.
- Each runner step still clones its sources into a fresh container. Reusing a prepared working directory across
  steps and agent sessions is the next step and gets its own decision.
