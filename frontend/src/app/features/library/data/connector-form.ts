/**
 * ADR 0064: the connector editor and the connector choice of an agent as pure functions (tested in
 * connector-form.spec.ts). A connector is a tool agents use: a remote MCP server (MCP_HTTP, with an optional
 * write-only secret) or GitLab read-only (GITLAB_READ, project scope only). A project connector shadows the platform
 * connector of the same key.
 */
import type {
  Connector,
  ConnectorAuth,
  ConnectorInput,
  ConnectorKind,
  Scope,
} from '../../../core/api/types';
import { compareText } from '../../../core/i18n/compare';

/** The key of a connector (as the contract's `ConnectorInput.key`). */
export const CONNECTOR_KEY_PATTERN = /^[a-z][a-z0-9-]{0,39}$/;
/** The name of a custom header (as the contract's `ConnectorInput.headerName`). */
export const HEADER_NAME_PATTERN = /^[A-Za-z0-9-]{1,64}$/;
export const CONNECTOR_URL_MAX = 500;
export const CONNECTOR_NAME_MAX = 200;

/** The authentications the editor offers, in display order. */
export const CONNECTOR_AUTHS: readonly ConnectorAuth[] = ['NONE', 'BEARER', 'HEADER'];

/** The kinds a scope may use: GitLab read-only needs a project's repositories. */
export function connectorKinds(projectScope: boolean): ConnectorKind[] {
  return projectScope ? ['MCP_HTTP', 'GITLAB_READ'] : ['MCP_HTTP'];
}

export function connectorKindLabel(kind: ConnectorKind | null | undefined): string {
  switch (kind) {
    case 'MCP_HTTP':
      return $localize`:@@library.connectors.kind.mcpHttp:MCP server (HTTP)`;
    case 'GITLAB_READ':
      return $localize`:@@library.connectors.kind.gitlabRead:GitLab (read-only)`;
    default:
      return String(kind ?? '');
  }
}

export function connectorAuthLabel(auth: ConnectorAuth | null | undefined): string {
  switch (auth ?? 'NONE') {
    case 'NONE':
      return $localize`:@@library.connectors.auth.none:None`;
    case 'BEARER':
      return $localize`:@@library.connectors.auth.bearer:Bearer token`;
    case 'HEADER':
      return $localize`:@@library.connectors.auth.header:Custom header`;
    default:
      return String(auth);
  }
}

/** What the editor holds: the input fields plus the typed secret and whether to remove the stored one. */
export interface ConnectorDraft {
  key: string;
  name: string;
  description: string;
  kind: ConnectorKind;
  url: string;
  auth: ConnectorAuth;
  headerName: string;
  /** A new secret; empty = keep the stored one (or none on create). */
  secret: string;
  /** Remove the stored secret on save. */
  removeSecret: boolean;
  /** GITLAB_READ: a project repository key; empty = the primary repository. */
  repository: string;
}

export function emptyConnector(): ConnectorDraft {
  return {
    key: '',
    name: '',
    description: '',
    kind: 'MCP_HTTP',
    url: '',
    auth: 'NONE',
    headerName: '',
    secret: '',
    removeSecret: false,
    repository: '',
  };
}

/** The editable fields of a saved connector (the secret is never returned). */
export function connectorDraftOf(c: Connector): ConnectorDraft {
  return {
    key: c.key ?? '',
    name: c.name,
    description: c.description ?? '',
    kind: c.kind,
    url: c.url ?? '',
    auth: c.auth ?? 'NONE',
    headerName: c.headerName ?? '',
    secret: '',
    removeSecret: false,
    repository: c.repository ?? '',
  };
}

