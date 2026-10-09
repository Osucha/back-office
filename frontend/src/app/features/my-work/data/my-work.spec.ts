import type { IntakeRule, Project, WorkflowDefinition } from '../../../core/api/types';
import {
  DEFAULT_JQL,
  emptyRule,
  ruleError,
  ruleInput,
  ruleStatus,
  workflowChoices,
} from './my-work';

const rule = (over: Partial<IntakeRule> = {}): IntakeRule =>
  ({
    id: 'r1',
    name: 'Mine',
    connectionId: 'c1',
    jql: DEFAULT_JQL,
    pollIntervalMinutes: 5,
    workflowId: 'w1',
    enabled: true,
    includeExisting: true,
    initialized: true,
    consecutiveFailures: 0,
    ...over,
  }) as IntakeRule;

describe('my work', () => {
  it('starts a rule with the open issues of the reading account', () => {
    const r = emptyRule();
    expect(r.jql).toContain('assignee = currentUser()');
    expect(r.pollIntervalMinutes).toBe(5);
    expect(r.includeExisting).toBe(true);
    expect(ruleError(r)).toBeTruthy(); // no connection and workflow yet
  });

  it('validates a rule field by field', () => {
    const ok = ruleInput(rule());
    expect(ruleError(ok)).toBeNull();
    expect(ruleError({ ...ok, name: ' ' })).toBeTruthy();
    expect(ruleError({ ...ok, connectionId: '' })).toBeTruthy();
    expect(ruleError({ ...ok, jql: '' })).toBeTruthy();
    expect(ruleError({ ...ok, pollIntervalMinutes: 0 })).toBeTruthy();
    expect(ruleError({ ...ok, pollIntervalMinutes: 1441 })).toBeTruthy();
    expect(ruleError({ ...ok, workflowId: '' })).toBeTruthy();
  });

  it('says where a rule stands', () => {
    expect(ruleStatus(rule({ enabled: false })).tone).toBe('off');
    expect(ruleStatus(rule({ lastError: 'HTTP 401' })).tone).toBe('error');
    expect(ruleStatus(rule({ initialized: false })).tone).toBe('pending');
    expect(ruleStatus(rule()).tone).toBe('ok');
    expect(ruleStatus(rule()).text).toContain('5');
  });

  it('offers enabled workflows, private projects first', () => {
    const p = (id: string, key: string, personal = false) =>
      ({ id, key, name: key, personal }) as Project;
    const w = (id: string, name: string, enabled = true) =>
      ({ id, name, enabled }) as WorkflowDefinition;
    const choices = workflowChoices(
      [p('t', 'TEAM'), p('m', 'MINE', true)],
      new Map([
        ['t', [w('w1', 'Review')]],
        ['m', [w('w2', 'Implement'), w('w3', 'Old', false)]],
      ]),
    );
    expect(choices.map((c) => c.label)).toEqual(['MINE · Implement', 'TEAM · Review']);
    expect(choices[0].personal).toBe(true);
  });
});
