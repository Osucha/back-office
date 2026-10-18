/**
 * Pure helpers for the workflow graph: node-kind metadata, output ports, edge identity,
 * immutable mutations of a `WorkflowDefinitionInput` and the mapping to the canvas model
 * consumed by `WorkflowCanvasComponent` (ngx-vflow).
 */
import type {
  NodeExecution,
  NodeExecutionStatus,
  NodeKind,
  PaletteTask,
  Point,
  WorkflowDefinitionInput,
  WorkflowEdge,
  WorkflowNode,
  WorkflowPalette,
} from '../../../core/api/types';

import { SLUG_PATTERN } from '../../../core/forms/patterns';

export const NODE_ID_PATTERN = SLUG_PATTERN;
export { SLUG_PATTERN };

export interface NodeKindMeta {
  kind: NodeKind;
  title: string;
  icon: string;
  /** Accent colour used by the node frame and the palette. */
  color: string;
  /** Output ports when nothing more specific is known (template ports, branches, options). */
  defaultPorts: string[];
  /** Whether the node accepts incoming edges. */
  hasInput: boolean;
  description: string;
}

export const NODE_KIND_META: Record<NodeKind, NodeKindMeta> = {
  START: {
    kind: 'START',
    title: $localize`:@@workflows.kind.start:Start`,
    icon: 'play-circle',
    color: 'var(--bo-ink-2)',
    defaultPorts: ['out'],
    hasInput: false,
    description: $localize`:@@workflows.kind.start.description:Entry point; exactly one per workflow.`,
  },
  END: {
    kind: 'END',
    title: $localize`:@@workflows.kind.end:End`,
    icon: 'flag',
    color: 'var(--bo-done)',
    defaultPorts: [],
    hasInput: true,
    description: $localize`:@@workflows.kind.end.description:Terminal node. Its outcome decides the run result and the CI gate.`,
  },
  TASK: {
    kind: 'TASK',
    title: $localize`:@@workflows.kind.task:Task`,
    icon: 'robot',
    color: 'var(--bo-ink)',
    defaultPorts: ['success', 'failure'],
    hasInput: true,
    description: $localize`:@@workflows.kind.task.description:Executes a task template from the library with optional overrides.`,
  },
  DECISION: {
    kind: 'DECISION',
    title: $localize`:@@workflows.kind.decision:Decision`,
    icon: 'fork',
    color: 'var(--bo-accent)',
    defaultPorts: [],
    hasInput: true,
    description: $localize`:@@workflows.kind.decision.description:Routes on boolean expressions over the run state; one port per branch.`,
  },
  HUMAN_GATE: {
    kind: 'HUMAN_GATE',
    title: $localize`:@@workflows.kind.humanGate:Human gate`,
    icon: 'pause-circle',
    color: 'var(--bo-you)',
    defaultPorts: ['approve', 'revise', 'reject'],
    hasInput: true,
    description: $localize`:@@workflows.kind.humanGate.description:Pauses until the owner picks an option in the inbox; one port per option.`,
  },
  WAIT_FOR_CI: {
    kind: 'WAIT_FOR_CI',
    title: $localize`:@@workflows.kind.waitForCi:Wait for CI`,
    icon: 'cloud-sync',
    color: 'var(--bo-ci)',
    defaultPorts: ['success', 'fail', 'timeout'],
    hasInput: true,
    description: $localize`:@@workflows.kind.waitForCi.description:Pauses until the project CI sends the named signal for this run.`,
  },
  NOTE: {
    kind: 'NOTE',
    title: $localize`:@@workflows.kind.note:Note`,
    icon: 'file-text',
    color: 'var(--bo-line-strong)',
    defaultPorts: [],
    hasInput: false,
    description: $localize`:@@workflows.kind.note.description:Sticky note on the canvas; never executed.`,
  },
};

/** Fast lookups over the designer palette. */
export interface PaletteIndex {
  tasks: Map<string, PaletteTask>;
  controlPorts: Map<NodeKind, string[]>;
}

export function indexPalette(palette: WorkflowPalette | null | undefined): PaletteIndex {
  const tasks = new Map<string, PaletteTask>();
  for (const t of palette?.tasks ?? []) if (t.key) tasks.set(t.key, t);
  const controlPorts = new Map<NodeKind, string[]>();
  for (const c of palette?.controlNodes ?? []) {
    if (c.kind && c.ports?.length) controlPorts.set(c.kind, c.ports);
  }
  return { tasks, controlPorts };
}

