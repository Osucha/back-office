/**
 * Client-side graph validation plus grouping of server issues by node / edge.
 * The server (`POST /workflows/validate`) is the authority; local checks give instant feedback.
 */
import type {
  ValidationIssue,
  ValidationResult,
  WorkflowDefinitionInput,
} from '../../../core/api/types';
import {
  NODE_ID_PATTERN,
  TERMINAL_WHEN_UNCONNECTED,
  portsFor,
  requiredPortsFor,
  type PaletteIndex,
  type CanvasIssueCounts,
} from './graph-model';
import { gateRuleProblems } from './gate-rules';

export interface Issues {
  errors: ValidationIssue[];
  warnings: ValidationIssue[];
}

export interface IssueMap {
  nodes: Record<string, Issues>;
  edges: Record<string, Issues>;
  /** Issues without a node / edge (workflow level). */
  global: Issues;
  errorCount: number;
  warningCount: number;
}

const emptyIssues = (): Issues => ({ errors: [], warnings: [] });

/** Groups a ValidationResult (server or local) by node id / edge id. */
export function mapValidation(result: ValidationResult | null | undefined): IssueMap {
  const map: IssueMap = {
    nodes: {},
    edges: {},
    global: emptyIssues(),
    errorCount: 0,
    warningCount: 0,
  };
  if (!result) return map;
  for (const e of result.errors ?? []) {
    bucket(map, e).errors.push(e);
    map.errorCount++;
  }
  for (const w of result.warnings ?? []) {
    bucket(map, w).warnings.push(w);
    map.warningCount++;
  }
  return map;
}

function bucket(map: IssueMap, issue: ValidationIssue): Issues {
  if (issue.nodeId) return (map.nodes[issue.nodeId] ??= emptyIssues());
  if (issue.edgeId) return (map.edges[issue.edgeId] ??= emptyIssues());
  return map.global;
}

/** Compact counts for the canvas (node / edge badges). */
export function issueCounts(map: IssueMap): CanvasIssueCounts {
  const nodes: CanvasIssueCounts['nodes'] = {};
  const edges: CanvasIssueCounts['edges'] = {};
  for (const [id, i] of Object.entries(map.nodes))
    nodes[id] = { errors: i.errors.length, warnings: i.warnings.length };
  for (const [id, i] of Object.entries(map.edges))
    edges[id] = { errors: i.errors.length, warnings: i.warnings.length };
  return { nodes, edges };
}

/**
 * Merges results (typically local + server), de-duplicating identical messages. An issue of a later
 * result is also dropped when an earlier result already reported one of the same severity on the
 * same node / edge and field (local and server word the same problem differently).
 */
export function mergeValidation(
  ...results: Array<ValidationResult | null | undefined>
): ValidationResult {
  const errors: ValidationIssue[] = [];
  const warnings: ValidationIssue[] = [];
  const seen = new Set<string>();
  const key = (kind: string, i: ValidationIssue) =>
    `${kind}|${i.nodeId ?? ''}|${i.edgeId ?? ''}|${i.field ?? ''}|${i.message}`;
  const fieldKey = (kind: string, i: ValidationIssue) =>
    i.field ? `${kind}|${i.nodeId ?? ''}|${i.edgeId ?? ''}|${i.field}` : null;
  const fieldsBefore = new Set<string>();
  for (const r of results) {
    const fieldsHere = new Set<string>();
    const take = (kind: 'E' | 'W', issue: ValidationIssue, into: ValidationIssue[]) => {
      const k = key(kind, issue);
      const f = fieldKey(kind, issue);
      if (seen.has(k) || (f && fieldsBefore.has(f))) return;
      seen.add(k);
      if (f) fieldsHere.add(f);
      into.push(issue);
    };
    for (const e of r?.errors ?? []) take('E', e, errors);
    for (const w of r?.warnings ?? []) take('W', w, warnings);
    for (const f of fieldsHere) fieldsBefore.add(f);
  }
  return { valid: errors.length === 0, errors, warnings };
}

