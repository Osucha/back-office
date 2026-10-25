/**
 * Names people understand for task executors (ADR 0054). The enum value (LLM, IMPLEMENT, ...) stays
 * an implementation detail: lists, editors and the designer show the label, the description goes
 * into tooltips and help texts, the raw value only as a small mono hint.
 */
import type { TaskExecutor } from '../api/types';

export interface ExecutorInfo {
  executor: TaskExecutor;
  label: string;
  description: string;
}

const infos = (): Record<TaskExecutor, Omit<ExecutorInfo, 'executor'>> => ({
  LLM: {
    label: $localize`:@@core.executor.llm:Model answer`,
    description: $localize`:@@core.executor.llm.description:One call to a language model; no repository, no tools.`,
  },
  IMPLEMENT: {
    label: $localize`:@@core.executor.implement:Coding agent`,
    description: $localize`:@@core.executor.implement.description:Claude Code or OpenCode works in a sandbox with the repository; it may push a branch and open a merge request.`,
  },
  SPEC_KIT: {
    label: $localize`:@@core.executor.specKit:Spec Kit step`,
    description: $localize`:@@core.executor.specKit.description:One Spec Kit phase (specify, clarify, plan, tasks, ...) run by a coding agent in a sandbox with the repository.`,
  },
  FETCH_ISSUE: {
    label: $localize`:@@core.executor.fetchIssue:Jira: read issue`,
    description: $localize`:@@core.executor.fetchIssue.description:Reads the Jira issue of the run so later steps can use it.`,
  },
  NOTIFY: {
    label: $localize`:@@core.executor.notify:Jira: comment`,
    description: $localize`:@@core.executor.notify.description:Writes a comment on the Jira issue of the run.`,
  },
  TRANSITION_ISSUE: {
    label: $localize`:@@core.executor.transitionIssue:Jira: change status`,
    description: $localize`:@@core.executor.transitionIssue.description:Moves the Jira issue of the run to another status.`,
  },
  START_WORKFLOW: {
    label: $localize`:@@core.executor.startWorkflow:Start workflows`,
    description: $localize`:@@core.executor.startWorkflow.description:Starts runs of other workflows.`,
  },
  PROJECT_DIGEST: {
    label: $localize`:@@core.executor.projectDigest:Project digest`,
    description: $localize`:@@core.executor.projectDigest.description:Collects a summary of the recent activity of the project.`,
  },
});

/** Fallback for an executor the UI does not know yet: "SOMETHING_NEW" -> "Something new". */
function humanize(executor: string): string {
  return executor
    .replace(/_/g, ' ')
    .toLowerCase()
    .replace(/^\w/, (c) => c.toUpperCase());
}

/** "Model answer", "Coding agent", ...; "inherited" for none. */
export function executorLabel(executor: string | null | undefined): string {
  if (!executor) return $localize`:@@core.executor.inherited:inherited`;
  return infos()[executor as TaskExecutor]?.label ?? humanize(executor);
}

/** One sentence on what the executor does; empty for none or an unknown executor. */
export function executorDescription(executor: string | null | undefined): string {
  if (!executor) return '';
  return infos()[executor as TaskExecutor]?.description ?? '';
}

/** Label and description of every executor, in the given order. */
export function executorInfos(executors: readonly TaskExecutor[]): ExecutorInfo[] {
  const all = infos();
  return executors.map((executor) => ({ executor, ...all[executor] }));
}
