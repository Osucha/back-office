import type { Connector } from '../../../core/api/types';
import {
  CONNECTOR_AUTHS,
  badConnectorKey,
  cleanConnectorKeys,
  connectorAuthLabel,
  connectorDraftOf,
  connectorHost,
  connectorKindLabel,
  connectorKinds,
  connectorOptions,
  connectorPayload,
  connectorProblems,
  connectorRows,
  emptyConnector,
  isHttpUrl,
  secretAfterSave,
} from './connector-form';

const platform: Connector = {
  id: 'c1',
  key: 'docs',
  name: 'Docs',
  kind: 'MCP_HTTP',
  url: 'https://mcp.example.com/mcp',
  auth: 'BEARER',
  hasSecret: true,
  scope: { type: 'GLOBAL', projectId: null },
};
const projectDocs: Connector = {
  id: 'c2',
  key: 'docs',
  name: 'Docs (SHOP)',
  kind: 'MCP_HTTP',
  url: 'https://docs.shop.example.com/mcp',
  auth: 'NONE',
  scope: { type: 'PROJECT', projectId: 'p1' },
};
const gitlab: Connector = {
  id: 'c3',
  key: 'gitlab-read',
  name: 'CI logs',
  kind: 'GITLAB_READ',
  auth: 'NONE',
  repository: 'api',
  scope: { type: 'PROJECT', projectId: 'p1' },
};
const otherProject: Connector = {
  id: 'c4',
  key: 'jira',
  name: 'Jira',
  kind: 'MCP_HTTP',
  url: 'https://jira.example.com/mcp',
  auth: 'NONE',
  scope: { type: 'PROJECT', projectId: 'p2' },
};

