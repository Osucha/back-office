/**
 * Names people understand for agents and actions (ADR 0056). An agent is a harness (none = one
 * model answer, OpenCode or Claude Code = a coding agent in the runner sandbox) and optionally a
 * model; an action is a deterministic integration step of a workflow (no model). Action titles
 * come from the backend in English; the UI translates them by type and falls back to the backend
 * text for a type it does not know yet.
 */
import type { ActionType, AgentAccess, AgentAccessMode, AgentHarness } from '../api/types';

/** The kind of an agent: its harness, or `null` for a model answer. */
export type AgentKind = AgentHarness | null;

/** "Model answer", "OpenCode", "Claude Code". */
export function agentKindLabel(harness: string | null | undefined): string {
  switch (harness) {
    case 'OPENCODE':
      return $localize`:@@core.agent.kind.opencode:OpenCode`;
    case 'CLAUDE_CODE':
      return $localize`:@@core.agent.kind.claudeCode:Claude Code`;
    case null:
    case undefined:
    case '':
      return $localize`:@@core.agent.kind.modelAnswer:Model answer`;
    default:
      return humanize(harness);
  }
}

/** One line on how an agent of this kind works. */
export function agentKindDescription(harness: string | null | undefined): string {
  return harness
    ? $localize`:@@core.agent.kind.coding.description:Coding agent in the runner sandbox with the repository.`
    : $localize`:@@core.agent.kind.modelAnswer.description:One model answer, no tools or repository.`;
}

/** ADR 0060: "Full", "Read only", "Only these paths". */
export function agentAccessModeLabel(mode: AgentAccessMode | null | undefined): string {
  switch (mode) {
    case 'READ_ONLY':
      return $localize`:@@core.agent.access.readOnly:Read only`;
    case 'PATHS':
      return $localize`:@@core.agent.access.paths:Only these paths`;
    default:
      return $localize`:@@core.agent.access.full:Full`;
  }
}

/** ADR 0060: what a coding agent may change, short: "Full", "Read only", "Only docs/**, README.md". */
export function agentAccessText(access: AgentAccess | null | undefined): string {
  if (access?.mode === 'PATHS') {
    const paths = (access.paths ?? []).filter((x) => !!x.trim()).join(', ');
    return paths
      ? $localize`:@@core.agent.access.pathsList:Only ${paths}:paths:`
      : agentAccessModeLabel('PATHS');
  }
  return agentAccessModeLabel(access?.mode);
}

/** Whether an agent (or agent reference) is a coding agent: it has a harness. */
export function isCodingAgent(agent: { harness?: string | null } | null | undefined): boolean {
  return !!agent?.harness;
}

/**
 * ADR 0059: who did a task step or a benchmark attempt, "Claude Code · Claude Sonnet" (the agent's
 * name and the model; the name alone without a model, '' without a record).
 */
export function agentModelText(
  a:
    | { agentName?: string | null; agentKey?: string | null; modelLabel?: string | null }
    | null
    | undefined,
): string {
  if (!a) return '';
  const name = a.agentName || a.agentKey || '';
  return [name, a.modelLabel].filter((x) => !!x).join(' · ');
}

const actionTexts = (): Record<ActionType, { title: string; description: string }> => ({
  FETCH_ISSUE: {
    title: $localize`:@@core.action.fetchIssue:Fetch issue`,
    description: $localize`:@@core.action.fetchIssue.description:Reads the Jira issue of the run so later steps can use it.`,
  },
  COMMENT_ISSUE: {
    title: $localize`:@@core.action.commentIssue:Comment on issue`,
    description: $localize`:@@core.action.commentIssue.description:Writes a comment on the Jira issue of the run.`,
  },
  TRANSITION_ISSUE: {
    title: $localize`:@@core.action.transitionIssue:Change issue status`,
    description: $localize`:@@core.action.transitionIssue.description:Moves the Jira issue of the run to another status.`,
  },
  START_WORKFLOW: {
    title: $localize`:@@core.action.startWorkflow:Start workflow`,
    description: $localize`:@@core.action.startWorkflow.description:Starts runs of other workflows.`,
  },
  PROJECT_DIGEST: {
    title: $localize`:@@core.action.projectDigest:Project digest`,
    description: $localize`:@@core.action.projectDigest.description:Collects a summary of the recent activity of the project.`,
  },
});

/** Translated title of an action; the backend's title (or the humanized type) when unknown. */
export function actionTitle(type: string | null | undefined, fallback?: string | null): string {
  if (!type) return fallback ?? '';
  return actionTexts()[type as ActionType]?.title ?? fallback ?? humanize(type);
}

/** Translated one-line description of an action; the backend's text when unknown. */
export function actionDescription(
  type: string | null | undefined,
  fallback?: string | null,
): string {
  if (!type) return fallback ?? '';
  return actionTexts()[type as ActionType]?.description ?? fallback ?? '';
}

/** "SOMETHING_NEW" -> "Something new". */
function humanize(value: string): string {
  return value
    .replace(/_/g, ' ')
    .toLowerCase()
    .replace(/^\w/, (c) => c.toUpperCase());
}
