// Screens of connections, Jira intake, notifications, personal connections, the project's system
// connection, the designer's "Act as" and Spec Kit (ADR 0030, 0033, 0035, 0036) against the running stack
// (Polish UI): signs in with the development test user and saves screenshots to
// e2e/screenshots/least-privilege/.
// Usage: node e2e/least-privilege-tour.mjs [outDir]
import { mkdirSync } from 'node:fs';
import { chromium } from 'playwright';

const base = process.env.BASE_URL ?? 'http://localhost:4200';
const out = process.argv[2] ?? 'e2e/screenshots/least-privilege';
mkdirSync(out, { recursive: true });

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'pl-PL' });
const page = await context.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
await page.goto(base + '/oauth2/authorization/oidc?returnTo=/home');
await page.fill('#username', process.env.SMOKE_USER ?? 'admin@example.com');
await page.fill('#password', process.env.SMOKE_PASSWORD ?? 'admin');
await Promise.all([page.waitForURL((u) => u.origin === base, { timeout: 30000 }), page.click('#kc-login')]);
await page.evaluate(() => localStorage.setItem('bo.locale', 'pl'));

/** GET a JSON API path in the signed-in page (session cookie). */
const api = (path) =>
  page.evaluate(async (p) => {
    const r = await fetch(p, { headers: { accept: 'application/json' } });
    return r.ok ? r.json().catch(() => null) : null;
  }, path);

const projects = await api('/api/v1/projects');
const list = Array.isArray(projects) ? projects : (projects?.content ?? []);
const project = list[0]?.id ?? null;

/** A workflow of the project with a Jira write step (for the designer's "Act as" section). */
let jiraWorkflow = null;
if (project) {
  const workflows = (await api(`/api/v1/projects/${project}/workflows`)) ?? [];
  jiraWorkflow =
    (Array.isArray(workflows) ? workflows : []).find((w) =>
      (w.nodes ?? []).some((n) => ['transition-issue', 'notify-jira'].includes(n.task?.templateKey)),
    ) ?? null;
}

async function shot(name, path, before) {
  await page.goto(base + path);
  await page.waitForLoadState('networkidle').catch(() => null);
  await page.waitForTimeout(800);
  if (before) await before();
  await page.screenshot({ path: `${out}/${name}.png`, fullPage: true });
  console.log(`${name}: ${page.url()}`);
}

const screens = [
  ['account', '/account'],
  ['account-connections', '/account?section=connections'],
  ['admin-connections', '/admin/connections'],
  ['platform-settings-features', '/admin/settings'],
];
if (project) {
  screens.push(
    ['project-connections', `/projects/${project}?tab=connections`],
    ['project-settings', `/projects/${project}?tab=settings`],
  );
}
for (const [name, path] of screens) await shot(name, path);

// the add-connection dialog with its guide (platform), and the personal one (scoped token first)
async function openAdd(path, name) {
  await page.goto(base + path);
  await page.waitForLoadState('networkidle').catch(() => null);
  await page.waitForTimeout(800);
  const add = page.getByRole('button', { name: /Dodaj połączenie|Add connection/i }).first();
  if (await add.count()) {
    await add.click();
    await page.waitForTimeout(800);
    await page.screenshot({ path: `${out}/${name}.png`, fullPage: true });
    console.log(`${name}: dialog`);
    // the classic token with its acknowledgement
    const classic = page.getByText(/Klasyczny token API|Classic API token/).first();
    if (await classic.count()) {
      await classic.click();
      await page.waitForTimeout(400);
      await page.screenshot({ path: `${out}/${name}-classic.png`, fullPage: true });
    }
    await page.keyboard.press('Escape');
  }
}
await openAdd('/admin/connections', 'add-connection');
await openAdd('/account?section=connections', 'add-personal-connection');

// the designer's Jira section with "Act as"
if (project && jiraWorkflow) {
  await shot('designer-act-as', `/workflows/${jiraWorkflow.id}/edit`, async () => {
    await page
      .getByRole('button', { name: /Dopasuj wszystko|Fit everything/i })
      .click()
      .catch(() => undefined);
    await page.waitForTimeout(500);
    const node = jiraWorkflow.nodes.find((n) => ['transition-issue', 'notify-jira'].includes(n.task?.templateKey));
    await page
      .locator(`.wf-node[data-node-id="${node.id}"]`)
      .first()
      .click()
      .catch(() => undefined);
    await page.waitForTimeout(800);
    await page
      .locator('app-node-connection-section')
      .first()
      .scrollIntoViewIfNeeded()
      .catch(() => undefined);
    await page.waitForTimeout(300);
  });
} else {
  console.log('designer-act-as: skipped (no workflow with a Jira write step)');
}
console.log(errors.length ? 'page errors: ' + errors.slice(0, 3).join(' | ') : 'no page errors');
await browser.close();
