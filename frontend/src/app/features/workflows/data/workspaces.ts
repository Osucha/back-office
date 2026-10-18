/**
 * Run workspaces in the workflow designer (ADR 0044): the project's workspaces (project tab Workspaces) are picked by
 * coding steps with `overrides.config.workspace` (plus `sessionRole`, `prepareOnly`) and drawn on the canvas as a
 * region around their steps.
 */
import type { ProjectWorkspace, WorkflowDefinitionInput } from '../../../core/api/types';
import { workspaceColor } from '../../../core/ui/workspaces';
import type { Rect } from './auto-layout';

export const WORKSPACE_KEY = 'workspace';
export const SESSION_ROLE_KEY = 'sessionRole';
export const PREPARE_ONLY_KEY = 'prepareOnly';
/** Step config keys edited by the workspace section (never by the generic config form). */
export const WORKSPACE_CONFIG_KEYS = [WORKSPACE_KEY, SESSION_ROLE_KEY, PREPARE_ONLY_KEY];

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

/**
 * One region per workspace the workflow uses: the box around its steps (flow coordinates). The name and colour come
 * from the project's [workspaces]; a key the project does not have is drawn with its key, in grey.
 */
export function workspaceRegions(
  def: WorkflowDefinitionInput,
  rects: ReadonlyMap<string, Rect>,
  workspaces: readonly ProjectWorkspace[],
): WorkspaceRegion[] {
  const keys = [
    ...new Set(def.nodes.map((n) => (n.kind === 'TASK' ? stepWorkspace(n.task?.overrides?.config).workspace : null))),
  ].filter((k): k is string => !!k);
  return keys.flatMap((key) => {
    const boxes = membersOf(def, key)
      .map((id) => ({ id, r: rects.get(id) }))
      .filter((b): b is { id: string; r: Rect } => !!b.r);
    if (!boxes.length) return [];
    const index = workspaces.findIndex((w) => w.key === key);
    const minX = Math.min(...boxes.map((b) => b.r.x)) - REGION_PADDING;
    const minY = Math.min(...boxes.map((b) => b.r.y)) - REGION_PADDING - REGION_LABEL_SPACE;
    const maxX = Math.max(...boxes.map((b) => b.r.x + b.r.width)) + REGION_PADDING;
    const maxY = Math.max(...boxes.map((b) => b.r.y + b.r.height)) + REGION_PADDING;
    return [
      {
        key,
        name: index >= 0 ? workspaces[index].name || key : key,
        color: index >= 0 ? workspaceColor(index) : '#8a8f98',
        x: minX,
        y: minY,
        width: maxX - minX,
        height: maxY - minY,
        nodeIds: boxes.map((b) => b.id),
      },
    ];
  });
}
