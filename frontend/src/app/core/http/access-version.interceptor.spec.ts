import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { IdentityService } from '../auth/identity.service';
import { meFixture } from '../auth/testing';
import {
  ACCESS_VERSION_HEADER,
  AccessFreshness,
  DENIED_REFRESH_COOLDOWN_MS,
  accessVersionInterceptor,
} from './access-version.interceptor';

describe('accessVersionInterceptor', () => {
  let http: HttpClient;
  let ctrl: HttpTestingController;
  let identity: IdentityService;
  let resolveRefresh: () => void;
  let refresh: ReturnType<typeof vi.fn>;

  const get = (url: string) => http.get(url).subscribe({ error: () => undefined });
  const answer = (url: string, version: number | null, status = 200) => {
    const headers: Record<string, string> =
      version === null ? {} : { [ACCESS_VERSION_HEADER]: String(version) };
    const req = ctrl.expectOne(url);
    if (status === 200) req.flush({}, { headers });
    else req.flush({ status, detail: 'nope' }, { status, statusText: 'Forbidden', headers });
  };
  /** Lets the pending refresh finish (its `finally` runs in a microtask). */
  const settle = async () => {
    resolveRefresh();
    await Promise.resolve();
    await Promise.resolve();
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([accessVersionInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    ctrl = TestBed.inject(HttpTestingController);
    identity = TestBed.inject(IdentityService);
    identity.setMe(meFixture({ accessVersion: 5 }));
    refresh = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          resolveRefresh = resolve;
        }),
    );
    identity.refresh = refresh as unknown as IdentityService['refresh'];
  });

  afterEach(() => ctrl.verify());

  it('does nothing while the announced version equals the loaded one or is older', () => {
    get('/api/v1/projects');
    answer('/api/v1/projects', 5);
    get('/api/v1/runs');
    answer('/api/v1/runs', 4);
    get('/api/v1/library');
    answer('/api/v1/library', null);
    expect(refresh).not.toHaveBeenCalled();
  });

  it('reloads /me once when parallel responses announce a newer version', async () => {
    get('/api/v1/projects');
    get('/api/v1/runs');
    get('/api/v1/library');
    answer('/api/v1/projects', 6);
    answer('/api/v1/runs', 6);
    answer('/api/v1/library', 7);
    expect(refresh).toHaveBeenCalledTimes(1);

    // the reload answered with an older version (a replica that had not seen the change yet): no loop on 6
    await settle();
    get('/api/v1/projects');
    answer('/api/v1/projects', 6);
    expect(refresh).toHaveBeenCalledTimes(1);
    // a newer change later is followed again
    get('/api/v1/projects');
    answer('/api/v1/projects', 8);
    expect(refresh).toHaveBeenCalledTimes(2);
  });

  it('stops once /me carries the new version', async () => {
    get('/api/v1/projects');
    answer('/api/v1/projects', 6);
    identity.setMe(meFixture({ accessVersion: 6 }));
    await settle();
    get('/api/v1/projects');
    answer('/api/v1/projects', 6);
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it('reloads /me on a 403, at most once per cool-down', async () => {
    get('/api/v1/admin/users');
    answer('/api/v1/admin/users', 5, 403);
    expect(refresh).toHaveBeenCalledTimes(1);
    await settle();
    get('/api/v1/admin/users');
    answer('/api/v1/admin/users', 5, 403);
    expect(refresh).toHaveBeenCalledTimes(1);

    TestBed.inject(AccessFreshness).denied(Date.now() + DENIED_REFRESH_COOLDOWN_MS + 1);
    expect(refresh).toHaveBeenCalledTimes(2);
    await settle();
  });

  it('does not reload for the 403 of a restricted session (forced password change)', () => {
    get('/api/v1/projects');
    ctrl
      .expectOne('/api/v1/projects')
      .flush({ code: 'password-change-required' }, { status: 403, statusText: 'Forbidden' });
    expect(refresh).not.toHaveBeenCalled();
  });

  it('ignores /me itself, other origins and a signed-out user', () => {
    http.get('/api/v1/me').subscribe();
    answer('/api/v1/me', 9);
    http.get('/assets/x.json').subscribe();
    answer('/assets/x.json', 9);
    expect(refresh).not.toHaveBeenCalled();

    identity.setMe(null);
    get('/api/v1/projects');
    answer('/api/v1/projects', 9, 403);
    expect(refresh).not.toHaveBeenCalled();
  });
});
