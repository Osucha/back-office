import { integrationBaton, integrationCards } from './integration-state';

describe('integration state', () => {
  it('maps configuration and test results to batons', () => {
    expect(integrationBaton(true)).toEqual({ holder: 'done', label: 'configured' });
    expect(integrationBaton(false)).toEqual({ holder: 'idle', label: 'not configured' });
    expect(integrationBaton(true, { ok: true })).toEqual({ holder: 'done', label: 'connected' });
    expect(integrationBaton(true, { ok: false, message: 'x' })).toEqual({
      holder: 'blocked',
      label: 'test failed',
    });
    expect(integrationBaton(false, null, true)).toEqual({
      holder: 'agent',
      label: 'testing',
      live: true,
    });
  });

  it('builds one card per integration with the right test availability', () => {
    const cards = integrationCards({
      jira: { configured: true, url: 'https://jira.example.com' },
      runner: { mode: 'DOCKER', image: 'back-office/runner:latest' },
      langfuse: { enabled: false },
    });
    expect(cards.map((c) => [c.name, c.configured, c.testable])).toEqual([
      ['jira', true, true],
      ['kubernetes', true, false],
      ['langfuse', false, true],
    ]);
    expect(cards[1].lines[0]).toEqual({ label: 'Mode', value: 'Docker' });
    expect(cards[1].untestable).toContain('Docker');
    const k8s = integrationCards({ runner: { mode: 'KUBERNETES', namespace: 'bo' } })[1];
    expect(k8s.testable).toBe(true);
    expect(k8s.untestable).toBeUndefined();
    expect(integrationCards({})[1]).toMatchObject({ configured: false, testable: false });
  });

  it('shows the platform connections on the Jira card and links to Administration → Connections', () => {
    const jira = integrationCards({
      jira: {
        configured: true,
        legacy: true,
        url: 'https://acme.atlassian.net',
        platformConnections: 2,
      },
    })[0];
    expect(jira.lines).toEqual([
      { label: 'Connections', value: '2' },
      { label: 'Legacy URL', value: 'https://acme.atlassian.net' },
    ]);
    expect(jira.link).toEqual({ label: 'Manage connections', path: '/admin/connections' });
    const scoped = integrationCards({ jira: { configured: true, platformConnections: 1 } })[0];
    expect(scoped.lines).toEqual([{ label: 'Connections', value: '1' }]);
  });

  it('shows the runner fleet (backoffice.runner.mode=FLEET, the default)', () => {
    const fleet = integrationCards({
      runner: { mode: 'FLEET' as never, image: 'back-office/runner:latest' },
    })[1];
    expect(fleet).toMatchObject({ name: 'kubernetes', configured: true, testable: true });
    expect(fleet.untestable).toBeUndefined();
    expect(fleet.what).toContain('sandboxes of registered runner pools');
    expect(fleet.lines[0]).toEqual({ label: 'Mode', value: 'Runner fleet' });
    expect(fleet.lines.some((l) => l.label === 'Namespace')).toBe(false);
    expect(fleet.link).toEqual({ label: 'Open runner fleet', path: '/admin/fleet' });
    // the older modes keep their cards
    const docker = integrationCards({ runner: { mode: 'DOCKER' } })[1];
    expect(docker.link).toBeUndefined();
    expect(docker.lines[0].value).toBe('Docker');
  });
});
