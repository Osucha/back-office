/**
 * The facts table of a run and of one step on the run detail page (label / value rows). Pure
 * functions over the run, its definition snapshot and the node executions.
 */
import type { NodeExecution, WorkflowRun } from '../../../core/api/types';
import { roleName } from '../../../core/i18n/catalog-labels';
import { icu } from '../../../core/i18n/icu';
import { intlLocale } from '../../../core/i18n/locale';
import { formatNumber } from '../../../core/i18n/numbers';

export interface Fact {
  label: string;
  value: string;
  mono?: boolean;
}

type SnapshotNode = NonNullable<NonNullable<WorkflowRun['definitionSnapshot']>['nodes']>[number];

/** Date and time in the UI language; the input unchanged when it is not a date. */
export function absolute(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? iso
    : d.toLocaleString(intlLocale(), { dateStyle: 'medium', timeStyle: 'medium' });
}

/**
 * The facts of step `s`: what the node is configured to do (by kind), where the baton came from and
 * went, then attempt, times, runner job and tokens.
 */
export function stepFacts(
  r: WorkflowRun,
  s: NodeExecution,
  renderedQuestion: string | null = null,
): Fact[] {
  const node = r.definitionSnapshot?.nodes?.find((n) => n.id === s.nodeId);
  return [...nodeFacts(s, node, renderedQuestion), ...batonFacts(r, s), ...executionFacts(s)];
}

function nodeFacts(
  s: NodeExecution,
  node: SnapshotNode | undefined,
  renderedQuestion: string | null,
): Fact[] {
  switch (s.kind) {
    case 'TASK':
      return taskFacts(s.templateKey ?? node?.task?.templateKey ?? '-', node);
    case 'HUMAN_GATE':
      return gateFacts(node, renderedQuestion);
    case 'WAIT_FOR_CI':
      return ciFacts(node);
    case 'DECISION':
      return decisionFacts(node);
    default:
      return [];
  }
}

function taskFacts(templateKey: string, node: SnapshotNode | undefined): Fact[] {
  const out: Fact[] = [
    { label: $localize`:@@runs.detail.fact.template:Template`, value: templateKey, mono: true },
  ];
  const o = node?.task?.overrides;
  if (o?.modelProfileId)
    out.push({
      label: $localize`:@@runs.detail.fact.modelOverride:Model override`,
      value: o.modelProfileId,
      mono: true,
    });
  if (o?.addSkills?.length)
    out.push({
      label: $localize`:@@runs.detail.fact.extraSkills:Extra skills`,
      value: o.addSkills.join(', '),
    });
  if (o?.promptReplace)
    out.push({
      label: $localize`:@@runs.detail.fact.prompt:Prompt`,
      value: $localize`:@@runs.detail.fact.promptReplaced:replaced on the node`,
    });
  else if (o?.promptAppend)
    out.push({
      label: $localize`:@@runs.detail.fact.promptExtended:Prompt`,
      value: $localize`:@@runs.detail.fact.promptExtendedValue:extended on the node`,
    });
  return out;
}

function gateFacts(node: SnapshotNode | undefined, renderedQuestion: string | null): Fact[] {
  const out: Fact[] = [];
  const g = node?.gate;
  const question = renderedQuestion ?? g?.question;
  if (question)
    out.push({ label: $localize`:@@runs.detail.fact.question:Question`, value: question });
  if (g?.options?.length)
    out.push({
      label: $localize`:@@runs.detail.fact.options:Options`,
      value: g.options.join(' · '),
    });
  if (g?.approvers?.accountable)
    out.push({
      label: $localize`:@@runs.detail.fact.accountable:Accountable`,
      value: roleName(g.approvers.accountable),
    });
  if (g?.checklist?.length)
    out.push({
      label: $localize`:@@runs.detail.fact.checklist:Checklist`,
      value: icu(
        $localize`:@@runs.detail.fact.checklistItems:{count, plural, one {# item} other {# items}}`,
        {
          count: g.checklist.length,
        },
      ),
    });
  return out;
}

function ciFacts(node: SnapshotNode | undefined): Fact[] {
  const out: Fact[] = [];
  if (node?.ci?.signal)
    out.push({
      label: $localize`:@@runs.detail.fact.signal:Signal`,
      value: node.ci.signal,
      mono: true,
    });
  if (node?.ci?.timeoutSeconds)
    out.push({
      label: $localize`:@@runs.detail.fact.timeout:Timeout`,
      value: node.ci.timeoutSeconds + ' s',
    });
  return out;
}

function decisionFacts(node: SnapshotNode | undefined): Fact[] {
  const out: Fact[] = (node?.decision?.branches ?? []).map((b) => ({
    label: `${$localize`:@@runs.detail.fact.branch:Branch`} ${b.id}`,
    value: b.expression,
    mono: true,
  }));
  if (node?.decision?.defaultBranch)
    out.push({
      label: $localize`:@@runs.detail.fact.otherwise:Otherwise`,
      value: node.decision.defaultBranch,
      mono: true,
    });
  return out;
}

/** Where the baton came from (the previous execution) and where it went (the port's edge). */
function batonFacts(r: WorkflowRun, s: NodeExecution): Fact[] {
  const out: Fact[] = [];
  const execs = r.nodes ?? [];
  const idx = execs.indexOf(s);
  const prev = idx > 0 ? execs[idx - 1] : null;
  if (prev)
    out.push({
      label: $localize`:@@runs.detail.fact.reachedFrom:Reached from`,
      value: (prev.nodeName || prev.nodeId) + (prev.port ? ' → ' + prev.port : ''),
    });
  if (s.port) {
    const snap = r.definitionSnapshot;
    const port = s.port.toLowerCase();
    const edge = snap?.edges?.find(
      (e) => e.from === s.nodeId && e.fromPort?.toLowerCase() === port,
    );
    const to = edge ? snap?.nodes?.find((n) => n.id === edge.to) : null;
    out.push({
      label: $localize`:@@runs.detail.fact.leftThrough:Left through`,
      value: s.port + (to ? ' → ' + (to.name || to.id) : ''),
    });
  }
  return out;
}

function executionFacts(s: NodeExecution): Fact[] {
  const out: Fact[] = [
    {
      label: $localize`:@@runs.detail.fact.attempt:Attempt`,
      value: String(s.attempt ?? 1),
    },
  ];
  if (s.startedAt)
    out.push({
      label: $localize`:@@runs.detail.fact.stepStarted:Started`,
      value: absolute(s.startedAt),
    });
  if (s.finishedAt)
    out.push({
      label: $localize`:@@runs.detail.fact.stepFinished:Finished`,
      value: absolute(s.finishedAt),
    });
  if (s.runnerJobId)
    out.push({
      label: $localize`:@@runs.detail.fact.runnerJob:Runner job`,
      value: s.runnerJobId.slice(0, 8),
      mono: true,
    });
  const tokens = (s.usage?.inputTokens ?? 0) + (s.usage?.outputTokens ?? 0);
  if (tokens)
    out.push({ label: $localize`:@@runs.detail.fact.tokens:Tokens`, value: formatNumber(tokens) });
  return out;
}