/** The port a gate leaves through when its approval expires (always present, like on the server). */
export const GATE_TIMEOUT_PORT = 'timeout';

/**
 * Ports that end the run when they are taken while unconnected (`failure`/`fail`/`timeout` fail it,
 * `reject` cancels it). The server does not warn about them; neither do we.
 */
export const TERMINAL_WHEN_UNCONNECTED = new Set(['failure', 'fail', 'timeout', 'reject']);

const distinct = (ports: string[]) => {
  const seen = new Set<string>();
  return ports.filter((p) => {
    const k = p.toLowerCase();
    if (!p || seen.has(k)) return false;
    seen.add(k);
    return true;
  });
};

/**
 * Output ports of a node, exactly as the server's `WorkflowValidator.effectivePorts` sees them:
 * template ports for tasks, branch ids plus the default branch for decisions (the default branch is
 * a port of its own when it is not a branch id), gate options plus `timeout`, the kind default else.
 */
export function portsFor(node: WorkflowNode, palette?: PaletteIndex | null): string[] {
  switch (node.kind) {
    case 'START':
      return palette?.controlPorts.get('START') ?? NODE_KIND_META.START.defaultPorts;
    case 'TASK': {
      const key = node.task?.templateKey;
      const fromPalette = key ? palette?.tasks.get(key)?.ports : undefined;
      return fromPalette?.length ? fromPalette : NODE_KIND_META.TASK.defaultPorts;
    }
    case 'DECISION':
      return distinct([
        ...(node.decision?.branches ?? []).map((b) => b.id),
        ...(node.decision?.defaultBranch ? [node.decision.defaultBranch] : []),
      ]);
    case 'HUMAN_GATE': {
      const options = node.gate?.options ?? [];
      return distinct([
        ...(options.length ? options : NODE_KIND_META.HUMAN_GATE.defaultPorts),
        GATE_TIMEOUT_PORT,
      ]);
    }
    case 'WAIT_FOR_CI':
      return palette?.controlPorts.get('WAIT_FOR_CI') ?? NODE_KIND_META.WAIT_FOR_CI.defaultPorts;
    case 'END':
    case 'NOTE':
    default:
      return [];
  }
}

/** Ports that must be connected when the node is reachable (server `requiredPorts`). */
export function requiredPortsFor(node: WorkflowNode, palette?: PaletteIndex | null): string[] {
  const ports = portsFor(node, palette);
  switch (node.kind) {
    case 'START':
      return ports.slice(0, 1);
    case 'TASK':
      return ports.includes('success') ? ['success'] : ports.slice(0, 1);
    case 'DECISION':
      return ports;
    case 'HUMAN_GATE':
      return [node.gate?.options?.[0] ?? 'approve'];
    case 'WAIT_FOR_CI':
      return ['success'];
    default:
      return [];
  }
}

/**
 * Ports drawn on the canvas: every port, except a gate's `timeout` while the gate has no timeout
 * and nothing is connected to it (it can never be taken, so it would only be noise).
 */
export function visiblePortsFor(
  node: WorkflowNode,
  palette: PaletteIndex | null | undefined,
  connected: ReadonlySet<string>,
): string[] {
  const ports = portsFor(node, palette);
  if (node.kind !== 'HUMAN_GATE') return ports;
  const hasTimeout = !!(node.gate?.timeoutSeconds || node.timeoutSeconds);
  return ports.filter((p) => p !== GATE_TIMEOUT_PORT || hasTimeout || connected.has(p));
}

export function edgeId(from: string, fromPort: string, to: string): string {
  return `${from}.${fromPort}->${to}`;
}

/**
 * Adds an edge, keeping the invariant "one edge per (from, fromPort)": an existing edge from the
 * same port is replaced. Self-loops are allowed (revision loops), NOTE/END sources are not.
 */
export function upsertEdge(edges: WorkflowEdge[], edge: Omit<WorkflowEdge, 'id'>): WorkflowEdge[] {
  const next = edges.filter((e) => !(e.from === edge.from && e.fromPort === edge.fromPort));
  next.push({ ...edge, id: edgeId(edge.from, edge.fromPort, edge.to) });
  return next;
}

export function removeEdge(edges: WorkflowEdge[], id: string): WorkflowEdge[] {
  return edges.filter((e) => e.id !== id);
}

