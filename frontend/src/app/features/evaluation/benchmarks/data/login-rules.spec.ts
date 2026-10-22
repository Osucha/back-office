import type { EvalTask, ExecutionTarget, ModelProfile } from '../../../../core/api/types';
import {
  applyLoginRules,
  blocksSave,
  fixable,
  loginCandidates,
  loginIssues,
  loginModels,
  type LoginRuleForm,
} from './login-rules';

describe('Claude Code login rules (ADR 0048, ADR 0050)', () => {
  const profile = (id: string, runtime?: ModelProfile['runtime']): ModelProfile => ({
    id,
    displayName: id,
    runtime,
    enabled: true,
    personal: runtime === 'CLAUDE_CODE_LOGIN',
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
  const noLogin: ExecutionTarget[] = [{ target: 'PERSONAL', pools: 1, onlineRunners: 0 }];
  const form = (patch: Partial<LoginRuleForm> = {}): LoginRuleForm => ({
    taskIds: [],
    modelProfileIds: ['api', 'opus'],
    judgeModelProfileId: 'api',
    codingHarness: 'OPENCODE',
    runnerTarget: 'SHARED',
    ...patch,
  });

  it('has nothing to say without a login model', () => {
    expect(loginIssues(form({ modelProfileIds: ['api', 'legacy'] }), profiles, tasks, [])).toEqual(
      [],
    );
    expect(loginCandidates(form(), profiles).map((p) => p.id)).toEqual(['opus']);
  });

  it('needs the personal runner and Claude Code when a login candidate meets coding tasks', () => {
    const issues = loginIssues(form(), profiles, tasks, targets);
    expect(issues).toEqual(['target', 'harness']);
    expect(blocksSave(issues)).toBe(true);
    expect(fixable(issues)).toBe(true);
    expect(
      loginIssues(
        form({ runnerTarget: 'PERSONAL', codingHarness: 'CLAUDE_CODE' }),
        profiles,
        tasks,
        targets,
      ),
    ).toEqual([]);
  });

  it('lets a login candidate run prompt tasks anywhere', () => {
    expect(loginIssues(form({ taskIds: ['p1'] }), profiles, tasks, targets)).toEqual([]);
    expect(loginIssues(form(), profiles, [tasks[0]], targets)).toEqual([]);
  });

  it('needs a personal runner that uses the login, which blocks only the start', () => {
    const issues = loginIssues(form({ taskIds: ['p1'] }), profiles, tasks, noLogin);
    expect(issues).toEqual(['localLogin']);
    expect(blocksSave(issues)).toBe(false);
    expect(fixable(issues)).toBe(false);
  });

  it('accepts a login judge but still needs the login on the personal runner', () => {
    const judged = form({ modelProfileIds: ['api'], judgeModelProfileId: 'opus' });
    expect(loginModels(judged, profiles).map((p) => p.id)).toEqual(['opus']);
    expect(loginIssues(judged, profiles, tasks, targets)).toEqual([]);
    expect(loginIssues(judged, profiles, tasks, noLogin)).toEqual(['localLogin']);
  });

  it('applies the personal runner and Claude Code in one step and keeps the tasks', () => {
    const fixed = applyLoginRules(
      { ...form({ taskIds: ['p1', 'c1'] }), name: 'kept' },
      profiles,
      tasks,
    );
    expect(fixed).toEqual({
      name: 'kept',
      taskIds: ['p1', 'c1'],
      modelProfileIds: ['api', 'opus'],
      judgeModelProfileId: 'api',
      codingHarness: 'CLAUDE_CODE',
      runnerTarget: 'PERSONAL',
    });
    expect(loginIssues(fixed, profiles, tasks, targets)).toEqual([]);
  });

  it('changes nothing when no coding task needs it', () => {
    const prompts = form({ taskIds: ['p1'] });
    expect(applyLoginRules(prompts, profiles, tasks)).toBe(prompts);
    const judged = form({ modelProfileIds: ['api'], judgeModelProfileId: 'opus' });
    expect(applyLoginRules(judged, profiles, tasks)).toBe(judged);
  });
});
