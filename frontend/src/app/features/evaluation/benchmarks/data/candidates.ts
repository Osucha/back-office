/**
 * ADR 0059: benchmark candidates are agents with models. Pure helpers for the create form (rows of
 * agent and model, validation, which tasks a candidate can do) and for labelling candidates on the
 * benchmark pages. Prompt tasks go to model answer agents (no harness), coding tasks to coding
 * agents; any other pair is not applicable and creates no attempt.
 */
import type {
  Agent,
  AgentHarness,
  BenchmarkCandidate,
  BenchmarkSummary,
  EvalTask,
} from '../../../../core/api/types';

/** One row of the form: the model is optional when the agent has its own. */
export interface CandidateDraft {
  agentKey: string | null;
  modelProfileId: string | null;
}

/** A row with its agent looked up: the harness and the model it will run with (null = none known). */
export interface ResolvedCandidate {
  agentKey: string;
  harness: AgentHarness | null;
  modelProfileId: string | null;
}

/** What is wrong with a row: no agent, no model (the agent has none either), the same pair twice. */
export type CandidateProblem = 'agent' | 'model' | 'duplicate';

export function emptyCandidate(): CandidateDraft {
  return { agentKey: null, modelProfileId: null };
}

export function resolveCandidate(
  d: CandidateDraft,
  agents: readonly Pick<Agent, 'key' | 'harness' | 'modelProfileId'>[],
): ResolvedCandidate | null {
  if (!d.agentKey) return null;
  const agent = agents.find((a) => a.key === d.agentKey);
  return {
    agentKey: d.agentKey,
    harness: agent?.harness ?? null,
    modelProfileId: d.modelProfileId || agent?.modelProfileId || null,
  };
}

/** The rows that name an agent, resolved. */
export function resolveCandidates(
  drafts: readonly CandidateDraft[],
  agents: readonly Pick<Agent, 'key' | 'harness' | 'modelProfileId'>[],
): ResolvedCandidate[] {
  return drafts.map((d) => resolveCandidate(d, agents)).filter((c): c is ResolvedCandidate => !!c);
}

/** One entry per row: its problem or null. A duplicate is the second (and later) row of a pair. */
export function candidateProblems(
  drafts: readonly CandidateDraft[],
  agents: readonly Pick<Agent, 'key' | 'harness' | 'modelProfileId'>[],
): (CandidateProblem | null)[] {
  const seen = new Set<string>();
  return drafts.map((d) => {
    const c = resolveCandidate(d, agents);
    if (!c) return 'agent';
    if (!c.modelProfileId) return 'model';
    const key = candidateKey(c);
    if (seen.has(key)) return 'duplicate';
    seen.add(key);
    return null;
  });
}

/** At least one row and every row complete and distinct. */
export function candidatesValid(
  drafts: readonly CandidateDraft[],
  agents: readonly Pick<Agent, 'key' | 'harness' | 'modelProfileId'>[],
): boolean {
  return drafts.length > 0 && candidateProblems(drafts, agents).every((p) => p === null);
}

/** What the API takes: the model only when one was chosen (otherwise the agent's own). */
export function toCandidates(drafts: readonly CandidateDraft[]): BenchmarkCandidate[] {
  return drafts
    .filter((d) => !!d.agentKey)
    .map((d) => ({ agentKey: d.agentKey!, modelProfileId: d.modelProfileId || null }));
}

/** "agent|model": one candidate. */
export function candidateKey(c: {
  agentKey?: string | null;
  modelProfileId?: string | null;
}): string {
  return `${c.agentKey ?? ''}|${c.modelProfileId ?? ''}`;
}

/** Whether an agent of this harness can do a task of this kind (prompt: model answer; coding: coding agent). */
export function canDo(harness: AgentHarness | null | undefined, kind: EvalTask['kind']): boolean {
  return kind === 'CODING_JOB' ? !!harness : !harness;
}

/** Of the tasks the benchmark runs, how many a candidate can do and how many are not applicable. */
export function applicability(
  harness: AgentHarness | null | undefined,
  tasks: readonly Pick<EvalTask, 'kind'>[],
): { applicable: number; notApplicable: number } {
  const applicable = tasks.filter((t) => canDo(harness, t.kind)).length;
  return { applicable, notApplicable: tasks.length - applicable };
}

/** Attempts the candidates will make: applicable tasks × repetitions, summed over candidates. */
export function attemptCount(
  candidates: readonly Pick<ResolvedCandidate, 'harness'>[],
  tasks: readonly Pick<EvalTask, 'kind'>[],
  repetitions: number,
): number {
  return candidates.reduce(
    (s, c) => s + applicability(c.harness, tasks).applicable * Math.max(1, repetitions || 1),
    0,
  );
}

/** Candidates of a benchmark in the list: its agents with models, or (older benchmarks) its models. */
export function candidateCount(
  b: Pick<BenchmarkSummary, 'candidates' | 'modelProfileIds'>,
): number {
  return b.candidates?.length || b.modelProfileIds?.length || 0;
}
