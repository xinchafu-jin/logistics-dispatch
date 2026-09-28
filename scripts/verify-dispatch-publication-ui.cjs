/* Browser smoke check: POST actions are intercepted; no real routes are published or withdrawn. */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const { chromium } = require(process.env.MAINTENANCE_PLAYWRIGHT_ROOT || 'playwright');
const theme = process.env.DISPATCH_PUBLICATION_UI_THEME || 'dark';
const appUrl = process.env.DISPATCH_PUBLICATION_UI_URL || 'http://localhost:4200';

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
    const get = async endpoint => {
      const response = await context.request.get(`http://localhost:8080/api/${endpoint}`, { headers: { Authorization: `Bearer ${token}` } });
      assert.equal(response.status(), 200); return response.json();
    };
    const date = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Taipei', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
    const [warehouses, drivers, orders] = await Promise.all([get('warehouses'), get('drivers'), get('orders')]);
    const boards = await Promise.all(warehouses.map(warehouse => get(`dispatch/board?date=${date}&warehouseId=${warehouse.id}`)));
    // 司機倉庫歸屬是此測試的前提；只在瀏覽器 fixture 補齊，不修改既有司機資料。
    const testDrivers = drivers.map(driver => ({...driver,
      warehouseId: boards.find(board => board.routes.some(route => route.driverId === driver.id))?.warehouse.id ?? driver.warehouseId}));
    const otherWarehouse = warehouses.find(warehouse => warehouse.isActive && warehouse.id !== boards[0].warehouse.id);
    const foreignDriver = testDrivers.find(driver => !boards.some(board => board.routes.some(route => route.driverId === driver.id)));
    assert.ok(otherWarehouse && foreignDriver, 'Fixture has another warehouse and a spare driver');
    foreignDriver.warehouseId = otherWarehouse.id; foreignDriver.isActive = true;
    const routeOrderIds = new Set(boards.flatMap(board => board.routes.flatMap(route => route.stops.map(stop => stop.orderId))));
    assert.ok(routeOrderIds.size > 0, 'The local database has a published route for this regression');
    const testOrders = orders.map(order => routeOrderIds.has(order.id) ? { ...order, status: 'CONFIRMED' } : order);
    let phase = 'PUBLISHED', rejectWithdrawal = true;
    const intercepted = [], unexpectedWrites = [];
    const stateBoards = () => boards.map(board => ({ ...board, routes: board.routes.map(route => ({ ...route, status: phase })) }));
    await context.route('**/api/**', async route => {
      const request = route.request(), url = new URL(request.url()), endpoint = url.pathname;
      const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
      if (request.method() === 'POST' && ['/api/dispatch/publish', '/api/dispatch/withdraw'].includes(endpoint)) {
        assert.equal(url.searchParams.get('date'), date); assert.equal(url.searchParams.has('warehouseId'), false);
        intercepted.push(endpoint);
        if (endpoint.endsWith('/withdraw') && rejectWithdrawal) {
          return json({ success: false, message: '已有訂單開始配送，不能撤回；要換人請使用司機交接' }, 400);
        }
        phase = endpoint.endsWith('/withdraw') ? 'DRAFT' : 'PUBLISHED'; return json(stateBoards());
      }
      if (request.method() !== 'GET') { unexpectedWrites.push(endpoint); return route.abort(); }
      if (endpoint === '/api/drivers') return json(testDrivers);
      if (endpoint === '/api/orders') return json(testOrders);
      if (endpoint === '/api/dispatch/board') return json(stateBoards().find(board => board.warehouse.id === Number(url.searchParams.get('warehouseId'))));
      if (endpoint === '/api/driver-schedules/months') return json({ id: 900001, scheduleMonth: `${date.slice(0, 7)}-01`, status: 'PUBLISHED' });
      if (endpoint === '/api/driver-schedules/months/900001/shifts') {
        return json(drivers.map((driver, i) => ({ id: i + 1, driverId: driver.id, workDate: date, shiftType: 'WORK' })));
      }
      await route.continue();
    });
    const page = await context.newPage(), errors = [];
    page.on('pageerror', error => errors.push(error.message));
    const output = path.join(__dirname, '../artifacts/dispatch-publication-ui', theme);
    fs.mkdirSync(output, { recursive: true });
    await page.goto(`${appUrl}/dispatch/dashboard`);
    const actions = page.getByRole('group', { name: '發布與撤回', exact: true });
    const publish = actions.getByRole('button', { name: '發布', exact: true });
    const withdraw = actions.getByRole('button', { name: '撤回發布', exact: true });
    await withdraw.waitFor();
    await page.waitForFunction(() => !document.querySelector('.publication-actions button:last-child').disabled);
    assert.equal(await publish.isDisabled(), true);
    const firstCard = page.locator('.published-route-card').first();
    assert.equal(await firstCard.locator('app-vehicle-maintenance-panel').count(), 0, 'Mileage starts folded');
    await firstCard.screenshot({path: path.join(output, 'route-mileage-collapsed.png')});
    const globalMileage = page.locator('.mileage-global-toggle');
    await globalMileage.click();
    await page.waitForFunction(() => document.querySelectorAll('.published-route-card app-vehicle-maintenance-panel').length === document.querySelectorAll('.published-route-card').length);
    assert.equal(await page.locator('.published-route-card app-vehicle-maintenance-panel').count(), await page.locator('.published-route-card').count());
    await firstCard.screenshot({path: path.join(output, 'route-mileage-expanded.png')});
    await firstCard.locator('.mileage-card-toggle').click();
    await firstCard.locator('app-vehicle-maintenance-panel').waitFor({state: 'detached'});
    assert.equal(await firstCard.locator('app-vehicle-maintenance-panel').count(), 0, 'Individual card closes independently');
    await globalMileage.click();
    await page.waitForFunction(() => document.querySelectorAll('.published-route-card app-vehicle-maintenance-panel').length === document.querySelectorAll('.published-route-card').length);
    assert.equal(await page.locator('.published-route-card app-vehicle-maintenance-panel').count(), await page.locator('.published-route-card').count());
    await globalMileage.click();
    await firstCard.locator('app-vehicle-maintenance-panel').waitFor({state: 'detached'});
    assert.equal(await page.locator('.published-route-card app-vehicle-maintenance-panel').count(), 0);
    await page.locator('.board-toolbar').screenshot({ path: path.join(output, 'published-toolbar.png') });
    await withdraw.click();
    const dialog = page.getByRole('dialog', { name: '撤回發布確認', exact: true });
    await dialog.waitFor();
    assert.ok((await dialog.textContent()).includes('全部倉庫'));
    assert.equal(await dialog.locator('.mat-mdc-dialog-surface').evaluate(element => getComputedStyle(element).backgroundColor),
      theme === 'light' ? 'rgb(255, 255, 255)' : 'rgb(27, 34, 34)');
    await dialog.evaluate(element => Promise.all(element.getAnimations({ subtree: true }).map(animation => animation.finished.catch(() => {}))));
    await dialog.screenshot({ path: path.join(output, 'withdraw-confirmation.png'), animations: 'disabled' });
    await dialog.getByRole('button', { name: '取消', exact: true }).click();
    await dialog.waitFor({ state: 'hidden' }); assert.equal(intercepted.length, 0);
    await withdraw.click(); await dialog.getByRole('button', { name: '確認撤回', exact: true }).click();
    await page.locator('.board-error').getByText(/撤回發布失敗：已有訂單開始配送/).waitFor();
    assert.equal(await page.locator('.published-route-card').count(), boards[0].routes.length);
    assert.equal(await publish.isDisabled(), true);
    rejectWithdrawal = false;
    await withdraw.click(); await dialog.getByRole('button', { name: '確認撤回', exact: true }).click();
    await page.locator('.slot-grid').waitFor();
    assert.equal(await page.locator('.slot-grid app-vehicle-maintenance-panel').count(), 0);
    await page.locator('.slot-grid .mileage-card-toggle').first().click();
    await page.locator('.slot-grid app-vehicle-maintenance-panel').first().waitFor();
    assert.equal(await page.locator('.slot-grid app-vehicle-maintenance-panel').count(), 1);
    await page.locator('.mileage-global-toggle').click();
    await page.locator('.slot-grid app-vehicle-maintenance-panel').first().waitFor({state: 'detached'});
    assert.equal(await page.locator('.slot-grid app-vehicle-maintenance-panel').count(), 0);
    await page.waitForFunction(() => !document.querySelector('.publication-publish').disabled);
    assert.equal(await withdraw.isDisabled(), true);
    assert.equal(await page.locator('.board-driver').first().isDisabled(), false);
    assert.equal(await page.locator(`.slot-grid .lane-driver-select option[value="${foreignDriver.id}"]`).count(), 0);
    const warehouseSelect = page.locator('.board-toolbar select').first();
    await warehouseSelect.selectOption(String(otherWarehouse.id));
    await page.locator(`.slot-grid .lane-driver-select option[value="${foreignDriver.id}"]`).first().waitFor({state: 'attached'});
    const localOptions = await page.locator('.slot-grid .lane-driver-select').first().locator('option[value]:not([value=""])').evaluateAll(options => options.map(option => Number(option.value)));
    assert.ok(localOptions.every(id => testDrivers.find(driver => driver.id === id)?.warehouseId === otherWarehouse.id));
    await warehouseSelect.selectOption(String(boards[0].warehouse.id));
    await page.locator('.slot-grid .board-lane').filter({hasText: boards[0].routes[0].plateNumber}).first().waitFor();
    assert.equal(await page.locator(`.slot-grid .lane-driver-select option[value="${foreignDriver.id}"]`).count(), 0);
    assert.equal(await page.locator('.pending-pool .board-dropzone').getAttribute('aria-disabled'), null);
    await page.locator('.board-toolbar').screenshot({ path: path.join(output, 'withdrawn-toolbar.png') });
    await publish.click();
    await page.locator('.published-route-card').first().waitFor();
    await page.waitForFunction(() => !document.querySelector('.publication-actions button:last-child').disabled);
    assert.equal(await publish.isDisabled(), true);
    assert.deepEqual(intercepted, ['/api/dispatch/withdraw', '/api/dispatch/withdraw', '/api/dispatch/publish']);
    await page.setViewportSize({ width: 390, height: 844 });
    await page.locator('.published-route-card').first().screenshot({path: path.join(output, 'route-mileage-mobile-collapsed.png')});
    assert.equal(await page.locator('.published-route-card').first().evaluate(element => element.scrollWidth > element.clientWidth + 1), false);
    await page.locator('.board-toolbar').screenshot({ path: path.join(output, 'publication-toolbar-mobile.png') });
    assert.equal(await page.locator('.board-toolbar').evaluate(element => element.scrollWidth > element.clientWidth + 1), false);
    await withdraw.click(); await dialog.waitFor();
    const bounds = await dialog.boundingBox(); assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= 390);
    await dialog.evaluate(element => Promise.all(element.getAnimations({ subtree: true }).map(animation => animation.finished.catch(() => {}))));
    await dialog.screenshot({ path: path.join(output, 'withdraw-confirmation-mobile.png'), animations: 'disabled' });
    await dialog.getByRole('button', { name: '取消', exact: true }).click();
    assert.deepEqual(unexpectedWrites, []); assert.deepEqual(errors, []);
    console.log(`${theme} publication UI passed on ${appUrl}: buttons, confirmation/cancel, protected rejection, withdrawal/editing, strict driver warehouse switching, republish, mobile and themed dialog; all POSTs mocked, database unchanged.`);
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
