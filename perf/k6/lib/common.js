// Shared helpers for the Back Office k6 scenarios (perf/README.md).
import http from 'k6/http';
import { check, fail } from 'k6';

export const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8095').replace(/\/$/, '');
export const API = `${BASE_URL}/api/v1`;
export const EMAIL = __ENV.BO_EMAIL || 'perf-admin@example.com';
export const PASSWORD = __ENV.BO_PASSWORD || 'K6-Baseline-Secret-77';

// SLOs from docs/architecture/09-scale-and-operations.md: p95 of reads < 300 ms, availability 99.9 %.
export const READ_P95_MS = Number(__ENV.READ_P95_MS || 300);
export const readThresholds = {
  http_req_failed: ['rate<0.001'],
  'http_req_duration{kind:read}': [`p(95)<${READ_P95_MS}`],
  checks: ['rate>0.999'],
};

/** Cookie value from a response (k6 exposes Set-Cookie per name). */
function cookie(res, name) {
  const c = res.cookies[name];
  return c && c.length ? c[0].value : null;
}

/**
 * Local-account login (ADR 0021): GET /auth/config for the XSRF-TOKEN cookie, then POST /auth/login with
 * the X-XSRF-TOKEN header. Returns the headers an authenticated browser would send.
 */
export function login(email = EMAIL, password = PASSWORD) {
  const jar = http.cookieJar();
  const config = http.get(`${API}/auth/config`, { tags: { name: 'auth/config', kind: 'auth' } });
  check(config, { 'auth config 200': (r) => r.status === 200 });
  const xsrf = cookie(config, 'XSRF-TOKEN') || (jar.cookiesForURL(BASE_URL)['XSRF-TOKEN'] || [])[0];
  if (!xsrf) fail('no XSRF-TOKEN cookie from /auth/config');
  const res = http.post(`${API}/auth/login`, JSON.stringify({ email, password }), {
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': xsrf },
    tags: { name: 'auth/login', kind: 'login' },
  });
  const ok = check(res, {
    'login 200': (r) => r.status === 200,
    'login authenticated': (r) => r.status === 200 && r.json('status') === 'AUTHENTICATED',
  });
  if (!ok) fail(`login failed: ${res.status} ${res.body}`);
  const session = cookie(res, 'BO_SESSION') || (jar.cookiesForURL(BASE_URL)['BO_SESSION'] || [])[0];
  const newXsrf = cookie(res, 'XSRF-TOKEN') || xsrf;
  return { session, xsrf: newXsrf };
}

/** Request params for a logged-in browser session (explicit Cookie header, so setup() data can be shared by VUs). */
export function auth(credentials, tags = {}) {
  return {
    headers: {
      Cookie: `BO_SESSION=${credentials.session}; XSRF-TOKEN=${credentials.xsrf}`,
      'X-XSRF-TOKEN': credentials.xsrf,
      'Content-Type': 'application/json',
    },
    tags,
  };
}

export function getJson(url, params) {
  const res = http.get(url, params);
  check(res, { [`${params.tags.name} 200`]: (r) => r.status === 200 });
  return res;
}