/** Generates a unique slug for a new node from a base (kind or template key). */
export function uniqueNodeId(base: string, existing: Iterable<string>): string {
  const taken = new Set(existing);
  let slug = base
    .toLowerCase()
    .replace(/[^a-z0-9-]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 36);
  if (!slug || !/^[a-z]/.test(slug)) slug = `n-${slug}`.replace(/-+$/, '');
  if (!taken.has(slug)) return slug;
  let i = 2;
  while (taken.has(`${slug}-${i}`)) i++;
  return `${slug}-${i}`;
}

/** A fresh node of the given kind (or task template) with sensible defaults. */
export function createNode(
  kind: NodeKind,
  position: Point,
  existingIds: Iterable<string>,
  task?: PaletteTask | null,
): WorkflowNode {
  const meta = NODE_KIND_META[kind];
  const base = kind === 'TASK' && task?.key ? task.key : kind;
  const node: WorkflowNode = {
    id: uniqueNodeId(base, existingIds),
    kind,
    name: kind === 'TASK' ? (task?.name ?? task?.key ?? meta.title) : meta.title,
    position: { x: Math.round(position.x), y: Math.round(position.y) },
    maxVisits: 3,
  };
  switch (kind) {
    case 'TASK':
      node.task = { templateKey: task?.key ?? '', overrides: {} };
      break;
    case 'DECISION':
      node.decision = {
        branches: [
          {
            id: 'yes',
            label: $localize`:@@workflows.newNode.yes:Yes`,
            expression: "lastOutcome == 'SUCCESS'",
          },
          { id: 'no', label: $localize`:@@workflows.newNode.no:No`, expression: 'true' },
        ],
        defaultBranch: 'no',
      };
      node.name = meta.title;
      break;
    case 'HUMAN_GATE':
      node.gate = {
        question: $localize`:@@workflows.newNode.gateQuestion:Approve to continue?`,
        options: ['approve', 'revise', 'reject'],
        showArtifacts: [],
        timeoutSeconds: null,
      };
      break;
    case 'WAIT_FOR_CI':
      node.ci = { signal: 'tests', timeoutSeconds: 3600 };
      node.name = $localize`:@@workflows.newNode.waitForCiTests:Wait for CI: tests`;
      break;
    case 'END':
      node.end = { outcome: 'SUCCESS' };
      node.name = $localize`:@@workflows.newNode.endSuccess:End (success)`;
      break;
    case 'NOTE':
      node.note = { text: meta.title };
      break;
    case 'START':
      node.name = meta.title;
      break;
  }
  return node;
}

export function replaceNode(
  def: WorkflowDefinitionInput,
  id: string,
  next: WorkflowNode,
): WorkflowDefinitionInput {
  const nodes = def.nodes.map((n) => (n.id === id ? next : n));
  let edges = def.edges;
  if (next.id !== id) {
    edges = def.edges.map((e) => {
      const from = e.from === id ? next.id : e.from;
      const to = e.to === id ? next.id : e.to;
      return from === e.from && to === e.to
        ? e
        : { ...e, from, to, id: edgeId(from, e.fromPort, to) };
    });
  }
  // Drop edges leaving ports that no longer exist (renamed branch / removed option).
  const ports = new Set(portsFor(next).map((p) => p.toLowerCase()));
  const stillValid = (e: WorkflowEdge) =>
    e.from !== next.id ||
    next.kind === 'TASK' ||
    next.kind === 'START' ||
    ports.has(e.fromPort.toLowerCase());
  edges = edges.filter(stillValid);
  return { ...def, nodes, edges };
}

export function removeNode(def: WorkflowDefinitionInput, id: string): WorkflowDefinitionInput {
  return {
    ...def,
    nodes: def.nodes.filter((n) => n.id !== id),
    edges: def.edges.filter((e) => e.from !== id && e.to !== id),
  };
}

export function moveNode(
  def: WorkflowDefinitionInput,
  id: string,
  position: Point,
): WorkflowDefinitionInput {
  const rounded = { x: Math.round(position.x), y: Math.round(position.y) };
  const node = def.nodes.find((n) => n.id === id);
  if (!node || (node.position.x === rounded.x && node.position.y === rounded.y)) return def;
  return { ...def, nodes: def.nodes.map((n) => (n.id === id ? { ...n, position: rounded } : n)) };
}

