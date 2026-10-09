// UI-driven check of local accounts (ADR 0021): an OIDC admin invites a LOCAL user through the admin UI,
// the user opens the set-password link, sets a password, signs in on the login page, enrols TOTP through
// the UI (the script computes the codes, RFC 6238), signs out and signs in with password + code. A wrong
// code on the way checks that the UI returns to the password step with the e-mail kept.
//
//   node e2e/local-login-ui-check.mjs            (dev stack: :4200 proxy or BASE_URL, Keycloak admin)
//   SHOTS=1 node e2e/local-login-ui-check.mjs    also writes Relay screenshots (light + dark) of every step
//   INVITE=api ...                              invite through the API instead of the admin UI (debugging)
import { chromium } from 'playwright';
import crypto from 'node:crypto';
import fs from 'node:fs';

const base = process.env.BASE_URL ?? process.env.SMOKE_BASE ?? 'http://localhost:4200';
const adminUser = process.env.SMOKE_USER ?? 'admin@example.com';
const adminPassword = process.env.SMOKE_PASSWORD ?? 'admin';
const shotsDir = 'e2e/screenshots/relay';
const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok: !!ok, detail: String(detail ?? '').slice(0, 200) });
  console.log(ok ? 'ok  ' : 'FAIL', name, detail ? '- ' + String(detail).slice(0, 160) : '');
};

// ---- RFC 6238 TOTP (SHA-1, 30 s, 6 digits)
function base32Decode(s) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = '';
  for (const c of s.replace(/[\s=]+/g, '').toUpperCase()) bits += alphabet.indexOf(c).toString(2).padStart(5, '0');
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
  return ((h.readUInt32BE(o) & 0x7fffffff) % 1_000_000).toString().padStart(6, '0');
}
/** Waits until the current 30 s step has at least `min` seconds left (so a code is not about to expire). */
async function freshStep(min = 8) {
  const left = 30 - (Math.floor(Date.now() / 1000) % 30);
  if (left < min) await new Promise((r) => setTimeout(r, (left + 1) * 1000));
}
/** Waits for the next 30 s step (the previous code was used: replay protection). */
async function nextStep() {
  const left = 30 - (Math.floor(Date.now() / 1000) % 30);
  await new Promise((r) => setTimeout(r, (left + 1) * 1000));
}

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const viewport = { width: 1440, height: 900 };

async function shot(page, name) {
  if (!process.env.SHOTS) return;
  fs.mkdirSync(shotsDir, { recursive: true });
  for (const theme of ['light', 'dark']) {
    await page.emulateMedia({ colorScheme: theme });
    await page.addStyleTag({ content: '*,*::before,*::after{transition:none!important;caret-color:transparent!important}' }).catch(() => undefined);
    await page.waitForTimeout(400);
    await page.screenshot({ path: `${shotsDir}/${name}-${theme}.png` });
  }
  await page.emulateMedia({ colorScheme: 'light' });
}

// ---- 1. admin (OIDC through Keycloak) invites a LOCAL user in the admin UI
const adminCtx = await browser.newContext({ viewport });
const admin = await adminCtx.newPage();
await admin.goto(base + '/oauth2/authorization/oidc?returnTo=/home');
await admin.fill('#username', adminUser);
await admin.fill('#password', adminPassword);
await Promise.all([admin.waitForURL((u) => u.origin === base, { timeout: 30000 }), admin.click('#kc-login')]);
check('admin signed in through Keycloak', new URL(admin.url()).origin === base, admin.url());

const email = `local.ui.${Date.now()}@example.com`;
const password = 'Relay-' + crypto.randomBytes(9).toString('base64url') + '-9';
let setPasswordUrl = null;