/** Cheap structural validation that runs on every change. */
export function validateLocally(
  def: WorkflowDefinitionInput,
  palette: PaletteIndex | null = null,
): ValidationResult {
  const errors: ValidationIssue[] = [];
  const warnings: ValidationIssue[] = [];
  const sink: IssueSink = {
    err: (message, where = {}) =>
      errors.push({ message, nodeId: null, edgeId: null, field: null, ...where }),
    warn: (message, where = {}) =>
      warnings.push({ message, nodeId: null, edgeId: null, field: null, ...where }),
  };

  if (!def.name?.trim())
    sink.err($localize`:@@workflows.validation.nameRequired:Workflow name is required`, {
      field: 'name',
    });
  const ids = new Map<string, number>();
  for (const n of def.nodes) ids.set(n.id, (ids.get(n.id) ?? 0) + 1);
  const startCount = checkStartAndEnd(def.nodes, sink);
  for (const n of def.nodes) {
    checkNodeBasics(n, ids, sink);
    checkNodeKind(n, palette, sink);
  }
  const connected = checkEdges(def, palette, sink);
  if (startCount === 1) checkPorts(def, palette, ids, connected, sink);
  for (const t of def.triggers ?? []) {
    if (t.type === 'JIRA_TRANSITION' && !t.status?.trim())
      sink.err(
        $localize`:@@workflows.validation.jiraStatus:JIRA_TRANSITION trigger needs a status`,
        {
          field: 'triggers',
        },
      );
  }
  return { valid: errors.length === 0, errors, warnings };
}

type Where = { nodeId?: string; edgeId?: string; field?: string };
type WorkflowNode = WorkflowDefinitionInput['nodes'][number];

/** Where the checks report: errors block saving, warnings do not. */
interface IssueSink {
  err(message: string, where?: Where): void;
  warn(message: string, where?: Where): void;
}

/** Exactly one START, at least one END; returns the number of START nodes. */
function checkStartAndEnd(nodes: readonly WorkflowNode[], sink: IssueSink): number {
  const starts = nodes.filter((n) => n.kind === 'START');
  if (starts.length === 0)
    sink.err($localize`:@@workflows.validation.oneStart:A workflow needs exactly one START node`, {
      field: 'nodes',
    });
  for (const s of starts.slice(1))
    sink.err($localize`:@@workflows.validation.onlyOneStart:Only one START node is allowed`, {
      nodeId: s.id,
    });
  if (!nodes.some((n) => n.kind === 'END'))
    sink.err($localize`:@@workflows.validation.oneEnd:A workflow needs at least one END node`, {
      field: 'nodes',
    });
  return starts.length;
}

/** Id, name, visits and timeout: the fields every kind has. */
function checkNodeBasics(n: WorkflowNode, ids: Map<string, number>, sink: IssueSink): void {
  const at = { nodeId: n.id };
  if (!n.id)
    sink.err($localize`:@@workflows.validation.idRequired:Node id is required`, { field: 'id' });
  else if (!NODE_ID_PATTERN.test(n.id))
    sink.err(
      $localize`:@@workflows.validation.idSlug:Node id must be a slug: lowercase letters, digits and dashes, max 40 chars`,
      {
        ...at,
        field: 'id',
      },
    );
  if ((ids.get(n.id) ?? 0) > 1)
    sink.err($localize`:@@workflows.validation.idDuplicate:Duplicate node id "${n.id}:id:"`, {
      ...at,
      field: 'id',
    });
  if (!n.name?.trim() && n.kind !== 'NOTE')
    sink.err($localize`:@@workflows.validation.nameOfNodeRequired:Node name is required`, {
      ...at,
      field: 'name',
    });
  if (n.maxVisits !== undefined && n.maxVisits < 1)
    sink.err($localize`:@@workflows.validation.maxVisits:maxVisits must be at least 1`, {
      ...at,
      field: 'maxVisits',
    });
  if (n.timeoutSeconds !== null && n.timeoutSeconds !== undefined && n.timeoutSeconds <= 0)
    sink.err($localize`:@@workflows.validation.timeoutPositive:Timeout must be positive`, {
      ...at,
      field: 'timeoutSeconds',
    });
}

