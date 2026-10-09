// Read paths of the UI: home/dashboard, runs list (filters, pages), run detail (with artefacts and logs),
// approvals inbox, CI executions list, usage summaries. One session shared by all VUs (created in setup).
import http from 'k6/http';
import { check, sleep } from 'k6';
import { API, login, auth, readThresholds } from './lib/common.js';

export const options = {
  scenarios: {
    home: { executor: 'constant-vus', vus: Number(__ENV.HOME_VUS || 5), duration: __ENV.DURATION || '1m', exec: 'home' },
    runsList: { executor: 'constant-vus', vus: Number(__ENV.RUNS_VUS || 5), duration: __ENV.DURATION || '1m', exec: 'runsList' },
    runDetail: { executor: 'constant-vus', vus: Number(__ENV.DETAIL_VUS || 5), duration: __ENV.DURATION || '1m', exec: 'runDetail' },
  },
  thresholds: {
    ...readThresholds,
    'http_req_duration{name:dashboard}': [`p(95)<${Number(__ENV.READ_P95_MS || 300)}`],
    'http_req_duration{name:runs}': [`p(95)<${Number(__ENV.READ_P95_MS || 300)}`],
    'http_req_duration{name:run}': [`p(95)<${Number(__ENV.READ_P95_MS || 300)}`],
  },
};

export function setup() {
  const credentials = login();
  const page = http.get(`${API}/runs?size=100`, auth(credentials, { name: 'setup' }));
  check(page, { 'setup runs 200': (r) => r.status === 200 });
  const runIds = (page.json('content') || []).map((r) => r.id);
  const projects = http.get(`${API}/projects`, auth(credentials, { name: 'setup' }));
  const projectIds = (projects.json() || []).map((p) => p.id).slice(0, 20);
  return { credentials, runIds, projectIds };
}

const pick = (list) => list[Math.floor(Math.random() * list.length)];

export function home(data) {
  const p = (name) => auth(data.credentials, { name, kind: 'read' });
  const responses = http.batch([
    ['GET', `${API}/dashboard`, null, p('dashboard')],
    ['GET', `${API}/me`, null, p('me')],
    ['GET', `${API}/approvals`, null, p('approvals')],
  ]);
  responses.forEach((r) => check(r, { 'home 200': (res) => res.status === 200 }));
  sleep(1);
}

export function runsList(data) {
  const p = auth(data.credentials, { name: 'runs', kind: 'read' });
  const variants = [
    `${API}/runs?page=0&size=20`,
    `${API}/runs?page=0&size=20&status=FAILED`,
    `${API}/runs?page=0&size=20&status=RUNNING&status=WAITING&status=PENDING`,
    `${API}/runs?page=4&size=20`,
  ];
  if (data.projectIds.length) variants.push(`${API}/runs?page=0&size=20&projectId=${pick(data.projectIds)}`);
  const res = http.get(pick(variants), p);
  check(res, { 'runs 200': (r) => r.status === 200 });
  const ci = http.get(`${API}/ci/executions?page=0&size=20`, auth(data.credentials, { name: 'ci-executions', kind: 'read' }));
  check(ci, { 'ci executions 200': (r) => r.status === 200 });
  sleep(0.5);
}

export function runDetail(data) {
  if (!data.runIds.length) return;
  const id = pick(data.runIds);
  const run = http.get(`${API}/runs/${id}`, auth(data.credentials, { name: 'run', kind: 'read' }));
  check(run, { 'run 200': (r) => r.status === 200 });
  const artifacts = http.get(`${API}/runs/${id}/artifacts`, auth(data.credentials, { name: 'run-artifacts', kind: 'read' }));
  check(artifacts, { 'artifacts 200': (r) => r.status === 200 });
  const logs = http.get(`${API}/runs/${id}/logs`, auth(data.credentials, { name: 'run-logs', kind: 'read' }));
  check(logs, { 'logs 200': (r) => r.status === 200 });
  sleep(0.5);
}

export default runsList;
