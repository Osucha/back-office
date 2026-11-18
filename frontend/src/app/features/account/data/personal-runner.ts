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

/** ADR 0069: where the runner-controller expects the OpenCode auth file (its `copilot-login.auth-file`). */
export const COPILOT_AUTH_MOUNT = '/var/run/backoffice/opencode-auth.json'; // i18n-ignore: path

/** ADR 0069: OpenCode's auth file on the user's computer (`opencode auth list` prints it). */
export function copilotAuthFile(shell: Shell): string {
  return shell === 'powershell'
    ? '$env:USERPROFILE\\.local\\share\\opencode\\auth.json' // i18n-ignore: path
    : '$HOME/.local/share/opencode/auth.json'; // i18n-ignore: path
}

/**
 * ADR 0069: the docker run command (bash form, as the server builds it) with the OpenCode auth file mounted read-only
 * before the image, the last line; the controller reads only its github-copilot entry.
 */
export function withCopilotMount(command: string, shell: Shell): string {
  const cut = command.lastIndexOf(' \\\n');
  if (cut < 0) return command;
  const mount = `-v "${copilotAuthFile(shell)}:${COPILOT_AUTH_MOUNT}:ro"`;
  return command.slice(0, cut) + ' \\\n  ' + mount + command.slice(cut);
}

/** ADR 0069: one-time sign-in of OpenCode with GitHub Copilot on the user's computer (needs OpenCode installed). */
export function copilotSetupCommands(): string {
  return [
    'npm install -g opencode-ai',
    'opencode auth login',
    'opencode auth list',
    'opencode models github-copilot',
  ].join('\n');
}

/** Online runners first, then by name. */
export function sortRunners(runners: readonly Runner[]): Runner[] {
  return [...runners].sort(
    (a, b) =>
      Number(b.online ?? false) - Number(a.online ?? false) ||
      (a.name ?? '').localeCompare(b.name ?? ''),
  );
}
