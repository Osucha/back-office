// In-app help against the running stack (dev server :4200 + backend :8080 + compose Keycloak):
// signs in through Keycloak with the development test user, opens a manual chapter at an anchor,
// checks that the page stays responsive and that F1 opens contextual help, and saves screenshots.
// Usage: node e2e/help-check.mjs [outDir]
import { chromium } from 'playwright';

const base = process.env.BASE_URL ?? 'http://localhost:4200';
const out = process.argv[2] ?? 'e2e/screenshots/help';
const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok });
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`);
};

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));

await page.goto(base + '/oauth2/authorization/oidc?returnTo=/home');
await page.fill('#username', 'admin@example.com');
await page.fill('#password', 'admin');
await Promise.all([page.waitForURL((u) => u.origin === base, { timeout: 30000 }), page.click('#kc-login')]);
check('signed in', !page.url().includes('/login'), page.url());

const started = Date.now();
await page.goto(base + '/help/integrations-jira#jira-token');
// the anchor is an empty <a id>: wait until it is in the DOM, it is never "visible"
await page.waitForSelector('#jira-token', { state: 'attached', timeout: 15000 }).catch(() => null);
const responsive = await Promise.race([
  page.evaluate(() => document.title).then(() => true),
  new Promise((r) => setTimeout(() => r(false), 5000)),
]);
check('chapter with anchor renders and stays responsive', responsive, `${Date.now() - started} ms`);
const anchorVisible = await page.locator('#jira-token').count();
check('anchor jira-token present', anchorVisible === 1);
const tokenText = await page.getByText('API token', { exact: false }).first().isVisible().catch(() => false);
check('Jira token steps visible', tokenText);
await page.screenshot({ path: `${out}/help-jira-token.png` });

await page.goto(base + '/help');
await page.waitForTimeout(1500);
await page.screenshot({ path: `${out}/help-index.png` });
const search = page.getByRole('searchbox').first();
if (await search.count()) {
  await search.fill('write_repository');
  await page.waitForTimeout(1500);
  await page.screenshot({ path: `${out}/help-search.png` });
  check('search finds git token scope', (await page.getByText('write_repository').count()) > 0);
}

await page.goto(base + '/projects');
await page.waitForTimeout(1500);
await page.keyboard.press('F1');
await page.waitForTimeout(1500);
check('F1 opens the help panel', (await page.getByText(/Open in manual/i).count()) > 0);
await page.screenshot({ path: `${out}/help-panel-projects.png` });

check('no page errors', errors.length === 0, errors.slice(0, 3).join(' | '));
await browser.close();
const failed = results.filter((r) => !r.ok).length;
console.log(`${results.length - failed}/${results.length} passed`);
process.exit(failed ? 1 : 0);
