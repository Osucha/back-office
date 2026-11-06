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

describe('Claude Code login rules (ADR 0048, ADR 0050, candidates ADR 0059)', () => {
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
  const answer = (m: string) => ({ harness: null, modelProfileId: m });
  const claude = (m: string) => ({ harness: 'CLAUDE_CODE' as const, modelProfileId: m });
  const opencode = (m: string) => ({ harness: 'OPENCODE' as const, modelProfileId: m });
  const form = (patch: Partial<LoginRuleForm> = {}): LoginRuleForm => ({
    taskIds: [],
    candidates: [answer('api'), claude('opus')],
    judgeModelProfileId: 'api',
    runnerTarget: 'SHARED',
    ...patch,
  });

  it('has nothing to say without a login model', () => {
    expect(
      loginIssues(form({ candidates: [answer('api'), claude('legacy')] }), profiles, tasks, []),
    ).toEqual([]);
    expect(loginCandidates(form(), profiles).map((p) => p.id)).toEqual(['opus']);
  });

  it('needs the personal runner when a coding agent with a login model meets coding tasks', () => {
    const issues = loginIssues(form(), profiles, tasks, targets);
    expect(issues).toEqual(['target']);
    expect(blocksSave(issues)).toBe(true);
    expect(fixable(issues)).toBe(true);
    expect(loginIssues(form({ runnerTarget: 'PERSONAL' }), profiles, tasks, targets)).toEqual([]);
  });

  it('needs a Claude Code agent for a login model, which one click cannot pick', () => {
    const issues = loginIssues(
      form({ candidates: [opencode('opus')], runnerTarget: 'PERSONAL' }),
      profiles,
      tasks,
      targets,
    );
    expect(issues).toEqual(['harness']);
    expect(blocksSave(issues)).toBe(true);
    expect(fixable(issues)).toBe(false);
  });

  it('lets a login model answer prompt tasks anywhere', () => {
    expect(loginIssues(form({ taskIds: ['p1'] }), profiles, tasks, targets)).toEqual([]);
    expect(loginIssues(form(), profiles, [tasks[0]], targets)).toEqual([]);
    expect(loginIssues(form({ candidates: [answer('opus')] }), profiles, tasks, targets)).toEqual(
      [],
    );
  });

  it('needs a personal runner that uses the login, which blocks only the start', () => {
    const issues = loginIssues(form({ taskIds: ['p1'] }), profiles, tasks, noLogin);
    expect(issues).toEqual(['localLogin']);
    expect(blocksSave(issues)).toBe(false);
    expect(fixable(issues)).toBe(false);
  });

  it('accepts a login judge but still needs the login on the personal runner', () => {
    const judged = form({ candidates: [answer('api')], judgeModelProfileId: 'opus' });
    expect(loginModels(judged, profiles).map((p) => p.id)).toEqual(['opus']);
    expect(loginIssues(judged, profiles, tasks, targets)).toEqual([]);
    expect(loginIssues(judged, profiles, tasks, noLogin)).toEqual(['localLogin']);
  });

  it('applies the personal runner in one step and keeps the rest', () => {
    const fixed = applyLoginRules(
      { ...form({ taskIds: ['p1', 'c1'] }), name: 'kept' },
      profiles,
      tasks,
    );
    expect(fixed).toEqual({
      name: 'kept',
      taskIds: ['p1', 'c1'],
      candidates: [answer('api'), claude('opus')],
      judgeModelProfileId: 'api',
      runnerTarget: 'PERSONAL',
    });
    expect(loginIssues(fixed, profiles, tasks, targets)).toEqual([]);
  });

  it('changes nothing when no coding task needs it', () => {
    const prompts = form({ taskIds: ['p1'] });
    expect(applyLoginRules(prompts, profiles, tasks)).toBe(prompts);
    const judged = form({ candidates: [answer('api')], judgeModelProfileId: 'opus' });
    expect(applyLoginRules(judged, profiles, tasks)).toBe(judged);
  });
});
