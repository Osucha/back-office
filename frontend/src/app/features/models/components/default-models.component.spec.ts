import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { NzMessageService } from 'ng-zorro-antd/message';
import type { ModelDefaults, ModelProfile } from '../../../core/api/types';
import { DefaultModelsComponent } from './default-models.component';

const profile = (id: string, personal = false): ModelProfile =>
  ({
    id,
    displayName: id.toUpperCase(),
    personal,
    enabled: true,
    ref: { provider: 'ANTHROPIC', modelId: id },
    pricing: { inputPerMTok: 1, outputPerMTok: 5, currency: 'USD' },
  }) as ModelProfile;

const defaults: ModelDefaults = {
  platform: { LLM_CALL: profile('p') },
  mine: { CODING_AGENT: profile('m', true) },
};

describe('DefaultModelsComponent', () => {
  let http: HttpTestingController;
  const success = vi.fn();

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [DefaultModelsComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: NzMessageService, useValue: { success } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function create(canManage: boolean) {
    const fixture = TestBed.createComponent(DefaultModelsComponent);
    fixture.componentRef.setInput('canManage', canManage);
    fixture.detectChanges();
    http.expectOne('/api/v1/model-profiles/defaults').flush(defaults);
    const options = http.match((r) => r.url === '/api/v1/model-profiles/options');
    const asked = options.map(
      (r) =>
        `${r.request.params.get('scope')}:${r.request.params.get('stepKind')}:${r.request.params.get('harness') ?? '-'}`,
    );
    options.forEach((r) => r.flush([]));
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, asked };
  }

  it('asks a manager for the options of both scopes and a user only for their own', () => {
    expect(create(true).asked.sort()).toEqual([
      'PERSONAL:CODING_AGENT:CLAUDE_CODE',
      'PERSONAL:LLM_CALL:-',
      'PLATFORM:CODING_AGENT:CLAUDE_CODE',
      'PLATFORM:LLM_CALL:-',
    ]);
    const { c, asked } = create(false);
    expect(asked.sort()).toEqual(['PERSONAL:CODING_AGENT:CLAUDE_CODE', 'PERSONAL:LLM_CALL:-']);
    const [llm, coding] = c.rows;
    expect(c.value('PLATFORM', llm)).toBe('p');
    expect(c.value('PERSONAL', coding)).toBe('m');
    expect(c.platformName(llm)).toBe('P');
    expect(c.platformName(coding)).toBeNull();
  });

  it('saves my default, and clears a platform default with null', () => {
    const { c } = create(true);
    const [llm, coding] = c.rows;
    c.change('PERSONAL', llm, 'x');
    const mine = http.expectOne('/api/v1/model-profiles/defaults/mine');
    expect(mine.request.body).toEqual({ stepKind: 'LLM_CALL', profileId: 'x' });
    mine.flush({ ...defaults, mine: { ...defaults.mine, LLM_CALL: profile('x', true) } });
    expect(c.value('PERSONAL', llm)).toBe('x');
    expect(success).toHaveBeenCalledWith('Default model saved');

    c.change('PLATFORM', llm, null);
    const platform = http.expectOne('/api/v1/model-profiles/defaults/platform');
    expect(platform.request.body).toEqual({ stepKind: 'LLM_CALL', profileId: null });
    platform.flush({ ...defaults, platform: {} });
    expect(c.value('PLATFORM', llm)).toBeNull();
    expect(success).toHaveBeenCalledWith('Default model cleared');

    // no request when nothing changed
    c.change('PERSONAL', coding, 'm');
    http.expectNone('/api/v1/model-profiles/defaults/mine');
  });

  it('puts the previous model back when saving fails', () => {
    const { c } = create(false);
    const coding = c.rows[1];
    c.change('PERSONAL', coding, 'other');
    expect(c.value('PERSONAL', coding)).toBe('other');
    http
      .expectOne('/api/v1/model-profiles/defaults/mine')
      .flush({ title: 'no' }, { status: 400, statusText: 'Bad Request' });
    expect(c.value('PERSONAL', coding)).toBe('m');
    expect(c.busy()).toBeNull();
  });
});
