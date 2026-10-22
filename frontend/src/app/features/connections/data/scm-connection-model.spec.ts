import type { ProjectRepository } from '../../../core/api/types';
import type { ScmConnection, ScmVerification } from './connection-types';
import {
  agentChip,
  emptyScmDraft,
  groupByRepository,
  isScmWriteCapability,
  mixesReadAndWrite,
  recommendedToken,
  scmCapabilityHint,
  scmDraftOf,
  scmMessageText,
  scmVerificationChip,
  sortScmCapabilities,
  toScmInput,
  validateScmDraft,
} from './scm-connection-model';

const repo = (key: string, primary = false): ProjectRepository =>
  ({
    key,
    url: `https://gitlab.example.com/group/${key}.git`,
    defaultBranch: 'main',
    purpose: 'SOURCE',
    primary,
  }) as ProjectRepository;

const conn = (
  id: string,
  repositoryKey: string,
  name: string,
  capabilities: string[],
  extra: Partial<ScmConnection> = {},
): ScmConnection => ({
  id,
  repositoryKey,
  repositoryExists: true,
  name,
  provider: 'GITLAB',
  capabilities: capabilities as ScmConnection['capabilities'],
  expiryStatus: 'NONE',
  readOnly: false,
  createdAt: '2026-10-01T10:00:00Z',
  createdBy: 'Owner',
  updatedAt: '2026-10-01T10:00:00Z',
  updatedBy: 'Owner',
  ...extra,
});

const verification = (extra: Partial<ScmVerification> = {}): ScmVerification => ({
  status: 'OK',
  checkedAt: '2026-10-01T10:00:00Z',
  granted: [],
  missing: [],
  writePowers: [],
  messages: [],
  ...extra,
});

