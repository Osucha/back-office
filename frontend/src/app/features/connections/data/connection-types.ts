/** Short names for the `connections` schemas of the contract (ADR 0030, ADR 0035). */
import type { components } from '../../../core/api/schema';

type S = components['schemas'];

export type Connection = S['Connection'];
export type ConnectionInput = S['ConnectionInput'];
export type ConnectionSecretInput = S['ConnectionSecretInput'];
export type ConnectionVerification = NonNullable<S['ConnectionVerification']>;
export type ConnectionProjectCheck = S['ConnectionProjectCheck'];
export type ConnectionMessage = S['ConnectionMessage'];
export type ConnectionResolution = S['ConnectionResolution'];
export type ConnectionTestRequest = S['ConnectionTestRequest'];
export type ConnectionScope = S['ConnectionScope'];
/** Known values; the server may add more, so code reading them accepts any string. */
export type ConnectionCapability = S['ConnectionCapability'];
export type JiraPermission = S['JiraPermission'];
/** ADR 0035: classic token (site URL) or scoped token (Atlassian API gateway). */
export type JiraTokenKind = S['JiraTokenKind'];
/** What administrators see of a personal connection (ADR 0035). */
export type PersonalConnectionSummary = S['PersonalConnectionSummary'];
/** The project's system connection and further connections (ADR 0035). */
export type ProjectJiraConnections = S['ProjectJiraConnections'];
export type ProjectJiraConnectionsInput = S['ProjectJiraConnectionsInput'];
export type JiraScopes = S['JiraScopes'];

/** Where a connection lives: the platform registry, one project's, or the caller's own (personal). */
export type ConnectionOwner =
  | { kind: 'platform' }
  | { kind: 'project'; projectId: string }
  | { kind: 'personal' };
