/** Labels of a run's trigger source (pure; tested in run-trigger.spec.ts). */

/** The run's trigger source (MANUAL / API / CI / JIRA / SCHEDULE) as a short word. */
export function triggerLabel(type: string): string {
  const jira = $localize`:@@runs.detail.fact.triggerJira:Jira transition`;
  const labels: Record<string, string> = {
    MANUAL: $localize`:@@runs.detail.fact.triggerManual:manual`,
    CI: 'CI',
    API: 'API',
    JIRA: jira,
    JIRA_TRANSITION: jira,
    SCHEDULE: $localize`:@@runs.detail.fact.triggerSchedule:schedule`,
  };
  return labels[type] ?? type.toLowerCase();
}

/** Who started a run in a list: the person, else the automatic source (CI, schedule), else "-". */
export function runStarter(
  startedByName: string | null,
  triggerType: string | null | undefined,
): string {
  if (startedByName) return startedByName;
  if (triggerType === 'CI' || triggerType === 'SCHEDULE') return triggerLabel(triggerType);
  return '-';
}
