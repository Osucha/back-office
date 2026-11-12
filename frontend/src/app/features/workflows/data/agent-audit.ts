/**
 * ADR 0061: the "Agents in this workflow" panel as the designer shows it: per agent its kind,
 * scope, steps (by name), rules, skills, the required skills with the steps needing them (missing
 * ones flagged) and its findings; findings not tied to one agent; the count for the toolbar badge.
 * Also the agent input "Add to agent" sends, and the labels of the coherence check. Pure; tested in
 * agent-audit.spec.ts.
 */
import type {
  Agent,
  AgentAudit,
  AgentAuditEntry,
  AgentAuditFinding,
  AgentInput,
  CoherenceFinding,
  Scope,
} from '../../../core/api/types';
import { agentKindLabel } from '../../../core/ui/agents';

export interface AuditNodeRef {
  id: string;
  name: string;
}

export interface AuditFindingView {
  kind: AgentAuditFinding['kind'];
  severity: AgentAuditFinding['severity'];
  /** Translated kind ("Missing skill"). */
  title: string;
  /** The server's message (English, with names). */
  message: string;
  key: string | null;
  nodes: AuditNodeRef[];
}

export interface AuditRequiredSkillView {
  key: string;
  name: string;
  nodes: AuditNodeRef[];
  missing: boolean;
}

export interface AuditAgentView {
  key: string;
  name: string;
  agentId: string | null;
  kind: string;
  coding: boolean;
  scope: 'GLOBAL' | 'PROJECT';
  nodes: AuditNodeRef[];
  rules: Array<{ key: string; name: string; automatic: boolean }>;
  skills: Array<{ key: string; name: string }>;
  required: AuditRequiredSkillView[];
  missing: string[];
  findings: AuditFindingView[];
  /** ERROR + WARNING findings of this agent. */
  problems: number;
}

export interface AuditView {
  agents: AuditAgentView[];
  findings: AuditFindingView[];
  /** All findings (the toolbar badge): every finding is an error or a warning. */
  problems: number;
}

/** Translated kind of a deterministic finding. */
export function auditFindingTitle(kind: AgentAuditFinding['kind']): string {
  switch (kind) {
    case 'MISSING_SKILL':
      return $localize`:@@workflows.audit.kind.missingSkill:Missing skill`;
    case 'NEEDS_CODING_AGENT':
      return $localize`:@@workflows.audit.kind.needsCodingAgent:Needs a coding agent`;
    case 'READ_ONLY_PUSH':
      return $localize`:@@workflows.audit.kind.readOnlyPush:Read-only agent on a pushing step`;
    case 'UNKNOWN_RULE':
      return $localize`:@@workflows.audit.kind.unknownRule:Unknown rule`;
    case 'UNKNOWN_SKILL':
      return $localize`:@@workflows.audit.kind.unknownSkill:Unknown skill`;
    case 'DUPLICATE_SKILL_NAME':
      return $localize`:@@workflows.audit.kind.duplicateSkillName:Two skills with the same name`;
    case 'UNKNOWN_AGENT':
      return $localize`:@@workflows.audit.kind.unknownAgent:Unknown agent`;
    default:
      return String(kind);
  }
}

/** Translated severity of a coherence finding. */
export function coherenceSeverityLabel(severity: CoherenceFinding['severity']): string {
  switch (severity) {
    case 'CONTRADICTION':
      return $localize`:@@workflows.audit.coherence.contradiction:Contradiction`;
    case 'AMBIGUITY':
      return $localize`:@@workflows.audit.coherence.ambiguity:Ambiguity`;
    case 'REDUNDANCY':
      return $localize`:@@workflows.audit.coherence.redundancy:Redundancy`;
    default:
      return String(severity);
  }
}

/** Contradictions first, then ambiguities, then redundancies. */
export function sortCoherenceFindings(findings: readonly CoherenceFinding[]): CoherenceFinding[] {
  const order = { CONTRADICTION: 0, AMBIGUITY: 1, REDUNDANCY: 2 } as Record<string, number>;
  return [...findings].sort((a, b) => (order[a.severity] ?? 3) - (order[b.severity] ?? 3));
}

function nodeRefs(ids: readonly string[] | null | undefined, names: ReadonlyMap<string, string>) {
  return (ids ?? []).map((id) => ({ id, name: names.get(id) || id }));
}

