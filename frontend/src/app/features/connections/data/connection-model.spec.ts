import {
  capabilityLabel,
  capabilityLabels,
  connectionGuide,
  covers,
  draftOf,
  emptyDraft,
  expiryChip,
  jiraPermissionName,
  normalizeProjectKeys,
  permissionsFor,
  pinOptions,
  pinnedConnection,
  resolutionView,
  toInput,
  validateDraft,
  verificationChip,
  verificationMessageText,
  withPinnedConnection,
} from './connection-model';
import type { Connection, ConnectionResolution } from './connection-types';

function connection(overrides: Partial<Connection> = {}): Connection {
  return {
    id: 'c1',
    scope: 'PLATFORM',
    type: 'JIRA',
    name: 'Jira reader',
    siteUrl: 'https://acme.atlassian.net',
    accountEmail: 'reader@acme.com',
    capabilities: ['ISSUE_READ'],
    allowedProjectKeys: [],
    secretSet: true,
    legacy: false,
    expiryStatus: 'NONE',
    ...overrides,
  };
}

describe('connection model', () => {
  it('maps capabilities to the Jira permissions they need', () => {
    expect(permissionsFor(['ISSUE_READ'])).toEqual(['BROWSE_PROJECTS']);
    expect(permissionsFor(['ISSUE_TRANSITION', 'ISSUE_COMMENT'])).toEqual([
      'BROWSE_PROJECTS',
      'ADD_COMMENTS',
      'TRANSITION_ISSUES',
    ]);
    expect(permissionsFor(['FUTURE_THING'])).toEqual([]);
    expect(jiraPermissionName('ADMINISTER_PROJECTS')).toBe('Administer Projects');
    expect(jiraPermissionName('SOME_NEW_PERMISSION')).toBe('Some new permission');
  });

  it('labels known capabilities and tolerates unknown ones', () => {
    expect(capabilityLabel('ISSUE_READ')).toBe('Read issues');
    expect(capabilityLabel('ISSUE_TRANSITION')).toBe('Move issues');
    expect(capabilityLabel('ISSUE_LINK')).toBe('Issue link');
    expect(capabilityLabels(['ISSUE_TRANSITION', 'ISSUE_READ'])).toBe('Read issues, Move issues');
  });

  it('adapts the guide to the selected capabilities', () => {
    expect(connectionGuide([])).toEqual({ role: null, permissions: [], steps: [] });

    const reader = connectionGuide(['ISSUE_READ']);
    expect(reader.role).toBe('reader');
    expect(reader.permissions).toEqual(['Browse Projects']);
    expect(reader.steps).toHaveLength(4);
    expect(reader.steps[0]).toContain('backoffice-jira-reader@your-company');
    expect(reader.steps[1]).toContain('ONLY Browse Projects');
    expect(reader.steps[1]).toContain('each Jira project it works in');
    expect(reader.steps[2]).toContain('not "with scopes"');

    const writer = connectionGuide(['ISSUE_READ', 'ISSUE_COMMENT'], ['SHOP', 'CRM']);
    expect(writer.role).toBe('writer');
    expect(writer.steps[0]).toContain('backoffice-jira-writer@your-company');
    expect(writer.steps[1]).toContain('(SHOP, CRM)');
    expect(writer.steps[1]).toContain('ONLY Browse Projects, Add Comments');

    expect(connectionGuide(['ISSUE_TRANSITION']).permissions).toEqual([
      'Browse Projects',
      'Transition Issues',
    ]);
  });

  it('renders verification messages from code, project key and permissions', () => {
    expect(
      verificationMessageText({
        severity: 'ERROR',
        code: 'MISSING_PERMISSIONS',
        projectKey: 'SHOP',
        permissions: ['TRANSITION_ISSUES', 'BROWSE_PROJECTS'],
        text: 'english',
      }),
    ).toBe(
      'Missing in SHOP for the declared capabilities: Browse Projects, Transition Issues. Steps that need them will fail.',
    );
    expect(
      verificationMessageText(
        {
          severity: 'WARNING',
          code: 'EXCESS_PERMISSIONS',
          projectKey: 'SHOP',
          permissions: ['ADMINISTER_PROJECTS', 'DELETE_ISSUES'],
          text: 'english',
        },
        ['BROWSE_PROJECTS'],
      ),
    ).toBe(
      'Over-privileged in SHOP: this account can also delete issues, administer the project. Use a dedicated account with only Browse Projects.',
    );
    // a scoped token: the account's extra permissions are out of the token's reach (INFO, nothing to fix)
    const scoped = {
      severity: 'INFO' as const,
      code: 'EXCESS_PERMISSIONS' as const,
      projectKey: 'SHOP',
      permissions: ['DELETE_ISSUES' as const],
      text: 'english',
    };
    expect(verificationMessageText(scoped, ['BROWSE_PROJECTS'])).toBe(
      'In SHOP this account can also delete issues, but the scoped token can only do what its scopes allow.',
    );
    expect(
      verificationChip({
        status: 'WARNING',
        checkedAt: '2026-10-12T10:00:00Z',
        projects: [],
        excess: ['DELETE_ISSUES'],
        messages: [scoped],
      } as unknown as Parameters<typeof verificationChip>[0]).label,
    ).toBe('check with warnings');
    expect(
      verificationMessageText({ severity: 'ERROR', code: 'AUTHENTICATION_FAILED', text: 'x' }),
    ).toContain('rejected the e-mail and API token');
    expect(
      verificationMessageText({
        severity: 'ERROR',
        code: 'PROJECT_NOT_FOUND',
        projectKey: 'NOPE',
        text: 'x',
      }),
    ).toBe('Jira project NOPE does not exist or this account cannot see it.');
    // unknown codes fall back to the server text
    expect(
      verificationMessageText({
        severity: 'INFO',
        code: 'SOMETHING_NEW' as never,
        text: 'Server says hi',
      }),
    ).toBe('Server says hi');
  });

  it('builds verification and expiry chips', () => {
    expect(verificationChip(null)).toEqual({ tone: 'neutral', label: 'not checked' });
    const base = { checkedAt: '2026-10-01T10:00:00Z', projects: [], missing: [], messages: [] };
    expect(verificationChip({ ...base, status: 'OK', excess: [] }).tone).toBe('ok');
    expect(verificationChip({ ...base, status: 'FAILED', excess: [] })).toEqual({
      tone: 'bad',
      label: 'check failed',
    });
    expect(verificationChip({ ...base, status: 'WARNING', excess: ['DELETE_ISSUES'] }).label).toBe(
      'over-privileged',
    );
    expect(expiryChip({ expiryStatus: 'OK', secretExpiresAt: '2027-01-01' })).toBeNull();
    expect(expiryChip({ expiryStatus: 'EXPIRED', secretExpiresAt: '2026-01-01' })?.tone).toBe(
      'bad',
    );
    expect(
      expiryChip({ expiryStatus: 'EXPIRING_SOON', secretExpiresAt: '2026-10-20' }),
    ).toMatchObject({ tone: 'warn', label: 'token expires Oct 20, 2026' });
  });

  it('maps resolution statuses to tones and texts', () => {
    const res = (r: Partial<ConnectionResolution>): ConnectionResolution => ({
      status: 'OK',
      required: ['ISSUE_READ'],
      excess: [],
      pinned: false,
      message: 'server text',
      ...r,
    });
    expect(resolutionView(res({ connection: connection() }))).toEqual({
      tone: 'ok',
      text: 'Uses Jira reader (least privilege).',
      guide: false,
    });
    const over = resolutionView(
      res({
        status: 'OVER_PRIVILEGED',
        connection: connection({ name: 'Writer' }),
        excess: ['ISSUE_COMMENT'],
      }),
    );
    expect(over.tone).toBe('warn');
    expect(over.text).toContain('more rights than needed (extra: Comment on issues)');
    expect(resolutionView(res({ status: 'NONE' }))).toMatchObject({ tone: 'bad', guide: true });
    expect(resolutionView(res({ status: 'PINNED_NOT_FOUND', pinned: true })).tone).toBe('bad');
    const insufficient = resolutionView(
      res({
        status: 'PINNED_INSUFFICIENT',
        pinned: true,
        required: ['ISSUE_READ', 'ISSUE_TRANSITION'],
        connection: connection({ name: 'Reader' }),
      }),
    );
    expect(insufficient.text).toContain('The pinned connection Reader lacks: Move issues.');
    expect(resolutionView(res({ status: 'WHATEVER' as never })).text).toBe('server text');
  });

  it('pins and unpins a connection in the node config', () => {
    expect(withPinnedConnection(undefined, 'c1')).toEqual({ jiraConnection: 'c1' });
    expect(withPinnedConnection({ toStatus: 'Done', jiraConnection: 'c1' }, null)).toEqual({
      toStatus: 'Done',
    });
    expect(withPinnedConnection({ jiraConnection: 'c1' }, null)).toBeUndefined();
    expect(pinnedConnection({ jiraConnection: 'c2' })).toBe('c2');
    expect(pinnedConnection({ jiraConnection: 42 })).toBeNull();
    expect(pinnedConnection(null)).toBeNull();
  });

  it('offers only connections that cover the requirement', () => {
    const reader = connection();
    const writer = connection({
      id: 'c2',
      name: 'Writer',
      scope: 'PROJECT',
      capabilities: ['ISSUE_READ', 'ISSUE_TRANSITION'],
    });
    expect(covers(writer, ['ISSUE_TRANSITION'])).toBe(true);
    expect(covers(reader, ['ISSUE_TRANSITION'])).toBe(false);
    const options = pinOptions([writer, reader], ['ISSUE_TRANSITION']);
    expect(options.map((o) => [o.id, o.disabled])).toEqual([
      ['c2', false],
      ['c1', true],
    ]);
    expect(options[0].label).toBe('Writer (project: Read issues, Move issues)');
    expect(options[1].reason).toBe('lacks Move issues');
  });

  it('validates the form and builds the request body', () => {
    expect(normalizeProjectKeys(['shop, crm', ' ops ', 'SHOP'])).toEqual(['SHOP', 'CRM', 'OPS']);
    const errors = validateDraft(
      { ...emptyDraft(), siteUrl: 'http://acme', capabilities: [], allowedProjectKeys: ['9X'] },
      true,
    );
    expect(Object.keys(errors).sort()).toEqual([
      'accountEmail',
      'allowedProjectKeys',
      'capabilities',
      'name',
      'secret',
      'siteUrl',
    ]);
    const edit = draftOf(connection({ verifyProjectKey: 'SHOP', classicTokenAcknowledged: true }));
    expect(validateDraft(edit, false)).toEqual({});
    // no token entered on edit: the stored one is kept (secret omitted)
    expect(toInput({ ...edit, siteUrl: 'https://acme.atlassian.net/' })).toEqual({
      type: 'JIRA',
      name: 'Jira reader',
      siteUrl: 'https://acme.atlassian.net',
      accountEmail: 'reader@acme.com',
      capabilities: ['ISSUE_READ'],
      allowedProjectKeys: [],
      verifyProjectKey: 'SHOP',
      secretExpiresAt: null,
      tokenKind: 'CLASSIC',
      acknowledgeClassicToken: true,
    });
    expect(toInput({ ...edit, secret: ' tok ' }).secret).toBe('tok');
  });
});