/** Duplicates a node next to the original (edges are not copied). */
export function duplicateNode(def: WorkflowDefinitionInput, id: string): WorkflowDefinitionInput {
  const src = def.nodes.find((n) => n.id === id);
  if (!src || src.kind === 'START') return def;
  const copy: WorkflowNode = structuredClone(src);
  copy.id = uniqueNodeId(
    src.id.replace(/-\d+$/, ''),
    def.nodes.map((n) => n.id),
  );
  copy.name = `${src.name} (copy)`;
  copy.position = { x: src.position.x + 40, y: src.position.y + 60 };
  return { ...def, nodes: [...def.nodes, copy] };
}

// ---------------------------------------------------------------- canvas model

export type NodeVisualStatus = NodeExecutionStatus | 'CURRENT' | null;

/** Data handed to the custom node components rendered by ngx-vflow. */
export interface CanvasNodeData {
  node: WorkflowNode;
  ports: string[];
  hasInput: boolean;
  /** Template metadata for TASK nodes (from the palette). */
  templateName: string | null;
  executor: string | null;
  interfaces: string[];
  /** Connection capabilities the template needs (Jira tasks, ADR 0030). */
  requiredCapabilities?: string[];
  /** Run mode: execution status colour. */
  status: NodeVisualStatus;
  /** Run mode: output ports taken by any execution of this node. */
  takenPortsList: string[];
  attempts: number;
  errorCount: number;
  warningCount: number;
  readonly: boolean;
}

export interface CanvasEdgeData {
  edge: WorkflowEdge;
  taken: boolean;
  hasError: boolean;
}

export interface CanvasIssueCounts {
  nodes: Record<string, { errors: number; warnings: number }>;
  edges: Record<string, { errors: number; warnings: number }>;
}

export function buildNodeData(
  node: WorkflowNode,
  opts: {
    palette?: PaletteIndex | null;
    executions?: NodeExecution[] | null;
    currentNodeId?: string | null;
    issues?: CanvasIssueCounts | null;
    readonly?: boolean;
    /** Output ports of this node that have an edge (decides whether a gate shows `timeout`). */
    connectedPorts?: ReadonlySet<string>;
  } = {},
): CanvasNodeData {
  const meta = NODE_KIND_META[node.kind];
  const template =
    node.kind === 'TASK' && node.task?.templateKey
      ? opts.palette?.tasks.get(node.task.templateKey)
      : undefined;
  const execs = (opts.executions ?? []).filter((e) => e.nodeId === node.id);
  const last = execs.length ? execs[execs.length - 1] : undefined;
  let status: NodeVisualStatus = last?.status ?? null;
  if (!status && opts.currentNodeId === node.id) status = 'CURRENT';
  const counts = opts.issues?.nodes[node.id];
  return {
    node,
    ports: visiblePortsFor(node, opts.palette, opts.connectedPorts ?? new Set()),
    hasInput: meta.hasInput,
    templateName: template?.name ?? null,
    executor: template?.executor ?? null,
    interfaces: template?.interfaces ?? [],
    requiredCapabilities: (template?.requiredCapabilities as string[] | undefined) ?? [],
    status,
    takenPortsList: Array.from(new Set(execs.map((e) => e.port).filter((p): p is string => !!p))),
    attempts: execs.length,
    errorCount: counts?.errors ?? 0,
    warningCount: counts?.warnings ?? 0,
    readonly: opts.readonly ?? false,
  };
}

/** Ports actually taken during a run: `(nodeId, port)` pairs from the node executions. */
export function takenPorts(executions: NodeExecution[] | null | undefined): Set<string> {
  const out = new Set<string>();
  for (const e of executions ?? []) if (e.nodeId && e.port) out.add(`${e.nodeId}.${e.port}`);
  return out;
}

export function buildEdgeData(
  edge: WorkflowEdge,
  taken: Set<string>,
  issues?: CanvasIssueCounts | null,
): CanvasEdgeData {
  return {
    edge,
    taken: taken.has(`${edge.from}.${edge.fromPort}`),
    hasError: (issues?.edges[edge.id]?.errors ?? 0) > 0,
  };
}

/** Handle id for an output port: `${nodeId}:${port}` keeps handles unique across the flow. */
export function portHandleId(port: string): string {
  return `port:${port}`;
}

export function portFromHandleId(handleId: string | undefined | null): string | null {
  if (!handleId) return null;
  return handleId.startsWith('port:') ? handleId.slice(5) : handleId;
}

// ---------------------------------------------------------------- templates

