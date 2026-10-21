import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ModelProfilesApi } from './model-profiles.service';
import type { ModelProfile } from './types';

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
});
