import type { JiraIntake, WorkflowDefinition } from '../../../core/api/types';
import {
  draftOf,
  intakeDraftProblems,
  intakeWorkflowOptions,
  jiraTriggerOf,
  readerState,
} from './jira-intake';

const wf = (id: string, name: string, triggers: WorkflowDefinition['triggers'], enabled = true) =>
  ({ id, name, triggers, enabled }) as WorkflowDefinition;

describe('Jira intake watcher (project settings)', () => {
  const workflows = [
    wf('w1', 'Manual only', [{ type: 'MANUAL' }]),
    wf('w2', 'From Jira', [
      { type: 'MANUAL' },
      { type: 'JIRA_TRANSITION', status: 'Ready for AI' },
    ]),
    wf('w3', 'New issues', [{ type: 'JIRA_TRANSITION', status: ' ' }]),
    wf('w4', 'Disabled', [{ type: 'JIRA_TRANSITION', status: 'Ready' }], false),
  ];

  it('offers the workflows with a Jira trigger first', () => {
    const options = intakeWorkflowOptions(workflows);
    expect(options.map((o) => [o.id, o.usable])).toEqual([
      ['w2', true],
      ['w3', true],
      ['w4', false],
      ['w1', false],
    ]);
    expect(options.find((o) => o.id === 'w3')!.status).toBeNull();
    expect(options.find((o) => o.id === 'w1')!.status).toBeUndefined();
    expect(jiraTriggerOf(workflows[1])).toEqual({ status: 'Ready for AI' });
  });

  it('checks the draft like the server', () => {
    const options = intakeWorkflowOptions(workflows);
    expect(
      intakeDraftProblems({ enabled: false, pollIntervalMinutes: 5, workflowId: null }, options),
    ).toEqual([]);
    expect(
      intakeDraftProblems({ enabled: true, pollIntervalMinutes: 0, workflowId: null }, options),
    ).toHaveLength(2);
    expect(
      intakeDraftProblems({ enabled: true, pollIntervalMinutes: 5, workflowId: 'w1' }, options)[0],
    ).toContain('JIRA_TRANSITION');
    expect(
      intakeDraftProblems({ enabled: true, pollIntervalMinutes: 5, workflowId: 'w2' }, options),
    ).toEqual([]);
  });

  it('maps the reader connection and the stored configuration', () => {
    expect(readerState({ connection: { status: 'OK' } } as JiraIntake)).toBe('ok');
    expect(readerState({ connection: { status: 'OVER_PRIVILEGED' } } as JiraIntake)).toBe(
      'overPrivileged',
    );
    expect(readerState({ connection: { status: 'NONE' } } as JiraIntake)).toBe('none');
    expect(readerState(null)).toBe('none');
    expect(draftOf(null)).toEqual({ enabled: false, pollIntervalMinutes: 5, workflowId: null });
  });
});
