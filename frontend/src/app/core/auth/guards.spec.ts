import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { NzNotificationService } from 'ng-zorro-antd/notification';
import { PendingSavesService } from '../state/pending-saves.service';
import { ConfirmService } from '../ui/confirm.service';
import {
  authGuard,
  pendingSavesGuard,
  permissionGuard,
  publicOnlyGuard,
  sessionGuard,
  unsavedChangesGuard,
} from './guards';
import { IdentityService } from './identity.service';
import { SessionExpiryService } from './session-expiry.service';
import { meFixture } from './testing';

@Component({ template: 'page' })
class PageComponent {}

@Component({ template: 'editor' })
class EditorComponent {
  dirty = false;
  hasUnsavedChanges(): boolean {
    return this.dirty;
  }
}

describe('route guards', () => {
  let router: Router;
  let identity: IdentityService;
  let confirmAnswer = true;
  const notification = { remove: vi.fn(), warning: vi.fn(), error: vi.fn(), template: vi.fn() };

  beforeEach(() => {
    confirmAnswer = true;
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: NzNotificationService, useValue: notification },
        { provide: ConfirmService, useValue: { confirm: () => Promise.resolve(confirmAnswer) } },
        provideRouter([
          { path: 'login', component: PageComponent, canActivate: [publicOnlyGuard] },
          { path: 'forbidden', component: PageComponent },
          {
            path: '',
            canActivate: [authGuard],
            canActivateChild: [sessionGuard, pendingSavesGuard, permissionGuard],
            children: [
              { path: 'dashboard', component: PageComponent },
              {
                path: 'admin/users',
                component: PageComponent,
                data: { permission: 'admin.users.view' },
              },
              {
                path: 'projects/:projectId',
                component: PageComponent,
                data: { projectPermission: 'project.view' },
              },
              {
                path: 'costs',
                component: PageComponent,
                data: { permission: 'usage.view', projectPermission: 'run.start' },
              },
              { path: 'editor', component: EditorComponent, canDeactivate: [unsavedChangesGuard] },
            ],
          },
        ]),
      ],
    });
    router = TestBed.inject(Router);
    identity = TestBed.inject(IdentityService);
  });

  it('unauthenticated users go to /login with returnTo', async () => {
    await router.navigateByUrl('/admin/users?x=1');
    expect(router.url).toBe('/login?returnTo=%2Fadmin%2Fusers%3Fx%3D1');
  });

  it('signed-in users skip the login page', async () => {
    identity.setMe(meFixture());
    await router.navigateByUrl('/login?returnTo=%2Fdashboard');
    expect(router.url).toBe('/dashboard');
  });

  it('a missing platform permission shows /forbidden and keeps the URL', async () => {
    identity.setMe(meFixture());
    await router.navigateByUrl('/admin/users');
    // skipLocationChange: the router state is /forbidden, the browser URL is unchanged.
    expect(router.routerState.snapshot.url).toContain('/forbidden');
    expect(router.routerState.snapshot.url).toContain('from=%2Fadmin%2Fusers');
  });

  it('project permissions are checked on :projectId', async () => {
    identity.setMe(meFixture());
    await router.navigateByUrl('/projects/p-1');
    expect(router.url).toBe('/projects/p-1');
    await router.navigateByUrl('/projects/other');
    expect(router.routerState.snapshot.url).toContain('/forbidden');
  });

  it('platform or project permission: either grants access', async () => {
    identity.setMe(meFixture());
    await router.navigateByUrl('/costs');
    expect(router.url).toBe('/costs');
  });

  it('an expired session blocks navigation and shows the notice again', async () => {
    identity.setMe(meFixture());
    await router.navigateByUrl('/dashboard');
    TestBed.inject(SessionExpiryService).markExpired();
    notification.warning.mockClear();
    const ok = await router.navigateByUrl('/projects/p-1');
    expect(ok).toBe(false);
    expect(router.url).toBe('/dashboard');
    expect(notification.warning).toHaveBeenCalled();
  });

  it('a save in flight blocks navigation', async () => {
    identity.setMe(meFixture());
    await router.navigateByUrl('/dashboard');
    const finish = TestBed.inject(PendingSavesService).begin();
    expect(await router.navigateByUrl('/projects/p-1')).toBe(false);
    finish();
    expect(await router.navigateByUrl('/projects/p-1')).toBe(true);
  });

  it('unsaved changes ask before leaving', async () => {
    const editor = new EditorComponent();
    const run = () =>
      TestBed.runInInjectionContext(() => unsavedChangesGuard(editor, null!, null!, null!));
    expect(await run()).toBe(true);
    editor.dirty = true;
    confirmAnswer = false;
    expect(await run()).toBe(false);
    confirmAnswer = true;
    expect(await run()).toBe(true);
  });
});
