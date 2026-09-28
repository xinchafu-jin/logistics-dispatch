/* Read-only browser regression check against the local database-backed order list. */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const { chromium } = require(process.env.MAINTENANCE_PLAYWRIGHT_ROOT || 'playwright');
const theme = process.env.ORDER_DATE_UI_THEME || 'dark';
const appUrl = process.env.ORDER_DATE_UI_URL || 'http://localhost:4200';

(async () => {
  const env = Object.fromEntries(fs.readFileSync(path.join(__dirname, '../backend/.env'), 'utf8')
    .split(/\r?\n/).filter(line => line && !line.startsWith('#') && line.includes('='))
    .map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
  const now = Math.floor(Date.now() / 1000);
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  const payload = `${encode({ alg: 'HS256', typ: 'JWT' })}.${encode({ iss: 'logistics-dispatch', sub: 'maintenance-ui-check', userId: 1, name: '介面驗證', role: 'ADMIN', iat: now, exp: now + 300 })}`;
  const token = `${payload}.${crypto.createHmac('sha256', env.APP_JWT_SECRET).update(payload).digest('base64url')}`;
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, timezoneId: 'Asia/Taipei' });
    await context.addInitScript(({ token, theme }) => {
      localStorage.setItem('logistics-dispatch.access-token', token);
      localStorage.setItem('logistics-dispatch.admin-theme', theme);
    }, { token, theme });
    const writes = [], errors = [];
    await context.route('**/api/**', async route => {
      if (route.request().method() !== 'GET') {
        writes.push(route.request().method());
        await route.abort();
      } else await route.continue();
    });
    const page = await context.newPage();
    page.on('pageerror', error => errors.push(error.message));
    const output = path.join(__dirname, '../artifacts/order-date-ui', theme);
    fs.mkdirSync(output, { recursive: true });
    const response = await context.request.get('http://localhost:8080/api/orders', { headers: { Authorization: `Bearer ${token}` } });
    assert.equal(response.status(), 200);
    const orders = await response.json();
    const dates = [...new Set(orders.map(order => order.deliveryDate).filter(Boolean))].sort();
    assert.ok(dates.length >= 2, 'The local database has multiple delivery days to verify');
    const waitOrders = async expectedOrders => {
      const ids = expectedOrders.map(order => order.orderNumber).sort();
      await page.waitForFunction(expected => JSON.stringify(
        [...document.querySelectorAll('.order-list .order-id')].map(element => element.textContent.trim()).sort(),
      ) === JSON.stringify(expected), ids);
      assert.ok((await page.locator('.list-count').textContent()).includes(`${ids.length} 筆`));
    };
    await page.goto(`${appUrl}/dispatch/orders`);
    await page.getByRole('group', { name: '配送日期篩選', exact: true }).waitFor();
    await waitOrders(orders);
    const from = page.getByLabel('開始日期', { exact: true });
    const to = page.getByLabel('結束日期', { exact: true });
    const clear = page.getByRole('button', { name: '清除日期', exact: true });
    const search = page.getByRole('searchbox', { name: '搜尋訂單', exact: true });
    const first = dates[0], second = dates[1];
    await from.fill(first); await to.fill(second);
    const inRange = orders.filter(order => order.deliveryDate >= first && order.deliveryDate <= second);
    await waitOrders(inRange);
    assert.ok((await page.locator('.order-row-date').first().textContent()).includes('配送日期'));
    await page.screenshot({ path: path.join(output, 'date-range-desktop.png') });
    await to.fill(first);
    await waitOrders(orders.filter(order => order.deliveryDate === first));
    await from.fill(second);
    await waitOrders([]);
    assert.ok((await page.getByRole('alert').textContent()).includes('開始日期不能晚於結束日期'));
    assert.equal(await page.locator('.order-detail-panel .detail-header').count(), 0, 'Invalid dates do not leave an unrelated order selected');
    await to.fill(second);
    await waitOrders(orders.filter(order => order.deliveryDate === second));
    assert.equal(await page.getByRole('alert').count(), 0);
    await clear.click(); await waitOrders(orders);
    assert.equal(await from.inputValue(), ''); assert.equal(await to.inputValue(), '');
    assert.equal(await clear.isDisabled(), true);
    await from.fill(second);
    await waitOrders(orders.filter(order => order.deliveryDate >= second));
    await clear.click(); await to.fill(first);
    await waitOrders(orders.filter(order => order.deliveryDate && order.deliveryDate <= first));
    await clear.click(); await from.fill(first); await to.fill(second);
    await page.getByRole('group', { name: '訂單狀態篩選', exact: true }).getByRole('button', { name: '已完成', exact: true }).click();
    const completed = inRange.filter(order => order.status === 'COMPLETED');
    await waitOrders(completed);
    const query = completed[0]?.orderNumber || 'NO-MATCHING-ORDER';
    await search.fill(query);
    await waitOrders(completed.filter(order => order.orderNumber.includes(query)));
    await clear.click();
    await waitOrders(orders.filter(order => order.status === 'COMPLETED' && order.orderNumber.includes(query)));
    assert.equal(await search.inputValue(), query, 'Clearing dates keeps the search');
    assert.equal(await page.getByRole('button', { name: '已完成', exact: true }).getAttribute('aria-pressed'), 'true', 'Clearing dates keeps the status filter');
    await search.fill('');
    await page.getByRole('group', { name: '訂單狀態篩選', exact: true }).getByRole('button', { name: '全部', exact: true }).click();
    await page.getByRole('button', { name: '今天', exact: true }).click();
    const today = await page.evaluate(() => {
      const now = new Date();
      return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
    });
    await page.waitForFunction(expected => [...document.querySelectorAll('.date-filter-field input')]
      .every(input => input.value === expected), today);
    assert.equal(await from.inputValue(), today); assert.equal(await to.inputValue(), today);
    await waitOrders(orders.filter(order => order.deliveryDate === today));
    await clear.click(); await from.fill('2099-01-01'); await to.fill('2099-01-31');
    await waitOrders([]);
    assert.ok((await page.locator('.order-list .empty-state').textContent()).includes('沒有符合目前篩選條件'));
    assert.equal(await page.locator('.order-detail-panel .detail-header').count(), 0);
    await clear.click(); await from.fill(first); await to.fill(second); await waitOrders(inRange);
    for (const width of [1100, 390, 360]) {
      await page.setViewportSize({ width, height: 1000 });
      const overflow = await page.locator('.date-filter-bar').evaluate(element => element.scrollWidth > element.clientWidth + 1);
      assert.equal(overflow, false, `${width}px date controls stay within the panel`);
      assert.equal(await from.evaluate(element => getComputedStyle(element).colorScheme), theme, 'Native calendar controls follow the admin theme');
      await page.locator('.date-filter-bar').screenshot({ path: path.join(output, `date-controls-${width}.png`) });
      if (width === 390) await page.screenshot({ path: path.join(output, 'date-range-mobile.png') });
    }
    assert.deepEqual(errors, []);
    assert.deepEqual(writes, [], 'Date filtering never changes order or database data');
    console.log(`${theme} date UI passed on ${appUrl}: ${orders.length} database orders; inclusive range, single date, partial range, status/search, clear, today, invalid/empty range and responsive layouts; no writes.`);
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
