// Playwright route mocks for the iteration-5 endpoints (runner fleet, operations, budgets, insights,
// provenance, sandbox policy) — docs/api/openapi.yaml v4.0.0. Each mock asks the real backend first
// and only answers when it returns 404/405/5xx (or is down), so the same scripts keep working once
// the backend implements the contract. `/me` is extended with `fleet.view` / `fleet.manage` when the
// signed-in administrator does not have them yet (the backend does not know those permissions).
//
//   import { installGovernanceMocks, MOCK_IDS } from './governance-mocks.mjs';
//   await installGovernanceMocks(context);        // GOV_MOCKS=0 disables it

const iso = (msAgo) => new Date(Date.now() - msAgo).toISOString();
const MIN = 60_000;
const H = 3_600_000;
const D = 24 * H;

export const MOCK_IDS = {
  pool: 'eu-gvisor',
  job: '5b0c8f7e-2a41-4c1e-9d8e-0000000000aa',
  runner: '7c3d1a2b-0000-4000-8000-000000000001',
  attestation: 'a7b8c9d0-0000-4000-8000-000000000001',
};

const ceiling = {
  isolation: 'STANDARD',
  resources: { cpu: '4', memory: '8Gi', ephemeralStorage: '20Gi', pids: 4096 },
  timeoutSeconds: 7200,
  network: { allow: ['registry.npmjs.org', '*.gradle.org', 'repo.maven.apache.org', 'pypi.org'] },
  tools: {
    allow: ['git *', 'npm *', './gradlew *', 'rg *', 'pytest *'],
    deny: ['curl *', 'wget *', 'ssh *', 'docker *', 'sudo *'],
    ask: ['rm -rf *'],
  },
  secrets: ['npm-read-token', 'gradle-cache-key'],
  environment: {
    image: 'ghcr.io/org/back-office-runner:*',
    caches: ['gradle', 'maven', 'npm'],
  },
  maxCostUsd: 5,
  maxTokens: 2000000,
};

const pools = [
  {
    id: 'eu-gvisor',
    name: 'EU sandbox (gVisor)',
    description: 'GKE europe-west4, GKE Sandbox node pool',
    labels: { region: 'eu', isolation: 'gvisor' },
    ceiling,
    allowedImages: ['ghcr.io/org/back-office-runner:*'],
    warmPoolSize: 3,
    maxConcurrentJobs: 12,
    projectIds: [],
    runners: 3,
    onlineRunners: 3,
    queued: 2,
    running: 7,
    createdAt: iso(40 * D),
  },
  {
    id: 'eu-kata',
    name: 'EU microVM (Kata)',
    description: 'AKS Kata node pool for untrusted repositories',
    labels: { region: 'eu', isolation: 'kata' },
    ceiling: { ...ceiling, isolation: 'HIGH', maxCostUsd: 3 },
    allowedImages: ['ghcr.io/org/back-office-runner:2.*'],
    warmPoolSize: 1,
    maxConcurrentJobs: 4,
    projectIds: [],
    runners: 1,
    onlineRunners: 1,
    queued: 0,
    running: 1,
    createdAt: iso(12 * D),
  },
  {
    id: 'dev-docker',
    name: 'Local docker (dev)',
    description: null,
    labels: { env: 'dev' },
    ceiling: { isolation: 'DEV', timeoutSeconds: 1800 },
    allowedImages: ['back-office-runner:dev'],
    warmPoolSize: 0,
    maxConcurrentJobs: 2,
    projectIds: [],
    runners: 1,
    onlineRunners: 0,
    queued: 0,
    running: 0,
    createdAt: iso(3 * D),
  },
];

const runners = [
  ['eu-gvisor-a', 'eu-gvisor', true, 3, 4, 8e3],
  ['eu-gvisor-b', 'eu-gvisor', true, 4, 4, 12e3],
  ['eu-gvisor-c', 'eu-gvisor', true, 0, 4, 6e3],
  ['eu-kata-a', 'eu-kata', true, 1, 4, 9e3],
  ['laptop-docker', 'dev-docker', false, 0, 2, 3 * H],
].map(([name, poolId, online, running, max, seen], i) => ({
  id: `7c3d1a2b-0000-4000-8000-00000000000${i + 1}`,
  poolId,
  name,
  version: i === 4 ? '2.0.0-dev' : '2.0.3',
  backends: poolId === 'dev-docker' ? ['DOCKER'] : ['AGENT_SANDBOX', 'K8S_JOB'],
  runtimeClasses: poolId === 'eu-kata' ? ['kata'] : poolId === 'dev-docker' ? [] : ['gvisor'],
  maxConcurrent: max,
  running,
  lastSeenAt: iso(seen),
  online,
  cluster: poolId === 'dev-docker' ? null : poolId === 'eu-kata' ? 'aks-weu-2' : 'gke-euw4-1',
  registeredAt: iso((10 + i) * D),
}));

