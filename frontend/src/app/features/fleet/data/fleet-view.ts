/** Pure helpers for the fleet pages: job filters, runner states, the "Add runner" snippets. */
import type {
  AgentJob,
  AgentJobStatus,
  Runner,
  RunnerPool,
  RunnerPoolInput,
} from '../../../core/api/types';
import type { Baton, BatonHolder } from '../../../core/ui/relay/baton';
import { batonOf } from '../../../core/ui/relay/baton';
import { icu } from '../../../core/i18n/icu';

/** Filter-bar holder chips -> the job statuses they stand for (the baton mapping, reversed). */
export const JOB_STATUSES_BY_HOLDER: Partial<Record<BatonHolder, AgentJobStatus[]>> = {
  idle: ['QUEUED', 'LEASED', 'CANCELLED'],
  agent: ['STARTING', 'RUNNING'],
  done: ['SUCCEEDED'],
  blocked: ['FAILED', 'TIMED_OUT', 'LOST'],
};
export const JOB_HOLDERS: BatonHolder[] = ['agent', 'idle', 'done', 'blocked'];

export const JOB_STATUS_LABELS: Record<AgentJobStatus, string> = {
  QUEUED: $localize`:@@fleet.jobStatus.queued:Queued`,
  LEASED: $localize`:@@fleet.jobStatus.leased:Leased`,
  STARTING: $localize`:@@fleet.jobStatus.starting:Starting`,
  RUNNING: $localize`:@@fleet.jobStatus.running:Running`,
  SUCCEEDED: $localize`:@@fleet.jobStatus.succeeded:Succeeded`,
  FAILED: $localize`:@@fleet.jobStatus.failed:Failed`,
  TIMED_OUT: $localize`:@@fleet.jobStatus.timedOut:Timed out`,
  CANCELLED: $localize`:@@fleet.jobStatus.cancelled:Cancelled`,
  LOST: $localize`:@@fleet.jobStatus.lost:Lost`,
};

/** Client-side filter of a page of jobs by holder chips (the API filters one status at a time). */
export function filterJobs(jobs: readonly AgentJob[], holders: readonly string[]): AgentJob[] {
  if (!holders.length) return [...jobs];
  return jobs.filter((j) => holders.includes(batonOf.agentJob(j.status).holder));
}

/** A job can still be cancelled while it has not reached a terminal state. */
export function isJobActive(status: AgentJobStatus | null | undefined): boolean {
  return (
    status === 'QUEUED' || status === 'LEASED' || status === 'STARTING' || status === 'RUNNING'
  );
}

/** What produced the job, for the table: "run 1f32… · implement" / "benchmark 9a1b…". */
export function jobContext(j: AgentJob): { label: string; link: unknown[] | null } {
  if (j.runId) {
    const run = j.runId.slice(0, 8);
    return {
      label: j.nodeId
        ? $localize`:@@fleet.job.context.runNode:run ${run}:run: · ${j.nodeId}:node:`
        : $localize`:@@fleet.job.context.run:run ${run}:run:`,
      link: ['/runs', j.runId],
    };
  }
  if (j.benchmarkId) {
    const benchmark = j.benchmarkId.slice(0, 8);
    return {
      label: $localize`:@@fleet.job.context.benchmark:benchmark ${benchmark}:benchmark:`,
      link: ['/evaluation/benchmarks', j.benchmarkId],
    };
  }
  return {
    label: j.mode ? j.mode.toLowerCase() : $localize`:@@fleet.job.context.job:job`,
    link: null,
  };
}

/** Duration of a job so far (ms), from start (or queue) to finish (or now). */
export function jobDurationMs(j: AgentJob, now = Date.now()): number | null {
  const from = Date.parse(j.startedAt ?? '');
  if (Number.isNaN(from)) return null;
  const to = j.finishedAt ? Date.parse(j.finishedAt) : now;
  return Number.isNaN(to) ? null : Math.max(0, to - from);
}

/** Runner online state as a baton: online and busy -> agent, online idle -> done, offline -> blocked. */
export function runnerBaton(r: Runner): Baton {
  if (!r.online) return { holder: 'blocked', label: $localize`:@@fleet.runner.offline:offline` };
  const online = $localize`:@@fleet.runner.online:online`;
  if ((r.running ?? 0) > 0) {
    const running = r.running ?? 0;
    const max = r.maxConcurrent ?? '?';
    return {
      holder: 'agent',
      label: online,
      detail: $localize`:@@fleet.runner.busy:${running}:running:/${max}:max: busy`,
      live: true,
    };
  }
  return { holder: 'done', label: online, detail: $localize`:@@fleet.runner.idle:idle` };
}

/** Pool health for the pools table. */
export function poolBaton(p: RunnerPool): Baton {
  const online = p.onlineRunners ?? 0;
  if (!(p.runners ?? 0))
    return { holder: 'idle', label: $localize`:@@fleet.pool.health.noRunners:no runners` };
  if (!online)
    return { holder: 'blocked', label: $localize`:@@fleet.pool.health.allOffline:all offline` };
  if ((p.queued ?? 0) > 0 && (p.running ?? 0) >= (p.maxConcurrentJobs ?? Infinity))
    return {
      holder: 'you',
      label: $localize`:@@fleet.pool.health.saturated:saturated`,
      detail: icu(
        $localize`:@@fleet.pool.health.waiting:{count, plural, one {# waiting} other {# waiting}}`,
        { count: p.queued ?? 0 },
      ),
    };
  return { holder: 'done', label: $localize`:@@fleet.pool.health.healthy:healthy` };
}

