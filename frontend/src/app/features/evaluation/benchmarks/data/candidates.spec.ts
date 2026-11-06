import type { Agent, EvalTask } from '../../../../core/api/types';
import {
  applicability,
  attemptCount,
  canDo,
  candidateCount,
  candidateKey,
  candidateProblems,
  candidatesValid,
  emptyCandidate,
  resolveCandidates,
  toCandidates,
} from './candidates';

type A = Pick<Agent, 'key' | 'harness' | 'modelProfileId'>;

describe('benchmark candidates (ADR 0059)', () => {
  const agents: A[] = [
    { key: 'model-answer', harness: null, modelProfileId: null },
    { key: 'claude-code', harness: 'CLAUDE_CODE', modelProfileId: 'sonnet' },
    { key: 'opencode', harness: 'OPENCODE', modelProfileId: null },
  ];
  const tasks = [{ kind: 'PROMPT' }, { kind: 'CODING_JOB' }, { kind: 'CODING_JOB' }] as Pick<
    EvalTask,
    'kind'
  >[];

  it('resolves the model: the chosen one, else the agent own', () => {
    const resolved = resolveCandidates(
      [
        { agentKey: 'claude-code', modelProfileId: null },
        { agentKey: 'claude-code', modelProfileId: 'opus' },
        emptyCandidate(),
      ],
      agents,
    );
    expect(resolved).toEqual([
      { agentKey: 'claude-code', harness: 'CLAUDE_CODE', modelProfileId: 'sonnet' },
      { agentKey: 'claude-code', harness: 'CLAUDE_CODE', modelProfileId: 'opus' },
    ]);
  });

  it('flags a missing agent, a missing model and a duplicate pair', () => {
    const drafts = [
      { agentKey: 'claude-code', modelProfileId: null },
      { agentKey: 'claude-code', modelProfileId: 'sonnet' },
      { agentKey: 'opencode', modelProfileId: null },
      emptyCandidate(),
      { agentKey: 'model-answer', modelProfileId: 'mini' },
    ];
    expect(candidateProblems(drafts, agents)).toEqual([null, 'duplicate', 'model', 'agent', null]);
    expect(candidatesValid(drafts, agents)).toBe(false);
    expect(candidatesValid([drafts[0], drafts[4]], agents)).toBe(true);
    expect(candidatesValid([], agents)).toBe(false);
  });

  it('sends the model only when one was chosen', () => {
    expect(
      toCandidates([
        { agentKey: 'claude-code', modelProfileId: '' },
        { agentKey: 'model-answer', modelProfileId: 'mini' },
        emptyCandidate(),
      ]),
    ).toEqual([
      { agentKey: 'claude-code', modelProfileId: null },
      { agentKey: 'model-answer', modelProfileId: 'mini' },
    ]);
    expect(candidateKey({ agentKey: 'a', modelProfileId: 'm' })).toBe('a|m');
  });

  it('pairs prompt tasks with model answers and coding tasks with coding agents', () => {
    expect(canDo(null, 'PROMPT')).toBe(true);
    expect(canDo(null, 'CODING_JOB')).toBe(false);
    expect(canDo('OPENCODE', 'CODING_JOB')).toBe(true);
    expect(canDo('CLAUDE_CODE', 'PROMPT')).toBe(false);
    expect(applicability(null, tasks)).toEqual({ applicable: 1, notApplicable: 2 });
    expect(applicability('OPENCODE', tasks)).toEqual({ applicable: 2, notApplicable: 1 });
    expect(attemptCount([{ harness: null }, { harness: 'CLAUDE_CODE' }], tasks, 2)).toBe(6);
    expect(attemptCount([{ harness: null }], tasks, 0)).toBe(1);
  });

  it('counts the candidates of new and older benchmarks', () => {
    expect(candidateCount({ candidates: [{ agentKey: 'a' }, { agentKey: 'b' }] })).toBe(2);
    expect(candidateCount({ candidates: [], modelProfileIds: ['x'] })).toBe(1);
    expect(candidateCount({})).toBe(0);
  });
});
