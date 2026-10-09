// End-to-end check of local accounts through the API (ADR 0021): an OIDC admin invites a LOCAL user, the
// user sets a password from the one-time link, signs in locally, enrols TOTP and signs in again with a code.
// Usage: node e2e/local-account-check.mjs   (needs the dev stack: :4200 proxy or SMOKE_BASE, Keycloak admin)
import { chromium, request } from 'playwright';
import crypto from 'node:crypto';

const base = process.env.SMOKE_BASE ?? 'http://localhost:4200';
const results = [];
const check = (name, ok, detail = '') => results.push({ name, ok: !!ok, detail: String(detail).slice(0, 160) });

// ---- RFC 6238 TOTP (SHA-1, 30 s, 6 digits)
function base32Decode(s) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = '';
  for (const c of s.replace(/=+$/, '').toUpperCase()) bits += alphabet.indexOf(c).toString(2).padStart(5, '0');
  const bytes = [];
  for (let i = 0; i + 8 <= bits.length; i += 8) bytes.push(parseInt(bits.slice(i, i + 8), 2));
  return Buffer.from(bytes);
}
function totp(secret, offsetSteps = 0) {
  const step = Math.floor(Date.now() / 1000 / 30) + offsetSteps;
  const msg = Buffer.alloc(8);
  msg.writeBigUInt64BE(BigInt(step));
  const h = crypto.createHmac('sha1', base32Decode(secret)).update(msg).digest();
  const o = h[h.length - 1] & 0xf;
  const code = ((h.readUInt32BE(o) & 0x7fffffff) % 1_000_000).toString().padStart(6, '0');
  return code;
}

// ---- 1. admin session through Keycloak
const browser = await chromium.launch({ channel: 'msedge', headless: true });
const adminPage = await browser.newPage();
await adminPage.goto(base + '/oauth2/authorization/oidc?returnTo=/home');
await adminPage.fill('#username', 'admin@example.com');
await adminPage.fill('#password', 'admin');
await Promise.all([adminPage.waitForURL((u) => u.origin === base, { timeout: 30000 }), adminPage.click('#kc-login')]);
const adminCall = (method, path, body) =>
  adminPage.evaluate(
    async ([m, p, b]) => {
      const xsrf = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1];
      const r = await fetch(p, { method: m, headers: { 'Content-Type': 'application/json', ...(xsrf ? { 'X-XSRF-TOKEN': decodeURIComponent(xsrf) } : {}) }, body: b ? JSON.stringify(b) : undefined });
      return { status: r.status, body: await r.json().catch(() => null) };
    },
    [method, path, body],
  );

const email = `local.${Date.now()}@example.com`;
const invite = await adminCall('POST', '/api/v1/admin/users', { email, displayName: 'Local Tester', authMode: 'LOCAL', platformRoles: [] });
check('admin invites a LOCAL user', invite.status === 201 && invite.body?.setPasswordUrl, `${invite.status} ${invite.body?.setPasswordUrl ?? invite.body?.detail}`);
const token = invite.body?.setPasswordUrl?.split('/').pop();

// ---- 2. the invited user, in a fresh API context (own cookies)
const api = await request.newContext({ baseURL: base });
const xsrf = async () => (await api.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value;
await api.get('/api/v1/auth/config');
const post = async (path, data) => {
  const r = await api.post(path, { data, headers: { 'X-XSRF-TOKEN': (await xsrf()) ?? '' } });
  return { status: r.status(), body: await r.json().catch(() => null) };
};

const info = await api.get(`/api/v1/auth/password/reset/${token}`);
check('set-password link is valid', info.status() === 200, (await info.json().catch(() => ({}))).email);

const weak = await post(`/api/v1/auth/password/reset/${token}`, { newPassword: 'short' });
check('weak password rejected', weak.status === 400, weak.body?.detail);

const password = 'Relay-' + crypto.randomBytes(9).toString('base64url');
const set = await post(`/api/v1/auth/password/reset/${token}`, { newPassword: password });
check('password set from the link', set.status === 200, set.body?.status);

const reuse = await post(`/api/v1/auth/password/reset/${token}`, { newPassword: password + 'x' });
check('link is single use', reuse.status === 404 || reuse.status === 400, reuse.status);

const me = await api.get('/api/v1/me');
check('signed in after setting the password', me.status() === 200, (await me.json().catch(() => ({})))?.user?.email);

// enrol TOTP
const enrol = await post('/api/v1/me/mfa/totp', {});
const secret = enrol.body?.secret;
check('TOTP enrolment started (QR present)', enrol.status === 200 && secret && enrol.body?.qrSvg?.includes('<svg'), enrol.status);
const confirmR = await api.put('/api/v1/me/mfa/totp', { data: { code: totp(secret) }, headers: { 'X-XSRF-TOKEN': (await xsrf()) ?? '' } });
const recovery = (await confirmR.json().catch(() => ({})))?.codes ?? [];
check('TOTP confirmed, 10 recovery codes', confirmR.status() === 200 && recovery.length === 10, recovery.length);

// sign out and in again with password + TOTP
await post('/api/v1/auth/logout', {});
await api.get('/api/v1/auth/config');
const step1 = await post('/api/v1/auth/login', { email, password });
check('password step asks for MFA', step1.body?.status === 'MFA_REQUIRED', step1.body?.status ?? step1.body?.detail);
await new Promise((r) => setTimeout(r, 31000)); // next TOTP step, the enrolment code was consumed (replay protection)
const step2 = await post('/api/v1/auth/login/mfa', { mfaToken: step1.body?.mfaToken, code: totp(secret) });
check('TOTP step signs in', step2.body?.status === 'AUTHENTICATED', step2.body?.status ?? step2.body?.detail);

// a recovery code works once
await post('/api/v1/auth/logout', {});
await api.get('/api/v1/auth/config');
const s1 = await post('/api/v1/auth/login', { email, password });
const s2 = await post('/api/v1/auth/login/mfa', { mfaToken: s1.body?.mfaToken, recoveryCode: recovery[0] });
check('recovery code signs in', s2.body?.status === 'AUTHENTICATED', s2.body?.status ?? s2.body?.detail);

const sec = await api.get('/api/v1/me/security');
const secBody = await sec.json().catch(() => ({}));
check('9 recovery codes left', secBody.recoveryCodesRemaining === 9, secBody.recoveryCodesRemaining);

// admin view and audit
const users = await adminCall('GET', `/api/v1/admin/users?q=${encodeURIComponent(email)}`);
const detail = users.body?.content?.[0];
check('admin sees LOCAL + MFA', detail?.authMode === 'LOCAL' && detail?.mfaEnabled === true, `${detail?.authMode} mfa=${detail?.mfaEnabled}`);
const audit = await adminCall('GET', `/api/v1/admin/audit?entityId=${detail?.id}&size=50`);
const ops = new Set((audit.body?.content ?? []).map((e) => e.operation));
check('audit has invite, reset, MFA, recovery', ['USER_INVITE', 'PASSWORD_RESET_COMPLETE', 'MFA_ENABLE', 'RECOVERY_CODE_USED'].every((o) => ops.has(o)), [...ops].join(','));

console.log(JSON.stringify(results, null, 1));
await browser.close();
process.exit(results.every((r) => r.ok) ? 0 : 1);
