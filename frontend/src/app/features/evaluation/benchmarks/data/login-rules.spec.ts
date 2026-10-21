import type { EvalTask, ExecutionTarget, ModelProfile } from '../../../../core/api/types';
import {
  applyLoginRules,
  blocksSave,
  fixable,
  loginCandidates,
  loginIssues,
  type LoginRuleForm,
} from './login-rules';

describe('Claude Code login rules (ADR 0048)', () => {
  const profile = (id: string, runtime?: ModelProfile['runtime']): ModelProfile => ({
    id,
    displayName: id,
    runtime,
    enabled: true,
    ref: { provider: 'ANTHROPIC', modelId: id },
    pricing: { inputPerMTok: 0, outputPerMTok: 0, currency: 'USD' },
  });
  const profiles = [profile('api', 'API'), profile('opus', 'CLAUDE_CODE_LOGIN'), profile('legacy')];
  const tasks = [
    { id: 'p1', kind: 'PROMPT' },
    { id: 'c1', kind: 'CODING_JOB' },
    { id: 'c2', kind: 'CODING_JOB' },
  ] as EvalTask[];
  const targets: ExecutionTarget[] = [
    { target: 'SHARED', pools: 1, onlineRunners: 1 },
    { target: 'PERSONAL', pools: 1, onlineRunners: 1, localLogin: true },
  ];
  const form = (patch: Partial<LoginRuleForm> = {}): LoginRuleForm => ({
    taskIds: [],
    modelProfileIds: ['api', 'opus'],
    codingHarness: 'OPENCODE',
    runnerTarget: 'SHARED',
    ...patch,
  });

  it('has nothing to say without a login candidate', () => {
    expect(loginIssues(form({ modelProfileIds: ['api', 'legacy'] }), profiles, tasks, [])).toEqual(
      [],
    );
    expect(loginCandidates(form(), profiles).map((p) => p.id)).toEqual(['opus']);
  });

  it('lists what a login candidate needs', () => {
    const issues = loginIssues(form(), profiles, tasks, targets);
    expect(issues).toEqual(['target', 'harness', 'promptTasks']);
    expect(blocksSave(issues)).toBe(true);
    expect(fixable(issues)).toBe(true);
    expect(
      loginIssues(
        form({ runnerTarget: 'PERSONAL', codingHarness: 'CLAUDE_CODE', taskIds: ['c1'] }),
        profiles,
        tasks,
        targets,
      ),
    ).toEqual([]);
  });

  it('needs a personal runner that uses the login, which blocks only the start', () => {
    const ok = form({ runnerTarget: 'PERSONAL', codingHarness: 'CLAUDE_CODE', taskIds: ['c1'] });
    const issues = loginIssues(ok, profiles, tasks, [
      { target: 'PERSONAL', pools: 1, onlineRunners: 0 },
    ]);
    expect(issues).toEqual(['localLogin']);
    expect(blocksSave(issues)).toBe(false);
    expect(fixable(issues)).toBe(false);
  });

  it('cannot fix a selection without coding tasks', () => {
    const issues = loginIssues(form({ taskIds: ['p1'] }), profiles, tasks, targets);
    expect(issues).toContain('noCodingTasks');
    const fixed = applyLoginRules(form({ taskIds: ['p1'] }), tasks);
    expect(fixed.taskIds).toEqual(['p1']);
    expect(loginIssues(fixed, profiles, tasks, targets)).toEqual(['promptTasks', 'noCodingTasks']);
    expect(fixable(['promptTasks', 'noCodingTasks'])).toBe(false);
  });

  it('applies the personal runner, Claude Code and the coding tasks in one step', () => {
    const fixed = applyLoginRules({ ...form(), name: 'kept' }, tasks);
    expect(fixed).toEqual({
      name: 'kept',
      taskIds: ['c1', 'c2'],
      modelProfileIds: ['api', 'opus'],
      codingHarness: 'CLAUDE_CODE',
      runnerTarget: 'PERSONAL',
    });
    expect(loginIssues(fixed, profiles, tasks, targets)).toEqual([]);
    expect(applyLoginRules(form({ taskIds: ['c2'] }), tasks).taskIds).toEqual(['c2']);
  });
});
