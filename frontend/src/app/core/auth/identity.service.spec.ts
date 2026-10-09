import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { BROWSER_NAVIGATION, safeReturnTo } from './browser-navigation';
import { IdentityService } from './identity.service';
import { buildPermissionView, hasProjectPermission, isAllowed } from './permissions';
import { meFixture } from './testing';

describe('IdentityService', () => {
  let identity: IdentityService;
  let http: HttpTestingController;
  const assign = vi.fn();

  beforeEach(() => {
    assign.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: BROWSER_NAVIGATION,
          useValue: { assign, currentPath: () => '/runs?status=FAILED' },
        },
      ],
    });
    identity = TestBed.inject(IdentityService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('can() checks platform permissions, arrays mean any-of', () => {
    identity.setMe(meFixture());
    expect(identity.can('projects.create')).toBe(true);
    expect(identity.can('admin.users.view')).toBe(false);
    expect(identity.can(['admin.users.view', 'library.view'])).toBe(true);
    expect(identity.isAdmin()).toBe(false);
  });

  it('canInProject() checks the permissions of that project, or any project without an id', () => {
    identity.setMe(meFixture());
    expect(identity.canInProject('workflow.edit', 'p-1')).toBe(true);
    expect(identity.canInProject('workflow.edit', 'p-2')).toBe(false);
    expect(identity.canInProject('workflow.edit', 'unknown')).toBe(false);
    expect(identity.canInProject('workflow.edit')).toBe(true);
    expect(identity.canInProject('run.approve')).toBe(false);
    expect(identity.projectRoles('p-2')).toEqual(['viewer']);
    expect(identity.projectsWith('project.view')).toEqual(['p-1', 'p-2']);
  });

  it('allProjects grants every project permission except the CI-token ones', () => {
    identity.setMe(meFixture({ allProjects: true, projects: [] }));
    expect(identity.allProjects()).toBe(true);
    expect(identity.canInProject('project.members.manage', 'any-project')).toBe(true);
    expect(identity.canInProject('ci.execute', 'any-project')).toBe(false);
  });

  it('admin.projects.all in the platform permissions counts as allProjects', () => {
    const view = buildPermissionView(meFixture({ platformPermissions: ['admin.projects.all'] }));
    expect(view.allProjects).toBe(true);
    expect(hasProjectPermission(view, 'run.approve', 'x')).toBe(true);
  });

  it('nobody signed in: every check is false', () => {
    expect(identity.isAuthenticated()).toBe(false);
    expect(identity.can('library.view')).toBe(false);
    expect(identity.canInProject('project.view', 'p-1')).toBe(false);
  });

  it('route rules: platform or project permission', () => {
    const view = buildPermissionView(meFixture());
    expect(isAllowed(view, {})).toBe(true);
    expect(isAllowed(view, { permission: 'usage.view' })).toBe(false);
    expect(isAllowed(view, { permission: 'usage.view', projectPermission: 'run.start' })).toBe(
      true,
    );
    expect(isAllowed(view, { projectPermission: 'workflow.edit' }, 'p-2')).toBe(false);
  });

  it('load(): 200 sets the identity', async () => {
    const done = identity.load();
    http.expectOne('/api/v1/me').flush(meFixture());
    expect(await done).not.toBeNull();
    expect(identity.status()).toBe('authenticated');
    expect(identity.user()?.email).toBe('ann@example.com');
  });

  it('load(): 401 means unauthenticated, never throws', async () => {
    const done = identity.load();
    http.expectOne('/api/v1/me').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(await done).toBeNull();
    expect(identity.status()).toBe('unauthenticated');
  });

  it('load(): network error means unreachable', async () => {
    const done = identity.load();
    http.expectOne('/api/v1/me').error(new ProgressEvent('error'), { status: 0 });
    expect(await done).toBeNull();
    expect(identity.status()).toBe('unreachable');
  });

  it('login() leaves for the backend login URL with returnTo = current page', async () => {
    const done = identity.login();
    http.expectOne('/api/v1/auth/config').flush({ loginUrl: '/oauth2/authorization/oidc' });
    await done;
    expect(assign).toHaveBeenCalledWith(
      '/oauth2/authorization/oidc?returnTo=' + encodeURIComponent('/runs?status=FAILED'),
    );
  });

  it('logout() posts, then navigates to the provider end-session URL', async () => {
    identity.setMe(meFixture());
    const done = identity.logout();
    const req = http.expectOne('/api/v1/auth/logout');
    expect(req.request.method).toBe('POST');
    req.flush({ logoutUrl: 'https://idp/logout?x=1' });
    await done;
    expect(identity.isAuthenticated()).toBe(false);
    expect(assign).toHaveBeenCalledWith('https://idp/logout?x=1');
  });

  it('logout() falls back to /login without a logout URL or on failure', async () => {
    const done = identity.logout();
    http.expectOne('/api/v1/auth/logout').flush({}, { status: 500, statusText: 'x' });
    await done;
    expect(assign).toHaveBeenCalledWith('/login');
  });
});

describe('safeReturnTo', () => {
  it('accepts same-origin paths only', () => {
    expect(safeReturnTo('/projects/1?tab=ci')).toBe('/projects/1?tab=ci');
    expect(safeReturnTo('//evil.com')).toBe('/');
    expect(safeReturnTo('https://evil.com')).toBe('/');
    expect(safeReturnTo('/\\evil.com')).toBe('/');
    expect(safeReturnTo('/login?error=x')).toBe('/');
    expect(safeReturnTo(null)).toBe('/');
  });
});
