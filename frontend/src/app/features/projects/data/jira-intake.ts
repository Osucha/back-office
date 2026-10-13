/**
 * Jira intake watcher of a project (ADR 0033), pure helpers for the project settings panel: which
 * workflows it can start (those with a JIRA_TRANSITION trigger), what it reacts to, and the state of
 * the read connection it uses.
 */
import type { JiraIntake, WorkflowDefinition } from '../../../core/api/types';

export interface IntakeWorkflowOption {
  id: string;
  name: string;
  /** The status of the JIRA_TRANSITION trigger; null = new issues; undefined = no Jira trigger. */
  status: string | null | undefined;
  /** The workflow can be started by the watcher (has a JIRA_TRANSITION trigger and is enabled). */
  usable: boolean;
}

export function jiraTriggerOf(
  w: Pick<WorkflowDefinition, 'triggers'>,
): { status: string | null } | null {
  const t = (w.triggers ?? []).find((x) => x.type === 'JIRA_TRANSITION');
  return t ? { status: t.status?.trim() || null } : null;
}

/** Workflows of the project for the "Workflow to start" select: usable ones first, then by name. */
export function intakeWorkflowOptions(
  workflows: readonly WorkflowDefinition[],
): IntakeWorkflowOption[] {
  return workflows
    .filter((w) => !!w.id)
    .map((w) => {
      const trigger = jiraTriggerOf(w);
      return {
        id: w.id!,
        name: w.name ?? w.id!,
        status: trigger ? trigger.status : undefined,
        usable: !!trigger && w.enabled !== false && !w.draft,
      };
    })
    .sort((a, b) => Number(b.usable) - Number(a.usable) || a.name.localeCompare(b.name));
}

export type ReaderState = 'ok' | 'overPrivileged' | 'none';

/** The ISSUE_READ connection the watcher would use: ok, over-privileged (warning) or none (refused). */
export function readerState(
  intake: Pick<JiraIntake, 'connection'> | null | undefined,
): ReaderState {
  switch (intake?.connection?.status) {
    case 'OK':
      return 'ok';
    case 'OVER_PRIVILEGED':
      return 'overPrivileged';
    default:
      return 'none';
  }
}

export interface IntakeDraft {
  enabled: boolean;
  pollIntervalMinutes: number;
  workflowId: string | null;
}

export function draftOf(intake: JiraIntake | null | undefined): IntakeDraft {
  return {
    enabled: !!intake?.enabled,
    pollIntervalMinutes: intake?.pollIntervalMinutes ?? 5,
    workflowId: intake?.workflowId ?? null,
  };
}

/** Client-side checks mirroring the server (it decides): a workflow when enabled, 1..1440 minutes. */
export function intakeDraftProblems(
  d: IntakeDraft,
  options: readonly IntakeWorkflowOption[],
): string[] {
  const problems: string[] = [];
  if (
    !Number.isInteger(d.pollIntervalMinutes) ||
    d.pollIntervalMinutes < 1 ||
    d.pollIntervalMinutes > 1440
  )
    problems.push(
      $localize`:@@projects.intake.error.interval:The poll interval is 1 to 1440 minutes.`,
    );
  if (d.enabled) {
    const w = options.find((o) => o.id === d.workflowId);
    if (!w)
      problems.push($localize`:@@projects.intake.error.workflow:Choose the workflow to start.`);
    else if (!w.usable)
      problems.push(
        $localize`:@@projects.intake.error.trigger:This workflow has no Jira trigger (or is disabled). Add a JIRA_TRANSITION trigger in the designer.`,
      );
  }
  return problems;
}

export function sameDraft(a: IntakeDraft, b: IntakeDraft): boolean {
  return (
    a.enabled === b.enabled &&
    a.pollIntervalMinutes === b.pollIntervalMinutes &&
    a.workflowId === b.workflowId
  );
}
