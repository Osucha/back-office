import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Agent } from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { meFixture } from '../../../core/auth/testing';
import { AgentsPageComponent } from './agents-page.component';

const agents: Agent[] = [
  {
    id: 'a0',
    key: 'model-answer',
    name: 'Model answer',
    scope: { type: 'GLOBAL' },
    harness: null,
    builtIn: true,
  },
  {
    id: 'a1',
    key: 'cheap-coder',
    name: 'Cheap coder',
    description: 'OpenCode on a small model',
    scope: { type: 'GLOBAL' },
    harness: 'OPENCODE',
    modelProfileId: 'm1',
    tags: ['budget'],
    builtIn: false,
  },
];

describe('AgentsPageComponent (ADR 0056)', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AgentsPageComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(IdentityService).setMe(
      meFixture({ platformPermissions: ['library.view', 'library.manage'] }),
    );
  });

  function create() {
    const fixture = TestBed.createComponent(AgentsPageComponent);
    fixture.detectChanges();
    http.match((r) => r.url.includes('/projects')).forEach((r) => r.flush([]));
    http
      .match((r) => r.url.includes('/model-profiles'))
      .forEach((r) =>
        r.flush([
          { id: 'm1', displayName: 'Small model', ref: { modelId: 'small' }, enabled: true },
        ]),
      );
    http.expectOne((r) => r.url === '/api/v1/agents').flush(agents);
    fixture.detectChanges();
    return { fixture, page: fixture.componentInstance };
  }

  it('lists agents with their kind and model, built-ins read-only', () => {
    const { fixture, page } = create();
    expect(page.rows().map((a) => a.name)).toEqual(['Model answer', 'Cheap coder']);
    const kinds = Array.from(
      fixture.nativeElement.querySelectorAll('[data-kind]') as NodeListOf<HTMLElement>,
    ).map((e) => e.textContent?.trim());
    expect(kinds).toContain('OpenCode');
    expect(kinds).toContain('Model answer');
    expect(fixture.nativeElement.textContent).toContain('Default for the step');
    expect(page.canEdit(agents[0])).toBe(false);
    expect(page.canEdit(agents[1])).toBe(true);
  });

  it('creates an agent without a key, with the chosen kind', () => {
    const { page } = create();
    page.patch({ name: 'Reviewer' });
    page.setKind('CLAUDE_CODE');
    expect(page.kind()).toBe('CLAUDE_CODE');
    expect(page.canSave()).toBe(true);
    page.save();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/agents');
    expect(req.request.body).toEqual({
      name: 'Reviewer',
      description: '',
      scope: { type: 'GLOBAL', projectId: null },
      harness: 'CLAUDE_CODE',
      modelProfileId: null,
      tags: [],
    });
    req.flush({ ...req.request.body, id: 'a2', key: 'reviewer' });
    http.match((r) => r.url === '/api/v1/agents').forEach((r) => r.flush(agents));
    page.setKind('MODEL_ANSWER');
    expect(page.draft().harness).toBeNull();
  });
});
