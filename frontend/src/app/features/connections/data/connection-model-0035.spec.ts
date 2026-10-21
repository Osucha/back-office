import {
  actAsOf,
  configFlag,
  connectionGuide,
  draftOf,
  emptyDraft,
  excessWriteScopes,
  moveItem,
  normalizeScopes,
  pinnedConnection,
  recommendedScopes,
  resolutionView,
  toInput,
  uncoveredCapabilities,
  validateDraft,
  verificationMessageText,
  withActAs,
  withFlag,
} from './connection-model';
import type { Connection } from './connection-types';

const base: Connection = {
  id: 'c1',
  scope: 'USER',
  type: 'JIRA',
  name: 'My Jira',
  siteUrl: 'https://acme.atlassian.net',
  accountEmail: 'anna@acme.com',
  capabilities: ['ISSUE_READ', 'ISSUE_COMMENT'],
  allowedProjectKeys: [],
  secretSet: true,
  legacy: false,
  expiryStatus: 'NONE',
  tokenKind: 'SCOPED',
  cloudId: '11111111-2222-3333-4444-555555555555',
  tokenScopes: ['read:jira-work', 'write:comment:jira', 'read:comment:jira'],
};

describe('connection model (ADR 0035)', () => {
  it('recommends granular scopes per capability and spots uncovered capabilities and extra writes', () => {
    const read = recommendedScopes(['ISSUE_READ']);
    expect(read).toContain('read:issue:jira');
    expect(read).toContain('read:user:jira');
    // what Jira actually demands of a scoped token: myself (identity), mypermissions and search/jql
    expect(read).toEqual(
      expect.arrayContaining([
        'read:group:jira',
        'read:application-role:jira',
        'read:avatar:jira',
        'read:permission:jira',
        'read:jql:jira',
        'read:issue-details:jira',
        'read:audit-log:jira',
      ]),
    );
    expect(read.some((s) => s.startsWith('write:'))).toBe(false);
    expect(recommendedScopes(['ISSUE_COMMENT'], 'CLASSIC')).toEqual([
      'read:jira-user',
      'read:jira-work',
      'write:jira-work',
    ]);
    expect(uncoveredCapabilities(['ISSUE_READ', 'ISSUE_TRANSITION'], ['read:jira-work'])).toEqual([
      'ISSUE_TRANSITION',
    ]);
    expect(
      uncoveredCapabilities(['ISSUE_COMMENT'], ['write:comment:jira', 'read:comment:jira']),
    ).toEqual([]);
    expect(
      excessWriteScopes(
        ['ISSUE_READ'],
        ['read:jira-work', 'write:comment:jira', 'delete:issue:jira'],
      ),
    ).toEqual(['delete:issue:jira', 'write:comment:jira']);
    expect(normalizeScopes(['Read:Jira-Work, write:comment:jira', 'read:jira-work'])).toEqual([
      'read:jira-work',
      'write:comment:jira',
    ]);
  });

  it('new connections start with a scoped token; a classic one needs the acknowledgement', () => {
    const fresh = emptyDraft();
    expect(fresh.tokenKind).toBe('SCOPED');
    expect(fresh.tokenScopes).toContain('read:issue:jira');
    const classic = {
      ...fresh,
      name: 'x',
      siteUrl: 'https://acme.atlassian.net',
      accountEmail: 'a@b.c',
      secret: 't',
      tokenKind: 'CLASSIC' as const,
    };
    expect(Object.keys(validateDraft(classic, true))).toEqual(['acknowledgeClassicToken']);
    expect(validateDraft({ ...classic, acknowledgeClassicToken: true }, true)).toEqual({});
    expect(toInput({ ...classic, acknowledgeClassicToken: true })).toMatchObject({
      tokenKind: 'CLASSIC',
      acknowledgeClassicToken: true,
    });
    const scoped = { ...classic, tokenKind: 'SCOPED' as const, tokenScopes: [], cloudId: 'nope' };
    expect(Object.keys(validateDraft(scoped, true)).sort()).toEqual(['cloudId', 'tokenScopes']);
  });

  it('a stored scoped connection round-trips through the draft without the token', () => {
    const d = draftOf(base);
    expect(d.tokenKind).toBe('SCOPED');
    expect(validateDraft(d, false)).toEqual({});
    const input = toInput(d);
    expect(input).toMatchObject({
      tokenKind: 'SCOPED',
      cloudId: '11111111-2222-3333-4444-555555555555',
      tokenScopes: ['read:comment:jira', 'read:jira-work', 'write:comment:jira'],
    });
    expect('secret' in input).toBe(false);
    expect('acknowledgeClassicToken' in input).toBe(false);
  });

  it('the guide explains scoped tokens and personal classic tokens', () => {
    const scoped = connectionGuide(['ISSUE_COMMENT'], [], 'SCOPED', true);
    expect(scoped.steps.join(' ')).toContain('Create API token with scopes');
    expect(scoped.steps.join(' ')).toContain('write:comment:jira');
    expect(scoped.steps[0]).toContain('your own account');
    const personal = connectionGuide(['ISSUE_READ'], [], 'CLASSIC', true);
    expect(personal.steps.join(' ')).toContain('can do everything your account can');
  });

  it('reads and writes the Act as settings of a Jira node', () => {
    expect(actAsOf(undefined)).toBe('SYSTEM');
    expect(actAsOf({ jiraConnection: 'SYSTEM' })).toBe('SYSTEM');
    expect(actAsOf({ jiraConnection: 'ACTING_USER' })).toBe('ACTING_USER');
    expect(actAsOf({ jiraConnection: 'c2' })).toBe('SPECIFIC');
    expect(pinnedConnection({ jiraConnection: 'ACTING_USER' })).toBeNull();
    expect(pinnedConnection({ jiraConnection: 'c2' })).toBe('c2');

    const acting = withActAs({ toStatus: 'Done', allowActingUser: true }, 'ACTING_USER');
    expect(acting).toEqual({ toStatus: 'Done', jiraConnection: 'ACTING_USER' });
    const withFallback = withFlag(acting, 'fallbackToSystem', true);
    expect(configFlag(withFallback, 'fallbackToSystem')).toBe(true);
    expect(withActAs(withFallback, 'SYSTEM')).toEqual({ toStatus: 'Done' });
    expect(withActAs({ toStatus: 'Done' }, 'SPECIFIC', 'c9')).toEqual({
      toStatus: 'Done',
      jiraConnection: 'c9',
    });
    expect(withActAs({ jiraConnection: 'c9' }, 'SYSTEM')).toBeUndefined();
    expect(withFlag({ allowActingUser: true }, 'allowActingUser', false)).toBeUndefined();
  });

  it('explains a fallback from the system connection and scope messages', () => {
    const view = resolutionView({
      status: 'OK',
      required: ['ISSUE_COMMENT'],
      connection: { ...base, scope: 'PROJECT', name: 'Commenter' },
      pinned: false,
      excess: [],
      message: 'x',
      fallback: true,
      mode: 'SYSTEM',
    });
    expect(view.text).toContain('uses Commenter from the project');
    expect(
      verificationMessageText({
        severity: 'ERROR',
        code: 'MISSING_SCOPES',
        text: 'needs write:comment:jira',
      }),
    ).toContain('needs write:comment:jira');
    expect(
      verificationMessageText({ severity: 'WARNING', code: 'BROAD_SCOPE', text: '' }),
    ).toContain('write:jira-work');
  });

  it('moves list items within bounds', () => {
    expect(moveItem(['a', 'b', 'c'], 0, 1)).toEqual(['b', 'a', 'c']);
    expect(moveItem(['a', 'b', 'c'], 2, 1)).toEqual(['a', 'b', 'c']);
    expect(moveItem(['a', 'b', 'c'], 2, -2)).toEqual(['c', 'a', 'b']);
  });
});
