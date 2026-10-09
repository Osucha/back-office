# 0018 - Transactional, write-only audit log with correlation ids

Status: accepted - 2026-09-27

## Context
With many users the owner must be able to answer "who changed this workflow, who approved this gate, who
gave Bob admin rights". The reference project has a strong audit design: a write-only port, entries written
in the business transaction, domain-declared diffs, sanitised details, denied access audited at one choke
point, and reading the audit log audited too.

## Decision
- `AuditPort` in shared-kernel has one method, `record(entry)`. The identity context implements it with a
  JDBC insert into `audit_log` in the caller's transaction (rolled back together with the change).
- Use cases record entries for every mutation; updates record `{field: {old, new}}` produced by
  `AuditableChanges.changed(...)` and skip the entry when nothing changed.
- `ACCESS_DENIED` is recorded by the 403 handler (its own transaction), authentication events by the login,
  logout and session-expiry handlers.
- Details are sanitised (control characters removed, truncated at 16 kB) and encoding never throws.
- A servlet filter at the highest precedence generates a correlation id per request (never taken from the
  client), puts it in MDC and in every audit row, and returns it as `X-Correlation-Id`.
- The admin UI has an audit viewer with filters and keyset pagination; each search is recorded as
  `AUDIT_VIEW`.

## Alternatives
- Hibernate Envers: audits entity versions, not intent, and misses denied access and logins.
- Only application logs: not queryable by entity or user, easy to lose.

## Consequences
- The audit table grows without bound; retention is a documented operational task (partitioning by month is
  prepared by the `(event_time, id)` key).
- Tests assert audit entries with `RecordingAuditPort`.
