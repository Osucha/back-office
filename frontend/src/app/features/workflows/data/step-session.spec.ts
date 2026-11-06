import type { WorkflowNode } from '../../../core/api/types';
import {
  continuedStep,
  continuesFromName,
  sessionCandidates,
  stepParameterFields,
  withContinueSession,
} from './step-session';

const node = (id: string, kind: WorkflowNode['kind'], extra: Partial<WorkflowNode> = {}) =>
  ({ id, kind, name: id.toUpperCase(), position: { x: 0, y: 0 }, ...extra }) as WorkflowNode;

describe('step session (ADR 0057)', () => {
  it('reads and writes continueSession, keeping the other config keys', () => {
    expect(continuedStep(undefined)).toBeNull();
    expect(continuedStep({ continueSession: ' ' })).toBeNull();
    expect(continuedStep({ continueSession: 'spec' })).toBe('spec');
    expect(withContinueSession({ push: true }, 'spec')).toEqual({
      push: true,
      continueSession: 'spec',
    });
    expect(withContinueSession({ push: true, continueSession: 'spec' }, null)).toEqual({
      push: true,
    });
    expect(withContinueSession({ continueSession: 'spec' }, null)).toBeUndefined();
  });

  it('offers the other coding agent steps, marking a different harness', () => {
    const nodes = [
      node('start', 'START'),
      node('spec', 'TASK'),
      node('review', 'TASK'),
      node('impl', 'TASK'),
      node('fix', 'TASK'),
    ];
    const harness: Record<string, string | null> = {
      spec: 'CLAUDE_CODE',
      review: null,
      impl: 'CLAUDE_CODE',
      fix: 'OPENCODE',
    };
    const c = sessionCandidates(nodes, 'impl', 'CLAUDE_CODE', (n) => ({
      harness: (harness[n.id] ?? null) as never,
    }));
    expect(c).toEqual([
      { id: 'spec', name: 'SPEC', harness: 'CLAUDE_CODE', sameHarness: true },
      { id: 'fix', name: 'FIX', harness: 'OPENCODE', sameHarness: false },
    ]);
  });

  it('names the continued step for the canvas marker', () => {
    const impl = node('impl', 'TASK', {
      task: { templateKey: 't', overrides: { config: { continueSession: 'spec' } } },
    });
    expect(continuesFromName(impl, new Map([['spec', 'Write spec']]))).toBe('Write spec');
    expect(continuesFromName(impl, null)).toBe('spec');
    expect(continuesFromName(node('n', 'NOTE'), null)).toBeNull();
    expect(continuesFromName(node('t', 'TASK'), null)).toBeNull();
  });
});

describe('step parameters (ADR 0058)', () => {
  const task = {
    key: 'llm-task',
    parameters: [{ name: 'task', label: 'Task', type: 'MULTILINE' as const, required: true }],
    parameterValues: {},
  };

  it('uses the palette declarations and the step values', () => {
    const missing = stepParameterFields(task, null, {});
    expect(missing[0].missing).toBe(true);
    const set = stepParameterFields(task, null, { parameters: { task: 'Summarise' } });
    expect(set[0].value).toBe('Summarise');
    expect(set[0].missing).toBe(false);
  });

  it('falls back to the resolved template', () => {
    const fields = stepParameterFields(
      { parameters: [] },
      {
        parameters: [
          {
            name: 'tone',
            label: 'Tone',
            type: 'TEXT',
            required: true,
            value: 'formal',
            source: 'TEMPLATE',
          },
        ],
      },
      null,
    );
    expect(fields[0].inherited).toBe('formal');
    expect(fields[0].inheritedSource).toBe('TEMPLATE');
    expect(fields[0].missing).toBe(false);
    expect(stepParameterFields(null, null, null)).toEqual([]);
  });
});
