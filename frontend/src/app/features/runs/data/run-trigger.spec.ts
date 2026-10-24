import { runStarter, triggerLabel } from './run-trigger';

describe('run trigger labels', () => {
  it('names every trigger source, SCHEDULE included', () => {
    expect(triggerLabel('MANUAL')).toBe('manual');
    expect(triggerLabel('API')).toBe('API');
    expect(triggerLabel('CI')).toBe('CI');
    expect(triggerLabel('JIRA')).toBe('Jira transition');
    expect(triggerLabel('JIRA_TRANSITION')).toBe('Jira transition');
    expect(triggerLabel('SCHEDULE')).toBe('schedule');
    expect(triggerLabel('SOMETHING')).toBe('something');
  });

  it('shows the person, else the automatic source, else a dash', () => {
    expect(runStarter('Ada', 'SCHEDULE')).toBe('Ada');
    expect(runStarter(null, 'CI')).toBe('CI');
    expect(runStarter(null, 'SCHEDULE')).toBe('schedule');
    expect(runStarter(null, 'API')).toBe('-');
    expect(runStarter(null, undefined)).toBe('-');
  });
});
