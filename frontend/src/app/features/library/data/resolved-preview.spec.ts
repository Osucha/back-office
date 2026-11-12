import type { ResolvedTask, TaskTemplate } from '../../../core/api/types';
import {
  automaticRules,
  customizeForProject,
  emptyTemplate,
  parentCandidates,
  presetOf,
  toPreview,
  toSavePayload,
  toTemplateInput,
} from './resolved-preview';

describe('toPreview', () => {
  it('maps a resolved task into the preview model', () => {
    const r: ResolvedTask = {
      key: 'code-review',
      agent: { key: 'model-answer', name: 'Model answer', harness: null },
      lineage: ['base-agent', 'code-review'],
      instructions: 'Review the diff.',
      skills: [{ key: 'a', name: 'A' }, { key: 'b' }],
      category: 'REVIEW',
      rules: [
        { key: 'no-secrets', name: 'No secrets', automatic: true },
        { key: 'polish', automatic: false },
      ],
      ports: ['success', 'failure'],
      produces: ['review'],
      consumes: ['diff'],
      config: { chunk: 2 },
      check: {
        ok: false,
        problems: ['unknown skill zzz'],
        warnings: ['agent coder lacks skill kotlin'],
      },
    };
    const p = toPreview(r)!;
    expect(p.lineage).toEqual(['base-agent', 'code-review']);
    expect(p.instructions).toBe('Review the diff.');
    expect(p.skills).toEqual([
      { key: 'a', name: 'A' },
      { key: 'b', name: 'b' },
    ]);
    expect(p.ok).toBe(false);
    expect(p.problems).toEqual(['unknown skill zzz']);
    expect(p.warnings).toEqual(['agent coder lacks skill kotlin']);
    expect(p.category).toBe('REVIEW');
    expect(p.rules).toEqual([
      { key: 'no-secrets', name: 'No secrets', automatic: true },
      { key: 'polish', name: 'polish', automatic: false },
    ]);
    expect(automaticRules(p)).toEqual([{ key: 'no-secrets', name: 'No secrets' }]);
    expect(p.config).toEqual({ chunk: 2 });
    expect(p.agent?.key).toBe('model-answer');
    expect(p.parameters).toEqual([]);
  });

  it('defaults lineage to the key and ok to true when the check is absent', () => {
    const p = toPreview({ key: 'x' })!;
    expect(p.lineage).toEqual(['x']);
    expect(p.ok).toBe(true);
    expect(p.rules).toEqual([]);
    expect(p.category).toBeNull();
    expect(automaticRules(null)).toEqual([]);
    expect(toPreview(null)).toBeNull();
  });
});

describe('save payload (ADR 0055)', () => {
  it('never sends the key on update and only a given one on create', () => {
    const draft = { ...emptyTemplate(), key: 'code-review', name: 'Code review' };
    expect('key' in toSavePayload(draft, true)).toBe(false);
    expect(toSavePayload(draft, false).key).toBe('code-review');
    expect('key' in toSavePayload({ ...emptyTemplate(), name: 'New' }, false)).toBe(false);
    expect('key' in toSavePayload({ ...emptyTemplate(), key: '', name: 'New' }, false)).toBe(false);
    expect('key' in emptyTemplate()).toBe(false);
  });

  it('customises a global template as a project template of the same key', () => {
    const d = customizeForProject({ key: 'code-review', name: 'Code review' }, 'p1');
    expect(d.key).toBe('code-review');
    expect(d.extendsKey).toBe('code-review');
    expect(d.name).toBe('Code review');
    expect(d.scope).toEqual({ type: 'PROJECT', projectId: 'p1' });
    expect(d.category).toBeNull();
    expect(d.parameters).toEqual([]);
    expect(d.parameterValues).toEqual({});
    expect(toSavePayload(d, false).key).toBe('code-review');
  });
});

describe('parentCandidates', () => {
  const all = [
    { key: 'base', extendsKey: null },
    { key: 'impl', extendsKey: 'base' },
    { key: 'impl-claude', extendsKey: 'impl' },
    { key: 'review', extendsKey: 'base' },
  ];

  it('excludes the template itself and all descendants', () => {
    expect(parentCandidates(all, 'impl').map((t) => t.key)).toEqual(['base', 'review']);
    expect(parentCandidates(all, 'base').map((t) => t.key)).toEqual([]);
    expect(parentCandidates(all, null)).toHaveLength(4);
  });
});

describe('toTemplateInput', () => {
  it('strips server fields and fills defaults', () => {
    const t: TaskTemplate = {
      id: 'uuid',
      key: 'k',
      name: 'n',
      abstract: false,
      scope: { type: 'GLOBAL' },
      builtIn: true,
      version: 3,
      instructions: 'u',
      instructionsMode: 'REPLACE',
    };
    const input = toTemplateInput(t as TaskTemplate & Record<string, unknown>);
    expect('id' in input).toBe(false);
    expect('builtIn' in input).toBe(false);
    expect(input.instructions).toBe('u');
    expect(input.instructionsMode).toBe('REPLACE');
    // ADR 0062: no run inputs and no sandbox policy on templates
    expect('runInputs' in input).toBe(false);
    expect('sandboxPolicy' in input).toBe(false);
    expect('prompt' in input).toBe(false);
    expect(input.parameters).toEqual([]);
    expect(input.parameterValues).toEqual({});
    expect(input.requiresSkills).toEqual([]);
    expect('skills' in input).toBe(false);
    expect(input.category).toBeNull();
    expect(input.extendsKey).toBeNull();
  });
});

describe('presetOf (ADR 0058)', () => {
  it('extends the template in its category and scope, with an empty name and no own values', () => {
    const d = presetOf({
      key: 'llm-task',
      category: 'ANALYSIS',
      scope: { type: 'PROJECT', projectId: 'p1' },
    });
    expect(d.extendsKey).toBe('llm-task');
    expect(d.category).toBe('ANALYSIS');
    expect(d.scope).toEqual({ type: 'PROJECT', projectId: 'p1' });
    expect(d.name).toBe('');
    expect(d.parameterValues).toEqual({});
    expect('key' in d).toBe(false);
    expect(
      presetOf({ key: 'x', scope: { type: 'GLOBAL' } }, { type: 'PROJECT', projectId: 'p2' }).scope,
    ).toEqual({
      type: 'PROJECT',
      projectId: 'p2',
    });
  });

  it('starts new templates without a parent and keeps parameters on edit', () => {
    expect(emptyTemplate().extendsKey).toBeNull();
    const input = toTemplateInput({
      ...emptyTemplate(),
      name: 'n',
      parameters: [{ name: 'task', label: 'Task', type: 'TEXT', required: true }],
      parameterValues: { tone: 'casual' },
    } as TaskTemplate & Record<string, unknown>);
    expect(input.parameters).toEqual([
      { name: 'task', label: 'Task', type: 'TEXT', required: true },
    ]);
    expect(input.parameterValues).toEqual({ tone: 'casual' });
  });
});
