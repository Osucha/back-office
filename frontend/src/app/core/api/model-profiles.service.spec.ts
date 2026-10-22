import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ModelProfilesApi } from './model-profiles.service';
import type { ModelDefaults, ModelProfile } from './types';

describe('ModelProfilesApi', () => {
  let api: ModelProfilesApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(ModelProfilesApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('listCallable() leaves out Claude Code login profiles (ADR 0048)', () => {
    let names: string[] = [];
    api.listCallable().subscribe((list) => (names = list.map((p) => p.displayName)));
    const profile = (displayName: string, runtime?: ModelProfile['runtime']) =>
      ({ id: displayName, displayName, runtime }) as unknown as ModelProfile;
    const req = http.expectOne(
      (r) => r.url.endsWith('/model-profiles') && r.params.get('enabled') === 'true',
    );
    req.flush(
      [profile('API'), profile('Default'), profile('Opus (my login)', 'CLAUDE_CODE_LOGIN')].map(
        (p, i) => (i === 0 ? { ...p, runtime: 'API' } : p),
      ),
    );
    expect(names).toEqual(['API', 'Default']);
  });

  it('defaults() reads the platform and personal defaults (ADR 0050 step 2)', () => {
    let result: ModelDefaults | null = null;
    api.defaults().subscribe((d) => (result = d));
    const req = http.expectOne('/api/v1/model-profiles/defaults');
    expect(req.request.method).toBe('GET');
    const body: ModelDefaults = { platform: {}, mine: {} };
    req.flush(body);
    expect(result).toEqual(body);
  });

  it('setPlatformDefault() and setMyDefault() send the step kind and profile id', () => {
    api.setPlatformDefault('LLM_CALL', 'p1').subscribe();
    const platform = http.expectOne('/api/v1/model-profiles/defaults/platform');
    expect(platform.request.method).toBe('PUT');
    expect(platform.request.body).toEqual({ stepKind: 'LLM_CALL', profileId: 'p1' });
    platform.flush({ platform: {}, mine: {} });

    api.setMyDefault('CODING_AGENT', null).subscribe();
    const mine = http.expectOne('/api/v1/model-profiles/defaults/mine');
    expect(mine.request.method).toBe('PUT');
    expect(mine.request.body).toEqual({ stepKind: 'CODING_AGENT', profileId: null });
    mine.flush({ platform: {}, mine: {} });
  });

  it('defaultOptions() passes step kind, scope and the harness when given', () => {
    api.defaultOptions('CODING_AGENT', 'PERSONAL', 'CLAUDE_CODE').subscribe();
    const req = http.expectOne((r) => r.url === '/api/v1/model-profiles/options');
    expect(req.request.params.get('stepKind')).toBe('CODING_AGENT');
    expect(req.request.params.get('scope')).toBe('PERSONAL');
    expect(req.request.params.get('harness')).toBe('CLAUDE_CODE');
    req.flush([]);

    api.defaultOptions('LLM_CALL', 'PLATFORM').subscribe();
    const plain = http.expectOne((r) => r.url === '/api/v1/model-profiles/options');
    expect(plain.request.params.has('harness')).toBe(false);
    expect(plain.request.params.get('scope')).toBe('PLATFORM');
    plain.flush([]);
  });
});
