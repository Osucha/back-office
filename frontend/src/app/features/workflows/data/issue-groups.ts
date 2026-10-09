/**
 * The designer's validation sheet: issues grouped by node. Workflow-level issues first, then one
 * group per node in canvas order (edge issues go to the node the edge leaves); errors before
 * warnings in a group.
 */
import type { ValidationIssue, WorkflowDefinitionInput } from '../../../core/api/types';
import type { IssueMap } from './workflow-validation';

export type IssueSeverity = 'error' | 'warning';

export interface IssueGroup {
  key: string;
  /** Node to jump to (null: workflow-level issues). */
  nodeId: string | null;
  title: string;
  errors: number;
  warnings: number;
  items: Array<{ severity: IssueSeverity; message: string; where: string | null }>;
}

const WORKFLOW_KEY = '__workflow';

export function groupIssues(m: IssueMap, def: WorkflowDefinitionInput): IssueGroup[] {
  const groups = new Map<string, IssueGroup>();
  const group = (nodeId: string | null): IssueGroup => {
    const key = nodeId ?? WORKFLOW_KEY;
    let g = groups.get(key);
    if (!g) {
      g = newGroup(key, nodeId, def);
      groups.set(key, g);
    }
    return g;
  };
  for (const sev of ['error', 'warning'] as const) addSeverity(m, def, sev, group);
  const order = new Map(def.nodes.map((n) => [n.id, n.position.x * 10 + n.position.y / 1000]));
  return [...groups.values()].sort((a, b) => compareGroups(a, b, order));
}

type IssueLists = { errors: ValidationIssue[]; warnings: ValidationIssue[] };

/** Adds the issues of one severity: workflow-level, per node, then per edge (to the node it leaves). */
function addSeverity(
  m: IssueMap,
  def: WorkflowDefinitionInput,
  sev: IssueSeverity,
  group: (nodeId: string | null) => IssueGroup,
): void {
  const pick = (i: IssueLists) => (sev === 'error' ? i.errors : i.warnings);
  for (const i of pick(m.global)) addIssue(group(null), sev, i.message, i.field ?? null);
  for (const [id, i] of Object.entries(m.nodes))
    for (const x of pick(i)) addIssue(group(id), sev, x.message, null);
  for (const [id, i] of Object.entries(m.edges)) {
    const edge = def.edges.find((e) => e.id === id);
    const where = edge ? `${edge.fromPort} → ${edge.to}` : id;
    for (const x of pick(i)) addIssue(group(edge?.from ?? null), sev, x.message, where);
  }
}

function newGroup(key: string, nodeId: string | null, def: WorkflowDefinitionInput): IssueGroup {
  const node = nodeId ? def.nodes.find((n) => n.id === nodeId) : null;
  return {
    key,
    nodeId: node ? nodeId : null,
    title: groupTitle(nodeId, node?.name || node?.id),
    errors: 0,
    warnings: 0,
    items: [],
  };
}

/** The node's name (or id), "Unknown node x" for a dangling reference, "Workflow" for the rest. */
function groupTitle(nodeId: string | null, nodeLabel: string | undefined): string {
  if (nodeLabel) return nodeLabel;
  return nodeId
    ? $localize`:@@workflows.designer.unknownNode:Unknown node ${nodeId}:id:`
    : $localize`:@@workflows.designer.workflowGroup:Workflow`;
}

function addIssue(g: IssueGroup, sev: IssueSeverity, message: string, where: string | null) {
  g.items.push({ severity: sev, message, where });
  if (sev === 'error') g.errors++;
  else g.warnings++;
}

/** Workflow group first, then by canvas position (left to right, then top to bottom). */
function compareGroups(a: IssueGroup, b: IssueGroup, order: ReadonlyMap<string, number>): number {
  if (a.nodeId === null) return -1;
  if (b.nodeId === null) return 1;
  return (order.get(a.nodeId) ?? 0) - (order.get(b.nodeId) ?? 0);
}
