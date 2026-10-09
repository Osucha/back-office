import type { NodeExecution, WorkflowRun } from '../../../core/api/types';
import { absolute, stepFacts } from './step-facts';

const snapshot = {
  nodes: [
    {
      id: 'impl',
      name: 'Implement',
      kind: 'TASK',
      task: {
        templateKey: 'implement',
        overrides: { modelProfileId: 'fast', addSkills: ['a', 'b'], promptAppend: 'more' },
      },
    },
    { id: 'review', name: 'Review', kind: 'HUMAN_GATE', gate: { options: ['APPROVE', 'REJECT'] } },
    { id: 'ci', kind: 'WAIT_FOR_CI', ci: { signal: 'tests', timeoutSeconds: 600 } },
    {
      id: 'route',
      kind: 'DECISION',
      decision: { branches: [{ id: 'big', expression: 'size > 3' }], defaultBranch: 'small' },
    },
  ],
  edges: [{ from: 'impl', fromPort: 'success', to: 'review' }],
};

function run(nodes: Partial<NodeExecution>[]): WorkflowRun {
  return { id: 'r1', definitionSnapshot: snapshot, nodes } as unknown as WorkflowRun;
}

const labels = (facts: { label: string; value: string }[]) =>
  Object.fromEntries(facts.map((f) => [f.label, f.value]));

describe('stepFacts', () => {
  it('lists the task configuration, where the baton went, and the execution', () => {
    const start = { nodeId: 'start', nodeName: 'Start', kind: 'START', port: 'next' };
    const impl = {
      nodeId: 'impl',
      kind: 'TASK',
      port: 'SUCCESS',
      attempt: 2,
      runnerJobId: '0123456789abcdef',
      usage: { inputTokens: 1000, outputTokens: 500 },
    };
    const r = run([start, impl] as Partial<NodeExecution>[]);
    const facts = stepFacts(r, r.nodes![1]);
    expect(facts.map((f) => f.label)).toEqual([
      'Template',
      'Model override',
      'Extra skills',
      'Prompt',
      'Reached from',
      'Left through',
      'Attempt',
      'Runner job',
      'Tokens',
    ]);
    const v = labels(facts);
    expect(v['Template']).toBe('implement');
    expect(v['Extra skills']).toBe('a, b');
    expect(v['Prompt']).toBe('extended on the node');
    expect(v['Reached from']).toBe('Start → next');
    expect(v['Left through']).toBe('SUCCESS → Review');
    expect(v['Attempt']).toBe('2');
    expect(v['Runner job']).toBe('01234567');
    expect(v['Tokens']).toBe('1,500');
  });

  it('prefers the rendered gate question and lists the options', () => {
    const r = run([{ nodeId: 'review', kind: 'HUMAN_GATE' }]);
    const v = labels(stepFacts(r, r.nodes![0], 'Ship it?'));
    expect(v['Question']).toBe('Ship it?');
    expect(v['Options']).toBe('APPROVE · REJECT');
    expect(v['Attempt']).toBe('1');
    expect(v['Reached from']).toBeUndefined();
  });

  it('describes CI waits and decisions', () => {
    const r = run([
      { nodeId: 'ci', kind: 'WAIT_FOR_CI' },
      { nodeId: 'route', kind: 'DECISION' },
    ]);
    expect(labels(stepFacts(r, r.nodes![0]))).toEqual(
      expect.objectContaining({ Signal: 'tests', Timeout: '600 s' }),
    );
    expect(labels(stepFacts(r, r.nodes![1]))).toEqual(
      expect.objectContaining({ 'Branch big': 'size > 3', Otherwise: 'small' }),
    );
  });

  it('keeps unparseable times as they are', () => {
    expect(absolute('not a date')).toBe('not a date');
  });
});
