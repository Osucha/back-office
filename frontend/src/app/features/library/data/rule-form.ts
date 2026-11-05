/**
 * ADR 0055: the rule editor as pure functions (tested in rule-form.spec.ts). A rule is always in
 * the agent's context; an automatic one applies to every step of its scope without being listed.
 */
import type { Rule, RuleInput } from '../../../core/api/types';

const STARTER = `- Concrete rule one.
- Concrete rule two.
`;

/** Blank rule draft (no key: the server derives it from the name). */
export function emptyRule(): RuleInput {
  return {
    name: '',
    description: '',
    content: STARTER,
    scope: { type: 'GLOBAL', projectId: null },
    automatic: false,
    tags: [],
  };
}

/** The editable fields of a saved rule (the key is kept by the server, never sent). */
export function ruleDraft(r: Rule): RuleInput {
  return {
    name: r.name,
    description: r.description ?? '',
    content: r.content,
    scope: { type: r.scope?.type ?? 'GLOBAL', projectId: r.scope?.projectId ?? null },
    automatic: !!r.automatic,
    tags: [...(r.tags ?? [])],
  };
}

/** Whether the draft may be saved: name, content and, for a project rule, its project. */
export function ruleSavable(d: RuleInput): boolean {
  return (
    !!d.name.trim() && !!d.content.trim() && (d.scope.type !== 'PROJECT' || !!d.scope.projectId)
  );
}

/** One line under the automatic switch: where the rule applies with the current scope. */
export function automaticHint(
  d: Pick<RuleInput, 'automatic' | 'scope'>,
  projectKey: string,
): string {
  if (!d.automatic)
    return $localize`:@@library.rules.automatic.off:Applies only to the templates that list it.`;
  return d.scope.type === 'PROJECT'
    ? $localize`:@@library.rules.automatic.project:Applies to every step of project ${projectKey}:project:, listed or not.`
    : $localize`:@@library.rules.automatic.global:Applies to every step of every project, listed or not.`;
}
