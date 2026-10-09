import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { NzNotificationService } from 'ng-zorro-antd/notification';
import { probe, silent } from '../api/api-base';
import { BROWSER_NAVIGATION } from '../auth/browser-navigation';
import { IdentityService } from '../auth/identity.service';
import { SessionExpiryService } from '../auth/session-expiry.service';
import { meFixture } from '../auth/testing';
import {
  ACCESS_DENIED_TITLE,
  CONNECTION_LOST_TITLE,
  httpErrorInterceptor,
} from './http-error.interceptor';

describe('httpErrorInterceptor', () => {
  let http: HttpClient;
  let ctrl: HttpTestingController;
  let session: SessionExpiryService;
  let identity: IdentityService;
  const notification = {
    remove: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
    template: vi.fn(),
    info: vi.fn(),
    success: vi.fn(),
  };
  const assign = vi.fn();

  beforeEach(() => {
    Object.values(notification).forEach((f) => f.mockReset());
    assign.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([httpErrorInterceptor])),
        provideHttpClientTesting(),
        { provide: NzNotificationService, useValue: notification },
        { provide: BROWSER_NAVIGATION, useValue: { assign, currentPath: () => '/' } },
      ],
    });
    http = TestBed.inject(HttpClient);
    ctrl = TestBed.inject(HttpTestingController);
    session = TestBed.inject(SessionExpiryService);
    identity = TestBed.inject(IdentityService);
    identity.setMe(meFixture());
    TestBed.tick();
  });

  afterEach(() => ctrl.verify());

  it('sends API GETs with cache: no-store and leaves other requests alone', () => {
    http.get('/api/v1/projects').subscribe();
    const api = ctrl.expectOne('/api/v1/projects');
    expect(api.request.cache).toBe('no-store');
    api.flush([]);
    http.get('/assets/x.svg', { responseType: 'text' }).subscribe();
    const asset = ctrl.expectOne('/assets/x.svg');
    expect(asset.request.cache).not.toBe('no-store');
    asset.flush('');
  });

  it('401 starts the session-expired flow instead of redirecting', () => {
    let failed = false;
    http.get('/api/v1/runs').subscribe({ error: () => (failed = true) });
    ctrl.expectOne('/api/v1/runs').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(failed).toBe(true);
    expect(session.expired()).toBe(true);
    expect(identity.isAuthenticated()).toBe(true);
    expect(assign).not.toHaveBeenCalled();
    expect(notification.warning).toHaveBeenCalled();
  });

  it('401 on an identity probe is left to the caller', () => {
    http.get('/api/v1/me', probe()).subscribe({ error: () => undefined });
    ctrl.expectOne('/api/v1/me').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(session.expired()).toBe(false);
  });

  it('status 0 shows "connection lost" and never ends the session', () => {
    http.get('/api/v1/runs').subscribe({ error: () => undefined });
    ctrl.expectOne('/api/v1/runs').error(new ProgressEvent('error'), { status: 0 });
    expect(notification.error).toHaveBeenCalledWith(
      CONNECTION_LOST_TITLE,
      expect.any(String),
      expect.anything(),
    );
    expect(session.expired()).toBe(false);
    expect(identity.isAuthenticated()).toBe(true);
  });

  it('403 shows the detail and the missing permission, even for silent requests', () => {
    http.post('/api/v1/projects', {}, silent()).subscribe({ error: () => undefined });
    ctrl
      .expectOne('/api/v1/projects')
      .flush(
        { detail: 'Not allowed.', permission: 'projects.create' },
        { status: 403, statusText: 'Forbidden' },
      );
    expect(notification.error).toHaveBeenCalledWith(
      ACCESS_DENIED_TITLE,
      'Not allowed. Missing permission: projects.create.',
      expect.anything(),
    );
    expect(assign).not.toHaveBeenCalled();
  });

  it('403 of a restricted session routes to the page that lifts the restriction', async () => {
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    http.get('/api/v1/projects').subscribe({ error: () => undefined });
    ctrl
      .expectOne('/api/v1/projects')
      .flush(
        { detail: 'Change your password first', code: 'password-change-required' },
        { status: 403, statusText: 'Forbidden' },
      );
    expect(navigate).toHaveBeenCalledWith(['/change-password'], expect.anything());
    http.get('/api/v1/runs').subscribe({ error: () => undefined });
    ctrl
      .expectOne('/api/v1/runs')
      .flush({ code: 'mfa-enrolment-required' }, { status: 403, statusText: 'Forbidden' });
    expect(navigate).toHaveBeenCalledWith(['/setup-mfa'], expect.anything());
    expect(notification.error).not.toHaveBeenCalled();
  });

  it('other errors are notified unless the request is silent', () => {
    http.get('/api/v1/x').subscribe({ error: () => undefined });
    ctrl.expectOne('/api/v1/x').flush({ detail: 'Boom' }, { status: 500, statusText: 'x' });
    expect(notification.error).toHaveBeenCalledWith('Server error', 'Boom', expect.anything());
    notification.error.mockClear();
    http.get('/api/v1/y', silent()).subscribe({ error: () => undefined });
    ctrl.expectOne('/api/v1/y').flush({ detail: 'Bad' }, { status: 400, statusText: 'x' });
    expect(notification.error).not.toHaveBeenCalled();
  });
});