/** What revoking a runner does, in words (the confirmation dialog). */
export function revokeConsequences(r: Runner): string {
  const running = r.running ?? 0;
  return [
    r.name
      ? $localize`:@@fleet.revoke.tokenStops:The runner token of ${r.name}:name: stops working at once; the controller can no longer lease jobs or report results.`
      : $localize`:@@fleet.revoke.tokenStopsThis:The runner token of this runner stops working at once; the controller can no longer lease jobs or report results.`,
    running
      ? icu(
          $localize`:@@fleet.revoke.leasedJobs:{count, plural, one {# leased job goes} other {# leased jobs go}} back to the queue and will be picked up by another runner of the pool.`,
          { count: running },
        )
      : $localize`:@@fleet.revoke.noLeasedJobs:It has no leased jobs right now.`,
    $localize`:@@fleet.revoke.bringBack:To bring it back, register it again with a new registration token.`,
  ].join(' ');
}

/** Empty pool form. */
export function emptyPool(): RunnerPoolInput {
  return {
    id: '',
    name: '',
    description: null,
    labels: {},
    ceiling: { isolation: 'STANDARD' },
    allowedImages: ['ghcr.io/org/back-office-runner:*'],
    warmPoolSize: 2,
    maxConcurrentJobs: 10,
    projectIds: [],
  };
}

export function toPoolInput(p: RunnerPool): RunnerPoolInput {
  return {
    id: p.id,
    name: p.name,
    description: p.description ?? null,
    labels: p.labels ?? {},
    ceiling: p.ceiling ?? {},
    allowedImages: p.allowedImages ?? [],
    warmPoolSize: p.warmPoolSize ?? 2,
    maxConcurrentJobs: p.maxConcurrentJobs ?? 10,
    projectIds: p.projectIds ?? [],
  };
}

export const POOL_ID_PATTERN = /^[a-z][a-z0-9-]{1,39}$/;

export function poolInputErrors(
  p: RunnerPoolInput,
): Partial<Record<keyof RunnerPoolInput, string>> {
  const out: Partial<Record<keyof RunnerPoolInput, string>> = {};
  if (!POOL_ID_PATTERN.test(p.id))
    out.id = $localize`:@@fleet.pool.error.id:2 to 40 characters: lower-case letters, digits and hyphens, starting with a letter.`;
  if (!p.name?.trim()) out.name = $localize`:@@fleet.pool.error.name:Name the pool.`;
  if ((p.warmPoolSize ?? 0) < 0)
    out.warmPoolSize = $localize`:@@fleet.pool.error.warmNegative:Zero or more.`;
  if ((p.maxConcurrentJobs ?? 0) < 1)
    out.maxConcurrentJobs = $localize`:@@fleet.pool.error.maxConcurrent:At least one job.`;
  if ((p.warmPoolSize ?? 0) > (p.maxConcurrentJobs ?? 0))
    out.warmPoolSize = $localize`:@@fleet.pool.error.warmAboveMax:Keep the warm pool at or below the concurrent jobs.`;
  return out;
}

// ---------------------------------------------------------------- "Add runner"

export const RUNNER_CHART = 'back-office-runner';

/** Release, namespace and values file name for a pool (`bo-runner-<pool>`). */
export function runnerRelease(poolId: string) {
  return {
    release: `bo-runner-${poolId}`,
    namespace: `bo-runner-${poolId}`,
    valuesFile: `runner-${poolId}.values.yaml`,
  };
}

/** The `helm install` command for the runner chart (values from the token dialog). */
export function helmInstallCommand(
  poolId: string,
  chartRef = `deploy/helm/${RUNNER_CHART}`,
): string {
  const r = runnerRelease(poolId);
  return [
    `helm upgrade --install ${r.release} ${chartRef} \\`,
    `  --namespace ${r.namespace} --create-namespace \\`,
    `  -f ${r.valuesFile}`,
  ].join('\n');
}

/**
 * Values for the runner chart (`deploy/helm/back-office-runner/values.yaml`) when the API does not
 * return `helmValues`: the public Back Office URL (the chart appends `/api/v1` itself), the pool id
 * and the single-use registration token (the controller exchanges it for a runner token on start).
 * Keys match the chart exactly: `backOffice.url`, `backOffice.poolId`, `registration.token`,
 * top-level `backend` and `runtimeClasses`.
 */
export function fallbackHelmValues(publicUrl: string, poolId: string, token: string): string {
  return [
    'backOffice:',
    `  url: ${publicUrl.replace(/\/+$/, '')}`,
    `  poolId: ${poolId}`,
    'registration:',
    '  # single use, expires in 24 h; the controller exchanges it for a runner token',
    `  token: ${token}`,
    '# auto = AGENT_SANDBOX when the Agent Sandbox CRDs exist, else K8S_JOB',
    'backend: auto',
    'runtimeClasses:',
    '  standard: gvisor',
    '  high: kata',
  ].join('\n');
}

/** "in 23 h" / "in 12 min" for the token expiry. */
export function expiresIn(iso: string | null | undefined, now = Date.now()): string {
  const t = Date.parse(iso ?? '');
  if (Number.isNaN(t)) return $localize`:@@fleet.token.expiresWithin:within 24 h`;
  const mins = Math.max(0, Math.round((t - now) / 60_000));
  if (mins < 90) return $localize`:@@fleet.token.expiresInMin:in ${mins}:minutes: min`;
  const hours = Math.round(mins / 60);
  return $localize`:@@fleet.token.expiresInHours:in ${hours}:hours: h`;
}
