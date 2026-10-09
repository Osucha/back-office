// Browser smoke test: signs in, visits every route (administration, account, project members tab,
// designer) and fails on JavaScript errors (missing providers, template errors, unhandled
// rejections). API failures are tolerated and collected.
//
// Sign-in:
//   - backend up (GET /api/v1/auth/config answers): real OIDC login starting from /login through
//     Keycloak (form #username / #password / #kc-login) with SMOKE_USER / SMOKE_PASSWORD
//     (default admin@example.com / admin);
//   - backend down: the identity is mocked in the browser (`GET /api/v1/me` returns an
//     administrator holding every permission) so every page still renders.
//
//   npm run e2e:smoke                      # against http://localhost:4200 (ng serve)
//   BASE_URL=http://host:8080 SMOKE_USER=ann@example.com SMOKE_PASSWORD=secret npm run e2e:smoke
//   SMOKE_MOCK=1 npm run e2e:smoke          # force the mocked identity
import { chromium } from 'playwright';
import { AxeBuilder } from '@axe-core/playwright';
import fs from 'node:fs';
import { MOCK_DENIED_JOB, MOCK_IDS, installGovernanceMocks } from './governance-mocks.mjs';

const base = process.env.BASE_URL || 'http://localhost:4200';
const user = process.env.SMOKE_USER || 'admin@example.com';
const password = process.env.SMOKE_PASSWORD || 'admin';
const PLACEHOLDER_ID = '00000000-0000-0000-0000-000000000000';

const routes = [
  '/home',
  '/inbox',
  '/my-work',
  '/projects',
  '/runs',
  '/ci',
  '/library/templates',
  '/library/interfaces',
  '/library/skills',
  '/library/workflows',
  '/models',
  '/evaluation/suites',
  '/evaluation/benchmarks',
  '/costs',
  '/settings',
  '/account',
  '/account?section=security',
  '/admin/users',
  '/admin/groups',
  '/admin/groups/new',
  '/admin/roles',
  '/admin/roles/new',
  '/admin/grants',
  '/admin/settings',
  '/admin/settings?section=budget',
  '/admin/audit',
  // iteration 5: fleet, operations, insights, IDE (mocked when the backend answers 404)
  '/insights',
  '/admin/fleet/pools',
  '/admin/fleet/pools/new',
  '/admin/fleet/pools/' + MOCK_IDS.pool,
  '/admin/fleet/runners',
  '/admin/fleet/jobs',
  '/admin/fleet/jobs?job=' + MOCK_DENIED_JOB,
  '/admin/operations',
  '/account?section=ide',
  // the user manual (docs/manual served at /manual/)
  '/help',
  '/help/integrations-gitlab-ci',
];

