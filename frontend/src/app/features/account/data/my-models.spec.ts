import type { ExecutionTarget, ModelOption } from '../../../core/api/types';
import { harnessName, personalRunnerStatus, splitModelOptions } from './my-models';

const target = (changes: Partial<ExecutionTarget> = {}): ExecutionTarget => ({
  target: 'PERSONAL',
  pools: 1,
  onlineRunners: 1,
  localLogin: true,
  claudeLogin: true,
  harnesses: ['OPENCODE', 'CLAUDE_CODE'],
  ...changes,
});

const option = (profileId: string, changes: Partial<ModelOption> = {}): ModelOption => ({
  profileId,
  displayName: profileId,
  provider: 'anthropic',
  modelId: `${profileId}-model`,
  runtime: 'API',
  route: 'GATEWAY',
  personal: false,
  usable: true,
  ...changes,
});

describe('my models (ADR 0050 step 3)', () => {
  it('is unknown until the targets are loaded', () => {
    expect(personalRunnerStatus(null)).toBeNull();
    expect(personalRunnerStatus(undefined)).toBeNull();
  });

  it('reports no personal runner when the target is missing or has no pool', () => {
    const shared: ExecutionTarget = { target: 'SHARED', pools: 2, onlineRunners: 3 };
    expect(personalRunnerStatus([shared])).toEqual({
      setUp: false,
      online: 0,
      localLogin: false,
      login: 'unknown',
      harnesses: [],
    });
    expect(personalRunnerStatus([target({ pools: 0, localLogin: true })])?.localLogin).toBe(false);
  });

  it('summarises an online runner with a reported login and sorted coding agents', () => {
    expect(personalRunnerStatus([target()])).toEqual({
      setUp: true,
      online: 1,
      localLogin: true,
      login: 'reported',
      harnesses: ['CLAUDE_CODE', 'OPENCODE'],
    });
  });

  it('tells a missing login apart from a login that was not reported', () => {
    expect(personalRunnerStatus([target({ claudeLogin: false })])?.login).toBe('none');
    expect(personalRunnerStatus([target({ claudeLogin: null })])?.login).toBe('unknown');
    expect(personalRunnerStatus([target({ claudeLogin: undefined })])?.login).toBe('unknown');
  });

  it('ignores login and agents while no runner is online', () => {
    const s = personalRunnerStatus([target({ onlineRunners: 0, claudeLogin: false })]);
    expect(s?.login).toBe('unknown');
    expect(s?.harnesses).toEqual([]);
    expect(s?.setUp).toBe(true);
  });

  it('names the coding agents', () => {
    expect(harnessName('CLAUDE_CODE')).toBe('Claude Code');
    expect(harnessName('OPENCODE')).toBe('OpenCode');
    expect(harnessName('SPEC_KIT')).toBe('Spec Kit');
    expect(harnessName('AIDER')).toBe('AIDER');
  });

  it('splits options into usable and unusable, platform first then by name', () => {
    const split = splitModelOptions([
      option('zeta'),
      option('mine', { personal: true }),
      option('alpha'),
      option('broken', { usable: false, problem: 'no login' }),
    ]);
    expect(split.usable.map((o) => o.profileId)).toEqual(['alpha', 'zeta', 'mine']);
    expect(split.unusable.map((o) => o.profileId)).toEqual(['broken']);
    expect(splitModelOptions(null)).toEqual({ usable: [], unusable: [] });
  });
});