/** Whether a text is an http(s) URL with a host (at most 500 characters). */
export function isHttpUrl(value: string): boolean {
  const v = value.trim();
  if (!v || v.length > CONNECTOR_URL_MAX || !/^https?:\/\//i.test(v)) return false;
  try {
    const u = new URL(v);
    return (u.protocol === 'http:' || u.protocol === 'https:') && !!u.hostname;
  } catch {
    return false;
  }
}

/** The host of an MCP URL (what the sandbox network policy must allow); null when not a URL. */
export function connectorHost(url: string | null | undefined): string | null {
  const v = (url ?? '').trim();
  if (!isHttpUrl(v)) return null;
  return new URL(v).hostname;
}

/** Whether a secret will be stored after saving the draft (typed now, or kept from before). */
export function secretAfterSave(d: ConnectorDraft, hasSecret: boolean): boolean {
  return !!d.secret || (hasSecret && !d.removeSecret);
}

/**
 * What blocks saving the draft; empty when it can be saved. `hasSecret`: the saved connector has a stored secret;
 * `projectScope`: the connector belongs to a project (GitLab read-only is project scope only).
 */
export function connectorProblems(
  d: ConnectorDraft,
  opts: { hasSecret?: boolean; projectScope: boolean },
): string[] {
  const out: string[] = [];
  const key = d.key.trim();
  if (key && !CONNECTOR_KEY_PATTERN.test(key))
    out.push(
      $localize`:@@library.connectors.problem.key:Key: lowercase letters, digits or dashes, starting with a letter (at most 40).`,
    );
  if (!d.name.trim()) out.push($localize`:@@library.connectors.problem.name:Name is required.`);
  else if (d.name.trim().length > CONNECTOR_NAME_MAX)
    out.push($localize`:@@library.connectors.problem.nameLength:Name: at most 200 characters.`);
  if (d.kind === 'GITLAB_READ') {
    if (!opts.projectScope)
      out.push(
        $localize`:@@library.connectors.problem.gitlabScope:GitLab (read-only) needs a project's repository: add it in the project.`,
      );
    return out;
  }
  if (!isHttpUrl(d.url))
    out.push(
      $localize`:@@library.connectors.problem.url:URL: an http:// or https:// address of the MCP endpoint.`,
    );
  if (d.auth === 'HEADER' && !HEADER_NAME_PATTERN.test(d.headerName.trim()))
    out.push(
      $localize`:@@library.connectors.problem.headerName:Header name: letters, digits or dashes (at most 64).`,
    );
  if (d.auth !== 'NONE' && !secretAfterSave(d, !!opts.hasSecret))
    out.push(
      $localize`:@@library.connectors.problem.secret:Secret: required for this authentication.`,
    );
  return out;
}

/**
 * What the editor sends. The key only when given (create: derived from the name otherwise) or kept (update). The
 * secret: a typed one replaces, "Remove secret" sends an empty one, otherwise null keeps the stored one; without
 * authentication a stored secret is removed (nothing would use it). GitLab read-only sends only its repository.
 */
export function connectorPayload(
  d: ConnectorDraft,
  scope: Scope,
  opts: { hasSecret?: boolean } = {},
): ConnectorInput {
  const key = d.key.trim();
  const base = {
    ...(key ? { key } : {}),
    name: d.name.trim(),
    description: d.description.trim(),
    kind: d.kind,
    scope,
  };
  if (d.kind === 'GITLAB_READ') {
    return {
      ...base,
      auth: 'NONE',
      url: null,
      headerName: null,
      secret: opts.hasSecret ? '' : null,
      repository: d.repository.trim() || null,
    };
  }
  let secret: string | null = null;
  if (d.auth === 'NONE') secret = opts.hasSecret ? '' : null;
  else if (d.secret) secret = d.secret;
  else if (d.removeSecret && opts.hasSecret) secret = '';
  return {
    ...base,
    url: d.url.trim(),
    auth: d.auth,
    headerName: d.auth === 'HEADER' ? d.headerName.trim() : null,
    secret,
    repository: null,
  };
}

/** Whether a connector belongs to the platform (scope GLOBAL). */
export function isPlatformConnector(c: Pick<Connector, 'scope'>): boolean {
  return c.scope?.type !== 'PROJECT';
}

/** A connector of the list with whether a project connector of the same key replaces it. */
export interface ConnectorRow {
  connector: Connector;
  platform: boolean;
  /** A platform connector shadowed by a project connector of the same key. */
  shadowed: boolean;
}

/**
 * The rows of a project's Connectors tab: the project's own connectors first, then the platform ones (read-only),
 * each by name; a platform connector whose key the project redefines is marked shadowed.
 */
export function connectorRows(
  list: readonly Connector[],
  projectId: string | null,
): ConnectorRow[] {
  const own = list.filter((c) => !isPlatformConnector(c) && c.scope.projectId === projectId);
  const ownKeys = new Set(own.map((c) => c.key ?? ''));
  const byName = (a: Connector, b: Connector) => compareText(a.name, b.name);
  return [
    ...[...own].sort(byName).map((connector) => ({ connector, platform: false, shadowed: false })),
    ...list
      .filter(isPlatformConnector)
      .sort(byName)
      .map((connector) => ({
        connector,
        platform: true,
        shadowed: !!projectId && ownKeys.has(connector.key ?? ''),
      })),
  ];
}

/** An option of the agent's connector select. */
export interface ConnectorOption {
  key: string;
  label: string;
  /** Not defined in the platform nor in the agent's project (a global agent may list keys projects provide). */
  unknown: boolean;
}

/**
 * ADR 0064: the connectors an agent of the scope may list: the platform ones plus, for a project agent, its
 * project's (which shadow platform ones of the same key); selected keys that none of them defines stay as unknown
 * options so they can be seen and removed.
 */
export function connectorOptions(
  list: readonly Connector[],
  scope: Pick<Scope, 'type' | 'projectId'>,
  selected: readonly string[] = [],
): ConnectorOption[] {
  const projectId = scope.type === 'PROJECT' ? scope.projectId : null;
  const byKey = new Map<string, ConnectorOption>();
  const visible = list.filter(
    (c) => isPlatformConnector(c) || (!!projectId && c.scope.projectId === projectId),
  );
  // platform first, so a project connector of the same key replaces it
  for (const c of [
    ...visible.filter(isPlatformConnector),
    ...visible.filter((c) => !isPlatformConnector(c)),
  ]) {
    const key = c.key ?? '';
    if (key) byKey.set(key, { key, label: `${c.name} (${key})`, unknown: false });
  }
  for (const key of selected)
    if (!byKey.has(key))
      byKey.set(key, {
        key,
        label: $localize`:@@library.connectors.option.unknown:${key}:key: (not defined here)`,
        unknown: true,
      });
  return [...byKey.values()];
}

/** The connector keys of an agent: trimmed, not empty, once. */
export function cleanConnectorKeys(keys: readonly string[] | null | undefined): string[] {
  return [...new Set((keys ?? []).map((k) => k.trim()).filter((k) => !!k))];
}

/** The first connector key of an agent that is not a valid key; null when all are. */
export function badConnectorKey(keys: readonly string[] | null | undefined): string | null {
  return cleanConnectorKeys(keys).find((k) => !CONNECTOR_KEY_PATTERN.test(k)) ?? null;
}
