/**
 * Pure mapping of a `ResolvedTask` into what the template editor preview shows: lineage
 * breadcrumb, effective prompts, skills, rules (ADR 0055), the check of missing skills and rules
 * and the diff against the draft.
 */
import type {
  AgentRef,
  ResolvedTask,
  RunInputField,
  TaskTemplateInput,
} from '../../../core/api/types';

export interface ResolvedPreview {
  lineage: string[];
  /** ADR 0056: the agent that runs the task (harness null = model answer). */
  agent: AgentRef | null;
  modelProfileId: string | null;
  systemPrompt: string;
  userPrompt: string;
  skills: Array<{ key: string; name: string }>;
  /** ADR 0055: the rules the step gets, automatic ones first. */
  rules: Array<{ key: string; name: string; automatic: boolean }>;
  /** ADR 0055: own or inherited category; null when none. */
  category: string | null;
  ports: string[];
  produces: string[];
  consumes: string[];
  /** ADR 0053: the run inputs merged over the lineage. */
  runInputs: RunInputField[];
  config: Record<string, unknown>;
  abstract: boolean;
  ok: boolean;
  problems: string[];
}

export function toPreview(r: ResolvedTask | null | undefined): ResolvedPreview | null {
  if (!r) return null;
  return {
    lineage: r.lineage?.length ? r.lineage : r.key ? [r.key] : [],
    agent: r.agent ?? null,
    modelProfileId: r.modelProfileId ?? null,
    systemPrompt: r.prompt?.system ?? '',
    userPrompt: r.prompt?.user ?? '',
    skills: (r.skills ?? []).map((s) => ({ key: s.key ?? '', name: s.name ?? s.key ?? '' })),
    rules: (r.rules ?? []).map((x) => ({
      key: x.key ?? '',
      name: x.name || x.key || '',
      automatic: !!x.automatic,
    })),
    category: r.category ?? null,
    ports: r.ports ?? [],
    produces: r.produces ?? [],
    consumes: r.consumes ?? [],
    runInputs: r.runInputs ?? [],
    config: r.config ?? {},
    abstract: !!r.abstract,
    ok: r.check?.ok !== false,
    problems: r.check?.problems ?? [],
  };
}

function delta(
  listed: readonly string[] | undefined,
  dropped: readonly string[] | undefined,
  resolvedKeys: readonly string[],
): { added: string[]; removed: string[] } {
  const resolved = new Set(resolvedKeys);
  return {
    added: (listed ?? []).filter((k) => !resolved.has(k)),
    removed: (dropped ?? []).filter((k) => resolved.has(k)),
  };
}

/** Skills that the draft adds compared to the resolved result (not yet saved / from a parent). */
export function draftSkillDelta(
  draft: TaskTemplateInput,
  preview: ResolvedPreview | null,
): { added: string[]; removed: string[] } {
  return delta(
    draft.skills,
    draft.removeSkills,
    (preview?.skills ?? []).map((s) => s.key),
  );
}

/** ADR 0055: rules that the draft adds or drops compared to the resolved result. */
export function draftRuleDelta(
  draft: TaskTemplateInput,
  preview: ResolvedPreview | null,
): { added: string[]; removed: string[] } {
  return delta(
    draft.rules,
    draft.removeRules,
    (preview?.rules ?? []).map((r) => r.key),
  );
}

/** ADR 0055: the automatic rules a step of this template gets anyway (read-only in the editor). */
export function automaticRules(
  preview: ResolvedPreview | null,
): Array<{ key: string; name: string }> {
  return (preview?.rules ?? [])
    .filter((r) => r.automatic)
    .map((r) => ({ key: r.key, name: r.name }));
}

/** Templates that may serve as a parent: anything but the template itself and its descendants. */
export function parentCandidates<T extends { key?: string | null; extendsKey?: string | null }>(
  all: T[],
  selfKey: string | null,
): T[] {
  if (!selfKey) return all;
  const children = new Map<string, string[]>();
  for (const t of all) {
    if (t.extendsKey && t.key) {
      if (!children.has(t.extendsKey)) children.set(t.extendsKey, []);
      children.get(t.extendsKey)!.push(t.key);
    }
  }
  const excluded = new Set<string>([selfKey]);
  const stack = [selfKey];
  while (stack.length) {
    const k = stack.pop()!;
    for (const c of children.get(k) ?? []) {
      if (!excluded.has(c)) {
        excluded.add(c);
        stack.push(c);
      }
    }
  }
  return all.filter((t) => !excluded.has(t.key ?? ''));
}

/** Blank template draft. */
export function emptyTemplate(): TaskTemplateInput {
  return {
    name: '',
    description: '',
    abstract: false,
    extendsKey: 'base-agent',
    category: null,
    agentKey: null,
    scope: { type: 'GLOBAL', projectId: null },
    modelProfileId: null,
    prompt: { system: '', user: '', mode: 'APPEND' },
    skills: [],
    removeSkills: [],
    rules: [],
    removeRules: [],
    config: {},
    outputSchema: null,
    confidenceField: null,
    produces: [],
    consumes: [],
    tags: [],
    runInputs: [],
  };
}

/** Strips server-only fields from a template so it can be sent back as input. */
export function toTemplateInput(t: TaskTemplateInput & Record<string, unknown>): TaskTemplateInput {
  return {
    ...(t.key ? { key: t.key } : {}),
    name: t.name,
    description: t.description ?? '',
    abstract: !!t.abstract,
    extendsKey: t.extendsKey ?? null,
    category: t.category ?? null,
    agentKey: t.agentKey ?? null,
    scope: t.scope ?? { type: 'GLOBAL', projectId: null },
    modelProfileId: t.modelProfileId ?? null,
    prompt: {
      system: t.prompt?.system ?? '',
      user: t.prompt?.user ?? '',
      mode: t.prompt?.mode ?? 'APPEND',
    },
    skills: t.skills ?? [],
    removeSkills: t.removeSkills ?? [],
    rules: t.rules ?? [],
    removeRules: t.removeRules ?? [],
    config: t.config ?? {},
    outputSchema: t.outputSchema ?? null,
    confidenceField: t.confidenceField ?? null,
    produces: t.produces ?? [],
    consumes: t.consumes ?? [],
    tags: t.tags ?? [],
    runInputs: t.runInputs ?? [],
    ...(t.sandboxPolicy && Object.keys(t.sandboxPolicy).length
      ? { sandboxPolicy: t.sandboxPolicy }
      : {}),
  };
}

/**
 * ADR 0055: what the editor sends. The key is derived by the server: never sent on update (it
 * stays) and on create only when given - a project template customising the global one of that key.
 */
export function toSavePayload(draft: TaskTemplateInput, update: boolean): TaskTemplateInput {
  const { key, ...rest } = draft;
  return !update && key ? { key, ...rest } : rest;
}

/**
 * ADR 0055: a new PROJECT template that customises the global template `t` for one project: the
 * same key and name, extending the global one, everything else inherited.
 */
export function customizeForProject(
  t: Pick<TaskTemplateInput, 'key' | 'name'>,
  projectId: string | null,
): TaskTemplateInput {
  return {
    ...emptyTemplate(),
    key: t.key,
    name: t.name,
    extendsKey: t.key ?? null,
    agentKey: null,
    prompt: { system: '', user: '', mode: 'APPEND' },
    scope: { type: 'PROJECT', projectId },
  };
}
