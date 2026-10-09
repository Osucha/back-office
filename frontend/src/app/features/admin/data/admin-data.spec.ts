import type { EffectivePermissions, PermissionDefinition } from '../../../core/api/types';
import {
  AUDIT_PAGE_SIZE,
  EMPTY_AUDIT_FILTERS,
  correlationFilters,
  toAuditQuery,
} from './audit-query';
import { effectLabel, groupEffective, moduleOf, sourceLabel } from './effective-permissions';
import {
  overrideOf,
  pickGrantSet,
  sameGrantSet,
  setOverride,
  toGrantSet,
  toOverrides,
} from './grants-model';
import {
  matrixFor,
  moduleState,
  toggleModule,
  togglePermission,
  unknownPermissions,
} from './permission-matrix';

const CATALOGUE: PermissionDefinition[] = [
  { key: 'admin.users.view', scope: 'PLATFORM', module: 'identity', description: 'list users' },
  { key: 'admin.users.manage', scope: 'PLATFORM', module: 'identity', description: 'manage users' },
  { key: 'library.view', scope: 'PLATFORM', module: 'tasks', description: 'read library' },
  { key: 'project.view', scope: 'PROJECT', module: 'projects', description: 'see project' },
  { key: 'workflow.edit', scope: 'PROJECT', module: 'projects', description: 'edit workflows' },
];

describe('effective permissions table', () => {
  const data: EffectivePermissions = {
    userId: 'u',
    rows: [
      {
        permission: 'library.view',
        system: 'ALLOW',
        groups: [],
        user: 'NONE',
        effective: true,
        source: 'SYSTEM',
      },
      {
        permission: 'admin.users.manage',
        system: 'NONE',
        groups: [
          { groupId: 'g1', groupName: 'Admins', effect: 'ALLOW' },
          { groupId: 'g2', groupName: 'Auditors', effect: 'DENY' },
          { groupId: 'g3', groupName: 'Quiet', effect: 'NONE' },
        ],
        user: 'NONE',
        effective: false,
        source: 'GROUP:Auditors',
      },
      {
        permission: 'admin.users.view',
        system: 'NONE',
        user: 'ALLOW',
        effective: true,
        source: 'USER',
      },
      { permission: 'x.unknown', effective: false, source: '' },
    ],
  };

  it('groups rows by catalogue module and maps each level', () => {
    const modules = groupEffective(data, CATALOGUE);
    expect(modules.map((m) => m.module)).toEqual(['identity', 'tasks', 'x']);
    const identity = modules[0];
    expect(identity.rows.map((r) => r.permission)).toEqual([
      'admin.users.manage',
      'admin.users.view',
    ]);
    expect(identity.granted).toBe(1);
    const manage = identity.rows[0];
    expect(manage.groups).toEqual([
      { name: 'Admins', effect: 'ALLOW' },
      { name: 'Auditors', effect: 'DENY' },
    ]);
    expect(manage.description).toBe('manage users');
    expect(sourceLabel(manage)).toBe('Group Auditors');
    expect(sourceLabel(identity.rows[1])).toBe('User');
    expect(sourceLabel(modules[2].rows[0])).toBe('no grant (denied by default)');
  });

  it('labels and module fallback', () => {
    expect(effectLabel('ALLOW')).toBe('Allow');
    expect(effectLabel('DENY')).toBe('Deny');
    expect(effectLabel('NONE')).toBe('-');
    expect(moduleOf('run.approve')).toBe('run');
    expect(groupEffective(null)).toEqual([]);
  });
});

describe('grants editor model', () => {
  it('GrantSet -> overrides: missing = inherit', () => {
    const map = toOverrides({
      level: 'GROUP',
      targetId: 'g',
      grants: [
        { permission: 'a', effect: 'ALLOW' },
        { permission: 'b', effect: 'DENY' },
      ],
    });
    expect(map).toEqual({ a: 'allow', b: 'deny' });
    expect(overrideOf(map, 'c')).toBe('inherit');
  });

  it('inherit removes the grant; the set is sorted and targetId is null for SYSTEM', () => {
    let map = setOverride({}, 'z', 'deny');
    map = setOverride(map, 'a', 'allow');
    map = setOverride(map, 'm', 'allow');
    map = setOverride(map, 'm', 'inherit');
    expect(toGrantSet('SYSTEM', 'ignored', ['member', 'member', 'auditor'], map)).toEqual({
      level: 'SYSTEM',
      targetId: null,
      roles: ['auditor', 'member'],
      grants: [
        { permission: 'a', effect: 'ALLOW' },
        { permission: 'z', effect: 'DENY' },
      ],
    });
  });

  it('round trip and equality ignore order', () => {
    const a = {
      level: 'USER' as const,
      targetId: 'u',
      roles: ['b', 'a'],
      grants: [{ permission: 'x', effect: 'ALLOW' as const }],
    };
    const b = toGrantSet('USER', 'u', ['a', 'b'], toOverrides(a));
    expect(sameGrantSet(a, b)).toBe(true);
    expect(sameGrantSet(a, { ...b, roles: ['a'] })).toBe(false);
  });

  it('picks the set of the target or an empty one', () => {
    const sets = [{ level: 'GROUP' as const, targetId: 'g1', roles: ['x'] }];
    expect(pickGrantSet(sets, 'GROUP', 'g1').roles).toEqual(['x']);
    expect(pickGrantSet(sets, 'GROUP', 'g2')).toEqual({
      level: 'GROUP',
      targetId: 'g2',
      roles: [],
      grants: [],
    });
    expect(pickGrantSet([], 'SYSTEM', 'x').targetId).toBeNull();
  });
});

describe('permission matrix', () => {
  it('groups one scope by module with module toggles', () => {
    const m = matrixFor(CATALOGUE, 'PLATFORM');
    expect(m.map((x) => x.module)).toEqual(['identity', 'tasks']);
    let selected: Set<string> = new Set(['admin.users.view']);
    expect(moduleState(m[0], selected)).toBe('some');
    selected = toggleModule(selected, m[0], true);
    expect(moduleState(m[0], selected)).toBe('all');
    selected = togglePermission(selected, 'admin.users.view', false);
    selected = togglePermission(selected, 'admin.users.manage', false);
    expect(moduleState(m[0], selected)).toBe('none');
    expect(
      unknownPermissions(new Set(['project.view', 'library.view']), CATALOGUE, 'PLATFORM'),
    ).toEqual(['project.view']);
  });
});

describe('audit query', () => {
  it('sends only set filters', () => {
    expect(toAuditQuery(EMPTY_AUDIT_FILTERS)).toEqual({ size: AUDIT_PAGE_SIZE });
    const from = new Date('2026-09-01T00:00:00Z');
    const to = new Date('2026-09-02T00:00:00Z');
    expect(
      toAuditQuery(
        { ...EMPTY_AUDIT_FILTERS, range: [from, to], operation: ' USER_INVITE ', result: 'DENIED' },
        'cur-1',
      ),
    ).toEqual({
      size: AUDIT_PAGE_SIZE,
      from: from.toISOString(),
      to: to.toISOString(),
      operation: 'USER_INVITE',
      result: 'DENIED',
      cursor: 'cur-1',
    });
    expect(toAuditQuery(correlationFilters('c-9')).correlationId).toBe('c-9');
  });
});
