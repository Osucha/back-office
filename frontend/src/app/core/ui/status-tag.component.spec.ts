import { humanize, statusColor } from './status-tag.component';

describe('status tag labels and colours', () => {
  it('knows the schedule trigger source and the ADR 0052 executors', () => {
    expect(humanize('SCHEDULE')).toBe('Schedule');
    expect(humanize('START_WORKFLOW')).toBe('Start workflow');
    expect(humanize('PROJECT_DIGEST')).toBe('Project digest');
    expect(statusColor('SCHEDULE')).toBe('cyan');
    expect(statusColor('START_WORKFLOW')).toBe('green');
    expect(statusColor('PROJECT_DIGEST')).toBe('purple');
  });

  it('falls back to sentence case and a neutral colour', () => {
    expect(humanize('SOME_NEW_VALUE')).toBe('Some new value');
    expect(humanize(null)).toBe('-');
    expect(statusColor('SOME_NEW_VALUE')).toBe('default');
  });
});
