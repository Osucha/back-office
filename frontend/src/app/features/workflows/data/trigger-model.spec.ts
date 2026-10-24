import type { Trigger } from '../../../core/api/types';
import { normalizeDefinition } from './graph-model';
import {
  addTrigger,
  changeTriggerType,
  DEFAULT_SCHEDULE_MINUTES,
  newTrigger,
  patchTrigger,
  validScheduleInterval,
} from './trigger-model';

describe('trigger model', () => {
  it('creates triggers with only the fields of their type', () => {
    expect(newTrigger('MANUAL')).toEqual({ type: 'MANUAL' });
    expect(newTrigger('JIRA_TRANSITION')).toEqual({ type: 'JIRA_TRANSITION', status: '' });
    expect(newTrigger('SCHEDULE')).toEqual({
      type: 'SCHEDULE',
      intervalMinutes: DEFAULT_SCHEDULE_MINUTES,
    });
    expect(newTrigger('API', { status: 'x', intervalMinutes: 10 })).toEqual({ type: 'API' });
  });

  it('adds the first unused type, SCHEDULE last, then API', () => {
    let list: Trigger[] = [];
    for (let i = 0; i < 6; i++) list = addTrigger(list);
    expect(list.map((t) => t.type)).toEqual([
      'MANUAL',
      'JIRA_TRANSITION',
      'API',
      'CI',
      'SCHEDULE',
      'API',
    ]);
    expect(list[4]).toEqual({ type: 'SCHEDULE', intervalMinutes: DEFAULT_SCHEDULE_MINUTES });
  });

  it('switches types, keeping a value entered before and dropping foreign fields', () => {
    const list: Trigger[] = [{ type: 'SCHEDULE', intervalMinutes: 15 }, { type: 'MANUAL' }];
    const asJira = changeTriggerType(list, 0, 'JIRA_TRANSITION');
    expect(asJira[0]).toEqual({ type: 'JIRA_TRANSITION', status: '' });
    expect(asJira[1]).toBe(list[1]);
    expect(changeTriggerType(list, 1, 'SCHEDULE')[1]).toEqual({
      type: 'SCHEDULE',
      intervalMinutes: DEFAULT_SCHEDULE_MINUTES,
    });
    expect(changeTriggerType(list, 0, 'SCHEDULE')[0]).toEqual({
      type: 'SCHEDULE',
      intervalMinutes: 15,
    });
  });

  it('patches the interval and keeps a cleared one empty so validation can flag it', () => {
    const list: Trigger[] = [
      { type: 'JIRA_TRANSITION', status: 'Ready' },
      { type: 'SCHEDULE', intervalMinutes: 30 },
    ];
    expect(patchTrigger(list, 1, { intervalMinutes: 45 })).toEqual([
      { type: 'JIRA_TRANSITION', status: 'Ready' },
      { type: 'SCHEDULE', intervalMinutes: 45 },
    ]);
    expect(patchTrigger(list, 1, { intervalMinutes: null })[1]).toEqual({
      type: 'SCHEDULE',
      intervalMinutes: null,
    });
    expect(patchTrigger([{ type: 'API', status: 'stale' } as Trigger], 0, {})).toEqual([
      { type: 'API' },
    ]);
  });

  it('validates the interval: whole minutes, at least 5', () => {
    expect(validScheduleInterval(5)).toBe(true);
    expect(validScheduleInterval(1440)).toBe(true);
    expect(validScheduleInterval(4)).toBe(false);
    expect(validScheduleInterval(5.5)).toBe(false);
    expect(validScheduleInterval(null)).toBe(false);
    expect(validScheduleInterval(undefined)).toBe(false);
  });

  it('round-trips intervalMinutes through the definition normalisation', () => {
    const d = normalizeDefinition({
      name: 'x',
      triggers: [{ type: 'SCHEDULE', intervalMinutes: 20 }],
    });
    expect(d.triggers).toEqual([{ type: 'SCHEDULE', intervalMinutes: 20 }]);
  });
});
