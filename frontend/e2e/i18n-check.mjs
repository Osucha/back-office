// UI languages against the running stack (dev server :4200 + backend :8080 + compose Keycloak),
// ADR 0026. Shows the login page in Polish (pre-auth switch), signs in through Keycloak with the
// development test user, switches to Polish with localStorage `bo.locale` and checks the shell,
// /home, /help (the Polish manual, falling back to English per chapter) and contextual help, then
// switches back to English through the avatar menu. Screenshots go to e2e/screenshots/i18n/.
// Usage: node e2e/i18n-check.mjs [outDir]
import { mkdirSync } from 'node:fs';
import { chromium } from 'playwright';

const base = process.env.BASE_URL ?? 'http://localhost:4200';
const out = process.argv[2] ?? 'e2e/screenshots/i18n';
const user = process.env.SMOKE_USER ?? 'admin@example.com';
const password = process.env.SMOKE_PASSWORD ?? 'admin';
mkdirSync(out, { recursive: true });

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok });
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`);
};

const browser = await chromium.launch({ channel: 'msedge', headless: true });
// An English browser: Polish must come from the user's choice, not from navigator.language.
const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'en-US' });
const page = await context.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
const settle = () => page.waitForLoadState('networkidle').catch(() => null);

// ---- login page: English by default, the switch turns it Polish
await page.goto(base + '/login');
await page.getByRole('heading', { name: 'Sign in' }).waitFor({ timeout: 20000 });
check('login page is English for an English browser', true);
await page.getByRole('button', { name: 'Polski' }).click();
await page.getByRole('heading', { name: 'Zaloguj się' }).waitFor({ timeout: 20000 });
check('login switch reloads in Polish', (await page.locator('html').getAttribute('lang')) === 'pl');
await settle();
await page.screenshot({ path: `${out}/login-pl.png` });

// ---- sign in (Keycloak keeps its own language)
await page.goto(base + '/oauth2/authorization/oidc?returnTo=/home');
await page.fill('#username', user);
await page.fill('#password', password);
await Promise.all([
  page.waitForURL((u) => u.origin === base, { timeout: 30000 }),
  page.click('#kc-login'),
]);
check('signed in', !page.url().includes('/login'), page.url());

// ---- the shell and /home in Polish (bo.locale was stored by the login switch; set it anyway)
await page.evaluate(() => localStorage.setItem('bo.locale', 'pl'));
await page.goto(base + '/home');
await settle();
await page.waitForTimeout(1000);
check('html lang is pl', (await page.locator('html').getAttribute('lang')) === 'pl');
check('rail is Polish', (await page.getByRole('link', { name: /Uruchomienia/ }).count()) > 0);
check('top bar is Polish', (await page.getByText('Szukaj lub przejdź do…').count()) > 0);
check('document title is Polish', (await page.title()) === 'Strona główna', await page.title());
await page.screenshot({ path: `${out}/home-pl.png` });

// ---- avatar menu shows the language switch
await page.locator('app-avatar-menu .avatar-btn').click();
await page.getByRole('menu').waitFor({ timeout: 5000 }).catch(() => null);
check(
  'avatar menu offers English / Polski',
  (await page.getByRole('menuitemradio', { name: 'English' }).count()) === 1 &&
    (await page.getByRole('menuitemradio', { name: 'Polski' }).count()) === 1,
);
await page.screenshot({ path: `${out}/avatar-menu-pl.png` });
await page.keyboard.press('Escape');

// ---- command palette
await page.keyboard.press('Control+k');
await page.waitForTimeout(600);
check('palette is Polish', (await page.getByText('Przejdź do', { exact: true }).count()) > 0);
await page.screenshot({ path: `${out}/palette-pl.png` });
await page.keyboard.press('Escape');

// ---- the manual in Polish (chapters not translated yet fall back to English)
await page.goto(base + '/help');
await settle();
await page.waitForTimeout(1200);
check('manual index uses the Polish manifest', (await page.getByText('Pierwsze kroki').count()) > 0);
await page.screenshot({ path: `${out}/help-pl.png` });
await page.goto(base + '/help/projects#members');
await page.waitForSelector('#members', { state: 'attached', timeout: 15000 }).catch(() => null);
await page.waitForTimeout(800);
check('Polish chapter renders', (await page.locator('article h1').first().innerText()).includes('Projekty'));
await page.screenshot({ path: `${out}/help-chapter-pl.png` });

// ---- contextual help (F1) in Polish
await page.goto(base + '/projects');
await settle();
await page.keyboard.press('F1');
await page.waitForTimeout(1500);
check('F1 help panel is Polish', (await page.getByText('Otwórz w podręczniku').count()) > 0);
await page.screenshot({ path: `${out}/help-panel-pl.png` });
await page.keyboard.press('Escape');

// ---- My account -> Preferences has the switch; back to English through the avatar menu
await page.goto(base + '/account?section=preferences');
await settle();
await page.waitForTimeout(800);
check('account preferences offer the language', (await page.getByText('Język / Language').count()) > 0);
await page.screenshot({ path: `${out}/account-preferences-pl.png` });
await page.locator('app-avatar-menu .avatar-btn').click();
await Promise.all([
  page.waitForEvent('load', { timeout: 20000 }),
  page.getByRole('menuitemradio', { name: 'English' }).click(),
]);
await settle();
check('switched back to English', (await page.locator('html').getAttribute('lang')) === 'en');
check(
  'English choice stored',
  (await page.evaluate(() => localStorage.getItem('bo.locale'))) === 'en',
);

check('no page errors', errors.length === 0, errors.slice(0, 3).join(' | '));
await browser.close();
const failed = results.filter((r) => !r.ok).length;
console.log(`${results.length - failed}/${results.length} passed; screenshots in ${out}`);
process.exit(failed ? 1 : 0);
