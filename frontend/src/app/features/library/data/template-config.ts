/**
 * ADR 0054/0056: the configuration of a template as a form. The agent of a template is its own or
 * the nearest ancestor's (none anywhere = `model-answer`); the form comes from the config schema of
 * a model answer or of a coding agent (`GET /workflows/task-config-schemas`). Pure; tested in
 * template-config.spec.ts.
 */
import type {
  Agent,
  AgentRef,
  Scope,
  TaskConfigSchemas,
  TaskTemplate,
  TaskTemplateInput,
} from '../../../core/api/types';
import type { JsonSchema } from '../../workflows/public-api';

/** ADR 0056: the agent of a step when neither the step nor any template names one. */
export const DEFAULT_AGENT_KEY = 'model-answer';

/**
 * The agent key a template runs with: its own, else the first one up the `extendsKey` chain (a
 * parent in the template's project wins over the global one), else `fallback`.
 */
export function effectiveAgentKey(
  draft: Pick<TaskTemplateInput, 'agentKey' | 'extendsKey' | 'scope'>,
  templates: readonly TaskTemplate[],
  fallback: string | null = null,
): string | null {
  if (draft.agentKey) return draft.agentKey;
  const seen = new Set<string>();
  let key = draft.extendsKey ?? null;
  while (key && !seen.has(key)) {
    seen.add(key);
    const parent = pickByScope(
      templates.filter((t) => t.key === key),
      draft.scope,
    );
    if (!parent) break;
    if (parent.agentKey) return parent.agentKey;
    key = parent.extendsKey ?? null;
  }
  return fallback;
}

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

/** The agents a template (or a step) of this scope may name: global ones plus the project's. */
export function agentsForScope(agents: readonly Agent[], scope?: Scope | null): Agent[] {
  const projectId = scope?.type === 'PROJECT' ? scope.projectId : null;
  return agents.filter(
    (a) => a.scope?.type !== 'PROJECT' || (!!projectId && a.scope.projectId === projectId),
  );
}

/** The short reference of an agent (as `ResolvedTask.agent` and `PaletteTask.agent` carry it). */
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
 * The config schema for the template editor: the coding agent's schema for a coding agent, the
 * model answer's otherwise; null when unknown or without keys. `required` is dropped: a template
 * only sets what it changes, the rest comes from its parent.
 */
export function templateConfigSchema(
  schemas: TaskConfigSchemas | null | undefined,
  codingAgent: boolean,
): JsonSchema | null {
  const schema = (codingAgent ? schemas?.codingAgent : schemas?.modelAnswer) as
    JsonSchema | undefined;
  if (!schema?.properties || !Object.keys(schema.properties).length) return null;
  const copy: JsonSchema = { ...schema };
  delete copy.required;
  return copy;
}