export const EMPTY_DEFINITION: WorkflowDefinitionInput = {
  name: '',
  description: '',
  nodes: [
    {
      id: 'start',
      kind: 'START',
      name: NODE_KIND_META.START.title,
      position: { x: 40, y: 200 },
      maxVisits: 3,
    },
    {
      id: 'done',
      kind: 'END',
      name: $localize`:@@workflows.newNode.endSuccess:End (success)`,
      position: { x: 640, y: 200 },
      maxVisits: 3,
      end: { outcome: 'SUCCESS' },
    },
  ],
  edges: [],
  triggers: [{ type: 'MANUAL' }],
  enabled: true,
  canvas: null,
};

/**
 * The "issue to reviewed merge request" example from docs/architecture/03-pipelines-and-agents.md.
 * The starter templates mirror the workflows the backend seeds (English, like the manual names them),
 * so their node names and texts are not translated.
 */
export function issueToMergeRequestTemplate(): WorkflowDefinitionInput {
  const n = (
    id: string,
    kind: NodeKind,
    name: string,
    extra: Partial<WorkflowNode> = {},
  ): WorkflowNode => ({ id, kind, name, position: { x: 0, y: 0 }, maxVisits: 3, ...extra });
  const nodes: WorkflowNode[] = [
    n('start', 'START', 'Start'),
    n('fetch-issue', 'TASK', 'Fetch issue', { task: { templateKey: 'fetch-issue' } }), // i18n-ignore
    n('specify', 'TASK', 'Specify', { task: { templateKey: 'spec-kit-specify' } }),
    n('clarify', 'TASK', 'Clarify spec', { task: { templateKey: 'clarify-spec' } }), // i18n-ignore
    n('confident', 'DECISION', 'Confident?', {
      decision: {
        branches: [
          { id: 'yes', label: 'confidence >= 0.8', expression: 'confidence >= 0.8' }, // i18n-ignore
          { id: 'no', label: 'needs review', expression: 'true' }, // i18n-ignore
        ],
        defaultBranch: 'no',
      },
    }),
    // i18n-ignore
    n('spec-gate', 'HUMAN_GATE', 'Spec ok?', {
      gate: {
        question: 'The spec for {{issue.key}} has open questions. Approve, revise or reject?', // i18n-ignore
        options: ['approve', 'revise', 'reject'],
        showArtifacts: ['spec', 'clarifications'],
        timeoutSeconds: null,
      },
    }),
    n('plan', 'TASK', 'Plan', { task: { templateKey: 'spec-kit-plan' } }),
    n('tasks', 'TASK', 'Tasks', { task: { templateKey: 'spec-kit-tasks' } }),
    n('implement', 'TASK', 'Implement', {
      task: { templateKey: 'implement-opencode' },
      maxVisits: 3,
    }),
    // i18n-ignore
    n('ci-tests', 'WAIT_FOR_CI', 'Wait for CI: tests', {
      ci: { signal: 'tests', timeoutSeconds: 3600 },
    }),
    n('review', 'TASK', 'Code review', { task: { templateKey: 'code-review' } }), // i18n-ignore
    // i18n-ignore
    n('review-ok', 'DECISION', 'Review passed?', {
      decision: {
        branches: [
          {
            id: 'yes',
            label: 'no changes requested', // i18n-ignore
            expression: "review.verdict != 'REQUEST_CHANGES'",
          },
          { id: 'no', label: 'changes requested', expression: 'true' }, // i18n-ignore
        ],
        defaultBranch: 'no',
      },
    }),
    n('merge-gate', 'HUMAN_GATE', 'Merge?', {
      gate: {
        question: 'MR {{mr.url}} passed review (score {{review.score}}). Merge?',
        options: ['approve', 'reject'],
        showArtifacts: ['review'],
        timeoutSeconds: null,
      },
    }),
    // i18n-ignore
    n('transition', 'TASK', 'Move to In Review', {
      task: { templateKey: 'transition-issue', overrides: { config: { toStatus: 'In Review' } } }, // i18n-ignore
    }),
    n('done', 'END', 'End (success)', { end: { outcome: 'SUCCESS' } }), // i18n-ignore
    n('failed', 'END', 'End (failure)', { end: { outcome: 'FAILURE' } }), // i18n-ignore
  ];
  const e = (from: string, fromPort: string, to: string): WorkflowEdge => ({
    id: edgeId(from, fromPort, to),
    from,
    fromPort,
    to,
  });
  const edges: WorkflowEdge[] = [
    e('start', 'out', 'fetch-issue'),
    e('fetch-issue', 'success', 'specify'),
    e('specify', 'success', 'clarify'),
    e('clarify', 'success', 'confident'),
    e('confident', 'yes', 'plan'),
    e('confident', 'no', 'spec-gate'),
    e('spec-gate', 'approve', 'plan'),
    e('spec-gate', 'revise', 'specify'),
    e('spec-gate', 'reject', 'failed'),
    e('plan', 'success', 'tasks'),
    e('tasks', 'success', 'implement'),
    e('implement', 'success', 'ci-tests'),
    e('ci-tests', 'success', 'review'),
    e('ci-tests', 'fail', 'failed'),
    e('ci-tests', 'timeout', 'failed'),
    e('review', 'success', 'review-ok'),
    e('review-ok', 'yes', 'merge-gate'),
    e('review-ok', 'no', 'implement'),
    e('merge-gate', 'approve', 'transition'),
    e('merge-gate', 'reject', 'failed'),
    e('transition', 'success', 'done'),
  ];
  return {
    name: 'Issue to reviewed MR', // i18n-ignore
    description: 'Fetch a Jira issue, specify, plan, implement, wait for CI, review and merge.', // i18n-ignore
    nodes,
    edges,
    triggers: [{ type: 'MANUAL' }, { type: 'JIRA_TRANSITION', status: 'Ready for AI' }], // i18n-ignore
    enabled: true,
    canvas: null,
  };
}

