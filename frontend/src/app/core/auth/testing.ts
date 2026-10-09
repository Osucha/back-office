/** Test helpers for identity-dependent code (not used by the app itself). */
import type { Me } from '../api/types';

export function meFixture(overrides: Partial<Me> = {}): Me {
  return {
    user: { id: 'u-1', email: 'ann@example.com', displayName: 'Ann Admin', status: 'ACTIVE' },
    platformPermissions: ['projects.create', 'library.view'],
    allProjects: false,
    projects: [
      {
        projectId: 'p-1',
        projectKey: 'SHOP',
        roles: ['developer'],
        permissions: ['project.view', 'workflow.edit', 'run.start'],
      },
      {
        projectId: 'p-2',
        projectKey: 'CRM',
        roles: ['viewer'],
        permissions: ['project.view'],
      },
    ],
    groups: [{ id: 'g-1', name: 'Developers' }],
    idleTimeoutSeconds: 1800,
    preferences: { theme: 'SYSTEM', pageSize: 20 },
    ...overrides,
  };
}