/** The configuration of the node's kind. */
function checkNodeKind(n: WorkflowNode, palette: PaletteIndex | null, sink: IssueSink): void {
  switch (n.kind) {
    case 'TASK':
      checkTask(n, palette, sink);
      break;
    case 'DECISION':
      checkDecision(n, sink);
      break;
    case 'HUMAN_GATE':
      checkGate(n, sink);
      break;
    case 'WAIT_FOR_CI':
      checkCiWait(n, sink);
      break;
    case 'END':
      if (!n.end?.outcome)
        sink.err($localize`:@@workflows.validation.endOutcome:END needs an outcome`, {
          nodeId: n.id,
          field: 'end.outcome',
        });
      break;
    default:
      break;
  }
}

function checkTask(n: WorkflowNode, palette: PaletteIndex | null, sink: IssueSink): void {
  const at = { nodeId: n.id };
  const key = n.task?.templateKey;
  if (!key)
    sink.err($localize`:@@workflows.validation.pickTemplate:Pick a task template`, {
      ...at,
      field: 'task.templateKey',
    });
  else if (palette && palette.tasks.size && !palette.tasks.has(key))
    sink.warn(
      $localize`:@@workflows.validation.templateNotInPalette:Template "${key}:key:" is not in the palette for this project`,
      {
        ...at,
        field: 'task.templateKey',
      },
    );
}

function checkDecision(n: WorkflowNode, sink: IssueSink): void {
  const at = { nodeId: n.id };
  const branches = n.decision?.branches ?? [];
  if (!branches.length)
    sink.err($localize`:@@workflows.validation.oneBranch:A decision needs at least one branch`, {
      ...at,
      field: 'decision',
    });
  const seen = new Set<string>();
  for (const b of branches) {
    const where = { ...at, field: 'decision.branches' };
    if (!b.id || !NODE_ID_PATTERN.test(b.id))
      sink.err(
        $localize`:@@workflows.validation.branchSlug:Branch id "${b.id}:id:" must be a slug`,
        where,
      );
    if (seen.has(b.id))
      sink.err(
        $localize`:@@workflows.validation.branchDuplicate:Duplicate branch id "${b.id}:id:"`,
        where,
      );
    seen.add(b.id);
    if (!b.expression?.trim())
      sink.err(
        $localize`:@@workflows.validation.branchExpression:Branch "${b.id}:id:" needs an expression`,
        where,
      );
  }
  // The default branch is a port of its own: an existing branch id or a new name ("otherwise").
  const d = n.decision?.defaultBranch;
  if (d !== null && d !== undefined && !NODE_ID_PATTERN.test(d))
    sink.err(
      $localize`:@@workflows.validation.defaultSlug:Default branch "${d}:id:" must be a slug`,
      {
        ...at,
        field: 'decision.defaultBranch',
      },
    );
  if (!d)
    sink.warn(
      $localize`:@@workflows.validation.noDefault:No default branch: the run fails when no expression is true`,
      {
        ...at,
        field: 'decision.defaultBranch',
      },
    );
}

function checkGate(n: WorkflowNode, sink: IssueSink): void {
  const at = { nodeId: n.id };
  if (!n.gate?.question?.trim())
    sink.err($localize`:@@workflows.validation.gateQuestion:The gate needs a question`, {
      ...at,
      field: 'gate.question',
    });
  if (!n.gate?.options?.length)
    sink.err($localize`:@@workflows.validation.gateOption:The gate needs at least one option`, {
      ...at,
      field: 'gate.options',
    });
  for (const problem of gateRuleProblems(n.gate?.approvers, n.gate?.checklist))
    sink.err(problem, { ...at, field: 'gate.approvers' });
}

function checkCiWait(n: WorkflowNode, sink: IssueSink): void {
  const at = { nodeId: n.id };
  if (!n.ci?.signal)
    sink.err($localize`:@@workflows.validation.signalRequired:The CI signal name is required`, {
      ...at,
      field: 'ci.signal',
    });
  else if (!NODE_ID_PATTERN.test(n.ci.signal))
    sink.err($localize`:@@workflows.validation.signalSlug:The signal must be a slug (e.g. tests)`, {
      ...at,
      field: 'ci.signal',
    });
}

/**
 * Edges: references, ports, uniqueness per (from, fromPort). Returns the connected ports as
 * `nodeId.port` (port in lower case: ports compare case-insensitively, like on the server).
 */
