import { buildPermissionView, isAllowed } from '../core/auth/permissions';
import { meFixture } from '../core/auth/testing';
import { NAVIGATION, visibleNavigation } from './navigation';
import { initialsOf } from './shell.component';
import { navTarget } from './navigation';

describe('menu built from permissions', () => {
  const labels = (platform: string[], allProjects = false) => {
    const view = buildPermissionView(meFixture({ platformPermissions: platform, allProjects }));
    return visibleNavigation(NAVIGATION, (rule) => isAllowed(view, rule)).map((i) => ({
      label: i.label,
      children: i.children?.map((c) => c.label),
    }));
  };

  it('drops entries without permission and empty groups', () => {
    const menu = labels(['library.view']);
    expect(menu.map((m) => m.label)).toEqual([
      'Home',
      'Your turn',
      'Projects',
      'Runs',
      'Insights',
      'CI',
      'Library',
      // Costs: the fixture has no project.usage.view, so it is hidden
    ]);
    expect(menu.find((m) => m.label === 'Administration')).toBeUndefined();
  });

  it('shows only the admin entries the user may open', () => {
    const menu = labels(['admin.audit.view']);
    expect(menu.find((m) => m.label === 'Administration')?.children).toEqual(['Audit log']);
  });

  it('shows Connections under Administration to those who manage platform connections', () => {
    const menu = labels(['admin.connections.manage', 'integrations.view']);
    expect(menu.find((m) => m.label === 'Administration')?.children).toEqual(['Connections']);
    expect(labels(['integrations.view']).find((m) => m.label === 'Administration')).toBeUndefined();
  });

  it('a group links to its first visible child', () => {
    const view = buildPermissionView(meFixture({ platformPermissions: ['admin.audit.view'] }));
    const admin = visibleNavigation(NAVIGATION, (rule) => isAllowed(view, rule)).find(
      (m) => m.label === 'Administration',
    )!;
    expect(navTarget(admin)).toBe('/admin/audit');
  });

  it('avatar initials', () => {
    expect(initialsOf('Ann Admin')).toBe('AA');
    expect(initialsOf('bob.builder@example.com')).toBe('BB');
    expect(initialsOf('x')).toBe('X');
  });
});