/** The CI code-review entrypoint example. */
export function ciCodeReviewTemplate(): WorkflowDefinitionInput {
  const nodes: WorkflowNode[] = [
    { id: 'start', kind: 'START', name: 'Start', position: { x: 0, y: 0 }, maxVisits: 3 },
    {
      id: 'code-review',
      kind: 'TASK',
      name: 'Code review', // i18n-ignore
      position: { x: 0, y: 0 },
      maxVisits: 3,
      task: { templateKey: 'code-review' },
    },
    {
      id: 'verdict',
      kind: 'DECISION',
      name: 'Verdict',
      position: { x: 0, y: 0 },
      maxVisits: 3,
      decision: {
        branches: [
          {
            id: 'pass',
            label: 'no changes requested', // i18n-ignore
            expression: "review.verdict != 'REQUEST_CHANGES'",
          },
          { id: 'fail', label: 'changes requested', expression: 'true' }, // i18n-ignore
        ],
        defaultBranch: 'fail',
      },
    },
    {
      id: 'pass',
      kind: 'END',
      name: 'Gate passes', // i18n-ignore
      position: { x: 0, y: 0 },
      maxVisits: 3,
      end: { outcome: 'SUCCESS' },
    },
    {
      id: 'fail',
      kind: 'END',
      name: 'Gate fails', // i18n-ignore
      position: { x: 0, y: 0 },
      maxVisits: 3,
      end: { outcome: 'FAILURE' },
    },
  ];
  const edges: WorkflowEdge[] = [
    {
      id: edgeId('start', 'out', 'code-review'),
      from: 'start',
      fromPort: 'out',
      to: 'code-review',
    },
    {
      id: edgeId('code-review', 'success', 'verdict'),
      from: 'code-review',
      fromPort: 'success',
      to: 'verdict',
    },
    {
      id: edgeId('code-review', 'failure', 'fail'),
      from: 'code-review',
      fromPort: 'failure',
      to: 'fail',
    },
    { id: edgeId('verdict', 'pass', 'pass'), from: 'verdict', fromPort: 'pass', to: 'pass' },
    { id: edgeId('verdict', 'fail', 'fail'), from: 'verdict', fromPort: 'fail', to: 'fail' },
  ];
  return {
    name: 'CI code review',
    description: 'Reviews the merge request diff uploaded by backoffice-ci and decides the gate.', // i18n-ignore
    nodes,
    edges,
    triggers: [{ type: 'CI' }],
    enabled: true,
    canvas: null,
  };
}

/** Normalises a definition coming from the API or the JSON tab (defaults, dropped nulls). */
export function normalizeDefinition(
  raw: Partial<WorkflowDefinitionInput>,
): WorkflowDefinitionInput {
  return {
    name: raw.name ?? '',
    description: raw.description ?? '',
    nodes: (raw.nodes ?? []).map((n) => ({
      ...n,
      position: n.position ?? { x: 0, y: 0 },
      maxVisits: n.maxVisits ?? 3,
    })),
    edges: (raw.edges ?? []).map((e) => ({ ...e, id: e.id || edgeId(e.from, e.fromPort, e.to) })),
    triggers: raw.triggers ?? [],
    enabled: raw.enabled ?? true,
    canvas: raw.canvas ?? null,
  };
}
