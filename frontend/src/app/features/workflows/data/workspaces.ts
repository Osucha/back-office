/**
 * Run workspaces of a workflow (ADR 0044): declared on the definition (`workspaces`), picked by coding steps with
 * `overrides.config.workspace` (plus `sessionRole`, `prepareOnly`), drawn on the canvas as a region around their steps.
 */
import type { WorkflowDefinitionInput, WorkflowWorkspace } from '../../../core/api/types';
import type { Rect } from './auto-layout';

export const WORKSPACE_KEY_PATTERN = /^[a-z][a-z0-9-]{0,19}$/;
export const WORKSPACE_KEY = 'workspace';
export const SESSION_ROLE_KEY = 'sessionRole';
export const PREPARE_ONLY_KEY = 'prepareOnly';
/** Step config keys edited by the workspace section (never by the generic config form). */
export const WORKSPACE_CONFIG_KEYS = [WORKSPACE_KEY, SESSION_ROLE_KEY, PREPARE_ONLY_KEY];

/** Region colours, by the workspace's position in the list (kept readable in both themes). */
const COLORS = ['#5a73e0', '#2f9e76', '#d9822b', '#b05cc8', '#3c9cc2', '#c4513f'];

export function workspaceColor(index: number): string {
  return COLORS[((index % COLORS.length) + COLORS.length) % COLORS.length];
}

export interface StepWorkspace {
  workspace: string | null;
  sessionRole: string | null;
  prepareOnly: boolean;
}

const text = (v: unknown): string | null => {
  const s = typeof v === 'string' ? v.trim() : v == null ? '' : String(v).trim();
  return s ? s : null;
};

export function stepWorkspace(config: Record<string, unknown> | null | undefined): StepWorkspace {
  return {
    workspace: text(config?.[WORKSPACE_KEY]),
    sessionRole: text(config?.[SESSION_ROLE_KEY]),
    prepareOnly: config?.[PREPARE_ONLY_KEY] === true || config?.[PREPARE_ONLY_KEY] === 'true',
  };
}

/** The node config with the workspace settings changed; unset values are removed (undefined when empty). */
export function withStepWorkspace(
  config: Record<string, unknown> | null | undefined,
  change: Partial<StepWorkspace>,
): Record<string, unknown> | undefined {
  const next: Record<string, unknown> = { ...(config ?? {}) };
  const merged = { ...stepWorkspace(config), ...change };
  if (!merged.workspace) {
    // without a workspace neither a session nor preparing makes sense
    merged.sessionRole = null;
    merged.prepareOnly = false;
  }
  for (const [k, v] of [
    [WORKSPACE_KEY, merged.workspace],
    [SESSION_ROLE_KEY, merged.prepareOnly ? null : merged.sessionRole],
    [PREPARE_ONLY_KEY, merged.prepareOnly || null],
  ] as const) {
    if (v === null || v === undefined || v === '') delete next[k];
    else next[k] = v;
  }
  return Object.keys(next).length ? next : undefined;
}

/** Ids of the TASK nodes that use the workspace [key]. */
export function membersOf(def: WorkflowDefinitionInput, key: string): string[] {
  return def.nodes
    .filter((n) => n.kind === 'TASK' && stepWorkspace(n.task?.overrides?.config).workspace === key)
    .map((n) => n.id);
}

/** A new workspace with a free key (`main`, then `ws-2`, `ws-3` ...). */
export function newWorkspace(existing: readonly WorkflowWorkspace[]): WorkflowWorkspace {
  const taken = new Set(existing.map((w) => w.key));
  let key = 'main';
  for (let i = existing.length + 1; taken.has(key); i++) key = `ws-${i}`;
  return { key, name: key, description: '', sources: [], setupCommand: null, roles: [] };
}

/** What blocks saving one workspace (the backend refuses these). */
export function workspaceProblems(ws: WorkflowWorkspace, all: readonly WorkflowWorkspace[]): string[] {
  const out: string[] = [];
  if (!WORKSPACE_KEY_PATTERN.test(ws.key))
    out.push($localize`:@@workflows.workspaces.problem.key:Key: 1-20 lower case letters, digits or hyphens, starting with a letter.`);
  else if (all.filter((w) => w.key === ws.key).length > 1)
    out.push($localize`:@@workflows.workspaces.problem.duplicate:Another workspace has this key.`);
  const badRole = (ws.roles ?? []).find((r) => !WORKSPACE_KEY_PATTERN.test(r));
  if (badRole)
    out.push($localize`:@@workflows.workspaces.problem.role:Role ${badRole}:role:: lower case letters, digits or hyphens.`);
  return out;
}

/**
 * The definition with [workspaces] and the steps following renamed (old key -> new key) or removed (old key -> null)
 * workspaces.
 */
export function applyWorkspaces(
  def: WorkflowDefinitionInput,
  workspaces: WorkflowWorkspace[],
  renames: Readonly<Record<string, string | null>> = {},
): WorkflowDefinitionInput {
  const touched = Object.keys(renames);
  if (!touched.length) return { ...def, workspaces };
  const nodes = def.nodes.map((n) => {
    const config = n.task?.overrides?.config;
    const key = stepWorkspace(config).workspace;
    if (n.kind !== 'TASK' || !n.task || !key || !touched.includes(key)) return n;
    const target = renames[key];
    const next = withStepWorkspace(config, target ? { workspace: target } : { workspace: null });
    return { ...n, task: { ...n.task, overrides: { ...(n.task.overrides ?? {}), config: next } } };
  });
  return { ...def, workspaces, nodes };
}

export interface WorkspaceRegion {
  key: string;
  name: string;
  color: string;
  x: number;
  y: number;
  width: number;
  height: number;
  nodeIds: string[];
}

/** Space around the steps of a region; the top keeps room for the name tab. */
export const REGION_PADDING = 18;
export const REGION_LABEL_SPACE = 26;

/** One region per workspace that has steps: the box around its steps (flow coordinates). */
export function workspaceRegions(
  def: WorkflowDefinitionInput,
  rects: ReadonlyMap<string, Rect>,
): WorkspaceRegion[] {
  return (def.workspaces ?? []).flatMap((ws, i) => {
    const boxes = membersOf(def, ws.key)
      .map((id) => ({ id, r: rects.get(id) }))
      .filter((b): b is { id: string; r: Rect } => !!b.r);
    if (!boxes.length) return [];
    const minX = Math.min(...boxes.map((b) => b.r.x)) - REGION_PADDING;
    const minY = Math.min(...boxes.map((b) => b.r.y)) - REGION_PADDING - REGION_LABEL_SPACE;
    const maxX = Math.max(...boxes.map((b) => b.r.x + b.r.width)) + REGION_PADDING;
    const maxY = Math.max(...boxes.map((b) => b.r.y + b.r.height)) + REGION_PADDING;
    return [
      {
        key: ws.key,
        name: ws.name || ws.key,
        color: workspaceColor(i),
        x: minX,
        y: minY,
        width: maxX - minX,
        height: maxY - minY,
        nodeIds: boxes.map((b) => b.id),
      },
    ];
  });
}
