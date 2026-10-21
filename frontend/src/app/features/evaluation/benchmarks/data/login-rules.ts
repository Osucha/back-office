import type {
  EvalTask,
  ExecutionTarget,
  ModelProfile,
  RunnerTarget,
} from '../../../../core/api/types';

/** The part of the benchmark form the ADR 0048 rules look at. */
export interface LoginRuleForm {
  taskIds: string[];
  modelProfileIds: string[];
  codingHarness: 'OPENCODE' | 'CLAUDE_CODE';
  runnerTarget: RunnerTarget;
}

/**
 * What a candidate running Claude Code with its owner's login (ADR 0048) still needs:
 * - `target`: coding tasks must run on the personal runner,
 * - `harness`: the coding harness must be Claude Code,
 * - `promptTasks`: prompt tasks are selected (only coding tasks are allowed),
 * - `noCodingTasks`: the selection has no coding task at all,
 * - `localLogin`: the personal runner does not use the Claude Code login (blocks the start, not the draft).
 */
export type LoginIssue = 'target' | 'harness' | 'promptTasks' | 'noCodingTasks' | 'localLogin';

export function isLogin(p: Pick<ModelProfile, 'runtime'> | null | undefined): boolean {
  return p?.runtime === 'CLAUDE_CODE_LOGIN';
}

/** Tasks the benchmark runs: the picked ones, or every task of the suite when none is picked. */
function selectedTasks(form: LoginRuleForm, tasks: EvalTask[]): EvalTask[] {
  return form.taskIds.length ? tasks.filter((t) => form.taskIds.includes(t.id!)) : tasks;
}

/** Selected candidates that are login profiles. */
export function loginCandidates(form: LoginRuleForm, profiles: ModelProfile[]): ModelProfile[] {
  return profiles.filter((p) => isLogin(p) && form.modelProfileIds.includes(p.id));
}

export function loginIssues(
  form: LoginRuleForm,
  profiles: ModelProfile[],
  tasks: EvalTask[],
  targets: ExecutionTarget[],
): LoginIssue[] {
  if (!loginCandidates(form, profiles).length) return [];
  const out: LoginIssue[] = [];
  if (form.runnerTarget !== 'PERSONAL') out.push('target');
  if (form.codingHarness !== 'CLAUDE_CODE') out.push('harness');
  const selected = selectedTasks(form, tasks);
  if (selected.some((t) => t.kind === 'PROMPT')) out.push('promptTasks');
  if (tasks.length && !selected.some((t) => t.kind === 'CODING_JOB')) out.push('noCodingTasks');
  if (!targets.find((t) => t.target === 'PERSONAL')?.localLogin) out.push('localLogin');
  return out;
}

/** Issues the server rejects already when the benchmark is saved (the login one only blocks the start). */
export function blocksSave(issues: LoginIssue[]): boolean {
  return issues.some((i) => i !== 'localLogin');
}

/** Whether `applyLoginRules` can fix something. */
export function fixable(issues: LoginIssue[]): boolean {
  return (
    issues.includes('target') ||
    issues.includes('harness') ||
    (issues.includes('promptTasks') && !issues.includes('noCodingTasks'))
  );
}

/** One click: the personal runner, the Claude Code harness and only the coding tasks of the selection. */
export function applyLoginRules<F extends LoginRuleForm>(form: F, tasks: EvalTask[]): F {
  const selected = selectedTasks(form, tasks);
  const coding = selected.filter((t) => t.kind === 'CODING_JOB').map((t) => t.id!);
  const keepTasks = !selected.some((t) => t.kind === 'PROMPT') || !coding.length;
  return {
    ...form,
    runnerTarget: 'PERSONAL',
    codingHarness: 'CLAUDE_CODE',
    taskIds: keepTasks ? form.taskIds : coding,
  };
}
