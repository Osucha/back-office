import {
  COPILOT_AUTH_MOUNT,
  copilotSetupCommands,
  forShell,
  setupCommands,
  sortRunners,
  withCopilotMount,
} from './personal-runner';

describe('personal runner', () => {
  const command =
    'docker run -d \\\n  -e CLAUDE_CODE_OAUTH_TOKEN \\\n  back-office/runner-controller:dev';

  it('keeps the bash command and converts line continuations for PowerShell', () => {
    expect(forShell(command, 'bash')).toBe(command);
    expect(forShell(command, 'powershell')).toBe(
      'docker run -d `\n  -e CLAUDE_CODE_OAUTH_TOKEN `\n  back-office/runner-controller:dev',
    );
  });

  it('prepares the Claude login only for LOCAL_LOGIN', () => {
    expect(setupCommands('bash', true)).toContain('claude setup-token');
    expect(setupCommands('bash', true)).toContain('export CLAUDE_CODE_OAUTH_TOKEN=');
    expect(setupCommands('powershell', true)).toContain('$env:CLAUDE_CODE_OAUTH_TOKEN');
    expect(setupCommands('bash', false)).not.toContain('CLAUDE_CODE_OAUTH_TOKEN');
    // ADR 0051: no git tokens on the runner any more
    expect(setupCommands('powershell', false)).toBe('');
    expect(setupCommands('bash', true)).not.toContain('BO_GIT_TOKEN');
  });

  it('mounts the OpenCode auth file read-only before the image (ADR 0069)', () => {
    const bash = withCopilotMount(command, 'bash');
    expect(bash).toBe(
      'docker run -d \\\n  -e CLAUDE_CODE_OAUTH_TOKEN \\\n' +
        `  -v "$HOME/.local/share/opencode/auth.json:${COPILOT_AUTH_MOUNT}:ro" \\\n` +
        '  back-office/runner-controller:dev',
    );
    const ps = forShell(withCopilotMount(command, 'powershell'), 'powershell');
    expect(ps).toContain('-v "$env:USERPROFILE\\.local\\share\\opencode\\auth.json:' + COPILOT_AUTH_MOUNT + ':ro" `');
    expect(ps.trim().endsWith('back-office/runner-controller:dev')).toBe(true);
    expect(withCopilotMount('docker run x', 'bash')).toBe('docker run x');
    expect(copilotSetupCommands()).toContain('opencode auth login');
    expect(copilotSetupCommands()).toContain('opencode models github-copilot');
  });

  it('lists online runners first', () => {
    const sorted = sortRunners([
      { id: '1', name: 'b', online: false },
      { id: '2', name: 'z', online: true },
      { id: '3', name: 'a', online: false },
    ]);
    expect(sorted.map((r) => r.id)).toEqual(['2', '3', '1']);
  });
});
