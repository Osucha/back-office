import type {
  EvalTask,
  ExecutionTarget,
  ModelProfile,
  RunnerTarget,
} from '../../../../core/api/types';

/** The part of the benchmark form the ADR 0048 / ADR 0050 rules look at. */
export interface LoginRuleForm {
  taskIds: string[];
  modelProfileIds: string[];
  judgeModelProfileId?: string | null;
  codingHarness: 'OPENCODE' | 'CLAUDE_CODE';
  runnerTarget: RunnerTarget;
}

/**
 * What a benchmark using Claude Code with its owner's login (ADR 0048, ADR 0050 step 4) still needs:
 * - `target`: coding tasks with a login candidate must run on the personal runner,
 * - `harness`: coding tasks with a login candidate need the Claude Code harness,
 * - `localLogin`: the personal runner does not use the Claude Code login, which every login model (candidate or
 *   judge) needs, also for prompt tasks and judging (blocks the start, not the draft).
 */
export type LoginIssue = 'target' | 'harness' | 'localLogin';

export function isLogin(p: Pick<ModelProfile, 'runtime'> | null | undefined): boolean {
  return p?.runtime === 'CLAUDE_CODE_LOGIN';
}

/** Tasks the benchmark runs: the picked ones, or every task of the suite when none is picked. */
function selectedTasks(form: LoginRuleForm, tasks: EvalTask[]): EvalTask[] {
  return form.taskIds.length ? tasks.filter((t) => form.taskIds.includes(t.id!)) : tasks;
}

function codingSelected(form: LoginRuleForm, tasks: EvalTask[]): boolean {
  return selectedTasks(form, tasks).some((t) => t.kind === 'CODING_JOB');
}

/** Selected candidates that are login profiles. */
export function loginCandidates(form: LoginRuleForm, profiles: ModelProfile[]): ModelProfile[] {
  return profiles.filter((p) => isLogin(p) && form.modelProfileIds.includes(p.id));
}

/** Login profiles the benchmark uses: the login candidates and a login judge. */
export function loginModels(form: LoginRuleForm, profiles: ModelProfile[]): ModelProfile[] {
  return profiles.filter(
    (p) => isLogin(p) && (form.modelProfileIds.includes(p.id) || form.judgeModelProfileId === p.id),
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
  if (loginCandidates(form, profiles).length && codingSelected(form, tasks)) {
    if (form.runnerTarget !== 'PERSONAL') out.push('target');
    if (form.codingHarness !== 'CLAUDE_CODE') out.push('harness');
  }
  if (!targets.find((t) => t.target === 'PERSONAL')?.localLogin) out.push('localLogin');
  return out;
}

/** Issues the server rejects already when the benchmark is saved (the login one only blocks the start). */
export function blocksSave(issues: LoginIssue[]): boolean {
  return issues.some((i) => i !== 'localLogin');
}

/** Whether `applyLoginRules` can fix something. */
export function fixable(issues: LoginIssue[]): boolean {
  return issues.includes('target') || issues.includes('harness');
}

/** One click: the personal runner and the Claude Code harness, only when coding tasks need them. */
export function applyLoginRules<F extends LoginRuleForm>(
  form: F,
  profiles: ModelProfile[],
  tasks: EvalTask[],
): F {
  if (!loginCandidates(form, profiles).length || !codingSelected(form, tasks)) return form;
  return { ...form, runnerTarget: 'PERSONAL', codingHarness: 'CLAUDE_CODE' };
}