describe('scm-connection-model (ADR 0051)', () => {
  it('splits read and write capabilities and sorts them read first', () => {
    expect(isScmWriteCapability('GIT_READ')).toBe(false);
    expect(isScmWriteCapability('CI_READ')).toBe(false);
    expect(isScmWriteCapability('PUSH_BRANCH')).toBe(true);
    expect(isScmWriteCapability('MR_COMMENT')).toBe(true);
    expect(
      sortScmCapabilities(['PIPELINE_RUN', 'CI_READ', 'PUSH_BRANCH', 'GIT_READ', 'X']),
    ).toEqual(['GIT_READ', 'CI_READ', 'PUSH_BRANCH', 'PIPELINE_RUN', 'X']);
    expect(mixesReadAndWrite(['GIT_READ', 'CI_READ'])).toBe(false);
    expect(mixesReadAndWrite(['GIT_READ', 'PUSH_BRANCH'])).toBe(true);
    expect(mixesReadAndWrite(['PUSH_BRANCH'])).toBe(false);
  });

  it('names the GitLab scope and role of a capability', () => {
    expect(scmCapabilityHint('CI_READ')).toBe('GitLab: scope read_api, role Reporter or higher.');
    expect(scmCapabilityHint('PUSH_BRANCH')).toContain('write_repository');
    expect(scmCapabilityHint('UNKNOWN')).toBe('');
  });

  it('recommends the least role and fewest scopes for a selection', () => {
    expect(recommendedToken([])).toEqual({ role: null, scopes: [] });
    expect(recommendedToken(['GIT_READ', 'CI_READ'])).toEqual({
      role: 'Reporter',
      scopes: ['read_repository', 'read_api'],
    });
    expect(recommendedToken(['GIT_READ', 'PUSH_BRANCH'])).toEqual({
      role: 'Developer',
      scopes: ['write_repository'],
    });
    expect(recommendedToken(['CI_READ', 'MR_COMMENT'])).toEqual({
      role: 'Reporter',
      scopes: ['api'],
    });
  });

  it('groups tokens by project repository, read-only first, orphans last', () => {
    const groups = groupByRepository(
      [
        conn('1', 'api', 'Writer', ['PUSH_BRANCH']),
        conn('2', 'api', 'Reader', ['GIT_READ', 'CI_READ']),
        conn('3', 'old', 'Gone', ['GIT_READ'], { repositoryExists: false }),
        conn('4', 'web', 'Stale', ['GIT_READ'], { repositoryExists: false }),
      ],
      [repo('api', true), repo('web'), repo('docs')],
    );
    expect(groups.map((g) => [g.key, g.exists, g.connections.map((c) => c.id)])).toEqual([
      ['api', true, ['2', '1']],
      ['web', true, []],
      ['docs', true, []],
      ['old', false, ['3']],
      ['web', false, ['4']],
    ]);
    expect(groups[0].primary).toBe(true);
    expect(groups[3].url).toBeNull();
  });

  it('shows the test status, over-privilege and whether agents may get the token', () => {
    expect(scmVerificationChip(null).tone).toBe('neutral');
    expect(scmVerificationChip(verification()).tone).toBe('ok');
    expect(scmVerificationChip(verification({ status: 'FAILED' })).tone).toBe('bad');
    expect(
      scmVerificationChip(verification({ status: 'WARNING', writePowers: ['git push'] })).label,
    ).toBe('more rights than declared');
    expect(agentChip({ capabilities: ['GIT_READ'], readOnly: true }).tone).toBe('ok');
    expect(agentChip({ capabilities: ['PUSH_BRANCH'], readOnly: false }).label).toBe(
      'Back Office only',
    );
    expect(agentChip({ capabilities: ['GIT_READ'], readOnly: false }).tone).toBe('warn');
  });

  it('localizes test messages and keeps the server text for unknown codes', () => {
    const v = verification({ role: 'Maintainer', writePowers: ['git push', 'merge into main'] });
    expect(scmMessageText({ severity: 'ERROR', code: 'MISSING_CI_READ', text: 'x' }, v)).toBe(
      'Read CI is missing: it needs scope read_api and role Reporter or higher.',
    );
    expect(scmMessageText({ severity: 'WARNING', code: 'EXCESS', text: 'x' }, v)).toContain(
      'git push, merge into main',
    );
    expect(scmMessageText({ severity: 'WARNING', code: 'ROLE_TOO_HIGH', text: 'x' }, v)).toContain(
      'Role Maintainer',
    );
    expect(
      scmMessageText({ severity: 'ERROR', code: 'UNREACHABLE', text: '401 Unauthorized' }),
    ).toContain('401 Unauthorized');
    expect(scmMessageText({ severity: 'INFO', code: 'NEW_CODE', text: 'Server text' })).toBe(
      'Server text',
    );
  });

  it('validates the draft and maps it to the request body', () => {
    const d = { ...emptyScmDraft('api'), name: '  Reader ', token: ' glpat-1 ' };
    expect(validateScmDraft(d, true, ['api'])).toEqual({});
    expect(Object.keys(validateScmDraft(emptyScmDraft(), true, ['api'])).sort()).toEqual([
      'name',
      'repositoryKey',
      'token',
    ]);
    expect(validateScmDraft({ ...d, token: '' }, false, ['api'])).toEqual({});
    expect(validateScmDraft({ ...d, capabilities: [] }, false, ['api']).capabilities).toBeTruthy();
    expect(validateScmDraft({ ...d, name: 'x'.repeat(81) }, false, ['api']).name).toBeTruthy();

    expect(toScmInput({ ...d, capabilities: ['CI_READ', 'GIT_READ'] })).toEqual({
      repositoryKey: 'api',
      name: 'Reader',
      provider: 'GITLAB',
      capabilities: ['GIT_READ', 'CI_READ'],
      secretExpiresAt: null,
      token: 'glpat-1',
    });
    const keep = toScmInput({ ...d, token: '', secretExpiresAt: '2027-01-31' });
    expect('token' in keep).toBe(false);
    expect(keep.secretExpiresAt).toBe('2027-01-31');
  });

  it('starts editing from the stored token without its secret', () => {
    const d = scmDraftOf(
      conn('1', 'api', 'Reader', ['CI_READ', 'GIT_READ'], { secretExpiresAt: '2027-01-31' }),
    );
    expect(d).toEqual({
      repositoryKey: 'api',
      name: 'Reader',
      capabilities: ['GIT_READ', 'CI_READ'],
      token: '',
      secretExpiresAt: '2027-01-31',
    });
  });
});
