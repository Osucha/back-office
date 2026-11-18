import type {
  EvalTask,
  ExecutionTarget,
  ModelProfile,
  RunnerTarget,
} from '../../../../core/api/types';
import type { ResolvedCandidate } from './candidates';

/** The part of the benchmark form the ADR 0048 / ADR 0050 rules look at (candidates: ADR 0059). */
export interface LoginRuleForm {
  taskIds: string[];
  /** The candidates with their agent's harness and the model they run with. */
  candidates: Pick<ResolvedCandidate, 'harness' | 'modelProfileId'>[];
  judgeModelProfileId?: string | null;
  runnerTarget: RunnerTarget;
}

/**
 * What a benchmark using its owner's own login (ADR 0048, ADR 0050 step 4, ADR 0069) still needs:
 * - `target`: coding tasks done by a coding agent with a login model must run on the personal runner,
 * - `harness`: a Claude Code login model needs a Claude Code agent (OpenCode cannot use the login),
 * - `copilotHarness`: a GitHub Copilot login model needs an OpenCode agent (ADR 0069),
 * - `judge`: a GitHub Copilot login model cannot judge (ADR 0069),
 * - `localLogin`: the personal runner does not use its owner's logins, which every login model (candidate or
 *   judge) needs, also for prompt tasks and judging (blocks the start, not the draft).
 */
export type LoginIssue = 'target' | 'harness' | 'copilotHarness' | 'judge' | 'localLogin';

export function isLogin(p: Pick<ModelProfile, 'runtime'> | null | undefined): boolean {
  return p?.runtime === 'CLAUDE_CODE_LOGIN' || p?.runtime === 'COPILOT_LOGIN';
}

/** ADR 0069: a GitHub Copilot login model (OpenCode with the user's Copilot login; never the judge). */
export function isCopilot(p: Pick<ModelProfile, 'runtime'> | null | undefined): boolean {
  return p?.runtime === 'COPILOT_LOGIN';
}

/** Tasks the benchmark runs: the picked ones, or every task of the suite when none is picked. */
function selectedTasks(form: LoginRuleForm, tasks: EvalTask[]): EvalTask[] {
  return form.taskIds.length ? tasks.filter((t) => form.taskIds.includes(t.id!)) : tasks;
}

function codingSelected(form: LoginRuleForm, tasks: EvalTask[]): boolean {
  return selectedTasks(form, tasks).some((t) => t.kind === 'CODING_JOB');
}

function loginIds(profiles: ModelProfile[]): Set<string> {
  return new Set(profiles.filter(isLogin).map((p) => p.id));
}

/** Coding agents among the candidates that run with a login model (they do the coding tasks). */
function loginCodingCandidates(
  form: LoginRuleForm,
  profiles: ModelProfile[],
): LoginRuleForm['candidates'] {
  const ids = loginIds(profiles);
  return form.candidates.filter(
    (c) => !!c.harness && !!c.modelProfileId && ids.has(c.modelProfileId),
  );
}

/** Login profiles the candidates run with. */
export function loginCandidates(form: LoginRuleForm, profiles: ModelProfile[]): ModelProfile[] {
  const used = new Set(form.candidates.map((c) => c.modelProfileId));
  return profiles.filter((p) => isLogin(p) && used.has(p.id));
}

/** Login profiles the benchmark uses: the login candidates and a login judge. */
export function loginModels(form: LoginRuleForm, profiles: ModelProfile[]): ModelProfile[] {
  const used = new Set(form.candidates.map((c) => c.modelProfileId));
  return profiles.filter(
    (p) => isLogin(p) && (used.has(p.id) || form.judgeModelProfileId === p.id),
  );
}

export function loginIssues(
  form: LoginRuleForm,
  profiles: ModelProfile[],
  tasks: EvalTask[],
  targets: ExecutionTarget[],
): LoginIssue[] {
  if (!loginModels(form, profiles).length) return [];
  const out: LoginIssue[] = [];
  const coding = loginCodingCandidates(form, profiles);
  if (coding.length && codingSelected(form, tasks)) {
    const copilotIds = new Set(profiles.filter(isCopilot).map((p) => p.id));
    if (form.runnerTarget !== 'PERSONAL') out.push('target');
    if (coding.some((c) => !copilotIds.has(c.modelProfileId!) && c.harness !== 'CLAUDE_CODE'))
      out.push('harness');
    if (coding.some((c) => copilotIds.has(c.modelProfileId!) && c.harness !== 'OPENCODE'))
      out.push('copilotHarness');
  }
  if (profiles.some((p) => isCopilot(p) && p.id === form.judgeModelProfileId)) out.push('judge');
  if (!targets.find((t) => t.target === 'PERSONAL')?.localLogin) out.push('localLogin');
  return out;
}

/** Issues the server rejects already when the benchmark is saved (the login one only blocks the start). */
export function blocksSave(issues: LoginIssue[]): boolean {
  return issues.some((i) => i !== 'localLogin');
}

/** Whether `applyLoginRules` can fix something (the agent is the user's choice). */
export function fixable(issues: LoginIssue[]): boolean {
  return issues.includes('target');
}

/** One click: the personal runner, only when coding tasks with a login model need it. */
export function applyLoginRules<F extends LoginRuleForm>(
  form: F,
  profiles: ModelProfile[],
  tasks: EvalTask[],
): F {
  if (!loginCodingCandidates(form, profiles).length || !codingSelected(form, tasks)) return form;
  return { ...form, runnerTarget: 'PERSONAL' };
}
