/**
 * ADR 0054/0061: the configuration of a template as a form, and the kind of worker the template
 * needs. A template no longer names an agent (the step does); it needs a coding agent when its
 * merged config sets `codingAgent` (or the legacy `prepareOnly`), else any agent. The form shows
 * the keys of model answer and coding agent steps together (`GET /workflows/task-config-schemas`).
 * Pure; tested in template-config.spec.ts.
 */
import type {
  Agent,
  AgentRef,
  Scope,
  TaskConfigSchemas,
  TaskTemplate,
  TaskTemplateInput,
} from '../../../core/api/types';
import { configNeedsCodingAgent } from '../../../core/ui/agents';
import { type JsonSchema, templateFormSchema } from '../../workflows/public-api';

/**
 * The agent with this key as a step of the given scope sees it: the project's own first, else
 * the global one.
 */
export function agentByKey(
  agents: readonly Agent[],
  key: string | null | undefined,
  scope?: Scope | null,
): Agent | null {
  if (!key) return null;
  const projectId = scope?.type === 'PROJECT' ? scope.projectId : null;
  const candidates = agents.filter((a) => a.key === key);
  return (
    (projectId ? candidates.find((a) => a.scope?.projectId === projectId) : undefined) ??
    candidates.find((a) => a.scope?.type !== 'PROJECT') ??
    null
  );
}

/** The agents a step of this scope may name: global ones plus the project's. */
export function agentsForScope(agents: readonly Agent[], scope?: Scope | null): Agent[] {
  const projectId = scope?.type === 'PROJECT' ? scope.projectId : null;
  return agents.filter(
    (a) => a.scope?.type !== 'PROJECT' || (!!projectId && a.scope.projectId === projectId),
  );
}

/** The short reference of an agent (as `ResolvedTask.agent` carries it). */
export function agentRef(a: Agent): AgentRef {
  return {
    key: a.key ?? '',
    name: a.name,
    harness: a.harness ?? null,
    modelProfileId: a.modelProfileId ?? null,
  };
}

function pickByScope(candidates: TaskTemplate[], scope: Scope | undefined): TaskTemplate | null {
  if (!candidates.length) return null;
  const projectId = scope?.type === 'PROJECT' ? scope.projectId : null;
  return (
    (projectId ? candidates.find((t) => t.scope?.projectId === projectId) : undefined) ??
    candidates.find((t) => t.scope?.type !== 'PROJECT') ??
    candidates[0]
  );
}

/**
 * The top-level config keys of a template after inheritance: the ancestors' configs (root first,
 * a parent in the template's project before the global one), then the template's own; a `null`
 * value deletes the key. Nested objects are not merged (the coding keys are flat).
 */
export function lineageConfig(
  draft: Pick<TaskTemplateInput, 'config' | 'extendsKey' | 'scope' | 'key'>,
  templates: readonly TaskTemplate[],
): Record<string, unknown> {
  const chain: Array<Record<string, unknown> | null | undefined> = [draft.config];
  // a project template customising the global one of its key extends that global one
  const seen = new Set<string>();
  let key = draft.extendsKey ?? null;
  let scope: Scope | undefined = draft.scope;
  while (key && !seen.has(key)) {
    seen.add(key);
    const candidates = templates.filter(
      (t) => t.key === key && !(key === draft.key && t.scope?.type === 'PROJECT'),
    );
    const parent = pickByScope(candidates, scope);
    if (!parent) break;
    chain.push(parent.config);
    key = parent.extendsKey ?? null;
    scope = parent.scope;
  }
  const merged: Record<string, unknown> = {};
  for (const config of chain.reverse())
    for (const [k, v] of Object.entries(config ?? {})) {
      if (v === null) delete merged[k];
      else merged[k] = v;
    }
  return merged;
}

/** ADR 0061: the template needs a coding agent (its merged config), else any agent does. */
export function templateNeedsCodingAgent(
  draft: Pick<TaskTemplateInput, 'config' | 'extendsKey' | 'scope' | 'key'>,
  templates: readonly TaskTemplate[],
): boolean {
  return configNeedsCodingAgent(lineageConfig(draft, templates));
}

/**
 * The config schema for the template editor: the keys of model answer steps and the coding agent keys a template
 * still sets (ADR 0063: verifyCommand, agentTools, the questions and codingAgent as a checkbox), translated; a
 * template does not know who runs it. Null when unknown or without keys. `required` is dropped: a template only
 * sets what it changes, the rest comes from its parent.
 */
export function templateConfigSchema(
  schemas: TaskConfigSchemas | null | undefined,
): JsonSchema | null {
  return templateFormSchema(
    schemas?.modelAnswer as JsonSchema | undefined,
    schemas?.codingAgent as JsonSchema | undefined,
  );
}

/** ADR 0063: the output file globs of a template, trimmed, without blanks and duplicates. */
export function cleanGlobs(values: readonly unknown[] | null | undefined): string[] {
  const out: string[] = [];
  for (const v of values ?? []) {
    const g = String(v ?? '').trim();
    if (g && !out.includes(g)) out.push(g);
  }
  return out;
}
