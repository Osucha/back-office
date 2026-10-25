/**
 * ADR 0054: the configuration of a template as a form. The executor of a template is its own or
 * the nearest ancestor's; the form comes from that executor's config schema
 * (`GET /workflows/task-executors`). Pure; tested in template-config.spec.ts.
 */
import type {
  Scope,
  TaskExecutor,
  TaskExecutorDescriptor,
  TaskTemplate,
  TaskTemplateInput,
} from '../../../core/api/types';
import type { JsonSchema } from '../../workflows/public-api';

/**
 * The executor a template runs with: its own, else the first one up the `extendsKey` chain (a
 * parent in the template's project wins over the global one), else `fallback` (the resolved view).
 */
export function effectiveExecutor(
  draft: Pick<TaskTemplateInput, 'executor' | 'extendsKey' | 'scope'>,
  templates: readonly TaskTemplate[],
  fallback: string | null = null,
): TaskExecutor | null {
  if (draft.executor) return draft.executor;
  const seen = new Set<string>();
  let key = draft.extendsKey ?? null;
  while (key && !seen.has(key)) {
    seen.add(key);
    const parent = pickByScope(
      templates.filter((t) => t.key === key),
      draft.scope,
    );
    if (!parent) break;
    if (parent.executor) return parent.executor;
    key = parent.extendsKey ?? null;
  }
  return (fallback as TaskExecutor | null) ?? null;
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
 * The config schema of an executor for the template editor: null when unknown or without keys.
 * `required` is dropped: a template only sets what it changes, the rest comes from its parent.
 */
export function templateConfigSchema(
  descriptors: readonly TaskExecutorDescriptor[] | null | undefined,
  executor: string | null | undefined,
): JsonSchema | null {
  if (!executor) return null;
  const schema = descriptors?.find((d) => d.executor === executor)?.configSchema as
    JsonSchema | undefined;
  if (!schema?.properties || !Object.keys(schema.properties).length) return null;
  const copy: JsonSchema = { ...schema };
  delete copy.required;
  return copy;
}
