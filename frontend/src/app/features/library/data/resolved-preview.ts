/**
 * Pure mapping of a `ResolvedTask` into what the template editor preview shows: lineage
 * breadcrumb, effective prompts, skills, interface check and diff against the draft.
 */
import type { ResolvedTask, TaskTemplateInput } from '../../../core/api/types';

export interface ResolvedPreview {
  lineage: string[];
  executor: string | null;
  modelProfileId: string | null;
  systemPrompt: string;
  userPrompt: string;
  skills: Array<{ key: string; name: string }>;
  interfaces: string[];
  ports: string[];
  produces: string[];
  consumes: string[];
  config: Record<string, unknown>;
  abstract: boolean;
  ok: boolean;
  problems: string[];
}

export function toPreview(r: ResolvedTask | null | undefined): ResolvedPreview | null {
  if (!r) return null;
  return {
    lineage: r.lineage?.length ? r.lineage : r.key ? [r.key] : [],
    executor: r.executor ?? null,
    modelProfileId: r.modelProfileId ?? null,
    systemPrompt: r.prompt?.system ?? '',
    userPrompt: r.prompt?.user ?? '',
    skills: (r.skills ?? []).map((s) => ({ key: s.key ?? '', name: s.name ?? s.key ?? '' })),
    interfaces: r.interfaces ?? [],
    ports: r.ports ?? [],
    produces: r.produces ?? [],
    consumes: r.consumes ?? [],
    config: r.config ?? {},
    abstract: !!r.abstract,
    ok: r.interfaceCheck?.ok !== false,
    problems: r.interfaceCheck?.problems ?? [],
  };
}

/** Skills that the draft adds compared to the resolved result (not yet saved / from a parent). */
export function draftSkillDelta(
  draft: TaskTemplateInput,
  preview: ResolvedPreview | null,
): { added: string[]; removed: string[] } {
  const resolved = new Set((preview?.skills ?? []).map((s) => s.key));
  const added = (draft.skills ?? []).filter((k) => !resolved.has(k));
  const removed = (draft.removeSkills ?? []).filter((k) => resolved.has(k));
  return { added, removed };
}

/** Templates that may serve as a parent: anything but the template itself and its descendants. */
export function parentCandidates<T extends { key: string; extendsKey?: string | null }>(
  all: T[],
  selfKey: string | null,
): T[] {
  if (!selfKey) return all;
  const children = new Map<string, string[]>();
  for (const t of all) {
    if (t.extendsKey) {
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
  return all.filter((t) => !excluded.has(t.key));
}

/** Blank template draft. */
export function emptyTemplate(): TaskTemplateInput {
  return {
    key: '',
    name: '',
    description: '',
    abstract: false,
    extendsKey: 'base-agent',
    implements: [],
    executor: 'LLM',
    scope: { type: 'GLOBAL', projectId: null },
    modelProfileId: null,
    prompt: { system: '', user: '', mode: 'APPEND' },
    skills: [],
    removeSkills: [],
    config: {},
    outputSchema: null,
    confidenceField: null,
    produces: [],
    consumes: [],
    tags: [],
  };
}

/** Strips server-only fields from a template so it can be sent back as input. */
export function toTemplateInput(t: TaskTemplateInput & Record<string, unknown>): TaskTemplateInput {
  return {
    key: t.key,
    name: t.name,
    description: t.description ?? '',
    abstract: !!t.abstract,
    extendsKey: t.extendsKey ?? null,
    implements: t.implements ?? [],
    executor: t.executor ?? null,
    scope: t.scope ?? { type: 'GLOBAL', projectId: null },
    modelProfileId: t.modelProfileId ?? null,
    prompt: {
      system: t.prompt?.system ?? '',
      user: t.prompt?.user ?? '',
      mode: t.prompt?.mode ?? 'APPEND',
    },
    skills: t.skills ?? [],
    removeSkills: t.removeSkills ?? [],
    config: t.config ?? {},
    outputSchema: t.outputSchema ?? null,
    confidenceField: t.confidenceField ?? null,
    produces: t.produces ?? [],
    consumes: t.consumes ?? [],
    tags: t.tags ?? [],
    ...(t.sandboxPolicy && Object.keys(t.sandboxPolicy).length
      ? { sandboxPolicy: t.sandboxPolicy }
      : {}),
  };
}