describe('connector form (ADR 0064)', () => {
  it('names kinds and authentications; GitLab read-only only in projects', () => {
    expect(connectorKindLabel('MCP_HTTP')).toBe('MCP server (HTTP)');
    expect(connectorKindLabel('GITLAB_READ')).toBe('GitLab (read-only)');
    expect(CONNECTOR_AUTHS.map(connectorAuthLabel)).toEqual([
      'None',
      'Bearer token',
      'Custom header',
    ]);
    expect(connectorKinds(false)).toEqual(['MCP_HTTP']);
    expect(connectorKinds(true)).toEqual(['MCP_HTTP', 'GITLAB_READ']);
  });

  it('accepts http(s) URLs and shows their host', () => {
    expect(isHttpUrl('https://mcp.example.com/x')).toBe(true);
    expect(isHttpUrl('http://localhost:8080/mcp')).toBe(true);
    expect(isHttpUrl('ftp://example.com')).toBe(false);
    expect(isHttpUrl('mcp.example.com')).toBe(false);
    expect(isHttpUrl('https://')).toBe(false);
    expect(isHttpUrl(`https://e.com/${'a'.repeat(500)}`)).toBe(false);
    expect(connectorHost(' https://mcp.example.com:8443/mcp ')).toBe('mcp.example.com');
    expect(connectorHost('nope')).toBeNull();
    expect(connectorHost(null)).toBeNull();
  });

  it('drafts a saved connector without its secret', () => {
    expect(connectorDraftOf(platform)).toEqual({
      key: 'docs',
      name: 'Docs',
      description: '',
      kind: 'MCP_HTTP',
      url: 'https://mcp.example.com/mcp',
      auth: 'BEARER',
      headerName: '',
      secret: '',
      removeSecret: false,
      repository: '',
    });
    expect(connectorDraftOf(gitlab).repository).toBe('api');
  });

  it('finds what blocks saving', () => {
    const ok = { ...emptyConnector(), name: 'Docs', url: 'https://e.com/mcp' };
    expect(connectorProblems(ok, { projectScope: false })).toEqual([]);
    expect(connectorProblems(emptyConnector(), { projectScope: false })).toEqual([
      'Name is required.',
      'URL: an http:// or https:// address of the MCP endpoint.',
    ]);
    expect(connectorProblems({ ...ok, key: 'Bad Key' }, { projectScope: false })[0]).toContain(
      'Key:',
    );
    expect(connectorProblems({ ...ok, name: 'x'.repeat(201) }, { projectScope: false })).toEqual([
      'Name: at most 200 characters.',
    ]);
    // a header needs a name, an authentication a secret (typed or stored)
    const header = { ...ok, auth: 'HEADER' as const, headerName: 'X Bad', secret: 's' };
    expect(connectorProblems(header, { projectScope: false })).toEqual([
      'Header name: letters, digits or dashes (at most 64).',
    ]);
    const bearer = { ...ok, auth: 'BEARER' as const };
    expect(connectorProblems(bearer, { projectScope: false })).toEqual([
      'Secret: required for this authentication.',
    ]);
    expect(connectorProblems(bearer, { projectScope: false, hasSecret: true })).toEqual([]);
    expect(
      connectorProblems(
        { ...bearer, removeSecret: true },
        { projectScope: false, hasSecret: true },
      ),
    ).toHaveLength(1);
    // GitLab read-only: no URL, but only in a project
    const gl = { ...emptyConnector(), name: 'CI', kind: 'GITLAB_READ' as const };
    expect(connectorProblems(gl, { projectScope: true })).toEqual([]);
    expect(connectorProblems(gl, { projectScope: false })[0]).toContain('GitLab');
  });

  it('knows whether a secret remains after saving', () => {
    const d = emptyConnector();
    expect(secretAfterSave(d, false)).toBe(false);
    expect(secretAfterSave(d, true)).toBe(true);
    expect(secretAfterSave({ ...d, removeSecret: true }, true)).toBe(false);
    expect(secretAfterSave({ ...d, secret: 'new' }, false)).toBe(true);
  });

  it('sends the secret write-only: new replaces, empty keeps, remove sends empty', () => {
    const scope = { type: 'GLOBAL' as const, projectId: null };
    const d = {
      ...connectorDraftOf(platform),
      name: ' Docs ',
      url: ' https://mcp.example.com/mcp ',
    };
    expect(connectorPayload(d, scope, { hasSecret: true })).toEqual({
      key: 'docs',
      name: 'Docs',
      description: '',
      kind: 'MCP_HTTP',
      scope,
      url: 'https://mcp.example.com/mcp',
      auth: 'BEARER',
      headerName: null,
      secret: null,
      repository: null,
    });
    expect(connectorPayload({ ...d, secret: 'tok' }, scope, { hasSecret: true }).secret).toBe(
      'tok',
    );
    expect(connectorPayload({ ...d, removeSecret: true }, scope, { hasSecret: true }).secret).toBe(
      '',
    );
    // without authentication a stored secret is removed; nothing to remove on create
    expect(connectorPayload({ ...d, auth: 'NONE' }, scope, { hasSecret: true }).secret).toBe('');
    expect(connectorPayload({ ...d, auth: 'NONE' }, scope).secret).toBeNull();
    // the header name only with a custom header
    const h = connectorPayload(
      { ...d, auth: 'HEADER', headerName: ' X-Api-Key ', secret: 's' },
      scope,
    );
    expect(h.headerName).toBe('X-Api-Key');
    // no key: derived by the server from the name
    expect('key' in connectorPayload({ ...d, key: ' ' }, scope)).toBe(false);
  });

  it('sends only the repository of a GitLab read-only connector', () => {
    const scope = { type: 'PROJECT' as const, projectId: 'p1' };
    const d = { ...connectorDraftOf(gitlab), url: 'https://x', secret: 'x' };
    expect(connectorPayload(d, scope)).toEqual({
      key: 'gitlab-read',
      name: 'CI logs',
      description: '',
      kind: 'GITLAB_READ',
      scope,
      auth: 'NONE',
      url: null,
      headerName: null,
      secret: null,
      repository: 'api',
    });
    expect(connectorPayload({ ...d, repository: '' }, scope).repository).toBeNull();
  });

  it('lists the project connectors first and marks shadowed platform ones', () => {
    const rows = connectorRows([platform, otherProject, gitlab, projectDocs], 'p1');
    expect(rows.map((r) => [r.connector.id, r.platform, r.shadowed])).toEqual([
      ['c3', false, false],
      ['c2', false, false],
      ['c1', true, true],
    ]);
    expect(connectorRows([platform], null)).toEqual([
      { connector: platform, platform: true, shadowed: false },
    ]);
  });

  it('offers an agent the connectors of its scope plus selected unknown keys', () => {
    const all = [platform, projectDocs, gitlab, otherProject];
    expect(connectorOptions(all, { type: 'GLOBAL', projectId: null })).toEqual([
      { key: 'docs', label: 'Docs (docs)', unknown: false },
    ]);
    expect(connectorOptions(all, { type: 'PROJECT', projectId: 'p1' }, ['gone'])).toEqual([
      { key: 'docs', label: 'Docs (SHOP) (docs)', unknown: false },
      { key: 'gitlab-read', label: 'CI logs (gitlab-read)', unknown: false },
      { key: 'gone', label: 'gone (not defined here)', unknown: true },
    ]);
  });

  it('cleans and checks the connector keys of an agent', () => {
    expect(cleanConnectorKeys([' a ', 'a', '', 'b'])).toEqual(['a', 'b']);
    expect(cleanConnectorKeys(undefined)).toEqual([]);
    expect(badConnectorKey(['docs', 'gitlab-read'])).toBeNull();
    expect(badConnectorKey(['docs', 'Bad'])).toBe('Bad');
  });
});
