// Visual review helper for the Relay screens: signs in through Keycloak and screenshots the main
// routes in light and dark (and optional extra states). Output: e2e/screenshots/relay/<name>-<theme>.png
//
//   SMOKE_USER=admin@example.com SMOKE_PASSWORD=admin node e2e/relay-shots.mjs
//   ROUTES=/home,/runs THEMES=dark node e2e/relay-shots.mjs
import { chromium } from 'playwright';
import fs from 'node:fs';

const base = process.env.BASE_URL || 'http://localhost:4200';
const user = process.env.SMOKE_USER || 'admin@example.com';
const password = process.env.SMOKE_PASSWORD || 'admin';
const out = process.env.OUT_DIR || 'e2e/screenshots/relay';
const themes = (process.env.THEMES || 'light,dark').split(',');
const width = Number(process.env.WIDTH || 1440);
const height = Number(process.env.HEIGHT || 900);
const suffix = process.env.SUFFIX || '';

const DEFAULT_ROUTES = ['/home', '/inbox', '/projects', '/runs', '/ci'];
const routes = (process.env.ROUTES || DEFAULT_ROUTES.join(','))
  .split(',')
  .filter(Boolean)
  .map((r) => (r.startsWith('/') ? r : '/' + r));

fs.mkdirSync(out, { recursive: true });
const browser = await chromium.launch({ channel: 'msedge', headless: true });
const context = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: 1 });
const page = await context.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push(String(e).slice(0, 300)));
page.on('console', (m) => {
  if (m.type() === 'error' && !/api\/v1|Failed to load resource|EventSource|net::/.test(m.text()))
    errors.push(m.text().slice(0, 300));
});

// SHOTS_PUBLIC=1: screenshots of public routes (login, set-password) without signing in.
const signIn = !process.env.SHOTS_PUBLIC;
if (signIn) {
await page.goto(base + '/oauth2/authorization/oidc?returnTo=/home', { waitUntil: 'domcontentloaded' });
await page.waitForSelector('#username', { timeout: 20000 });
await page.fill('#username', user);
await page.fill('#password', password);
await page.click('#kc-login');
await page.waitForURL((u) => u.toString().startsWith(base) && !u.pathname.startsWith('/login'), {
  timeout: 20000,
});
}

// MOCK_GATE=1: one open gate (for reviewing the gate panel when no run is waiting).
if (process.env.MOCK_GATE) {
  const gate = {
    id: '99999999-0000-0000-0000-000000000001',
    runId: '99999999-0000-0000-0000-0000000000aa',
    projectId: '7a0ad29b-644a-40c9-80ef-bb18ae7b5de0',
    projectKey: 'DEMO',
    workflowName: 'Issue to reviewed MR',
    issueKey: 'DEMO-42',
    nodeId: 'spec-gate',
    question: 'Approve the specification for DEMO-42 (checkout retries)?',
    options: ['approve', 'revise', 'reject'],
    context: {
      summary:
        'The agent wrote a specification with 6 requirements and 3 open questions. Retries use exponential backoff capped at 30 s; the idempotency key is the order id.',
      confidence: 0.72,
      artifacts: [
        {
          id: '99999999-0000-0000-0000-0000000000a1',
          nodeId: 'spec',
          name: 'spec.md',
          kind: 'SPEC',
          mediaType: 'text/markdown',
          sizeBytes: 2400,
        },
      ],
      mergeRequestUrl: null,
    },
    status: 'OPEN',
    createdAt: new Date(Date.now() - 2.5 * 3600_000).toISOString(),
    approvers: {
      projectRoles: ['approver', 'owner'],
      users: [],
      accountable: 'approver',
      forbidSelfApproval: true,
    },
    checklist: [
      { id: 'scope', text: 'The scope matches the Jira issue', required: true },
      { id: 'tests', text: 'Acceptance criteria are testable', required: true },
      { id: 'docs', text: 'Docs impact is noted', required: false },
    ],
    startedBy: { kind: 'USER', displayName: 'Dev Developer' },
    canDecide: true,
  };
  await context.route('**/api/v1/approvals?*', (route) =>
    route.request().url().includes('status=OPEN')
      ? route.fulfill({ json: [gate] })
      : route.fallback(),
  );
  await context.route('**/api/v1/runs/*/artifacts/*', (route) =>
    route.fulfill({
      json: {
        ...gate.context.artifacts[0],
        content:
          '# Checkout retries\n\n## Requirements\n\n1. Retry failed payment captures up to 5 times.\n2. Use exponential backoff, capped at 30 s.\n3. The order id is the idempotency key.\n\n## Open questions\n\n- Should partial captures be retried?\n',
      },
    }),
  );
}

const name = (r) => r.replace(/[\/?=&]+/g, '_').replace(/^_|_$/g, '') || 'root';

for (const theme of themes) {
  // The user's preference is SYSTEM: the OS colour scheme decides (as for real users).
  await page.emulateMedia({ colorScheme: theme === 'dark' ? 'dark' : 'light' });
  for (const r of routes) {
    const [path, action] = r.split('#');
    await page.goto(base + path, { waitUntil: 'domcontentloaded' });
    // The theme is forced after load; without this, CSS transitions are caught half-way.
    await page
      .addStyleTag({ content: '*,*::before,*::after{transition:none!important}' })
      .catch(() => undefined);
    await page.waitForTimeout(Number(process.env.WAIT || 2200));
    if (action === 'panel') {
      await page
        .locator('bo-turn-card button.open, .row')
        .first()
        .click()
        .catch(() => undefined);
      await page.waitForTimeout(1200);
    } else if (action === 'palette') {
      await page.keyboard.press('Control+k');
      await page.keyboard.type(process.env.PALETTE_QUERY || 'ru');
      await page.waitForTimeout(900);
    } else if (action === 'shortcuts') {
      await page.keyboard.press('Shift+?');
      await page.waitForTimeout(500);
    } else if (action === 'rail') {
      await page.hover('app-rail');
      await page.waitForTimeout(500);
    } else if (action === 'first') {
      await page
        .locator(process.env.FIRST || 'a[href^="/runs/"]')
        .first()
        .click()
        .catch(() => undefined);
      await page.waitForTimeout(2200);
    }
    await page.waitForTimeout(150);
    if (process.env.EVAL)
      console.log('eval', JSON.stringify(await page.evaluate(process.env.EVAL)));
    const file = `${out}/${name(path)}${action ? '-' + action : ''}${suffix}-${theme}.png`;
    await page.screenshot({ path: file, fullPage: process.env.FULL === '1' });
    console.log('shot', file, page.url());
  }
}
console.log('ERRORS', errors.length);
for (const e of errors.slice(0, 20)) console.log(' -', e);
await browser.close();
