import type { AgentJob, Runner, RunnerPool } from '../../../core/api/types';
import { AGENT_JOB_STATUSES } from '../../../core/api/types';
import { batonOf } from '../../../core/ui/relay/baton';
import {
  JOB_STATUSES_BY_HOLDER,
  emptyPool,
  expiresIn,
  fallbackHelmValues,
  filterJobs,
  helmInstallCommand,
  isJobActive,
  jobContext,
  jobDurationMs,
  poolBaton,
  poolInputErrors,
  revokeConsequences,
  runnerBaton,
} from './fleet-view';

describe('fleet view', () => {
  it('the holder chips cover every job status exactly as the baton maps it', () => {
    const covered = Object.entries(JOB_STATUSES_BY_HOLDER).flatMap(([holder, statuses]) =>
      statuses!.map((s) => [s, holder]),
    );
    expect(covered.map(([s]) => s).sort()).toEqual([...AGENT_JOB_STATUSES].sort());
    for (const [s, holder] of covered)
      expect(batonOf.agentJob(s as AgentJob['status']).holder).toBe(holder);
  });

  it('filters jobs by holder', () => {
    const jobs: AgentJob[] = [
      { id: 'a', status: 'RUNNING' },
      { id: 'b', status: 'LEASED' },
      { id: 'c', status: 'LOST' },
    ];
    expect(filterJobs(jobs, ['agent']).map((j) => j.id)).toEqual(['a']);
    expect(filterJobs(jobs, ['idle', 'blocked']).map((j) => j.id)).toEqual(['b', 'c']);
    expect(filterJobs(jobs, [])).toHaveLength(3);
  });

  it('knows which jobs can be cancelled', () => {
    expect(AGENT_JOB_STATUSES.filter(isJobActive)).toEqual([
      'QUEUED',
      'LEASED',
      'STARTING',
      'RUNNING',
    ]);
  });

  it('links a job to its run or benchmark', () => {
    expect(jobContext({ runId: '1f32aa00-0000', nodeId: 'implement' })).toEqual({
      label: 'run 1f32aa00 · implement',
      link: ['/runs', '1f32aa00-0000'],
    });
    expect(jobContext({ benchmarkId: '9a1bcc00-1111' }).link).toEqual([
      '/evaluation/benchmarks',
      '9a1bcc00-1111',
    ]);
    expect(jobContext({ mode: 'IMPLEMENT' })).toEqual({ label: 'implement', link: null });
  });

  it('measures the job duration', () => {
    const now = Date.parse('2026-09-28T10:00:00Z');
    expect(jobDurationMs({ startedAt: '2026-09-28T09:58:00Z' }, now)).toBe(120_000);
    expect(
      jobDurationMs({ startedAt: '2026-09-28T09:00:00Z', finishedAt: '2026-09-28T09:00:30Z' }, now),
    ).toBe(30_000);
    expect(jobDurationMs({}, now)).toBeNull();
  });

  it('maps runner and pool states', () => {
    const r: Runner = { name: 'eu-1', online: true, running: 2, maxConcurrent: 4 };
    expect(runnerBaton(r)).toEqual({
      holder: 'agent',
      label: 'online',
      detail: '2/4 busy',
      live: true,
    });
    expect(runnerBaton({ ...r, running: 0 }).holder).toBe('done');
    expect(runnerBaton({ ...r, online: false }).holder).toBe('blocked');
    const p: RunnerPool = {
      id: 'eu',
      name: 'EU',
      runners: 2,
      onlineRunners: 2,
      queued: 0,
      warmPoolSize: 2,
      maxConcurrentJobs: 10,
    };
    expect(poolBaton(p).label).toBe('healthy');
    expect(poolBaton({ ...p, runners: 0 }).label).toBe('no runners');
    expect(poolBaton({ ...p, onlineRunners: 0 }).holder).toBe('blocked');
    expect(poolBaton({ ...p, queued: 5, running: 10, maxConcurrentJobs: 10 }).holder).toBe('you');
  });

  it('explains what revoking does', () => {
    expect(revokeConsequences({ name: 'eu-1', running: 2 })).toContain(
      '2 leased jobs go back to the queue',
    );
    expect(revokeConsequences({ name: 'eu-1', running: 0 })).toContain('no leased jobs');
  });

  it('validates the pool form', () => {
    expect(poolInputErrors({ ...emptyPool(), id: 'eu-gvisor', name: 'EU' })).toEqual({});
    const e = poolInputErrors({ ...emptyPool(), id: 'EU', name: ' ', warmPoolSize: 20 });
    expect(Object.keys(e).sort()).toEqual(['id', 'name', 'warmPoolSize']);
  });

  it('builds the add-runner snippets', () => {
    expect(helmInstallCommand('eu-gvisor')).toBe(
      'helm upgrade --install bo-runner-eu-gvisor deploy/helm/back-office-runner \\\n' +
        '  --namespace bo-runner-eu-gvisor --create-namespace \\\n' +
        '  -f runner-eu-gvisor.values.yaml',
    );
    const values = fallbackHelmValues('https://bo.example.com/', 'eu', 'bo_rrt_x');
    // Exactly the keys of deploy/helm/back-office-runner (values.yaml, templates/controller.yaml).
    const keys = values
      .split('\n')
      .filter((l) => l.trim() && !l.trim().startsWith('#'))
      .map((l) => l.replace(/\s+#.*$/, ''));
    expect(keys).toEqual([
      'backOffice:',
      '  url: https://bo.example.com',
      '  poolId: eu',
      'registration:',
      '  token: bo_rrt_x',
      'backend: auto',
      'runtimeClasses:',
      '  standard: gvisor',
      '  high: kata',
    ]);
    expect(values).not.toContain('registrationToken');
    expect(values).not.toContain('controller:');
    const now = Date.parse('2026-09-28T10:00:00Z');
    expect(expiresIn('2026-09-29T10:00:00Z', now)).toBe('in 24 h');
    expect(expiresIn('2026-09-28T10:30:00Z', now)).toBe('in 30 min');
    expect(expiresIn(null, now)).toBe('within 24 h');
  });
});