function findingView(f: AgentAuditFinding, names: ReadonlyMap<string, string>): AuditFindingView {
  return {
    kind: f.kind,
    severity: f.severity,
    title: auditFindingTitle(f.kind),
    message: f.message,
    key: f.key ?? null,
    nodes: nodeRefs(f.nodeIds, names),
  };
}

/** Errors before warnings, keeping the server's order otherwise. */
function bySeverity(list: AuditFindingView[]): AuditFindingView[] {
  return list
    .map((f, i) => ({ f, i }))
    .sort((a, b) => (a.f.severity === b.f.severity ? a.i - b.i : a.f.severity === 'ERROR' ? -1 : 1))
    .map((x) => x.f);
}

function agentView(e: AgentAuditEntry, names: ReadonlyMap<string, string>): AuditAgentView {
  const missing = new Set(e.missingSkills ?? []);
  const findings = bySeverity((e.findings ?? []).map((f) => findingView(f, names)));
  return {
    key: e.agent.key,
    name: e.agent.name || e.agent.key,
    agentId: e.agentId ?? null,
    kind: agentKindLabel(e.agent.harness),
    coding: !!e.agent.harness,
    scope: e.scope?.type === 'PROJECT' ? 'PROJECT' : 'GLOBAL',
    nodes: nodeRefs(e.nodeIds, names),
    rules: (e.rules ?? []).map((r) => ({
      key: r.key ?? '',
      name: r.name || r.key || '',
      automatic: !!r.automatic,
    })),
    skills: (e.skills ?? []).map((s) => ({ key: s.key ?? '', name: s.name || s.key || '' })),
    required: (e.requiredSkills ?? []).map((s) => ({
      key: s.key ?? '',
      name: s.name || s.key || '',
      nodes: nodeRefs(s.nodeIds, names),
      missing: missing.has(s.key ?? ''),
    })),
    missing: [...missing],
    findings,
    problems: findings.length,
  };
}

/**
 * The panel's view of an audit: agents with problems first (then by name), node ids named by
 * `nodeNames` (falls back to the id).
 */
export function auditView(
  audit: AgentAudit | null | undefined,
  nodeNames: ReadonlyMap<string, string>,
): AuditView {
  if (!audit) return { agents: [], findings: [], problems: 0 };
  const agents = (audit.agents ?? [])
    .map((e) => agentView(e, nodeNames))
    .sort(
      (a, b) => Number(b.problems > 0) - Number(a.problems > 0) || a.name.localeCompare(b.name),
    );
  const findings = bySeverity((audit.findings ?? []).map((f) => findingView(f, nodeNames)));
  return {
    agents,
    findings,
    problems: findings.length + agents.reduce((n, a) => n + a.problems, 0),
  };
}

/** What "Add to agent" changes. */
export type AddSkillPlan =
  | { kind: 'update'; agentId: string; input: AgentInput }
  | { kind: 'copy'; input: AgentInput }
  | { kind: 'none' };

/**
 * ADR 0061: "Add to agent" for a missing skill. A project agent gets the skill (PUT); a global
 * agent is first copied into the project with the same key and the skill added (POST), so other
 * projects keep theirs. `draftOf` is the library's agent draft (`agentDraft`), `copyOf` its
 * project copy (`customizeAgentForProject`).
 */
export function addSkillPlan(
  agent: Agent | null | undefined,
  skillKey: string,
  projectId: string,
  draftOf: (a: Agent) => AgentInput,
  copyOf: (a: Agent, projectId: string) => AgentInput,
): AddSkillPlan {
  if (!agent?.id || !skillKey) return { kind: 'none' };
  const withSkill = (input: AgentInput): AgentInput => ({
    ...input,
    skills: [...new Set([...(input.skills ?? []), skillKey])],
  });
  if (isProjectScope(agent.scope, projectId))
    return { kind: 'update', agentId: agent.id, input: withSkill(draftOf(agent)) };
  return { kind: 'copy', input: withSkill(copyOf(agent, projectId)) };
}

function isProjectScope(scope: Scope | undefined, projectId: string): boolean {
  return scope?.type === 'PROJECT' && scope.projectId === projectId;
}