/** Every permission of the catalogue in docs/architecture/08-identity-and-access.md. */
const ALL_PERMISSIONS = [
  'admin.users.view',
  'admin.users.manage',
  'admin.roles.manage',
  'admin.audit.view',
  'admin.projects.all',
  'admin.settings.manage',
  'tokens.personal',
  'projects.create',
  'integrations.view',
  'library.view',
  'library.manage',
  'models.view',
  'models.manage',
  'evaluation.view',
  'evaluation.run',
  'usage.view',
  'fleet.view',
  'fleet.manage',
];
const MOCK_ME = {
  user: {
    id: '11111111-1111-1111-1111-111111111111',
    email: user,
    displayName: 'Smoke Admin',
    status: 'ACTIVE',
  },
  platformPermissions: ALL_PERMISSIONS,
  allProjects: true,
  projects: [],
  groups: [],
  preferences: { theme: 'SYSTEM', pageSize: 20 },
  idleTimeoutSeconds: 1800,
  absoluteExpiresAt: new Date(Date.now() + 12 * 3600_000).toISOString(),
  authenticatedAt: new Date().toISOString(),
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
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await context.newPage();
// Iteration-5 endpoints: the real backend first, a mock where it answers 404 (governance-mocks.mjs).
const gov = await installGovernanceMocks(context);

/** JS-level problems fail the run; API problems are reported only. */
const jsProblems = [];
const apiProblems = [];
const isApiNoise = (text) =>
  /HttpErrorResponse|status(Text)?: ?0|Http failure|ERR_CONNECTION_REFUSED|Failed to load resource|net::ERR|\/api\/v1\/|EventSource/.test(
    text,
  );
page.on('console', (m) => {
  if (m.type() !== 'error' && m.type() !== 'warning') return;
  const text = m.text().slice(0, 400);
  (isApiNoise(text) ? apiProblems : jsProblems).push({ where: page.url(), type: m.type(), text });
});
page.on('pageerror', (e) => {
  const text = String(e).slice(0, 400);
  (isApiNoise(text) ? apiProblems : jsProblems).push({
    where: page.url(),
    type: 'pageerror',
    text,
  });
});
page.on('requestfailed', (r) =>
  apiProblems.push({
    where: page.url(),
    type: 'requestfailed',
    text: r.failure()?.errorText + ' ' + r.url(),
  }),
);
page.on('response', (r) => {
  if (r.status() >= 400)
    apiProblems.push({ where: page.url(), type: 'http', text: r.status() + ' ' + r.url() });
});

fs.mkdirSync('shots', { recursive: true });
const report = [];

// ---- public pages (login with the available methods, an invalid set-password link), with axe
await page.goto(base + '/login?error=not-invited', { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(1200);
report.push({
  route: '/login',
  oidcButton: await page.getByRole('button', { name: /Continue with|Sign in with/ }).count(),
  passwordForm: await page.getByLabel('Work e-mail').count(),
  errorShown: (await page.getByText('has not been invited').count()) > 0,
});
await page.screenshot({ path: 'shots/_login.png' });
await page.goto(base + '/set-password/not-a-valid-token', { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(1500);
report.push({
  route: '/set-password/<invalid>',
  invalidState: (await page.getByText('This link does not work any more').count()) > 0,
});
if (!(await page.getByText('This link does not work any more').count()) && (await backendUp()))
  jsProblems.push({ where: '/set-password', type: 'ui', text: 'invalid link state not shown' });
await page.screenshot({ path: 'shots/_set_password_invalid.png' });

// ---- sign in
let mode = 'mock';
if (!process.env.SMOKE_MOCK && (await backendUp())) {
  mode = 'oidc';
  try {
    await page.goto(base + '/login', { waitUntil: 'domcontentloaded' });
    await page.getByRole('button', { name: /Continue with|Sign in with/ }).click();
    await page.waitForSelector('#username', { timeout: 20000 });
    await page.fill('#username', user);
    await page.fill('#password', password);
    await page.click('#kc-login');
    await page.waitForURL(
      (u) => u.toString().startsWith(base) && !u.pathname.startsWith('/login'),
      {
        timeout: 20000,
      },
    );
    report.push({ login: 'oidc', landedOn: page.url() });
  } catch (e) {
    jsProblems.push({
      where: page.url(),
      type: 'login',
      text: 'OIDC login failed: ' + String(e).slice(0, 300),
    });
    await page.screenshot({ path: 'shots/_login_failed.png' });
  }
}
if (mode === 'mock') {
  await context.route('**/api/v1/me', (route) =>
    route.request().method() === 'GET' ? route.fulfill({ json: MOCK_ME }) : route.fallback(),
  );
  await context.route('**/api/v1/auth/config', (route) =>
    route.fulfill({
      json: {
        loginUrl: '/oauth2/authorization/oidc',
        providerName: 'Keycloak',
        registration: 'INVITE_ONLY',
      },
    }),
  );
  report.push({ login: 'mocked identity (backend not reachable)' });
}

/**
 * Accessibility: axe (WCAG 2.2 A/AA) on the Relay routes; serious and critical violations fail the
 * run. Other routes are still checked and reported (they are redesigned separately).
 */
const RELAY_ROUTES = [
  /^\/home/,
  /^\/inbox/,
  /^\/projects/,
  /^\/runs/,
  /^\/ci/,
  /^\/workflows\//,
  /^\/login/,
  /^\/set-password/,
  /^\/change-password/,
  /^\/setup-mfa/,
  /^\/account/,
  /^\/admin/,
  /^\/library/,
  /^\/models/,
  /^\/evaluation/,
  /^\/costs/,
  /^\/settings/,
  /^\/insights/,
];
const a11y = [];
async function axe(where) {
  if (process.env.SMOKE_AXE === '0') return;
  const path = new URL(page.url()).pathname;
  // A notification that is still sliding in (opacity < 1) reads as low contrast: let it settle.
  // (API failures of the dev backend raise error toasts; axe measures them mid-animation.)
  await page.waitForTimeout(400);
  if (await page.locator('.ant-notification-notice').count()) await page.waitForTimeout(1500);
  try {
    const result = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
      .analyze();
    const relay = RELAY_ROUTES.some((re) => re.test(path));
    for (const v of result.violations) {
      if (v.impact !== 'serious' && v.impact !== 'critical') continue;
      const entry = {
        where: where + ' (' + path + ')',
        rule: v.id,
        impact: v.impact,
        nodes: v.nodes.length,
        sample: v.nodes
          .slice(0, 2)
          .map((n) => n.target.join(' '))
          .join(' | '),
      };
      a11y.push({ ...entry, relay });
      if (relay)
        jsProblems.push({
          where: entry.where,
          type: 'a11y',
          text: v.id + ' (' + v.impact + '): ' + entry.sample,
        });
    }
  } catch (e) {
    apiProblems.push({ where, type: 'axe', text: String(e).slice(0, 200) });
  }
}

async function visit(r) {
  await page
    .goto(base + r, { waitUntil: 'domcontentloaded', timeout: 30000 })
    .catch((e) => jsProblems.push({ where: r, type: 'nav', text: String(e) }));
  await page.waitForTimeout(1500);
  const path = new URL(page.url()).pathname;
  if (path === '/login')
    jsProblems.push({ where: r, type: 'auth', text: 'redirected to /login (not signed in)' });
  const title = await page.title();
  const h1 = await page
    .locator('h1, h2, .ant-page-header-heading-title, nz-page-header, .ant-result-title')
    .first()
    .textContent()
    .catch(() => '');
  const bodyLen = (await page.locator('body').innerText()).length;
  const shot = r.replace(/[\/?=&]/g, '_').replace(/^_/, '');
  await page.screenshot({ path: 'shots/' + shot + '.png', fullPage: false });
  await axe(r);
  report.push({ route: r, title, heading: (h1 || '').trim().slice(0, 60), bodyChars: bodyLen });
}
for (const r of routes) await visit(r);

// Axe on the public pages, signed out, in a separate context (the main page is signed in now).
{
  const anon = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  for (const r of ['/login', '/set-password/not-a-valid-token']) {
    await anon.goto(base + r, { waitUntil: 'domcontentloaded' });
    await anon.waitForTimeout(1500);
    if (process.env.SMOKE_AXE === '0') continue;
    const result = await new AxeBuilder({ page: anon })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
      .analyze()
      .catch(() => ({ violations: [] }));
    for (const v of result.violations) {
      if (v.impact !== 'serious' && v.impact !== 'critical') continue;
      const sample = v.nodes
        .slice(0, 2)
        .map((n) => n.target.join(' '))
        .join(' | ');
      a11y.push({
        where: r,
        rule: v.id,
        impact: v.impact,
        nodes: v.nodes.length,
        sample,
        relay: true,
      });
      jsProblems.push({ where: r, type: 'a11y', text: v.id + ' (' + v.impact + '): ' + sample });
    }
  }
  await anon.close();
}

// Menu: Administration visible for the admin.
report.push({ adminMenu: await page.getByText('Administration').count() });

/** Opens the designer, loads the bundled template and counts rendered nodes/edges. */
async function visitDesigner(projectId) {
  await visit('/projects/' + projectId + '/workflows/new');
  await page
    .getByRole('button', { name: /Template/ })
    .first()
    .click()
    .catch(() =>
      jsProblems.push({ where: 'designer', type: 'ui', text: 'Template button missing' }),
    );
  await page
    .getByText('Issue to reviewed MR')
    .first()
    .click()
    .catch(() =>
      jsProblems.push({ where: 'designer', type: 'ui', text: 'template entry missing' }),
    );
  await page.waitForTimeout(1500);
  const nodes = await page.locator('.wf-node').count();
  const edges = await page.locator('.wf-edge').count();
  // Open a HUMAN_GATE node: the approvers/checklist editor must render. The designer opens at a
  // readable zoom from START, so fit everything first to have the gate on screen.
  await page
    .getByRole('button', { name: 'Fit everything' })
    .click()
    .catch(() => undefined);
  await page.waitForTimeout(500);
  await page
    .locator('.wf-node[aria-label*="gate" i], .wf-node[aria-label*="approve" i]')
    .first()
    .click()
    .catch(() => undefined);
  await page.waitForTimeout(500);
  const panel = await page.locator('app-node-property-panel').count();
  const gateEditor = await page.locator('app-gate-rules-editor').count();
  report.push({ route: 'designer', nodes, edges, propertyPanel: panel, gateEditor });
  if (nodes < 10 || edges < 10) {
    jsProblems.push({
      where: 'designer',
      type: 'ui',
      text: 'canvas rendered ' + nodes + ' nodes / ' + edges + ' edges',
    });
  }
  await page.screenshot({ path: 'shots/_designer_template.png' });
  await axe('designer');
}

// Library drawer opens without a backend (client-side state only).
await page.goto(base + '/library/templates', { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(800);
await page
  .getByRole('button', { name: /New template/ })
  .first()
  .click()
  .catch(() =>
    jsProblems.push({
      where: '/library/templates',
      type: 'ui',
      text: 'New template button missing',
    }),
  );
await page.waitForTimeout(800);
report.push({
  route: '/library/templates#editor',
  editors: await page.locator('.ant-drawer-open, bo-side-panel aside.panel').count(),
});
if (!(await page.locator('.ant-drawer-open, bo-side-panel aside.panel').count()))
  jsProblems.push({
    where: '/library/templates',
    type: 'ui',
    text: 'template editor did not open',
  });
await page.screenshot({ path: 'shots/_template_drawer.png' });

// Invite drawer on the users page.
await page.goto(base + '/admin/users', { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(800);
await page
  .getByRole('button', { name: 'Invite user', exact: true })
  .first()
  .click()
  .catch(() =>
    jsProblems.push({ where: '/admin/users', type: 'ui', text: 'Invite user button missing' }),
  );
await page.waitForTimeout(800);
report.push({
  route: '/admin/users#invite',
  invitePanel: await page.locator('bo-side-panel.invite-panel aside.panel').count(),
});
if (!(await page.locator('bo-side-panel.invite-panel aside.panel').count()))
  jsProblems.push({ where: '/admin/users', type: 'ui', text: 'invite panel did not open' });
await page.screenshot({ path: 'shots/_invite_drawer.png' });

// First user detail when the backend lists users.
const firstUser = page.locator('tr.clickable-row').first();
if (mode === 'oidc' && (await firstUser.count())) {
  await page.keyboard.press('Escape');
  await firstUser.click().catch(() => undefined);
  await page.waitForTimeout(1500);
  report.push({ route: page.url(), bodyChars: (await page.locator('body').innerText()).length });
  await page.screenshot({ path: 'shots/_user_detail.png' });
}

// Project detail tabs (incl. Members) + designer.
await page.goto(base + '/projects', { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(1200);
const firstProject = page.locator("main tr.clickable-row, main a[href^='/projects/']").first();
let projectId = PLACEHOLDER_ID;
if (await firstProject.count()) {
  await firstProject.click();
  await page.mouse.move(800, 500);
  await page.waitForTimeout(1500);
  const m = page.url().match(/\/projects\/([0-9a-f-]{36})/i);
  if (m) projectId = m[1];
} else {
  report.push({ note: 'no project on /projects (backend down?) - using a placeholder id' });
  await page.goto(base + '/projects/' + projectId, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1500);
}
const tabs = page.locator('.ant-tabs-tab');
const n = await tabs.count();
report.push({ route: page.url(), tabs: n });
for (let i = 0; i < n; i++) {
  const name = (await tabs.nth(i).innerText()).trim();
  await tabs.nth(i).click();
  await page.waitForTimeout(800);
  const active = await page
    .locator('.ant-tabs-tabpane-active')
    .innerText()
    .catch(() => '');
  report.push({
    tab: name,
    activeChars: active.length,
    sample: active.slice(0, 80).replace(/\s+/g, ' '),
  });
  await page.screenshot({ path: 'shots/_project_tab_' + i + '.png' });
}
await visit('/projects/' + projectId + '?tab=members');
gov.setProjects([{ id: projectId, key: 'DEMO' }]);
await visit('/projects/' + projectId + '?tab=sandbox');
await visit('/projects/' + projectId + '?tab=budget');

// Add runner: registration token shown once with the chart values.
await page.goto(base + '/admin/fleet/pools?addRunner=' + MOCK_IDS.pool, {
  waitUntil: 'domcontentloaded',
});
await page.waitForTimeout(1200);
await page
  .locator('[data-testid=create-registration-token]')
  .click({ timeout: 10000 })
  .catch(() => undefined);
await page
  .getByText('bo_rrt_')
  .first()
  .waitFor({ timeout: 8000 })
  .catch(() => undefined);
const tokenShown = await page.getByText('bo_rrt_').count();
report.push({ route: '/admin/fleet/pools#add-runner', tokenShown });
if (!tokenShown)
  jsProblems.push({
    where: '/admin/fleet/pools',
    type: 'ui',
    text: 'registration token not shown',
  });
await axe('add runner');

await visitDesigner(projectId);

// Suite detail when available.
await page.goto(base + '/evaluation/suites', { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(800);
const suite = page.locator("a[href^='/evaluation/suites/']").first();
if (await suite.count()) {
  await suite.click();
  await page.waitForTimeout(1200);
  report.push({ route: page.url(), bodyChars: (await page.locator('body').innerText()).length });
  await page.screenshot({ path: 'shots/_suite_detail.png' });
}

// Run and CI execution details (first rows of the lists), with axe.
for (const [list, name] of [
  ['/runs', 'run detail'],
  ['/ci', 'ci detail'],
]) {
  await page.goto(base + list, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1500);
  const row = page.locator('tr.clickable-row').first();
  if (await row.count()) {
    await row.click();
    await page.waitForTimeout(1800);
    await page.screenshot({ path: 'shots/_' + name.replace(' ', '_') + '.png' });
    report.push({
      route: page.url(),
      detail: name,
      bodyChars: (await page.locator('body').innerText()).length,
    });
    await axe(name);
    if (name === 'run detail') {
      await page
        .getByRole('tab', { name: 'Provenance' })
        .click()
        .catch(() => undefined);
      await page.waitForTimeout(1500);
      const facts = await page.locator('app-provenance-panel dl.facts').count();
      report.push({ route: page.url() + '#provenance', provenanceFacts: facts });
      await axe('run provenance');
    }
  }
}
// Command palette and shortcuts overlay open and close with the keyboard.
await page.goto(base + '/home', { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(1200);
await page.keyboard.press('Control+k');
await page
  .locator('bo-command-palette [role=combobox]')
  .waitFor({ timeout: 5000 })
  .catch(() => undefined);
const paletteOpen = await page.locator('bo-command-palette [role=combobox]').count();
await axe('command palette');
await page.keyboard.press('Escape');
await page.keyboard.press('Shift+?');
await page.waitForTimeout(500);
const overlayOpen = await page.locator('bo-shortcuts-overlay [role=dialog]').count();
await page.keyboard.press('Escape');
report.push({ palette: paletteOpen, shortcutsOverlay: overlayOpen });
if (!paletteOpen)
  jsProblems.push({ where: '/home', type: 'ui', text: 'command palette did not open with Ctrl+K' });

// In-app help: F1 opens the help panel of the current page (one manual section).
await page.goto(base + '/settings', { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(1200);
await page.keyboard.press('F1');
await page
  .locator('bo-help-panel [role=dialog]')
  .waitFor({ timeout: 5000 })
  .catch(() => undefined);
await page.waitForTimeout(800);
const helpOpen = await page.locator('bo-help-panel [role=dialog]').count();
const helpTitle = helpOpen
  ? await page.locator('bo-help-panel [role=dialog] h2').first().innerText()
  : null;
await axe('help panel');
await page.screenshot({ path: 'shots/_help_panel.png' });
await page.keyboard.press('Escape');
report.push({ helpPanel: helpOpen, helpTitle });
if (!helpOpen)
  jsProblems.push({ where: '/settings', type: 'ui', text: 'help panel did not open with F1' });

fs.writeFileSync(
  'report.json',
  JSON.stringify({ mode, report, jsProblems, apiProblems, a11y }, null, 2),
);
console.log(JSON.stringify(report, null, 1));
console.log('SIGN-IN MODE:', mode);
console.log('GOVERNANCE MOCKS USED:', [...gov.used].sort().join(', ') || 'none');
console.log('API PROBLEMS (tolerated):', apiProblems.length);
for (const p of apiProblems.slice(0, 10)) console.log('  -', p.type, '@', p.where, '::', p.text);
console.log(
  'A11Y (serious/critical):',
  a11y.length,
  '(on Relay routes:',
  a11y.filter((a) => a.relay).length + ')',
);
for (const a of a11y.slice(0, 30))
  console.log('  -', a.relay ? 'RELAY' : 'other', a.impact, a.rule, '@', a.where, '::', a.sample);
console.log('JS PROBLEMS:', jsProblems.length);
for (const p of jsProblems.slice(0, 40)) console.log('  -', p.type, '@', p.where, '::', p.text);
await browser.close();
process.exit(jsProblems.length ? 1 : 0);
