/**
 * Run workspaces of a project (ADR 0044), shared by the project tab and the workflow designer: key rules and the
 * colour of a workspace (by its position in the project's list), used on the canvas and in the lists.
 */
import type { ProjectWorkspaceInput } from '../api/types';

export const WORKSPACE_KEY_PATTERN = /^[a-z][a-z0-9-]{0,19}$/;

/** Region colours, readable in both themes. */
const COLORS = ['#5a73e0', '#2f9e76', '#d9822b', '#b05cc8', '#3c9cc2', '#c4513f'];

export function workspaceColor(index: number): string {
  if (index < 0) return COLORS[0];
  return COLORS[index % COLORS.length];
}

/** What blocks saving a workspace (the backend refuses these too). */
export function workspaceProblems(
  ws: ProjectWorkspaceInput,
  others: readonly { key: string }[],
): string[] {
  const out: string[] = [];
  if (!WORKSPACE_KEY_PATTERN.test(ws.key ?? ''))
    out.push(
      $localize`:@@core.workspaces.problem.key:Key: 1-20 lower case letters, digits or hyphens, starting with a letter.`,
    );
  else if (others.some((w) => w.key === ws.key))
    out.push(
      $localize`:@@core.workspaces.problem.duplicate:Another workspace of the project has this key.`,
    );
  const badRole = (ws.roles ?? []).find((r) => !WORKSPACE_KEY_PATTERN.test(r));
  if (badRole)
    out.push(
      $localize`:@@core.workspaces.problem.role:Role ${badRole}:role:: lower case letters, digits or hyphens.`,
    );
  return out;
}

/** Display form of the sources of a workspace (`lib-<key>` for library sources, as in a job). */
export function sourceLabels(ws: Pick<ProjectWorkspaceInput, 'sources'>): string[] {
  return (ws.sources ?? []).map((s) => (s.library ? `lib-${s.key}` : s.key));
}
