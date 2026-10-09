import type { ResolvedTask, TaskTemplate } from '../../../core/api/types';
import {
  draftSkillDelta,
  emptyTemplate,
  parentCandidates,
  toPreview,
  toTemplateInput,
} from './resolved-preview';

describe('toPreview', () => {
  it('maps a resolved task into the preview model', () => {
    const r: ResolvedTask = {
      key: 'code-review',
      executor: 'LLM',
      lineage: ['base-agent', 'code-review'],
      prompt: { system: 'sys', user: 'usr' },
      skills: [{ key: 'a', name: 'A' }, { key: 'b' }],
      interfaces: ['reviewer'],
      ports: ['success', 'failure'],
      produces: ['review'],
      consumes: ['diff'],
      config: { chunk: 2 },
      interfaceCheck: { ok: false, problems: ['missing output review'] },
    };
    const p = toPreview(r)!;
    expect(p.lineage).toEqual(['base-agent', 'code-review']);
    expect(p.systemPrompt).toBe('sys');
    expect(p.skills).toEqual([
      { key: 'a', name: 'A' },
      { key: 'b', name: 'b' },
    ]);
    expect(p.ok).toBe(false);
    expect(p.problems).toEqual(['missing output review']);
    expect(p.config).toEqual({ chunk: 2 });
  });

  it('defaults lineage to the key and ok to true when the check is absent', () => {
    const p = toPreview({ key: 'x' })!;
    expect(p.lineage).toEqual(['x']);
    expect(p.ok).toBe(true);
    expect(toPreview(null)).toBeNull();
  });
});

describe('draftSkillDelta', () => {
  it('lists skills the draft adds or removes versus the resolved result', () => {
    const preview = toPreview({ key: 'x', skills: [{ key: 'a' }, { key: 'b' }] });
    const delta = draftSkillDelta(
      { ...emptyTemplate(), skills: ['a', 'c'], removeSkills: ['b', 'zzz'] },
      preview,
    );
    expect(delta).toEqual({ added: ['c'], removed: ['b'] });
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
      prompt: { user: 'u', mode: 'REPLACE' },
    };
    const input = toTemplateInput(t as TaskTemplate & Record<string, unknown>);
    expect('id' in input).toBe(false);
    expect('builtIn' in input).toBe(false);
    expect(input.prompt).toEqual({ system: '', user: 'u', mode: 'REPLACE' });
    expect(input.skills).toEqual([]);
    expect(input.extendsKey).toBeNull();
  });
});
