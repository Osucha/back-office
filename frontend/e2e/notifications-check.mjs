// Desktop notifications against the running UI (ADR 0032, docs/manual/account.md#notifications).
// Signs in through Keycloak with the development test user when the backend answers (otherwise the
// identity is mocked like e2e/smoke.mjs), grants the 'notifications' permission, stubs
// window.Notification (records every construction) and counts Web Audio oscillators, then:
//   1. turns notifications on in My account -> Notifications (the permission request runs from the click),
//   2. previews a sound (oscillators scheduled),
//   3. sends a test notification (a Notification constructed with the "Test:" title),
//   4. turns "Only when the tab is not in view" off and makes a gate appear: the approvals list is
//      mocked (empty, then one decidable gate) and the SSE stream delivers RunWaitingForHuman, so the
//      inbox reloads and the notifier must construct a "Your turn" notification with the gate's tag.
// Usage: node e2e/notifications-check.mjs   (BASE_URL, SMOKE_USER, SMOKE_PASSWORD, SMOKE_MOCK=1)
import { chromium } from 'playwright';

const base = process.env.BASE_URL ?? 'http://localhost:4200';
const user = process.env.SMOKE_USER ?? 'admin@example.com';
const password = process.env.SMOKE_PASSWORD ?? 'admin';
const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok });
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`);
};

async function backendUp() {
  try {
    const res = await fetch(base + '/api/v1/auth/config', { signal: AbortSignal.timeout(5000) });
    return res.ok;
  } catch {
    return false;
  }
}

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const context = await browser.newContext({
  viewport: { width: 1440, height: 900 },
  locale: 'en-US',
});
await context.grantPermissions(['notifications'], { origin: base });
await context.addInitScript(() => {
  window.__notes = [];
  class FakeNotification {
    constructor(title, options = {}) {
      this.title = title;
      Object.assign(this, options);
      window.__notes.push({ title, ...options });
    }
    close() {}
  }
  FakeNotification.permission = 'granted';
  FakeNotification.requestPermission = () => Promise.resolve('granted');
  window.Notification = FakeNotification;
  window.__osc = 0;
  const Ctx = window.AudioContext;
  if (Ctx) {
    const create = Ctx.prototype.createOscillator;
    Ctx.prototype.createOscillator = function () {
      window.__osc++;
      return create.call(this);
    };
  }
});

const page = await context.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));

// ---- sign in
if (!process.env.SMOKE_MOCK && (await backendUp())) {
  await page.goto(base + '/oauth2/authorization/oidc?returnTo=/home');
  await page.fill('#username', user);
  await page.fill('#password', password);
  await Promise.all([
    page.waitForURL((u) => u.origin === base, { timeout: 30000 }),
    page.click('#kc-login'),
  ]);
  check('signed in through Keycloak', !page.url().includes('/login'), page.url());
} else {
  await context.route('**/api/v1/me', (route) =>
    route.request().method() === 'GET'
      ? route.fulfill({
          json: {
            user: {
              id: '11111111-1111-1111-1111-111111111111',
              email: user,
              displayName: 'E2E Admin',
              status: 'ACTIVE',
            },
            platformPermissions: ['admin.settings.manage', 'usage.view'],
            allProjects: true,
            projects: [],
            groups: [],
            preferences: { theme: 'SYSTEM', pageSize: 20 },
            idleTimeoutSeconds: 1800,
            absoluteExpiresAt: new Date(Date.now() + 12 * 3600_000).toISOString(),
            authenticatedAt: new Date().toISOString(),
          },
        })
      : route.fallback(),
  );
  check('identity mocked (backend not reachable)', true);
}

// ---- a gate that appears later, and an SSE stream we control
const GATE = {
  id: '22222222-2222-2222-2222-222222222222',
  runId: '33333333-3333-3333-3333-333333333333',
  projectId: '44444444-4444-4444-4444-444444444444',
  projectKey: 'DEMO',
  workflowName: 'Feature',
  issueKey: 'DEMO-7',
  nodeId: 'review',
  question: 'Approve the plan?',
  status: 'OPEN',
  canDecide: true,
  createdAt: new Date().toISOString(),
};
let gateOpen = false;
await context.route('**/api/v1/approvals?*', (route) =>
  route.fulfill({ json: gateOpen ? [GATE] : [] }),
);
await context.route('**/api/v1/events*', (route) =>
  route.fulfill({
    status: 200,
    headers: { 'content-type': 'text/event-stream', 'cache-control': 'no-store' },
    body: gateOpen
      ? `id: 9001\nevent: RunWaitingForHuman\ndata: ${JSON.stringify({ id: '9001', type: 'RunWaitingForHuman', occurredAt: new Date().toISOString(), payload: { runId: GATE.runId, projectId: GATE.projectId, approvalId: GATE.id, nodeId: 'review', question: GATE.question } })}\n\n`
      : ': idle\n\n',
  }),
);

// ---- My account -> Notifications
await page.goto(base + '/account?section=notifications');
const master = page.locator('nz-switch#nt-enabled');
await master.waitFor({ timeout: 20000 });
check('notifications section renders', true);
await master.click();
await page.waitForTimeout(800);
const enabled = await page.evaluate(() =>
  Object.keys(localStorage)
    .filter((k) => k.startsWith('bo.notifications.') && k !== 'bo.notifications.claims')
    .map((k) => JSON.parse(localStorage.getItem(k) ?? '{}').enabled)
    .some(Boolean),
);
check('master switch turns notifications on (stored per user)', enabled);
check(
  'permission state shown as allowed',
  (await page.getByText('Your browser allows notifications from Back Office.').count()) > 0,
);

const before = await page.evaluate(() => window.__osc);
await page.getByRole('button', { name: 'Preview the sound: Your turn' }).click();
await page.waitForTimeout(300);
const after = await page.evaluate(() => window.__osc);
check('preview schedules the synthesised sound', after > before, `${after - before} oscillators`);

await page.getByRole('button', { name: 'Send a test notification' }).click();
await page.waitForTimeout(300);
const test = await page.evaluate(() => window.__notes.at(-1));
check(
  'test notification constructed',
  !!test && test.title.startsWith('Test: ') && test.icon === '/favicon.ico' && test.silent === true,
  JSON.stringify(test),
);

// ---- a gate appears while the tab is open
await page.locator('nz-switch#nt-hidden').click();
await page.waitForTimeout(300);
const count = await page.evaluate(() => window.__notes.length);
gateOpen = true;
await page
  .waitForFunction((n) => window.__notes.length > n, count, { timeout: 20000 })
  .catch(() => null);
const turn = await page.evaluate(() => window.__notes.at(-1));
check(
  '"Your turn" notification for the new gate',
  !!turn &&
    turn.title === 'Your turn · DEMO' &&
    turn.tag === `bo-gate-${'22222222-2222-2222-2222-222222222222'}`,
  JSON.stringify(turn),
);
check(
  'body is one line with the issue and the question',
  turn?.body === 'DEMO-7 · Approve the plan?',
);
await page.screenshot({ path: 'e2e/screenshots/notifications-preferences.png', fullPage: false });

check('no page errors', errors.length === 0, errors.slice(0, 3).join(' | '));
await browser.close();
const failed = results.filter((r) => !r.ok).length;
console.log(`${results.length - failed}/${results.length} passed`);
process.exit(failed ? 1 : 0);
