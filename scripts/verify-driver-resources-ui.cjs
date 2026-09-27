/* Visual/regression QA. Driver writes are intercepted; API persistence is tested with transactional rollback separately. */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const { chromium } = require(process.env.MAINTENANCE_PLAYWRIGHT_ROOT || 'playwright');
const theme = process.env.DRIVER_RESOURCES_UI_THEME || 'dark';
const appUrl = process.env.DRIVER_RESOURCES_UI_URL || 'http://localhost:63055';
(async () => {
  const env = Object.fromEntries(fs.readFileSync(path.join(__dirname, '../backend/.env'), 'utf8').split(/\r?\n/)
    .filter(line => line && !line.startsWith('#') && line.includes('='))
    .map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
  const now = Math.floor(Date.now() / 1000), encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  const payload = `${encode({alg: 'HS256', typ: 'JWT'})}.${encode({iss: 'logistics-dispatch', sub: 'driver-resources-ui-check', userId: 1, name: '介面驗證', role: 'ADMIN', iat: now, exp: now + 300})}`;
  const token = `${payload}.${crypto.createHmac('sha256', env.APP_JWT_SECRET).update(payload).digest('base64url')}`;
  const browser = await chromium.launch({channel: 'chrome', headless: true});
  try {
    const context = await browser.newContext({viewport: {width: 1440, height: 1000}, timezoneId: 'Asia/Taipei'});
    await context.addInitScript(({token, theme}) => {
      localStorage.setItem('logistics-dispatch.access-token', token); localStorage.setItem('logistics-dispatch.admin-theme', theme);
    }, {token, theme});
    const get = async endpoint => {
      const response = await context.request.get(`http://localhost:8080/api/${endpoint}`, {headers: {Authorization: `Bearer ${token}`}});
      assert.equal(response.status(), 200, endpoint); return response.json();
    };
    const [realDrivers, warehouses] = await Promise.all([get('drivers'), get('warehouses')]);
    assert.ok(realDrivers.length > 0); assert.ok(Object.hasOwn(realDrivers[0], 'warehouseId'), 'New backend schema/DTO is running');
    const active = warehouses.filter(warehouse => warehouse.isActive); assert.ok(active.length > 1);
    const first = active[0], second = active[1];
    let drivers = realDrivers.map((driver, index) => index === 0 ? {...driver, warehouseId: first.id} : driver);
    const writes = [], unexpectedWrites = [];
    await context.route('**/api/**', async route => {
      const request = route.request(), endpoint = new URL(request.url()).pathname;
      const json = (body, status = 200) => route.fulfill({status, contentType: 'application/json', body: JSON.stringify(body)});
      if (endpoint === '/api/drivers' && request.method() === 'GET') return json(drivers);
      if (/^\/api\/drivers(?:\/\d+)?$/.test(endpoint) && ['POST', 'PUT'].includes(request.method())) {
        const dto = request.postDataJSON(); writes.push({method: request.method(), dto});
        assert.ok(dto.warehouseId); const {password, ...saved} = dto;
        if (request.method() === 'POST') {
          assert.equal(password, 'A123456789'); const driver = {...saved, id: 900001}; drivers = [...drivers, driver]; return json(driver, 201);
        }
        assert.equal(password, undefined); const id = Number(endpoint.split('/').at(-1));
        drivers = drivers.map(driver => driver.id === id ? {...saved, id} : driver);
        return json(drivers.find(driver => driver.id === id));
      }
      if (request.method() !== 'GET') {unexpectedWrites.push(endpoint); return route.abort();}
      return route.continue();
    });
    const page = await context.newPage(), errors = []; page.on('pageerror', error => errors.push(error.message));
    const output = path.join(__dirname, '../artifacts/driver-resources-ui', theme); fs.mkdirSync(output, {recursive: true});
    await page.goto(`${appUrl}/dispatch/resources`);
    await page.getByRole('group', {name: '資源類型'}).getByRole('button', {name: '司機', exact: true}).click();
    await page.locator('.driver-resource-card').first().waitFor();
    assert.equal(await page.locator('.driver-resource-card').count(), realDrivers.length);
    const originalName = drivers[0].name;
    const card = page.getByRole('article', {name: `司機 ${originalName}`, exact: true});
    assert.ok((await card.textContent()).includes(first.name));
    const bounds = await card.locator('.driver-detail-block').evaluateAll(elements => elements.map(element => {
      const rect = element.getBoundingClientRect(); return {top: rect.top, height: rect.height, border: getComputedStyle(element).borderTopWidth};
    }));
    assert.ok(bounds.every(bound => Math.abs(bound.top - bounds[0].top) < 1 && Math.abs(bound.height - bounds[0].height) < 1 && bound.border === '1px'));
    await card.screenshot({path: path.join(output, 'driver-three-blocks.png')});
    const search = page.locator('.search-field input'); await search.fill(drivers[0].account);
    await page.waitForFunction(() => document.querySelectorAll('.driver-resource-card').length === 1);
    await page.getByRole('button', {name: '清除搜尋', exact: true}).click();
    await page.getByRole('combobox', {name: '司機所屬倉庫篩選', exact: true}).click();
    await page.getByRole('option', {name: `${first.warehouseCode} · ${first.name}`, exact: true}).click();
    assert.equal(await page.locator('.driver-resource-card').count(), 1);
    await card.getByRole('button', {name: `編輯司機 ${originalName}`, exact: true}).click();
    const dialog = page.getByRole('dialog', {name: '編輯司機', exact: true}); await dialog.waitFor();
    assert.equal(await dialog.locator('input[type=password]').count(), 0);
    const warehouseSelect = dialog.getByRole('combobox', {name: '司機所屬倉庫', exact: true});
    await warehouseSelect.click();
    await page.locator('.tonnage-filter-panel').screenshot({path: path.join(output, 'warehouse-options.png'), animations: 'disabled'});
    await page.getByRole('option', {name: `${second.warehouseCode} · ${second.name}`, exact: true}).click();
    await page.locator('.tonnage-filter-panel').waitFor({state: 'hidden'});
    await dialog.screenshot({path: path.join(output, 'edit-driver.png'), animations: 'disabled'});
    await dialog.getByRole('button', {name: '儲存司機資料', exact: true}).click(); await dialog.waitFor({state: 'hidden'});
    assert.equal(writes.length, 1); assert.equal(writes[0].dto.warehouseId, second.id);
    await page.getByRole('combobox', {name: '司機所屬倉庫篩選', exact: true}).click();
    await page.getByRole('option', {name: '全部倉庫', exact: true}).click();
    await card.waitFor(); assert.ok((await card.textContent()).includes(second.name));
    await page.getByRole('button', {name: '新增司機', exact: true}).click();
    const create = page.getByRole('dialog', {name: '新增司機', exact: true});
    await create.getByLabel('司機姓名', {exact: true}).fill('測試新增司機');
    await create.getByLabel('手機號碼', {exact: true}).fill('0912345678');
    await create.getByLabel('登入帳號', {exact: true}).fill('DRIVER-UI-NEW');
    await create.getByLabel('身分證字號（初始密碼）', {exact: true}).fill('A123456789');
    await create.getByRole('button', {name: '建立司機帳號', exact: true}).click(); await create.waitFor({state: 'hidden'});
    assert.equal(writes.length, 2); assert.equal(await page.locator('.driver-resource-card').count(), realDrivers.length + 1);
    await page.setViewportSize({width: 390, height: 844});
    await page.locator('.driver-resource-card').first().screenshot({path: path.join(output, 'driver-mobile.png')});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1), false);
    await card.getByRole('button', {name: `編輯司機 ${originalName}`, exact: true}).click(); await dialog.waitFor();
    await dialog.screenshot({path: path.join(output, 'edit-driver-mobile.png')});
    const mobile = await dialog.boundingBox(); assert.ok(mobile.x >= 0 && mobile.x + mobile.width <= 390);
    await dialog.getByRole('button', {name: '取消', exact: true}).click();
    await page.setViewportSize({width: 360, height: 800});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1), false);
    assert.deepEqual(errors, []); assert.deepEqual(unexpectedWrites, []);
    console.log(JSON.stringify({theme, realDrivers: realDrivers.length, warehouseSelection: 'passed', createAndTransfer: 'passed', threeFramesAligned: true, mobile: '390/360 passed', realWrites: 0}));
  } finally { await browser.close(); }
})().catch(error => {console.error(error.message); process.exitCode = 1;});
