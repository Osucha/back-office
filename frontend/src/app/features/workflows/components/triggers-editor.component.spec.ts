import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import type { Trigger } from '../../../core/api/types';
import { TriggersEditorComponent } from './triggers-editor.component';

describe('TriggersEditorComponent', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [TriggersEditorComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    // nz-icon loads its svg files over HTTP
    http.match((r) => r.url.startsWith('assets/')).forEach((r) => r.flush('<svg></svg>'));
    http.verify();
  });

  function create(triggers: Trigger[], readonly = false) {
    const fixture = TestBed.createComponent(TriggersEditorComponent);
    fixture.componentRef.setInput('triggers', triggers);
    fixture.componentRef.setInput('readonly', readonly);
    fixture.detectChanges();
    const emitted: Trigger[][] = [];
    fixture.componentInstance.triggersChange.subscribe((t) => emitted.push(t));
    return { fixture, c: fixture.componentInstance, emitted };
  }

  it('shows the interval input of a SCHEDULE trigger and flags an invalid interval', () => {
    const { fixture } = create([{ type: 'SCHEDULE', intervalMinutes: 3 }]);
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Every (minutes)');
    expect(el.textContent).toContain('never while a run is active');
    const number = el.querySelector('nz-input-number');
    expect(number).not.toBeNull();
    expect(number?.getAttribute('aria-label')).toBe('Trigger 1 every (minutes)');
    expect(number?.getAttribute('aria-invalid')).toBe('true');
    fixture.componentRef.setInput('triggers', [{ type: 'SCHEDULE', intervalMinutes: 30 }]);
    fixture.detectChanges();
    expect(number?.getAttribute('aria-invalid')).toBe('false');
  });

  it('emits the interval, a type switch and an added SCHEDULE trigger', () => {
    const { c, emitted } = create([
      { type: 'MANUAL' },
      { type: 'JIRA_TRANSITION', status: 'Ready' },
      { type: 'API' },
      { type: 'CI' },
    ]);
    c.add();
    expect(emitted.at(-1)?.at(-1)).toEqual({ type: 'SCHEDULE', intervalMinutes: 60 });
    c.changeType(0, 'SCHEDULE');
    expect(emitted.at(-1)?.[0]).toEqual({ type: 'SCHEDULE', intervalMinutes: 60 });
    c.changeType(1, 'MANUAL');
    expect(emitted.at(-1)?.[1]).toEqual({ type: 'MANUAL' });
  });

  it('patches only the edited trigger', () => {
    const { c, emitted } = create([
      { type: 'SCHEDULE', intervalMinutes: 30 },
      { type: 'JIRA_TRANSITION', status: 'Ready' },
    ]);
    c.update(0, { intervalMinutes: 15 });
    expect(emitted.at(-1)).toEqual([
      { type: 'SCHEDULE', intervalMinutes: 15 },
      { type: 'JIRA_TRANSITION', status: 'Ready' },
    ]);
  });
});
