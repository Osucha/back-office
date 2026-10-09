/** Audit log filters -> `GET /admin/audit` query (keyset pagination via `cursor`). */
import type { AuditQuery, AuditResult } from '../../../core/api/types';

export interface AuditFilters {
  range: [Date, Date] | null;
  actorId: string | null;
  operation: string;
  entityType: string;
  entityId: string;
  projectId: string | null;
  result: AuditResult | null;
  correlationId: string;
}

export const EMPTY_AUDIT_FILTERS: AuditFilters = {
  range: null,
  actorId: null,
  operation: '',
  entityType: '',
  entityId: '',
  projectId: null,
  result: null,
  correlationId: '',
};

export const AUDIT_PAGE_SIZE = 50;

/** Only set filters are sent; texts are trimmed; the range becomes `from`/`to` (ISO). */
export function toAuditQuery(
  f: AuditFilters,
  cursor: string | null = null,
  size = AUDIT_PAGE_SIZE,
): AuditQuery {
  const q: AuditQuery = { size };
  if (f.range) {
    q.from = f.range[0].toISOString();
    q.to = f.range[1].toISOString();
  }
  if (f.actorId) q.actorId = f.actorId;
  if (f.operation.trim()) q.operation = f.operation.trim();
  if (f.entityType.trim()) q.entityType = f.entityType.trim();
  if (f.entityId.trim()) q.entityId = f.entityId.trim();
  if (f.projectId) q.projectId = f.projectId;
  if (f.result) q.result = f.result;
  if (f.correlationId.trim()) q.correlationId = f.correlationId.trim();
  if (cursor) q.cursor = cursor;
  return q;
}

/** Filters showing every entry of one request (the detail drawer action). */
export function correlationFilters(correlationId: string): AuditFilters {
  return { ...EMPTY_AUDIT_FILTERS, correlationId };
}

/** Operation codes offered as suggestions (free text is allowed too). */
export const KNOWN_OPERATIONS = [
  'LOGIN_SUCCESS',
  'LOGIN_FAILURE',
  'LOGOUT',
  'ACCESS_DENIED',
  'AUDIT_VIEW',
  'USER_INVITE',
  'USER_UPDATE',
  'USER_ACTIVATE',
  'USER_DEACTIVATE',
  'GROUP_CREATE',
  'GROUP_UPDATE',
  'GROUP_DELETE',
  'ROLE_CREATE',
  'ROLE_UPDATE',
  'ROLE_DELETE',
  'GRANT_CHANGE',
  'MEMBER_ADD',
  'MEMBER_ROLE_CHANGE',
  'MEMBER_REMOVE',
  'SETTINGS_UPDATE',
  'TOKEN_CREATE',
  'TOKEN_REVOKE',
  'CI_TOKEN_CREATE',
  'CI_TOKEN_REVOKE',
  'PROJECT_CREATE',
  'PROJECT_UPDATE',
  'PROJECT_DELETE',
  'WORKFLOW_CREATE',
  'WORKFLOW_UPDATE',
  'WORKFLOW_DELETE',
  'RUN_START',
  'RUN_CANCEL',
  'RUN_RETRY',
  'RUN_GATE_DECIDED',
];

/** URL filter values of the audit page (all strings; `from`/`to` ISO). */
export interface AuditUrlValues {
  q: string | null;
  operation: string | null;
  result: string | null;
  actorId: string | null;
  projectId: string | null;
  entityType: string | null;
  correlationId: string | null;
  from: string | null;
  to: string | null;
}

const RESULTS: readonly string[] = ['SUCCESS', 'DENIED', 'FAILURE'];

/** URL values -> filters; the search field is the entity id, unknown results are dropped. */
export function auditFiltersFromUrl(v: AuditUrlValues): AuditFilters {
  const from = v.from ? new Date(v.from) : null;
  const to = v.to ? new Date(v.to) : null;
  const valid = (d: Date | null) => !!d && !Number.isNaN(d.getTime());
  return {
    range: valid(from) && valid(to) ? [from!, to!] : null,
    actorId: v.actorId || null,
    operation: v.operation ?? '',
    entityType: v.entityType ?? '',
    entityId: v.q ?? '',
    projectId: v.projectId || null,
    result: v.result && RESULTS.includes(v.result) ? (v.result as AuditResult) : null,
    correlationId: v.correlationId ?? '',
  };
}