if (process.env.INVITE === 'api') {
  setPasswordUrl = await admin.evaluate(async (e) => {
    const xsrf = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1];
    const r = await fetch('/api/v1/admin/users', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': decodeURIComponent(xsrf ?? '') },
      body: JSON.stringify({ email: e, displayName: 'Lena Local', authMode: 'LOCAL', platformRoles: [] }),
    });
    return (await r.json()).setPasswordUrl;
  }, email);
  check('LOCAL user invited (API)', !!setPasswordUrl, setPasswordUrl);
} else {
  await admin.goto(base + '/admin/users?invite=local', { waitUntil: 'domcontentloaded' });
  const panel = admin.locator('bo-side-panel.invite-panel aside.panel');
  await panel.waitFor({ timeout: 15000 });
  await panel.getByLabel(/^E-mail/).fill(email);
  await panel.getByLabel(/^(Display )?name/i).first().fill('Lena Local').catch(() => undefined);
  await panel.getByRole('radio', { name: 'Local', exact: true }).check({ force: true });
  await panel.getByRole('button', { name: 'Invite user', exact: true }).last().click();
  const url = panel.locator('app-one-time-link input.url');
  await url.waitFor({ timeout: 15000 }).catch(() => undefined);
  setPasswordUrl = (await url.inputValue().catch(() => '')).match(/https?:\/\/\S+\/set-password\/[A-Za-z0-9_-]+/)?.[0] ?? null;
  check('LOCAL user invited in the admin UI; the panel shows the set-password link', !!setPasswordUrl, setPasswordUrl);
  if (setPasswordUrl) await shot(admin, 'admin-invite-local');
}
if (!setPasswordUrl) {
  console.log(JSON.stringify(results, null, 1));
  await browser.close();
  process.exit(1);
}
// The link points at the public URL of the UI; open it on the base under test.
const linkPath = new URL(setPasswordUrl).pathname;

// ---- 2. the invited user sets the password from the link (fresh browser: own cookies)
const userCtx = await browser.newContext({ viewport });
const page = await userCtx.newPage();
const jsErrors = [];
page.on('pageerror', (e) => jsErrors.push(String(e).slice(0, 200)));
if (process.env.DEBUG) page.on('response', async (r) => r.url().includes('/api/v1/auth') && console.log('   ', r.status(), r.request().method(), new URL(r.url()).pathname, r.status() >= 400 ? await r.text().catch(() => '') : '', r.status() >= 400 ? r.request().postData() : ''));

await page.goto(base + linkPath, { waitUntil: 'domcontentloaded' });
await page.getByText(email).first().waitFor({ timeout: 15000 });
check('set-password page shows the e-mail of the link', true, email);
await page.getByLabel('New password', { exact: true }).fill('password2026!');
await page.getByText('Not a common password').waitFor();
const commonFlagged = await page.locator('app-password-hints li:not(.ok)', { hasText: 'Not a common password' }).count();
check('decorated common password is flagged live', commonFlagged === 1);
await page.getByLabel('New password', { exact: true }).fill(password);
await page.getByLabel('Repeat the new password').fill(password);
await shot(page, 'set-password');
await page.getByRole('button', { name: 'Set password and sign in' }).click();
await page.waitForURL((u) => u.pathname === '/home', { timeout: 20000 }).catch(() => undefined);
check('password set; signed in and on /home', new URL(page.url()).pathname === '/home', page.url());

// ---- 3. sign out, then sign in with the password on the login page
async function signOut() {
  await page.goto(base + '/account', { waitUntil: 'domcontentloaded' });
  await page.getByRole('button', { name: /Sign out/ }).first().click();
  await page.waitForURL((u) => u.pathname === '/login', { timeout: 15000 });
}
async function passwordStep() {
  await page.getByLabel('Work e-mail').waitFor({ timeout: 15000 });
  await page.getByLabel('Work e-mail').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
}
await signOut();
check('signed out to /login', new URL(page.url()).pathname === '/login');
await passwordStep();
await page.waitForURL((u) => u.pathname !== '/login', { timeout: 20000 }).catch(() => undefined);
check('password sign-in on the login page lands in the app', !new URL(page.url()).pathname.startsWith('/login'), page.url());

