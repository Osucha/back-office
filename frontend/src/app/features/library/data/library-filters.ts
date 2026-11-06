/** Client-side filters and labels of the Library lists (pure; tested in library-filters.spec.ts). */
import type { Agent, Rule, Skill, TaskTemplate } from '../../../core/api/types';
import { icu } from '../../../core/i18n/icu';
import { fixed } from '../../../core/i18n/numbers';
import { agentKindLabel } from '../../../core/ui/agents';
import { categoryLabel } from '../../../core/ui/template-categories';
import { isDeprecated } from '../../../core/ui/template-tags';
import { DEFAULT_AGENT_KEY, agentByKey, effectiveAgentKey } from './template-config';

/**
 * ADR 0056: the agent a template runs with (own or inherited, else `model-answer`) as the list
 * shows it; null when the agent is not among the loaded ones.
 */
export function templateAgent(
  t: TaskTemplate,
  all: readonly TaskTemplate[],
  agents: readonly Agent[],
) {
  const key = effectiveAgentKey(t, all, DEFAULT_AGENT_KEY) ?? DEFAULT_AGENT_KEY;
  return { key, agent: agentByKey(agents, key, t.scope) };
}

function matches(q: string, ...fields: (string | null | undefined | readonly string[])[]): boolean {
  return fields.some((f) =>
    Array.isArray(f)
      ? f.some((x) => x.toLowerCase().includes(q))
      : typeof f === 'string' && f.toLowerCase().includes(q),
  );
}

/**
 * ADR 0055: the category of a template - its own, else the nearest ancestor's (a parent in the
 * template's project wins over the global one), else GENERAL.
 */
export function effectiveCategory(
  t: Pick<TaskTemplate, 'category' | 'extendsKey' | 'scope' | 'key'>,
  all: readonly TaskTemplate[],
): string {
  if (t.category) return t.category;
  const projectId = t.scope?.type === 'PROJECT' ? t.scope.projectId : null;
  const seen = new Set<string>(t.key ? [t.key] : []);
  let key = t.extendsKey ?? null;
  while (key && !seen.has(key)) {
    seen.add(key);
    const candidates = all.filter((x) => x.key === key);
    const parent =
      (projectId ? candidates.find((x) => x.scope?.projectId === projectId) : undefined) ??
      candidates.find((x) => x.scope?.type !== 'PROJECT') ??
      candidates[0];
    if (!parent) break;
    if (parent.category) return parent.category;
    key = parent.extendsKey ?? null;
  }
  return 'GENERAL';
}

/**
 * Templates matching the search (name, key, description, category, tag, agent name and kind),
 * agent and category (ADR 0055/0056, effective ones); deprecated templates (ADR 0054) only with
 * `showDeprecated`.
 */
export function filterTemplates(
  list: readonly TaskTemplate[],
  opts: {
    query?: string | null;
    /** ADR 0056: key of the (effective) agent. */
    agent?: string | null;
    category?: string | null;
    showDeprecated?: boolean | null;
    /** The loaded agents, for the search by agent name and kind. */
    agents?: readonly Agent[];
  },
): TaskTemplate[] {
  const q = (opts.query ?? '').trim().toLowerCase();
  return list.filter((t) => {
    const category = effectiveCategory(t, list);
    const { key: agentKey, agent } = templateAgent(t, list, opts.agents ?? []);
    return (
      (opts.showDeprecated || !isDeprecated(t)) &&
      (!opts.agent || agentKey === opts.agent) &&
      (!opts.category || category === opts.category) &&
      (!q ||
        matches(
          q,
          t.key,
          t.name,
          t.description,
          categoryLabel(category),
          t.tags ?? [],
          agent?.name,
          agent ? agentKindLabel(agent.harness) : null,
        ))
    );
  });
}

/** ADR 0056: agents matching the search (name, key, description, tag, kind). */
export function filterAgents(list: readonly Agent[], query: string | null | undefined): Agent[] {
  const q = (query ?? '').trim().toLowerCase();
  if (!q) return [...list];
  return list.filter((a) =>
    matches(q, a.key, a.name, a.description, a.tags ?? [], agentKindLabel(a.harness)),
  );
}

/** The display name of a library item referred to by key (falls back to the key). */
export function nameOfKey(
  list: readonly { key?: string | null; name?: string | null }[],
  key: string | null | undefined,
): string {
  if (!key) return '';
  return list.find((x) => x.key === key)?.name || key;
}

/** Skills matching the search (key, name, description, tag). */
export function filterSkills(list: readonly Skill[], query: string | null | undefined): Skill[] {
  const q = (query ?? '').trim().toLowerCase();
  if (!q) return [...list];
  return list.filter((s) => matches(q, s.key, s.name, s.description, s.tags ?? []));
}

/** Rules matching the search (name, key, description, tag) and, with `automatic`, only those. */
export function filterRules(
  list: readonly Rule[],
  opts: { query?: string | null; automatic?: boolean | null },
): Rule[] {
  const q = (opts.query ?? '').trim().toLowerCase();
  return list.filter(
    (r) =>
      (!opts.automatic || r.automatic) &&
      (!q || matches(q, r.key, r.name, r.description, r.tags ?? [])),
  );
}

/** "2.4k chars" for a skill body. */
export function sizeLabel(chars: number): string {
  if (chars < 1000)
    return icu($localize`:@@library.size.chars:{count, plural, other {# chars}}`, { count: chars });
  const value = fixed(chars / 1000, chars < 10000 ? 1 : 0);
  return $localize`:@@library.size.kChars:${value}:value:k chars`;
}
