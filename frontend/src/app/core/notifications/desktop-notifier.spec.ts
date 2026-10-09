import { claim } from './desktop-notifier.service';

describe('cross-tab claim of a notification', () => {
  beforeEach(() => localStorage.removeItem('bo.notifications.claims'));

  it('lets only the first tab notify a tag within a minute', async () => {
    const t = 1_000_000;
    expect(await claim('bo-gate-a1', t)).toBe(true);
    expect(await claim('bo-gate-a1', t + 1000)).toBe(false);
    expect(await claim('bo-gate-a2', t + 1000)).toBe(true);
    expect(await claim('bo-gate-a1', t + 61_000)).toBe(true);
  });
});