// ---- 4. enrol TOTP through the UI (palette target: account security, set up MFA)
await page.goto(base + '/account?section=security&setup=mfa', { waitUntil: 'domcontentloaded' });
const key = page.locator('app-mfa-enrolment code.key');
await key.waitFor({ timeout: 15000 });
const secret = (await key.innerText()).replace(/\s+/g, '');
check('enrolment shows the QR code and the key', (await page.locator('app-mfa-enrolment .qr img').count()) === 1 && secret.length >= 16, secret.length);
await shot(page, 'account-security-enrol');
await freshStep();
await page.getByLabel('Digit 1 of 6').click();
await page.keyboard.type(totp(secret), { delay: 40 });
await page.getByText('Save your recovery codes').first().waitFor({ timeout: 15000 });
const codes = await page.locator('app-recovery-codes .codes li').allInnerTexts();
check('TOTP confirmed; 10 recovery codes shown', codes.length === 10, codes.length);
const downloadPromise = page.waitForEvent('download', { timeout: 5000 }).catch(() => null);
await page.getByRole('button', { name: /Download \.txt/ }).click();
const download = await downloadPromise;
const downloaded = download ? fs.readFileSync(await download.path(), 'utf8') : '';
check('recovery codes download (.txt) lists the codes', download && codes.every((c) => downloaded.includes(c.trim())), download?.suggestedFilename());
await shot(page, 'account-security-codes');
await page.getByRole('checkbox', { name: /I saved these codes/ }).check();
await page.getByRole('button', { name: /Done, I saved them/ }).click();
await page.getByText('authenticator app').first().waitFor({ timeout: 10000 });
await shot(page, 'account-security');
check('security section shows two-factor on', (await page.locator('app-security-section bo-baton', { hasText: 'on' }).count()) > 0);

// ---- 5. sign out; password + wrong code goes back to the password step with the e-mail kept
await signOut();
await passwordStep();
await page.getByLabel('Digit 1 of 6').waitFor({ timeout: 15000 });
await shot(page, 'login-mfa');
await page.getByLabel('Digit 1 of 6').click();
const wrong = String((Number(totp(secret)) + 500_000) % 1_000_000).padStart(6, '0');
await page.keyboard.type(wrong, { delay: 30 });
await page.getByText('That code did not work. Sign in again.').waitFor({ timeout: 15000 }).catch(async (e) => {
  await page.screenshot({ path: 'e2e/_local-login-failure.png' });
  throw e;
});
const kept = await page.getByLabel('Work e-mail').inputValue();
check('a wrong code returns to the password step, e-mail kept', kept === email, kept);

// ---- 6. password + correct code signs in (next step: the enrolment code was consumed)
await nextStep();
await passwordStep();
await page.getByLabel('Digit 1 of 6').waitFor({ timeout: 15000 });
await freshStep();
await page.getByLabel('Digit 1 of 6').click();
await page.keyboard.type(totp(secret), { delay: 30 });
await page.waitForURL((u) => u.pathname === '/home', { timeout: 20000 }).catch(() => undefined);
check('password + TOTP code signs in', new URL(page.url()).pathname === '/home', page.url());

// ---- 7. the login page itself (signed out), for the screenshots
if (process.env.SHOTS) {
  const anon = await (await browser.newContext({ viewport })).newPage();
  await anon.goto(base + '/login', { waitUntil: 'domcontentloaded' });
  await anon.getByLabel('Work e-mail').waitFor();
  await shot(anon, 'login');
  await anon.goto(base + '/set-password/not-a-valid-token', { waitUntil: 'domcontentloaded' });
  await anon.getByText('This link does not work any more').waitFor();
  await shot(anon, 'set-password-invalid');
}

check('no JavaScript errors in the user session', jsErrors.length === 0, jsErrors.join(' | '));
console.log(JSON.stringify(results.filter((r) => !r.ok), null, 1));
console.log(`${results.filter((r) => r.ok).length}/${results.length} checks passed`);
await browser.close();
process.exit(results.every((r) => r.ok) ? 0 : 1);
