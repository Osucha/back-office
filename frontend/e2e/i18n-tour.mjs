// Polish UI tour against the running stack: signs in (development test user), switches to Polish and
// screenshots the main screens into e2e/screenshots/i18n-tour/, listing visible words that look English
// (a reviewer's aid, not a gate: server data such as workflow names stays English by design).
// Usage: node e2e/i18n-tour.mjs [outDir]
import { mkdirSync } from 'node:fs';
import { chromium } from 'playwright';

const base = process.env.BASE_URL ?? 'http://localhost:4200';
const out = process.argv[2] ?? 'e2e/screenshots/i18n-tour';
mkdirSync(out, { recursive: true });

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'pl-PL' });
const page = await context.newPage();
await page.goto(base + '/oauth2/authorization/oidc?returnTo=/home');
await page.fill('#username', process.env.SMOKE_USER ?? 'admin@example.com');
await page.fill('#password', process.env.SMOKE_PASSWORD ?? 'admin');
await Promise.all([page.waitForURL((u) => u.origin === base, { timeout: 30000 }), page.click('#kc-login')]);
await page.evaluate(() => localStorage.setItem('bo.locale', 'pl'));

const screens = [
  ['home', '/home'], ['inbox', '/inbox'], ['projects', '/projects'], ['runs', '/runs'], ['insights', '/insights'],
  ['ci', '/ci'], ['templates', '/library/templates'], ['models', '/models'], ['suites', '/evaluation/suites'],
  ['benchmarks', '/evaluation/benchmarks'], ['costs', '/costs'], ['settings', '/settings'], ['account', '/account'],
  ['users', '/admin/users'], ['roles', '/admin/roles'], ['grants', '/admin/grants'], ['platform-settings', '/admin/settings'],
  ['fleet', '/admin/fleet/pools'], ['agent-jobs', '/admin/fleet/jobs'], ['operations', '/admin/operations'], ['audit', '/admin/audit'],
];
// common English UI words that should not appear in Polish chrome
const english = /\b(Create|Delete|Save|Cancel|Search|Settings|Projects|Runs|Status|Owner|Members|Workflow designer|No [a-z]+ yet|Last|Name|Actions|Filter|Loading|Open|Edit|Add|Remove|Start run|Your turn|In flight)\b/;

const project = await page.evaluate(async () => {
  const r = await fetch('/api/v1/projects', { headers: { accept: 'application/json' } });
  const j = await r.json().catch(() => null);
  const list = Array.isArray(j) ? j : j?.content ?? [];
  return list[0]?.id ?? null;
});
if (project) {
  screens.push(['project-detail', `/projects/${project}`], ['project-ci', `/projects/${project}?tab=ci`], ['project-sandbox', `/projects/${project}?tab=sandbox`]);
}

for (const [name, path] of screens) {
  await page.goto(base + path);
  await page.waitForLoadState('networkidle').catch(() => null);
  await page.waitForTimeout(600);
  await page.screenshot({ path: `${out}/${name}.png` });
  const text = await page.evaluate(() => {
    const main = document.querySelector('main') ?? document.body;
    return main.innerText;
  });
  const hits = [...new Set(text.split('\n').map((l) => l.trim()).filter((l) => l && english.test(l)))].slice(0, 8);
  console.log(`${name.padEnd(18)} ${hits.length ? 'english? ' + JSON.stringify(hits) : 'ok'}`);
}
await browser.close();
