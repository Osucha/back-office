import type { ResolvedTask, TaskTemplate } from '../../../core/api/types';
import {
  automaticRules,
  customizeForProject,
  draftRuleDelta,
  draftSkillDelta,
  emptyTemplate,
  parentCandidates,
  toPreview,
  toSavePayload,
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
      category: 'REVIEW',
      rules: [
        { key: 'no-secrets', name: 'No secrets', automatic: true },
        { key: 'polish', automatic: false },
      ],
      ports: ['success', 'failure'],
      produces: ['review'],
      consumes: ['diff'],
      config: { chunk: 2 },
      check: { ok: false, problems: ['unknown skill zzz'] },
    };
    const p = toPreview(r)!;
    expect(p.lineage).toEqual(['base-agent', 'code-review']);
    expect(p.systemPrompt).toBe('sys');
    expect(p.skills).toEqual([
      { key: 'a', name: 'A' },
      { key: 'b', name: 'b' },
    ]);
    expect(p.ok).toBe(false);
    expect(p.problems).toEqual(['unknown skill zzz']);
    expect(p.category).toBe('REVIEW');
    expect(p.rules).toEqual([
      { key: 'no-secrets', name: 'No secrets', automatic: true },
      { key: 'polish', name: 'polish', automatic: false },
    ]);
    expect(automaticRules(p)).toEqual([{ key: 'no-secrets', name: 'No secrets' }]);
    expect(p.config).toEqual({ chunk: 2 });
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

describe('draftRuleDelta', () => {
  it('lists rules the draft adds or drops versus the resolved result', () => {
    const preview = toPreview({ key: 'x', rules: [{ key: 'auto', automatic: true }] });
    const delta = draftRuleDelta(
      { ...emptyTemplate(), rules: ['auto', 'new'], removeRules: ['auto', 'zzz'] },
      preview,
    );
    expect(delta).toEqual({ added: ['new'], removed: ['auto'] });
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
    expect(d.executor).toBeNull();
    expect(d.category).toBeNull();
    expect(d.skills).toEqual([]);
    expect(d.rules).toEqual([]);
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
      prompt: { user: 'u', mode: 'REPLACE' },
    };
    const input = toTemplateInput(t as TaskTemplate & Record<string, unknown>);
    expect('id' in input).toBe(false);
    expect('builtIn' in input).toBe(false);
    expect(input.prompt).toEqual({ system: '', user: 'u', mode: 'REPLACE' });
    expect(input.skills).toEqual([]);
    expect(input.rules).toEqual([]);
    expect(input.removeRules).toEqual([]);
    expect(input.category).toBeNull();
    expect(input.extendsKey).toBeNull();
  });
});
