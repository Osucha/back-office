-- ADR 0043: sources of the library, attachable read-only to steps of every project. The first kind is a git
-- repository (company standards, templates, reference code). A project's own sources are its repositories (V109).

CREATE TABLE library_source (
    id              uuid PRIMARY KEY,
    key             text        NOT NULL UNIQUE,
    name            text        NOT NULL,
    description     text        NOT NULL DEFAULT '',
    kind            text        NOT NULL DEFAULT 'GIT' CHECK (kind IN ('GIT')),
    url             text        NOT NULL,
    default_branch  text        NOT NULL DEFAULT 'main',
    purpose         text        NOT NULL DEFAULT 'STANDARDS',
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    updated_by_kind text,
    updated_by_id   uuid,
    updated_by_name text
);
