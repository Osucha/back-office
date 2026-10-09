/** Pure helpers of My work (ADR 0038): rule form defaults and validation, rule status, workflow choices. */
import type {
  IntakeRule,
  IntakeRuleInput,
  Project,
  WorkflowDefinition,
} from '../../../core/api/types';

/** Issues assigned to the person whose connection reads, not done yet. */
export const DEFAULT_JQL =
  'assignee = currentUser() AND statusCategory != Done ORDER BY updated DESC'; // i18n-ignore (JQL)

export function emptyRule(): IntakeRuleInput {
  return {
    name: $localize`:@@my-work.rule.defaultName:My Jira issues`,
    connectionId: '',
    jql: DEFAULT_JQL,
    pollIntervalMinutes: 5,
    workflowId: '',
    enabled: true,
    includeExisting: true,
  };
}

export function ruleInput(r: IntakeRule): IntakeRuleInput {
  return {
    name: r.name,
    connectionId: r.connectionId,
    jql: r.jql,
    pollIntervalMinutes: r.pollIntervalMinutes ?? 5,
    workflowId: r.workflowId,
    enabled: r.enabled ?? true,
    includeExisting: r.includeExisting ?? true,
  };
}

/** The first problem of a rule form, or null when it can be saved. */
export function ruleError(r: IntakeRuleInput): string | null {
  if (!r.name?.trim()) return $localize`:@@my-work.rule.error.name:Give the rule a name`;
  if (!r.connectionId)
    return $localize`:@@my-work.rule.error.connection:Choose one of your Jira connections`;
  if (!r.jql?.trim())
    return $localize`:@@my-work.rule.error.jql:Write the JQL that finds your issues`;
  const n = r.pollIntervalMinutes ?? 5;
  if (!Number.isInteger(n) || n < 1 || n > 1440)
    return $localize`:@@my-work.rule.error.interval:Check every 1 to 1440 minutes`;
  if (!r.workflowId) return $localize`:@@my-work.rule.error.workflow:Choose the workflow to propose`;
  return null;
}

export type RuleTone = 'ok' | 'error' | 'off' | 'pending';

/** One line about where a rule stands. */
export function ruleStatus(r: IntakeRule): { tone: RuleTone; text: string } {
  if (!r.enabled) return { tone: 'off', text: $localize`:@@my-work.rule.status.off:switched off` };
  if (r.lastError) {
    return {
      tone: 'error',
      text: $localize`:@@my-work.rule.status.error:last check failed (${r.consecutiveFailures ?? 1}:count:×)`,
    };
  }
  if (!r.initialized)
    return { tone: 'pending', text: $localize`:@@my-work.rule.status.pending:first check pending` };
  return {
    tone: 'ok',
    text: $localize`:@@my-work.rule.status.ok:checks every ${r.pollIntervalMinutes ?? 5}:minutes: min`,
  };
}

export interface WorkflowChoice {
  id: string;
  label: string;
  projectKey: string;
  personal: boolean;
}

/** Enabled workflows of the given projects, private projects first, as "KEY · name". */
export function workflowChoices(
  projects: Project[],
  workflowsByProject: Map<string, WorkflowDefinition[]>,
): WorkflowChoice[] {
  return [...projects]
    .sort((a, b) => Number(!!b.personal) - Number(!!a.personal) || a.key.localeCompare(b.key))
    .flatMap((p) =>
      (workflowsByProject.get(p.id) ?? [])
        .filter((w) => w.enabled !== false)
        .sort((a, b) => a.name.localeCompare(b.name))
        .map((w) => ({
          id: w.id,
          label: `${p.key} · ${w.name}`,
          projectKey: p.key,
          personal: !!p.personal,
        })),
    );
}
