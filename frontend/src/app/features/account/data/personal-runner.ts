import type { Runner } from '../../../core/api/types';

export type Shell = 'bash' | 'powershell';

/** Images of the docker run command, built from the repository root (the same in bash and PowerShell). */
export const BUILD_COMMANDS = [
  'docker build -t back-office/runner:dev runner/',
  'docker build -t back-office/egress-proxy:dev backend/runner-controller/egress-proxy/',
  'docker build -t back-office/runner-controller:dev -f backend/runner-controller/Dockerfile backend/',
].join('\n');

/** The server builds the command for bash (`\` line continuations); PowerShell continues lines with a backtick. */
export function forShell(command: string, shell: Shell): string {
  return shell === 'powershell' ? command.replace(/ \\\n/g, ' `\n') : command;
}

/** Commands that prepare the variables the docker run command passes by name (`-e NAME`). */
export function setupCommands(shell: Shell, localLogin: boolean): string {
  const lines: string[] = [];
  if (shell === 'powershell') {
    if (localLogin)
      lines.push('claude setup-token', '$env:CLAUDE_CODE_OAUTH_TOKEN = "<token printed above>"');
    lines.push(
      '$env:BO_GIT_TOKEN_READ = "<read-only git token>"',
      '$env:BO_GIT_TOKEN_PUSH = "<git token that may push>"',
    );
  } else {
    if (localLogin)
      lines.push('claude setup-token', 'export CLAUDE_CODE_OAUTH_TOKEN="<token printed above>"');
    lines.push(
      'export BO_GIT_TOKEN_READ="<read-only git token>"',
      'export BO_GIT_TOKEN_PUSH="<git token that may push>"',
    );
  }
  return lines.join('\n');
}

/** Online runners first, then by name. */
export function sortRunners(runners: readonly Runner[]): Runner[] {
  return [...runners].sort(
    (a, b) =>
      Number(b.online ?? false) - Number(a.online ?? false) ||
      (a.name ?? '').localeCompare(b.name ?? ''),
  );
}
