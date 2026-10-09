// Logs in as a non-admin user through Keycloak and checks that administration is blocked in the UI and
// in the API. Usage: SMOKE_USER=viewer@example.com SMOKE_PASSWORD=viewer node e2e/access-check.mjs
import { chromium } from 'playwright';

const base = process.env.SMOKE_BASE ?? 'http://localhost:4200';
const user = process.env.SMOKE_USER ?? 'viewer@example.com';
const password = process.env.SMOKE_PASSWORD ?? 'viewer';

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage();
const results = [];
const check = (name, ok, detail = '') => results.push({ name, ok, detail });

await page.goto(base + '/oauth2/authorization/oidc?returnTo=/dashboard');
await page.fill('#username', user);
await page.fill('#password', password);
await Promise.all([page.waitForURL((url) => url.origin === base, { timeout: 30000 }), page.click('#kc-login')]);

const me = await page.evaluate(async () => (await fetch('/api/v1/me')).json());
check('signed in', me?.user?.email === user, me?.user?.email);
check('no admin permissions', !(me.platformPermissions ?? []).some((p) => p.startsWith('admin.')), (me.platformPermissions ?? []).join(','));

const adminApi = await page.evaluate(async () => {
  const r = await fetch('/api/v1/admin/users');
  return { status: r.status, body: await r.json().catch(() => null) };
});
check('admin API is 403', adminApi.status === 403, `${adminApi.status} permission=${adminApi.body?.permission}`);

const csrfless = await page.evaluate(async () => (await fetch('/api/v1/tokens', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"name":"x","expiresInDays":1}' })).status);
check('POST without CSRF header is rejected', csrfless === 403, String(csrfless));

await page.goto(base + '/admin/users');
await page.waitForTimeout(1500);
// permissionGuard renders /forbidden but keeps the refused URL in the address bar (skipLocationChange).
const denied = await page.getByText('Access denied').isVisible().catch(() => false);
check('admin page shows Access denied', denied && page.url().endsWith('/admin/users'), page.url());

const menu = await page.locator('nz-sider, .ant-layout-sider').innerText().catch(() => '');
check('menu hides Administration', !/Administration/i.test(menu), '');

console.log(JSON.stringify(results, null, 1));
await browser.close();
process.exit(results.every((r) => r.ok) ? 0 : 1);
