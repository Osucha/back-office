// Screenshots of the operations and governance screens (iteration 5) in light and dark, with the
// endpoints of docs/api/openapi.yaml v4.0.0 served by governance-mocks.mjs where the backend does not
// implement them yet. Output: e2e/screenshots/relay/<name>-<theme>.png
//
//   node e2e/governance-shots.mjs                 # all screens, both themes
//   SHOTS=fleet-pools,insights THEMES=dark node e2e/governance-shots.mjs
import { chromium } from 'playwright';
import fs from 'node:fs';
import { MOCK_DENIED_JOB, MOCK_IDS, installGovernanceMocks } from './governance-mocks.mjs';

const base = process.env.BASE_URL || 'http://localhost:4200';
const user = process.env.SMOKE_USER || 'admin@example.com';
const password = process.env.SMOKE_PASSWORD || 'admin';
const out = process.env.OUT_DIR || 'e2e/screenshots/relay';
const themes = (process.env.THEMES || 'light,dark').split(',');
const only = process.env.SHOTS ? new Set(process.env.SHOTS.split(',')) : null;

fs.mkdirSync(out, { recursive: true });
const browser = await chromium.launch({ channel: 'msedge', headless: true });
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const gov = await installGovernanceMocks(context, {
  coreFallbacks: true,
  systemTheme: true,
  forceMocks: process.env.LIVE !== '1',
});
const page = await context.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push(String(e).slice(0, 300)));

// Sign in through Keycloak; the dev backend's callback is slow at times, so wait and retry once.
async function signIn() {
  await page.goto(base + '/oauth2/authorization/oidc?returnTo=/home', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#username', { timeout: 30000 });
  await page.fill('#username', user);
  await page.fill('#password', password);
  await page.click('#kc-login', { noWaitAfter: true });
  await page.waitForURL(
    (u) => u.toString().startsWith(base) && !u.pathname.startsWith('/login') && !u.pathname.startsWith('/oauth2'),
    { timeout: 90000 },
  );
}
try {
  await signIn();
} catch (e) {
  console.log('sign-in retry at', page.url(), String(e).slice(0, 80));
  await signIn();
}

const api = (path) =>
  page.evaluate(async (p) => {
    const r = await fetch('/api/v1' + p, { headers: { Accept: 'application/json' } });
    return r.ok ? r.json() : null;
  }, path);
const projects = (await api('/projects')) ?? [];
console.log('projects', projects.length);
gov.setProjects(projects);
const project = projects[0];
const runs = (await api('/runs?size=20'))?.content ?? [];
const run = runs.find((r) => r.status === 'SUCCEEDED') ?? runs[0];

const click = async (sel) => {
  await page.locator(sel).first().click().catch(() => undefined);
  await page.waitForTimeout(900);
};

/** name, path, optional action after load */
const SHOTS = [
  ['fleet-pools', '/admin/fleet/pools'],
  ['fleet-add-runner', '/admin/fleet/pools?addRunner=' + MOCK_IDS.pool, () => click('[data-testid=create-registration-token]')],
  ['fleet-pool-editor', '/admin/fleet/pools/' + MOCK_IDS.pool, () => page.mouse.wheel(0, 700)],
  ['fleet-runners', '/admin/fleet/runners'],
  ['fleet-job-panel', '/admin/fleet/jobs?job=' + MOCK_DENIED_JOB],
  ['operations', '/admin/operations'],
  ['insights', '/insights'],
  ...(project
    ? [
        ['project-sandbox', `/projects/${project.id}?tab=sandbox`, () => page.mouse.wheel(0, 380)],
        ['project-budget', `/projects/${project.id}?tab=budget`],
      ]
    : []),
  ['home-budget-turn', '/home'],
  ['costs-budget', '/costs'],
  ...(run ? [['run-provenance', `/runs/${run.id}`, () => click('div[role=tab]:has-text("Provenance")').then(() => click('button:has-text("Verify")'))]] : []),
  [
    'account-ide',
    '/account?section=ide',
    async () => {
      await page.locator('app-connect-ide-section').waitFor({ timeout: 5000 }).catch(() => undefined);
      await page.evaluate(() => document.querySelector('#ide')?.scrollIntoView({ block: 'start' }));
      await page.waitForTimeout(600);
    },
  ],
];

for (const theme of themes) {
  await page.emulateMedia({ colorScheme: theme === 'dark' ? 'dark' : 'light' });
  for (const [name, path, action] of SHOTS) {
    if (only && !only.has(name)) continue;
    await page.goto(base + path, { waitUntil: 'domcontentloaded' });
    await page
      .addStyleTag({ content: '*,*::before,*::after{transition:none!important;animation:none!important}' })
      .catch(() => undefined);
    await page.waitForTimeout(Number(process.env.WAIT || 2400));
    if (action) await action();
    await page.waitForTimeout(400);
    const file = `${out}/${name}-${theme}.png`;
    await page.screenshot({ path: file });
    console.log('shot', file, page.url());
  }
}
console.log('MOCKS USED', [...gov.used].sort().join(', '));
console.log('ERRORS', errors.length);
for (const e of errors.slice(0, 20)) console.log(' -', e);
await browser.close();
