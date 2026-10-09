import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { NzNotificationService } from 'ng-zorro-antd/notification';
import { BROWSER_NAVIGATION } from './browser-navigation';
import { IdentityService } from './identity.service';
import { SESSION_NOTICE_KEY, SessionExpiryService } from './session-expiry.service';
import { meFixture } from './testing';

describe('SessionExpiryService', () => {
  let session: SessionExpiryService;
  let identity: IdentityService;
  let http: HttpTestingController;
  const notification = { remove: vi.fn(), warning: vi.fn(), template: vi.fn() };
  const assign = vi.fn();

  beforeEach(() => {
    vi.useFakeTimers({ now: new Date('2026-09-27T10:00:00Z') });
    Object.values(notification).forEach((f) => f.mockReset());
    assign.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: NzNotificationService, useValue: notification },
        { provide: BROWSER_NAVIGATION, useValue: { assign, currentPath: () => '/projects/p-1' } },
      ],
    });
    session = TestBed.inject(SessionExpiryService);
    identity = TestBed.inject(IdentityService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  function signIn(idleSeconds = 300, absoluteInSeconds?: number): void {
    identity.setMe(
      meFixture({
        idleTimeoutSeconds: idleSeconds,
        absoluteExpiresAt: absoluteInSeconds
          ? new Date(Date.now() + absoluteInSeconds * 1000).toISOString()
          : undefined,
      }),
    );
    TestBed.tick();
  }

  it('warns 60 s before the idle deadline and expires at the deadline', () => {
    signIn(300);
    vi.advanceTimersByTime(239_000);
    expect(session.warning()).toBeNull();
    vi.advanceTimersByTime(1_000);
    expect(session.warning()).toBe('idle');
    expect(session.secondsLeft()).toBe(60);
    vi.advanceTimersByTime(30_000);
    expect(session.secondsLeft()).toBe(30);
    vi.advanceTimersByTime(30_000);
    expect(session.expired()).toBe(true);
    const lastCall = notification.warning.mock.calls.at(-1)!;
    expect(lastCall[0]).toBe('Your session has expired');
    expect(lastCall[2]).toEqual({ nzKey: SESSION_NOTICE_KEY, nzDuration: 0 });
  });

  it('a successful API response (touched) moves the deadline', () => {
    signIn(300);
    vi.advanceTimersByTime(200_000);
    session.touched();
    vi.advanceTimersByTime(200_000);
    expect(session.expired()).toBe(false);
    vi.advanceTimersByTime(100_000);
    expect(session.expired()).toBe(true);
  });

  it('user input after a quiet minute extends the server session with GET /me', () => {
    signIn(300);
    vi.advanceTimersByTime(30_000);
    window.dispatchEvent(new KeyboardEvent('keydown'));
    http.expectNone('/api/v1/me');
    vi.advanceTimersByTime(40_000);
    window.dispatchEvent(new MouseEvent('mousedown'));
    http.expectOne('/api/v1/me').flush(meFixture({ idleTimeoutSeconds: 300 }));
    // (the interceptor would call touched(); here the request is raw)
    http.verify();
  });

  it('"Stay signed in" calls /me (not /auth/session) and never polls on its own', () => {
    signIn(300);
    vi.advanceTimersByTime(250_000);
    http.expectNone(() => true);
    session.staySignedIn();
    http.expectOne('/api/v1/me').flush(meFixture());
    session.touched();
    expect(session.warning()).toBeNull();
    expect(notification.remove).toHaveBeenCalledWith(SESSION_NOTICE_KEY);
  });

  it('the absolute limit wins over the idle deadline and cannot be extended', () => {
    signIn(1800, 120);
    vi.advanceTimersByTime(60_000);
    expect(session.warning()).toBe('absolute');
    session.touched();
    expect(session.warning()).toBe('absolute');
    vi.advanceTimersByTime(60_000);
    expect(session.expired()).toBe(true);
  });

  it('"Sign in again" starts the login with returnTo, "Stay on this page" keeps the screen', async () => {
    signIn(300);
    session.markExpired();
    session.stayOnPage();
    expect(session.expired()).toBe(true);
    expect(assign).not.toHaveBeenCalled();
    session.signInAgain();
    http.expectOne('/api/v1/auth/config').flush({ loginUrl: '/oauth2/authorization/oidc' });
    await vi.runAllTimersAsync();
    expect(assign).toHaveBeenCalledWith('/oauth2/authorization/oidc?returnTo=%2Fprojects%2Fp-1');
  });

  it('does nothing when nobody is signed in', () => {
    session.markExpired();
    expect(session.expired()).toBe(false);
    expect(notification.warning).not.toHaveBeenCalled();
  });
});
