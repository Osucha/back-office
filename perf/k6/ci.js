// CI integration path (ADR 0013): POST /ci/executions with a project CI token (bo_ci_...), then poll
// GET /ci/executions/{id} until SUCCEEDED/FAILED like backoffice-ci.sh does. setup() creates a project and
// a CI token through the admin session. Without LLM provider keys on the API under test the review step
// fails fast, so the numbers measure the platform path (queueing, workflow engine, persistence), not the LLM.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';
import { API, login, auth } from './lib/common.js';

const completion = new Trend('ci_execution_completion_ms', true);
const completed = new Rate('ci_execution_completed');

export const options = {
  scenarios: {
    ci: { executor: 'constant-arrival-rate', rate: Number(__ENV.CI_RATE || 2), timeUnit: '1s', duration: __ENV.DURATION || '1m',
          preAllocatedVUs: 10, maxVUs: 50, exec: 'ciExecution' },
  },
  thresholds: {
    http_req_failed: ['rate<0.001'],
    'http_req_duration{name:ci-execute}': [`p(95)<${Number(__ENV.CI_POST_P95_MS || 500)}`],
    'http_req_duration{name:ci-poll}': [`p(95)<${Number(__ENV.READ_P95_MS || 300)}`],
    ci_execution_completed: ['rate>0.99'],
    ci_execution_completion_ms: [`p(95)<${Number(__ENV.CI_COMPLETION_P95_MS || 15000)}`],
  },
};

export function setup() {
  const admin = login();
  const key = `K6${Date.now().toString(36).toUpperCase().slice(-8)}`;
  const project = http.post(`${API}/projects`, JSON.stringify({
    key, name: `k6 ${key}`, repository: { url: 'https://git.example.com/perf/k6.git', defaultBranch: 'main' },
  }), auth(admin, { name: 'setup' }));
  check(project, { 'project created': (r) => r.status === 201 });
  const token = http.post(`${API}/projects/${project.json('id')}/ci-tokens`, JSON.stringify({ name: 'k6' }), auth(admin, { name: 'setup' }));
  check(token, { 'ci token created': (r) => r.status === 201 });
  return { token: token.json('token') };
}

export function ciExecution(data) {
  const params = (name) => ({ headers: { Authorization: `Bearer ${data.token}`, 'Content-Type': 'application/json' }, tags: { name } });
  const sha = `${__VU}-${__ITER}-${Date.now()}`;
  const body = JSON.stringify({
    entrypoint: 'code-review',
    diff: 'diff --git a/A.kt b/A.kt\n+val x = 1\n',
    changedFiles: ['A.kt'],
    context: {
      provider: 'GITLAB', projectPath: 'perf/k6', pipelineId: String(__ITER), jobId: String(__VU), ref: 'feature/k6', commitSha: sha,
      defaultBranch: 'main', mergeRequest: { iid: String(__VU), title: 'k6', sourceBranch: 'feature/k6', targetBranch: 'main' },
    },
  });
  const started = Date.now();
  const res = http.post(`${API}/ci/executions`, body, params('ci-execute'));
  if (!check(res, { 'execute 202': (r) => r.status === 202 })) {
    completed.add(false);
    return;
  }
  const id = res.json('id');
  for (let i = 0; i < 60; i++) {
    sleep(0.5);
    const poll = http.get(`${API}/ci/executions/${id}`, params('ci-poll'));
    check(poll, { 'poll 200': (r) => r.status === 200 });
    const status = poll.status === 200 ? poll.json('status') : null;
    if (status === 'SUCCEEDED' || status === 'FAILED') {
      completion.add(Date.now() - started);
      completed.add(true);
      return;
    }
  }
  completed.add(false);
}

export default ciExecution;