const jobPolicy = {
  ...ceiling,
  resources: { cpu: '2', memory: '4Gi', ephemeralStorage: '10Gi', pids: 2048 },
  timeoutSeconds: 3600,
  network: { allow: ['registry.npmjs.org', '*.gradle.org'] },
  environment: { image: 'ghcr.io/org/back-office-runner:2.0-jdk21-node22', caches: ['gradle', 'npm'] },
  maxCostUsd: 2,
};
const jobRows = [
  ['RUNNING', 'implement', 'claude-opus-5', 'ANTHROPIC', 4 * MIN, null, 0.21, [], null],
  ['STARTING', 'review', 'gpt-5', 'OPENAI', 40e3, null, 0, [], null],
  ['QUEUED', 'spec', 'claude-sonnet-5', 'ANTHROPIC', null, null, 0, [], null],
  ['LEASED', 'plan', 'claude-sonnet-5', 'ANTHROPIC', null, null, 0, [], null],
  ['SUCCEEDED', 'implement', 'claude-opus-5', 'ANTHROPIC', 42 * MIN, 30 * MIN, 0.64, [], null],
  [
    'FAILED',
    'implement',
    'claude-opus-5',
    'ANTHROPIC',
    3 * H,
    2.8 * H,
    0.38,
    ['pastebin.com:443', 'api.github.com:443'],
    'verification failed: ./gradlew test exited 1',
  ],
  ['TIMED_OUT', 'tests', 'gpt-5', 'OPENAI', 6 * H, 5 * H, 1.12, [], 'DeadlineExceeded after 1h'],
  ['LOST', 'implement', 'claude-sonnet-5', 'ANTHROPIC', 9 * H, 8.9 * H, 0.05, [], 'no heartbeat for 90 s'],
  ['CANCELLED', 'spec', 'claude-haiku-4.5', 'ANTHROPIC', 26 * H, 25.9 * H, 0.01, [], 'cancelled by Ada Admin'],
  ['SUCCEEDED', null, 'gpt-5-mini', 'OPENAI', 30 * H, 29.8 * H, 0.03, [], null],
];
const jobs = jobRows.map(([status, nodeId, model, provider, started, finished, cost, denied, reason], i) => {
  const benchmark = nodeId === null;
  return {
    id: `5b0c8f7e-2a41-4c1e-9d8e-00000000000${i}`.slice(0, 36),
    poolId: status === 'QUEUED' ? null : i % 4 === 3 ? 'eu-kata' : 'eu-gvisor',
    runnerId: ['QUEUED'].includes(status) ? null : runners[i % 3].id,
    status,
    mode: benchmark ? 'BENCHMARK' : nodeId === 'implement' ? 'IMPLEMENT' : 'SPEC_KIT',
    projectId: null,
    runId: benchmark ? null : `1f32aa0${i}-0000-4000-8000-000000000000`,
    nodeId,
    benchmarkId: benchmark ? '9a1bcc00-0000-4000-8000-000000000001' : null,
    model: { provider, modelId: model },
    isolation: i % 4 === 3 ? 'HIGH' : 'STANDARD',
    policy: status === 'QUEUED' ? null : jobPolicy,
    queuedAt: iso((started ?? 2 * MIN) + 20e3),
    startedAt: started ? iso(started) : null,
    finishedAt: finished ? iso(finished) : null,
    reason,
    cost: { amount: cost, currency: 'USD' },
    egressDenied: denied,
  };
});
jobs[0].id = MOCK_IDS.job;
// The job with denied egress is the one the screenshots open.
jobs[5].id = '5b0c8f7e-2a41-4c1e-9d8e-0000000000ff';
export const MOCK_DENIED_JOB = jobs[5].id;

