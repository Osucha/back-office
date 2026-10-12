import { forShell, setupCommands, sortRunners } from './personal-runner';

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
    expect(setupCommands('powershell', false)).toContain('$env:BO_GIT_TOKEN_READ');
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
