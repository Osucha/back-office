// SSE subscriptions (GET /api/v1/events): VUS clients each hold a subscription for HOLD seconds while the
// read scenario runs against the same API. Stock k6 cannot read a streaming body incrementally, so a
// subscription counts as held when the request is still open at the client timeout (k6 error 1050) and as
// failed when the server answers early (401/5xx, connection refused). Event delivery latency
// (commit -> SSE, SLO p95 < 2 s) needs an SSE-capable client (xk6-sse); see perf/README.md.
import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { API, login, auth, readThresholds } from './lib/common.js';
import { runsList } from './reads.js';

const held = new Rate('sse_subscription_held');
const readDuringSse = new Trend('read_during_sse_ms', true);
const HOLD = Number(__ENV.HOLD || 30);

export const options = {
  scenarios: {
    subscribers: { executor: 'constant-vus', vus: Number(__ENV.VUS || 50), duration: __ENV.DURATION || '1m', exec: 'subscribe', gracefulStop: `${HOLD + 5}s` },
    reads: { executor: 'constant-vus', vus: 5, duration: __ENV.DURATION || '1m', exec: 'readWhileSubscribed', startTime: '5s' },
  },
  thresholds: {
    sse_subscription_held: ['rate>0.99'],
    'http_req_duration{kind:read}': readThresholds['http_req_duration{kind:read}'],
    checks: ['rate>0.99'],
  },
};

export function setup() {
  const credentials = login();
  const page = http.get(`${API}/runs?size=50`, auth(credentials, { name: 'setup' }));
  return { credentials, runIds: (page.json('content') || []).map((r) => r.id), projectIds: [] };
}

export function subscribe(data) {
  const params = auth(data.credentials, { name: 'events', kind: 'sse' });
  params.headers.Accept = 'text/event-stream';
  params.timeout = `${HOLD}s`;
  params.responseType = 'none';
  const res = http.get(`${API}/events`, params);
  // still streaming at the timeout = held; an early answer is a failure (except a clean 200 close by the server)
  held.add(res.error_code === 1050 || (res.status === 200 && res.timings.duration >= HOLD * 1000 * 0.95));
}

export function readWhileSubscribed(data) {
  const start = Date.now();
  runsList(data);
  readDuringSse.add(Date.now() - start);
  check(null, { 'read iteration ran': () => true });
}

export default subscribe;