const overview = () => ({
  replicas: [
    { instance: 'back-office-api-7d9f-x2k4p', roles: ['api', 'scheduler', 'sse'], version: '4.0.0', lastSeenAt: iso(4e3) },
    { instance: 'back-office-api-7d9f-mq8zt', roles: ['api', 'sse'], version: '4.0.0', lastSeenAt: iso(9e3) },
    { instance: 'back-office-worker-5c6b-h7t2w', roles: ['worker', 'run-advance', 'ci-dispatch'], version: '4.0.0', lastSeenAt: iso(6e3) },
    { instance: 'back-office-worker-5c6b-p1r9d', roles: ['worker', 'run-advance'], version: '4.0.1', lastSeenAt: iso(95e3) },
  ],
  workItems: { READY: 14, LEASED: 6, DONE: 18342, DEAD: 2 },
  deadItems: 2,
  oldestReadyAgeSeconds: 38,
  agentJobs: { QUEUED: 2, LEASED: 1, STARTING: 1, RUNNING: 7, SUCCEEDED: 412, FAILED: 23 },
  sseSubscribers: 17,
});
const deadItems = {
  content: [
    {
      id: 'd0000000-0000-4000-8000-000000000001',
      kind: 'RUN_ADVANCE',
      dedupeKey: 'run:1f32aa00:implement',
      status: 'DEAD',
      attempts: 8,
      runAfter: iso(2 * H),
      leaseOwner: null,
      lastError: 'GitLabApiException: 502 Bad Gateway while creating merge request',
      createdAt: iso(3 * H),
    },
    {
      id: 'd0000000-0000-4000-8000-000000000002',
      kind: 'CI_DISPATCH',
      dedupeKey: 'ci:aa11bb22',
      status: 'DEAD',
      attempts: 8,
      runAfter: iso(20 * H),
      leaseOwner: null,
      lastError: 'SocketTimeoutException: connect timed out (jira.example.com)',
      createdAt: iso(22 * H),
    },
  ],
  page: 0,
  size: 50,
  totalElements: 2,
};

const month = new Date().toISOString().slice(0, 7);
const budgetFor = (p, i) => ({
  scope: 'PROJECT',
  projectId: p.id,
  projectKey: p.key,
  period: month,
  monthlyLimitUsd: i === 0 ? 50 : 200,
  alertAtPercent: 80,
  hardStop: true,
  spentUsd: i === 0 ? 43.1 : 38.2,
  forecastUsd: i === 0 ? 61.4 : 72,
  state: i === 0 ? 'ALERT' : 'OK',
});
const platformBudget = {
  scope: 'PLATFORM',
  projectId: null,
  projectKey: null,
  period: month,
  monthlyLimitUsd: 1000,
  alertAtPercent: 80,
  hardStop: false,
  spentUsd: 412.5,
  forecastUsd: 690,
  state: 'OK',
};

function insights(projectId) {
  const weeks = 8;
  const weekly = Array.from({ length: weeks }, (_, i) => {
    const d = new Date(Date.now() - (weeks - 1 - i) * 7 * D);
    d.setUTCDate(d.getUTCDate() - ((d.getUTCDay() + 6) % 7));
    return {
      weekStart: d.toISOString().slice(0, 10),
      succeeded: [6, 9, 7, 11, 10, 13, 12, 15][i],
      failed: [3, 2, 4, 2, 3, 1, 2, 2][i],
      humanWaitHours: [22, 30, 26, 41, 35, 28, 24, 19][i],
      cost: { amount: [18, 24, 21, 30, 28, 33, 31, 37][i], currency: 'USD' },
    };
  });
  return {
    from: iso(56 * D),
    to: iso(0),
    projectId: projectId ?? null,
    leadTime: { p50Seconds: 26 * 3600, p90Seconds: 3.2 * 86400, count: 64 },
    humanWait: { p50Seconds: 2.4 * 3600, p90Seconds: 19 * 3600, totalSeconds: 225 * 3600, count: 188 },
    ciWait: { p50Seconds: 7 * 60, p90Seconds: 24 * 60, count: 142 },
    runs: { total: 110, succeeded: 83, failed: 19, cancelled: 8 },
    reworkLoopsPerRun: 0.6,
    costPerSuccessfulRun: { amount: 2.71, currency: 'USD' },
    costPerMergeRequest: { amount: 3.48, currency: 'USD' },
    totalCost: { amount: 225.2, currency: 'USD' },
    agentSuccessByTemplate: [
      { templateKey: 'implement-opencode', jobs: 96, succeeded: 71, rate: 0.74, cost: { amount: 141.2, currency: 'USD' } },
      { templateKey: 'spec-kit-specify', jobs: 88, succeeded: 85, rate: 0.966, cost: { amount: 22.4, currency: 'USD' } },
      { templateKey: 'code-review', jobs: 142, succeeded: 139, rate: 0.979, cost: { amount: 38.9, currency: 'USD' } },
      { templateKey: 'write-tests', jobs: 21, succeeded: 9, rate: 0.43, cost: { amount: 12.6, currency: 'USD' } },
    ],
    agentSuccessByModel: [
      { model: 'claude-opus-5', jobs: 118, succeeded: 97, rate: 0.82, cost: { amount: 151.3, currency: 'USD' } },
      { model: 'gpt-5', jobs: 84, succeeded: 71, rate: 0.845, cost: { amount: 49.1, currency: 'USD' } },
      { model: 'claude-sonnet-5', jobs: 145, succeeded: 136, rate: 0.938, cost: { amount: 24.8, currency: 'USD' } },
    ],
    gateDecisions: [
      { option: 'approve', count: 131 },
      { option: 'revise', count: 38 },
      { option: 'reject', count: 7 },
    ],
    weekly,
    bottleneck: {
      nodeId: 'spec-gate',
      nodeName: 'Spec approval',
      workflowName: 'Issue to reviewed MR',
      p90WaitSeconds: 19 * 3600,
    },
  };
}

