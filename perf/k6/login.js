// Local-account login throughput and latency (Argon2id hashing dominates; ADR 0021).
// The login throttle (20 attempts / 5 min per IP by default) must be raised for this scenario:
// BACKOFFICE_AUTH_LOCAL_THROTTLE_MAX_ATTEMPTS=1000000 on the API under test (perf/README.md).
import http from 'k6/http';
import { check } from 'k6';
import { API, login, auth } from './lib/common.js';

export const options = {
  scenarios: {
    login: { executor: 'constant-vus', vus: Number(__ENV.VUS || 5), duration: __ENV.DURATION || '1m', exec: 'loginFlow' },
  },
  thresholds: {
    http_req_failed: ['rate<0.001'],
    'http_req_duration{kind:login}': [`p(95)<${Number(__ENV.LOGIN_P95_MS || 800)}`],
    checks: ['rate>0.999'],
  },
};

export function loginFlow() {
  http.cookieJar().clear(__ENV.BASE_URL || 'http://localhost:8095');
  const credentials = login();
  const me = http.get(`${API}/me`, auth(credentials, { name: 'me', kind: 'read' }));
  check(me, { 'me 200': (r) => r.status === 200 });
}

export default loginFlow;