function checkEdges(
  def: WorkflowDefinitionInput,
  palette: PaletteIndex | null,
  sink: IssueSink,
): Set<string> {
  const byId = new Map(def.nodes.map((n) => [n.id, n]));
  const seenPorts = new Map<string, string>();
  for (const e of def.edges) {
    const at = { edgeId: e.id };
    const from = byId.get(e.from);
    const to = byId.get(e.to);
    if (!from)
      sink.err(
        $localize`:@@workflows.validation.edgeFrom:Edge "${e.id}:edge:" starts at unknown node "${e.from}:node:"`,
        at,
      );
    if (!to)
      sink.err(
        $localize`:@@workflows.validation.edgeTo:Edge "${e.id}:edge:" ends at unknown node "${e.to}:node:"`,
        at,
      );
    if (from) checkEdgeSource(from, e.fromPort, palette, at, sink);
    if (to && (to.kind === 'START' || to.kind === 'NOTE'))
      sink.err(`${to.kind} nodes cannot be a target`, at); // i18n-ignore: worded like the server (de-duplication)
    const key = `${e.from}.${e.fromPort.toLowerCase()}`;
    if (seenPorts.has(key))
      sink.err(
        $localize`:@@workflows.validation.portTaken:Port ${key}:port: already has an edge (${seenPorts.get(key)}:edge:)`,
        at,
      );
    seenPorts.set(key, e.id);
  }
  return new Set(seenPorts.keys());
}

function checkEdgeSource(
  from: WorkflowNode,
  fromPort: string,
  palette: PaletteIndex | null,
  at: Where,
  sink: IssueSink,
): void {
  if (from.kind === 'END' || from.kind === 'NOTE') {
    sink.err(`${from.kind} nodes have no output ports`, at); // i18n-ignore: worded like the server (de-duplication)
    return;
  }
  const ports = portsFor(from, palette).map((p) => p.toLowerCase());
  if (!ports.includes(fromPort.toLowerCase()))
    sink.err(
      $localize`:@@workflows.validation.noPort:Node "${from.id}:node:" has no port "${fromPort}:port:"`,
      at,
    );
}

/**
 * Reachability and ports, mirroring the server: required ports of reachable nodes must be
 * connected; other unconnected ports warn unless taking them simply ends the run (failure, fail,
 * timeout, reject). Messages and fields match the server's so merged results do not double up.
 */
function checkPorts(
  def: WorkflowDefinitionInput,
  palette: PaletteIndex | null,
  ids: Map<string, number>,
  connected: Set<string>,
  sink: IssueSink,
): void {
  const reachable = reachableNodes(def);
  for (const n of def.nodes) {
    if (n.kind === 'NOTE' || ids.get(n.id) !== 1) continue;
    if (reachable.has(n.id)) checkNodePorts(n, palette, connected, sink);
    else sink.warn('node is never reached from START', { nodeId: n.id }); // i18n-ignore: server wording (de-duplication)
  }
}

function checkNodePorts(
  n: WorkflowNode,
  palette: PaletteIndex | null,
  connected: Set<string>,
  sink: IssueSink,
): void {
  const required = requiredPortsFor(n, palette).map((p) => p.toLowerCase());
  for (const p of portsFor(n, palette)) {
    const lower = p.toLowerCase();
    const where = { nodeId: n.id, field: `ports.${p}` };
    if (connected.has(`${n.id}.${lower}`)) continue;
    if (required.includes(lower))
      sink.err(
        $localize`:@@workflows.validation.portRequired:port ${p}:port: must be connected`,
        where,
      );
    else if (!TERMINAL_WHEN_UNCONNECTED.has(lower))
      sink.warn(
        $localize`:@@workflows.validation.portUnconnected:port ${p}:port: is not connected; the run fails when it is taken`,
        where,
      );
  }
}

/** Node ids reachable from START following edges. */
export function reachableNodes(def: WorkflowDefinitionInput): Set<string> {
  const seen = new Set<string>();
  const start = def.nodes.find((n) => n.kind === 'START');
  if (!start) return seen;
  const out = new Map<string, string[]>();
  for (const e of def.edges) {
    if (!out.has(e.from)) out.set(e.from, []);
    out.get(e.from)!.push(e.to);
  }
  const stack = [start.id];
  while (stack.length) {
    const id = stack.pop()!;
    if (seen.has(id)) continue;
    seen.add(id);
    for (const next of out.get(id) ?? []) if (!seen.has(next)) stack.push(next);
  }
  return seen;
}