const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64');
function attestations(runId) {
  return [
    {
      id: MOCK_IDS.attestation,
      runId,
      nodeId: 'implement',
      jobId: MOCK_IDS.job,
      kind: 'AGENT_JOB',
      subjects: [
        { name: 'git+https://gitlab.example.com/demo/shop@9f2c1ab4de5f60718293a4b5c6d7e8f901234567', digest: { sha256: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855' } },
        { name: 'diff', digest: { sha256: '4f1c9a0b3e7d2c8f6a5b4e3d2c1b0a9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b' } },
      ],
      keyId: 'bo-ed25519-2026-09',
      createdAt: iso(40 * MIN),
    },
    {
      id: 'a7b8c9d0-0000-4000-8000-000000000002',
      runId,
      nodeId: null,
      jobId: null,
      kind: 'RUN',
      subjects: [
        { name: 'git+https://gitlab.example.com/demo/shop@9f2c1ab4de5f60718293a4b5c6d7e8f901234567', digest: { sha256: 'e3b0c442' } },
      ],
      keyId: 'bo-ed25519-2026-09',
      createdAt: iso(20 * MIN),
    },
  ];
}
function envelope(runId, id) {
  const statement = {
    _type: 'https://in-toto.io/Statement/v1',
    subject: attestations(runId)[0].subjects,
    predicateType: 'https://back-office.dev/attestations/agent-run/v1',
    predicate: {
      runId,
      nodeId: id.endsWith('2') ? null : 'implement',
      workflow: { id: 'w1', name: 'Issue to reviewed MR', version: 3 },
      template: { key: 'implement-opencode', version: 4, lineage: ['base-agent', 'implement', 'implement-opencode'] },
      model: { provider: 'ANTHROPIC', model: 'claude-opus-5', effort: 'HIGH' },
      prompts: { system: 'sha256:1a2b3c', user: 'sha256:4d5e6f', skills: ['sha256:7a8b9c'] },
      sandbox: { isolation: 'STANDARD', policyDigest: 'sha256:9c1d7e', imageDigest: 'sha256:ab12cd34ef56' },
      egress: { allowedHosts: ['registry.npmjs.org', '*.gradle.org', 'gitlab.example.com'], logDigest: 'sha256:5e6f7a' },
      usage: { inputTokens: 412000, outputTokens: 18400 },
      cost: { amount: 0.64, currency: 'USD' },
      verification: { command: './gradlew test', exitCode: 0 },
      gateDecisions: [
        { decidedBy: { displayName: 'Ada Admin' }, option: 'APPROVE', at: iso(2 * H) },
        { decidedBy: { displayName: 'Dev Developer' }, option: 'APPROVE', at: iso(50 * MIN) },
      ],
      timestamps: { startedAt: iso(72 * MIN), finishedAt: iso(41 * MIN) },
    },
  };
  return {
    payloadType: 'application/vnd.in-toto+json',
    payload: b64(statement),
    signatures: [{ keyid: 'bo-ed25519-2026-09', sig: 'MEUCIQDk0rQ2mockSignatureOnlyForScreenshots0000000000000000==' }],
    _statement: statement,
  };
}

const projectPolicy = {
  isolation: 'STANDARD',
  resources: { cpu: '2', memory: '4Gi' },
  network: { allow: ['registry.npmjs.org', '*.gradle.org', 'evil.example.com'] },
  tools: { deny: ['npm publish *'] },
  secrets: ['npm-read-token'],
  environment: { image: 'ghcr.io/org/back-office-runner:2.0-jdk21-node22', caches: ['gradle', 'npm'] },
  maxCostUsd: 3,
};
const policyView = (project) => ({
  project,
  ceiling,
  effective: {
    ...ceiling,
    ...project,
    resources: { ...ceiling.resources, ...project.resources },
    network: { allow: (project.network?.allow ?? []).filter((h) => h !== 'evil.example.com') },
    tools: { ...ceiling.tools, deny: [...ceiling.tools.deny, ...(project.tools?.deny ?? [])] },
  },
  sources: {
    isolation: 'PROJECT',
    resources: 'PROJECT',
    'resources.pids': 'POOL',
    timeoutSeconds: 'POOL',
    'network.allow': 'PROJECT',
    'tools.deny': 'POOL',
    'environment.image': 'PROJECT',
    maxCostUsd: 'PROJECT',
  },
});

// Missing endpoints answer 404/405/501; the dev backend answered 500 for some of these paths during
// the smoke runs, so 5xx falls back to the mock too.
const MISSING = new Set([404, 405, 500, 501, 502, 503]);
const FLEET_PERMS = ['fleet.view', 'fleet.manage'];

/**
 * Installs the mocks on a browser context. `projects()` supplies known projects (for budgets);
 * returns a function that updates them.
 */
export async function installGovernanceMocks(context, opts = {}) {
  if (process.env.GOV_MOCKS === '0') return { setProjects: () => undefined, used: new Set() };
  let projects = opts.projects ?? [];
  const used = new Set();
  let sandbox = structuredClone(projectPolicy);
  const budgets = new Map();
  let platform = structuredClone(platformBudget);

  /** Real backend first; the mock only when the endpoint does not exist (or the backend is down). */
  const mock = (pattern, handler, name) =>
    context.route(pattern, async (route) => {
      let res = null;
      if (!opts.forceMocks) {
        try {
          res = await route.fetch();
        } catch {
          res = null;
        }
        if (res && !MISSING.has(res.status())) return route.fulfill({ response: res });
      }
      const out = await handler(route.request(), route);
      if (out === undefined) return res ? route.fulfill({ response: res }) : route.abort();
      used.add(name);
      return route.fulfill(
        out === null ? { status: 204, body: '' } : { status: out.status ?? 200, json: out.json ?? out },
      );
    });

  // /me: add the fleet permissions (unknown to the current backend) for the administrator.
  await context.route('**/api/v1/me', async (route) => {
    if (route.request().method() !== 'GET') return route.fallback();
    let res;
    try {
      res = await route.fetch();
    } catch {
      return route.fallback();
    }
    if (res.status() !== 200) return route.fulfill({ response: res });
    const me = await res.json();
    const perms = new Set(me.platformPermissions ?? []);
    if (perms.has('admin.settings.manage')) for (const p of FLEET_PERMS) perms.add(p);
    me.platformPermissions = [...perms];
    // Screenshots: the OS colour scheme decides the theme (SYSTEM), whatever the account saved.
    if (opts.systemTheme) me.preferences = { ...(me.preferences ?? {}), theme: 'SYSTEM' };
    return route.fulfill({ response: res, json: me });
  });

  await mock('**/api/v1/admin/runner-pools', (req) => {
    if (req.method() === 'GET') return { json: pools };
    if (req.method() === 'POST') return { status: 201, json: { ...JSON.parse(req.postData() || '{}'), runners: 0, onlineRunners: 0, queued: 0, running: 0, createdAt: iso(0) } };
  }, 'pools');
  await mock('**/api/v1/admin/runner-pools/*/registration-tokens', (req) => {
    const poolId = req.url().split('/runner-pools/')[1].split('/')[0];
    const token = 'bo_rrt_3Kx9vQ2mT7pL0aZ8wYc4Rn6bFh1sJd5e';
    return {
      status: 201,
      json: {
        token,
        expiresAt: iso(-24 * H),
        helmValues: [
          'backOffice:',
          `  url: ${new URL(req.url()).origin}/api/v1`,
          `  pool: ${poolId}`,
          `  registrationToken: ${token}   # single use, 24 h`,
          'controller:',
          '  backend: AGENT_SANDBOX',
          '  maxConcurrent: 4',
          'runtimeClasses:',
          '  standard: gvisor',
          '  high: kata',
          'egressProxy:',
          '  image: ghcr.io/org/back-office-egress:1.4',
        ].join('\n'),
      },
    };
  }, 'registration-token');
  await mock(/\/api\/v1\/admin\/runner-pools\/[^/?]+$/, (req) => {
    const id = decodeURIComponent(req.url().split('/runner-pools/')[1]);
    const p = pools.find((x) => x.id === id);
    if (req.method() === 'GET') return p ? { json: p } : { status: 404, json: { title: 'Not found' } };
    if (req.method() === 'PUT') return { json: { ...p, ...JSON.parse(req.postData() || '{}') } };
    if (req.method() === 'DELETE') return null;
  }, 'pool');
  await mock(/\/api\/v1\/admin\/runners(\?.*)?$/, (req) => {
    const poolId = new URL(req.url()).searchParams.get('poolId');
    return { json: poolId ? runners.filter((r) => r.poolId === poolId) : runners };
  }, 'runners');
  await mock('**/api/v1/admin/runners/*', (req) => (req.method() === 'DELETE' ? null : undefined), 'revoke');
  await mock(/\/api\/v1\/admin\/agent-jobs(\?.*)?$/, (req) => {
    const q = new URL(req.url()).searchParams;
    const list = jobs.filter(
      (j) => (!q.get('poolId') || j.poolId === q.get('poolId')) && (!q.get('status') || j.status === q.get('status')),
    );
    return { json: { content: list, page: 0, size: 50, totalElements: list.length } };
  }, 'jobs');
  await mock(/\/api\/v1\/admin\/agent-jobs\/[^/?]+$/, (req) => {
    const id = req.url().split('/agent-jobs/')[1];
    const j = jobs.find((x) => x.id === id);
    return j ? { json: j } : { status: 404, json: { title: 'Not found' } };
  }, 'job');
  await mock('**/api/v1/admin/agent-jobs/*/cancel', () => ({ status: 202, json: {} }), 'cancel');
  await mock('**/api/v1/admin/operations/overview', () => ({ json: overview() }), 'operations');
  await mock('**/api/v1/admin/operations/work-items?*', () => ({ json: deadItems }), 'work-items');
  await mock('**/api/v1/admin/operations/work-items/*/retry', () => ({ status: 202, json: {} }), 'retry');

  await mock('**/api/v1/projects/*/sandbox-policy', (req) => {
    if (req.method() === 'PUT') sandbox = JSON.parse(req.postData() || '{}');
    return { json: policyView(sandbox) };
  }, 'sandbox-policy');
  await mock(/\/api\/v1\/budgets$/, () => ({
    json: [platform, ...projects.slice(0, 3).map((p, i) => budgets.get(p.id) ?? budgetFor(p, i))],
  }), 'budgets');
  await mock('**/api/v1/admin/budget', (req) => {
    platform = { ...platform, ...JSON.parse(req.postData() || '{}') };
    return { json: platform };
  }, 'platform-budget');
  await mock('**/api/v1/projects/*/budget', (req) => {
    const id = req.url().split('/projects/')[1].split('/')[0];
    const i = Math.max(0, projects.findIndex((p) => p.id === id));
    const p = projects[i] ?? { id, key: 'DEMO' };
    let b = budgets.get(id) ?? budgetFor(p, i);
    if (req.method() === 'PUT') {
      b = { ...b, ...JSON.parse(req.postData() || '{}') };
      budgets.set(id, b);
    }
    return { json: b };
  }, 'project-budget');
  await mock('**/api/v1/insights/delivery*', (req) => ({
    json: insights(new URL(req.url()).searchParams.get('projectId')),
  }), 'insights');
  await mock('**/api/v1/runs/*/attestations', (req) => ({
    json: attestations(req.url().split('/runs/')[1].split('/')[0]),
  }), 'attestations');
  await mock(/\/api\/v1\/attestations\/[0-9a-f-]{36}$/, (req) => {
    const id = req.url().split('/attestations/')[1];
    const { _statement, ...env } = envelope('1f32aa00-0000-4000-8000-000000000000', id);
    return { json: env };
  }, 'attestation');
  await mock('**/api/v1/attestations/verify', (req) => {
    const env = JSON.parse(req.postData() || '{}');
    let statement = {};
    try {
      statement = JSON.parse(Buffer.from(env.payload, 'base64').toString('utf8'));
    } catch {
      /* invalid */
    }
    return { json: { valid: true, keyId: 'bo-ed25519-2026-09', statement, reason: null } };
  }, 'verify');

  if (opts.coreFallbacks) await installCoreFallbacks(mock);
  return { setProjects: (list) => (projects = list), used };
}

// ---- screenshots only: stand-ins for existing endpoints when the dev backend fails (5xx)
export const DEMO_PROJECT = {
  id: '7a0ad29b-644a-40c9-80ef-bb18ae7b5de0',
  key: 'DEMO',
  name: 'Demo shop',
  description: 'Storefront and checkout',
  repository: { provider: 'GITLAB', url: 'https://gitlab.example.com/demo/shop', defaultBranch: 'main' },
  issueTracker: { jiraProjectKey: 'DEMO' },
  myRoles: ['owner'],
  myPermissions: ['project.view', 'project.manage', 'project.members.manage', 'workflow.edit', 'run.start', 'project.ci.manage'],
  stats: { openApprovals: 1, ciExecutionsLast7Days: 12, costLast30Days: { amount: 43.1, currency: 'USD' } },
  createdAt: iso(60 * D),
};
const DEMO_RUN = {
  id: '1f32aa00-0000-4000-8000-000000000000',
  projectId: DEMO_PROJECT.id,
  projectKey: 'DEMO',
  workflowId: 'w1',
  workflowName: 'Issue to reviewed MR',
  workflowVersion: 3,
  issueKey: 'DEMO-42',
  status: 'SUCCEEDED',
  outcome: 'SUCCESS',
  currentNodeId: 'end',
  createdAt: iso(3 * H),
  startedAt: iso(3 * H),
  finishedAt: iso(20 * MIN),
  startedBy: { kind: 'USER', displayName: 'Ada Admin' },
  nodes: [
    { nodeId: 'start', kind: 'START', status: 'SUCCEEDED', startedAt: iso(3 * H), finishedAt: iso(3 * H) },
    { nodeId: 'spec', kind: 'TASK', status: 'SUCCEEDED', startedAt: iso(3 * H), finishedAt: iso(2.5 * H), port: 'success' },
    { nodeId: 'spec-gate', kind: 'HUMAN_GATE', status: 'SUCCEEDED', startedAt: iso(2.5 * H), finishedAt: iso(2 * H), port: 'approve' },
    { nodeId: 'implement', kind: 'TASK', status: 'SUCCEEDED', startedAt: iso(72 * MIN), finishedAt: iso(41 * MIN), port: 'success' },
    { nodeId: 'end', kind: 'END', status: 'SUCCEEDED', startedAt: iso(20 * MIN), finishedAt: iso(20 * MIN) },
  ],
  input: {},
};
async function installCoreFallbacks(mock) {
  await mock(/\/api\/v1\/projects$/, (req) => (req.method() === 'GET' ? { json: [DEMO_PROJECT] } : undefined), 'core-projects');
  await mock(/\/api\/v1\/projects\/[0-9a-f-]{36}$/, () => ({ json: DEMO_PROJECT }), 'core-project');
  await mock(/\/api\/v1\/projects\/[0-9a-f-]{36}\/workflows$/, () => ({ json: [] }), 'core-workflows');
  await mock(/\/api\/v1\/runs\?.*$/, () => ({ json: { content: [DEMO_RUN], page: 0, size: 50, totalElements: 1 } }), 'core-runs');
  await mock(/\/api\/v1\/runs\/[0-9a-f-]{36}$/, () => ({ json: DEMO_RUN }), 'core-run');
  await mock(/\/api\/v1\/runs\/[0-9a-f-]{36}\/(artifacts|logs)(\?.*)?$/, () => ({ json: [] }), 'core-run-extras');
  await mock(/\/api\/v1\/approvals\?.*$/, () => ({ json: [] }), 'core-approvals');
  await mock(/\/api\/v1\/ci\/executions\?.*$/, () => ({ json: { content: [], page: 0, size: 50, totalElements: 0 } }), 'core-ci');
  await mock(/\/api\/v1\/dashboard$/, () => ({ json: {} }), 'core-dashboard');
}
