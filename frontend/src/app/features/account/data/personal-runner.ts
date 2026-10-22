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

/**
 * Commands that prepare the variables the docker run command passes by name (`-e NAME`): only the Claude login.
 * Git tokens are no longer set on the runner (ADR 0051): the project's repository tokens reach each job sealed.
 * Empty when there is nothing to prepare.
 */
export function setupCommands(shell: Shell, localLogin: boolean): string {
  if (!localLogin) return '';
  return shell === 'powershell'
    ? ['claude setup-token', '$env:CLAUDE_CODE_OAUTH_TOKEN = "<token printed above>"'].join('\n')
    : ['claude setup-token', 'export CLAUDE_CODE_OAUTH_TOKEN="<token printed above>"'].join('\n');
}

/** Online runners first, then by name. */
export function sortRunners(runners: readonly Runner[]): Runner[] {
  return [...runners].sort(
    (a, b) =>
      Number(b.online ?? false) - Number(a.online ?? false) ||
      (a.name ?? '').localeCompare(b.name ?? ''),
  );
}
