/* Local, read-only UI smoke check. Uses a short-lived test JWT without exposing secrets. */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const { chromium } = require(process.env.MAINTENANCE_PLAYWRIGHT_ROOT || 'playwright');
const lightTheme = process.env.MAINTENANCE_UI_THEME === 'light';
const appUrl = process.env.MAINTENANCE_UI_URL || 'http://localhost:4200';

async function checkVehicleAlignment(row, label) {
  const positions = await row.evaluate(element => {
    const rect = selector => {
      const bounds = element.querySelector(selector).getBoundingClientRect();
      return { top: bounds.top, bottom: bounds.bottom, height: bounds.height, left: bounds.left, width: bounds.width };
    };
    return {
      columns: ['.vehicle-row-header', '.vehicle-mileage-column', '.vehicle-maintenance-summary'].map(rect),
      cards: ['.vehicle-row-header', '.vehicle-mileage-column', '.vehicle-maintenance-summary'].map(selector => {
        const style = getComputedStyle(element.querySelector(selector));
        return { border: style.borderTopWidth, radius: style.borderTopLeftRadius, background: style.backgroundColor };
      }),
      actions: rect('.vehicle-row-actions'),
      controls: rect('.vehicle-header-controls'),
      headings: ['.vehicle-identity strong', '.vehicle-mileage-header > span', '.actual h3'].map(rect),
      rows: [1, 2, 3].map(index => ({
        count: rect(`.service-count:nth-child(${index})`),
        remaining: rect(`.actual .values > div:nth-child(${index})`),
        countValue: rect(`.service-count:nth-child(${index}) .count-value`),
        remainingValue: rect(`.actual .values > div:nth-child(${index}) dd`),
      })),
    };
  });
  const aligned = (values, description) => assert.ok(Math.max(...values) - Math.min(...values) <= 1,
    `${label}: ${description} (${values.join(', ')})`);
  aligned(positions.columns.map(column => column.top), 'all three columns start at the same level');
  aligned(positions.columns.map(column => column.bottom), 'all three columns end at the same level');
  aligned(positions.columns.map(column => column.width), 'all three independent cards have equal widths');
  aligned(positions.headings.map(heading => heading.top), 'headings line up');
  assert.deepEqual(positions.cards.map(card => card.border), ['1px', '1px', '1px'], `${label}: all three sections have their own border`);
  assert.deepEqual(positions.cards.map(card => card.radius), ['12px', '12px', '12px'], `${label}: all three cards use matching corners`);
  assert.equal(new Set(positions.cards.map(card => card.background)).size, 1, `${label}: all three cards use the same surface`);
  aligned([positions.actions.bottom, positions.columns[0].bottom - 19], 'edit and delete sit at the bottom of the vehicle card');
  aligned([positions.actions.left + positions.actions.width / 2,
    positions.columns[0].left + positions.columns[0].width / 2], 'edit and delete are centered inside the vehicle card');
  assert.ok(positions.actions.top >= positions.controls.bottom, `${label}: actions are below the status and restriction`);
  for (const [index, item] of positions.rows.entries()) {
    aligned([item.count.top, item.remaining.top], `row ${index + 1} starts aligned`);
    aligned([item.count.bottom, item.remaining.bottom], `row ${index + 1} ends aligned`);
    aligned([item.countValue.top, item.remainingValue.top], `row ${index + 1} numbers line up`);
  }
  console.log(`${label} alignment passed`);
}

async function checkLightContrast(page, selector, label, includeDark = false) {
  if (!lightTheme && !includeDark) return;
  const samples = await page.locator(selector).evaluateAll(roots => {
    const canvas = document.createElement('canvas'); canvas.width = canvas.height = 1;
    const context = canvas.getContext('2d', { willReadFrequently: true });
    const rgba = color => {
      context.clearRect(0, 0, 1, 1); context.fillStyle = color; context.fillRect(0, 0, 1, 1);
      const pixel = context.getImageData(0, 0, 1, 1).data;
      return [pixel[0], pixel[1], pixel[2], pixel[3] / 255];
    };
    const blend = (front, back) => front.slice(0, 3).map((channel, i) => channel * front[3] + back[i] * (1 - front[3]));
    const luminance = color => color.slice(0, 3).map(channel => {
      const value = channel / 255;
      return value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4;
    }).reduce((sum, value, i) => sum + value * [.2126, .7152, .0722][i], 0);
    const elements = [...new Set(roots.flatMap(root => [root, ...root.querySelectorAll('*')]))];
    return elements.filter(element => {
      const rect = element.getBoundingClientRect();
      return rect.width > 0 && rect.height > 0 && !element.disabled &&
        (((element instanceof HTMLInputElement || element instanceof HTMLSelectElement) && element.value) ||
          [...element.childNodes].some(node => node.nodeType === Node.TEXT_NODE && node.textContent.trim()));
    }).map(element => {
      const ancestors = []; for (let current = element; current; current = current.parentElement) ancestors.unshift(current);
      let background = [255, 255, 255];
      for (const ancestor of ancestors) background = blend(rgba(getComputedStyle(ancestor).backgroundColor), background);
      const foreground = blend(rgba(getComputedStyle(element).color), background);
      const a = luminance(foreground), b = luminance(background);
      return { text: (element.value || element.textContent).trim().slice(0, 40), ratio: (Math.max(a, b) + .05) / (Math.min(a, b) + .05) };
    });
  });
  assert.ok(samples.length > 0, `${label}: readable text is present`);
  const failures = samples.filter(sample => sample.ratio < 4.5);
  assert.deepEqual(failures, [], `${label}: normal text contrast must be at least 4.5:1`);
  console.log(`${label} contrast passed (${samples.length} text samples, min ${Math.min(...samples.map(sample => sample.ratio)).toFixed(2)}:1)`);
}

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
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
    await context.addInitScript(token => localStorage.setItem('logistics-dispatch.access-token', token), token);
    await context.addInitScript(theme => localStorage.setItem('logistics-dispatch.admin-theme', theme), lightTheme ? 'light' : 'dark');
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    page.on('response', response => { if (response.url().includes('/api/auth/me')) console.log('Auth check:', response.status()); });
    const output = path.join(__dirname, '../artifacts/maintenance-ui', lightTheme ? 'light' : 'dark');
    fs.mkdirSync(output, { recursive: true });
    await page.goto(`${appUrl}/dispatch/resources`);
    console.log('Page:', page.url());
    try {
      await page.getByRole('button', { name: '保養與退役規則', exact: true }).waitFor();
    } catch (error) {
      console.log('Final location:', page.url(), 'Errors:', errors);
      console.log('Headings:', await page.locator('h1,h2,h3').allTextContents());
      await page.screenshot({ path: path.join(output, 'failure.png') });
      throw error;
    }
    await page.locator('.vehicle-table .resource-row').first().waitFor();
    await page.locator('app-vehicle-maintenance-panel').first().waitFor();
    assert.ok(await page.locator('app-vehicle-maintenance-panel').count() > 0);
    assert.ok(await page.locator('app-vehicle-maintenance-panel .actual').count() > 0);
    assert.equal(await page.locator('.vehicle-table app-vehicle-maintenance-panel .history').count(), 0, 'Resource rows do not duplicate service counts in the mileage panel');
    assert.equal(await page.locator('.vehicle-table .resource-row').first().locator('.service-count').count(), 3, 'Resource rows show minor, major and repair counts separately');
    const maintenanceCard = page.locator('.vehicle-table .resource-row').first().locator('.vehicle-maintenance-card');
    const vehicleRow = page.locator('.vehicle-table .resource-row').first();
    assert.equal(await vehicleRow.locator('.vehicle-row-header .vehicle-status-pill').count(), 1, 'Status is grouped with vehicle identity');
    assert.equal(await vehicleRow.locator('.vehicle-row-actions .row-action').count(), 2, 'Actions belong to each vehicle row');
    assert.equal(await vehicleRow.locator('.vehicle-row-header .vehicle-row-actions .row-action').count(), 2, 'Both actions are inside the vehicle card footer');
    assert.equal(await vehicleRow.locator('.vehicle-detail-block').count(), 3, 'Vehicle, history and remaining mileage have separate outer frames');
    assert.equal(await vehicleRow.locator('.vehicle-specs > div').count(), 2, 'Vehicle type and capacity have separate labelled information rows');
    assert.equal(await vehicleRow.locator('.vehicle-status-line').count(), 1, 'Status shares a clearly labelled information row');
    assert.equal(await vehicleRow.locator('.vehicle-header-controls').evaluate(element => getComputedStyle(element).flexGrow), '0', 'Available vehicle status does not stretch into an empty spacer');
    assert.equal(await maintenanceCard.count(), 1, 'Mileage and service details share one card');
    assert.equal(await maintenanceCard.locator('.odometer').count(), 1, 'Actual mileage appears once');
    assert.equal(await maintenanceCard.locator('.actual .values > div').count(), 3, 'Remaining mileage has three clear rows');
    assert.equal(await maintenanceCard.locator('.actual .source').count(), 0, 'Resource card has no redundant recorded badge');
    await checkVehicleAlignment(vehicleRow, 'Available vehicle');
    const lastServiceTime = vehicleRow.locator('.last-service-date time').first();
    const originalServiceTime = await lastServiceTime.textContent();
    try {
      await lastServiceTime.evaluate(element => { element.textContent = '2026/09/27 09:00（完成時間較長時仍保持對齊）'; });
      await checkVehicleAlignment(vehicleRow, 'Wrapped completion time');
    } finally {
      await lastServiceTime.evaluate((element, text) => { element.textContent = text; }, originalServiceTime);
    }
    assert.equal(await page.locator('.filter-control').getByRole('button', { name: '保養排程', exact: true }).count(), 0);
    assert.equal(await page.locator('.filter-control').getByRole('button', { name: '維修中', exact: true }).count(), 1);
    assert.equal(await page.locator('.filter-control').getByRole('button', { name: '小保中', exact: true }).count(), 1);
    assert.equal(await page.locator('.filter-control').getByRole('button', { name: '大保中', exact: true }).count(), 1);
    const vehicleDataResponse = await context.request.get('http://localhost:8080/api/vehicles', { headers: { Authorization: `Bearer ${token}` } });
    assert.equal(vehicleDataResponse.status(), 200);
    const vehicleData = await vehicleDataResponse.json();
    const searchInput = page.getByRole('searchbox', { name: '搜尋車號或車型', exact: true });
    const searchResult = page.locator('.search-result');
    const waitVehiclePlates = async vehicles => {
      const plates = vehicles.map(vehicle => vehicle.plateNumber).sort();
      await page.waitForFunction(expected => JSON.stringify(
        [...document.querySelectorAll('.vehicle-table .vehicle-identity strong')].map(element => element.textContent.trim()).sort(),
      ) === JSON.stringify(expected), plates);
    };
    const exactVehicle = vehicleData.find(vehicle => vehicle.plateNumber === 'CAR-0001');
    assert.ok(exactVehicle, 'The reported search example exists in the database');
    await searchInput.fill('CAR-0001');
    await waitVehiclePlates([exactVehicle]);
    assert.ok((await searchResult.textContent()).includes('1 台'), 'Complete plate search reports one exact result');
    await page.screenshot({ path: path.join(output, 'vehicle-search-desktop.png') });
    await searchInput.fill(' car-0001 ');
    await waitVehiclePlates([exactVehicle]);
    await searchInput.fill('CAR-');
    await waitVehiclePlates(vehicleData.filter(vehicle => vehicle.plateNumber.toLowerCase().includes('car-') || vehicle.vehicleType?.toLowerCase().includes('car-')));
    await searchInput.fill('NO-SUCH-VEHICLE');
    await waitVehiclePlates([]);
    assert.ok((await searchResult.textContent()).includes('0 台'));
    await page.getByRole('button', { name: '清除搜尋', exact: true }).click();
    await waitVehiclePlates(vehicleData);
    assert.equal(await searchInput.inputValue(), '', 'Clear empties the input and restores the full vehicle list');
    assert.equal(await searchResult.count(), 0);
    await page.locator('.filter-control').getByRole('button', { name: '維修中', exact: true }).click();
    await waitVehiclePlates(vehicleData.filter(vehicle => vehicle.status === 'MAINTENANCE'));
    await searchInput.fill('CAR-0001');
    await waitVehiclePlates(exactVehicle.status === 'MAINTENANCE' ? [exactVehicle] : []);
    assert.ok((await searchResult.textContent()).includes('依目前篩選條件'));
    await page.getByRole('button', { name: '清除搜尋', exact: true }).click();
    await waitVehiclePlates(vehicleData.filter(vehicle => vehicle.status === 'MAINTENANCE'));
    await page.locator('.filter-control').getByRole('button', { name: '全部', exact: true }).click();
    await waitVehiclePlates(vehicleData);
    const searchTonnageFilter = page.getByRole('combobox', { name: '車輛噸位', exact: true });
    const otherTonnage = vehicleData.find(vehicle => vehicle.tonnage && vehicle.tonnage !== exactVehicle.tonnage)?.tonnage;
    assert.ok(otherTonnage, 'There is another tonnage to exercise combined filtering');
    await searchTonnageFilter.click();
    await page.getByRole('option', { name: `${otherTonnage} 噸`, exact: true }).click();
    await waitVehiclePlates(vehicleData.filter(vehicle => vehicle.tonnage === otherTonnage));
    await searchInput.fill('CAR-0001');
    await waitVehiclePlates([]);
    await page.getByRole('button', { name: '清除搜尋', exact: true }).click();
    await waitVehiclePlates(vehicleData.filter(vehicle => vehicle.tonnage === otherTonnage));
    await searchTonnageFilter.click();
    await page.getByRole('option', { name: '全部噸位', exact: true }).click();
    await waitVehiclePlates(vehicleData);
    await page.setViewportSize({ width: 390, height: 844 });
    await searchInput.fill('CAR-0001');
    await waitVehiclePlates([exactVehicle]);
    await checkLightContrast(page, '.search-field, .search-result', 'Vehicle search', true);
    assert.equal(await page.locator('.resource-tools').evaluate(element => element.scrollWidth > element.clientWidth + 1), false, 'Search and clear button have no mobile overflow');
    await page.locator('.resource-tools').screenshot({ path: path.join(output, 'vehicle-search-mobile.png') });
    await page.getByRole('button', { name: '清除搜尋', exact: true }).click();
    await waitVehiclePlates(vehicleData);
    await page.setViewportSize({ width: 1440, height: 1000 });
    console.log('Vehicle search passed: exact, partial, case/space, no matches, clear, status, tonnage and mobile');
    for (const vehicle of vehicleData) {
      assert.ok(Number.isInteger(vehicle.maintenance.repairCount), 'Repair counts come from the database API');
      assert.ok(Object.hasOwn(vehicle.maintenance, 'lastRepairAt'), 'API includes the latest completed repair time');
    }
    await page.locator('.filter-control').getByRole('button', { name: '維修中', exact: true }).click();
    const repairCount = vehicleData.filter(vehicle => vehicle.status === 'MAINTENANCE').length;
    await page.waitForFunction(expected => document.querySelectorAll('.vehicle-table .resource-row').length === expected, repairCount);
    const repairStatuses = await page.locator('.vehicle-table .resource-row .status-badge').allTextContents();
    assert.equal(repairStatuses.length, repairCount);
    for (const status of repairStatuses) {
      assert.equal(status.trim(), '維修中');
    }
    await page.screenshot({ path: path.join(output, 'repairs-desktop.png') });
    if (repairCount) {
      const repairRow = page.locator('.vehicle-table .resource-row').first();
      assert.equal(await repairRow.locator('.vehicle-header-controls [role="status"]').count(), 1, 'Dispatch restriction sits below the repair status');
      assert.equal(await repairRow.locator('.vehicle-maintenance-summary [role="status"]').count(), 0, 'Remaining mileage does not duplicate the restriction');
      await checkVehicleAlignment(repairRow, 'Repair vehicle with restriction');
      await repairRow.evaluate(element => element.scrollIntoView({ block: 'center', inline: 'nearest' }));
      await repairRow.screenshot({ path: path.join(output, 'repair-card-desktop.png') });
      await page.setViewportSize({ width: 1100, height: 1000 });
      await checkVehicleAlignment(repairRow, 'Narrow desktop repair vehicle');
      assert.equal(await repairRow.evaluate(element => element.scrollWidth > element.clientWidth + 1), false, 'Three separate cards have no narrow desktop overflow');
      await repairRow.screenshot({ path: path.join(output, 'repair-card-narrow-desktop.png') });
      await page.setViewportSize({ width: 390, height: 1200 });
      await repairRow.evaluate(element => {
        element.scrollIntoView({ block: 'center', inline: 'nearest' });
        const headerBottom = document.querySelector('.workspace-header')?.getBoundingClientRect().bottom || 0;
        window.scrollBy({ top: element.getBoundingClientRect().top - headerBottom - 12, behavior: 'instant' });
      });
      await repairRow.screenshot({ path: path.join(output, 'repair-card-mobile.png') });
      assert.equal(await repairRow.evaluate(element => element.scrollWidth > element.clientWidth + 1), false, 'Repair row has no mobile overflow');
      assert.ok(await repairRow.locator('.vehicle-edit-action').evaluate(element => element.getBoundingClientRect().height <= 40), 'Mobile action buttons do not stretch with the vehicle details');
      assert.ok(await repairRow.evaluate(element => {
        const actions = element.querySelector('.vehicle-row-actions').getBoundingClientRect();
        const vehicle = element.querySelector('.vehicle-row-header').getBoundingClientRect();
        const controls = element.querySelector('.vehicle-header-controls').getBoundingClientRect();
        return actions.top >= controls.bottom && actions.bottom < vehicle.bottom;
      }), 'Mobile edit and delete stay below the status inside the vehicle card');
      await page.setViewportSize({ width: 1440, height: 1000 });
      await page.getByRole('button', { name: /^編輯車輛 / }).first().click();
      const repairDialog = page.getByRole('dialog', { name: '編輯車輛', exact: true });
      await repairDialog.locator('.maintenance-history article').first().waitFor();
      assert.ok((await repairDialog.locator('.maintenance-history').textContent()).includes('維修 · 進行中'));
      await repairDialog.getByRole('button', { name: '取消', exact: true }).click();
    }
    await page.locator('.filter-control').getByRole('button', { name: '全部', exact: true }).click();
    await page.waitForFunction(expected => document.querySelectorAll('.vehicle-table .resource-row').length === expected, vehicleData.length);
    const tonnageFilter = page.getByRole('combobox', { name: '車輛噸位', exact: true });
    const tonnageMenu = page.locator('.tonnage-filter-panel');
    const waitTonnageMenu = async () => {
      await tonnageMenu.waitFor();
      await tonnageMenu.evaluate(element => Promise.all(element.getAnimations().map(animation => animation.finished.catch(() => {}))));
    };
    const screenshotTonnageMenu = async name => {
      await waitTonnageMenu();
      const trigger = await tonnageFilter.boundingBox(), menu = await tonnageMenu.boundingBox();
      const viewport = page.viewportSize();
      const x = Math.max(0, Math.min(trigger.x, menu.x) - 18), y = Math.max(0, Math.min(trigger.y, menu.y) - 18);
      const right = Math.min(viewport.width, Math.max(trigger.x + trigger.width, menu.x + menu.width) + 18);
      const bottom = Math.min(viewport.height, Math.max(trigger.y + trigger.height, menu.y + menu.height) + 18);
      await page.screenshot({ path: path.join(output, name), animations: 'disabled', clip: { x, y, width: right - x, height: bottom - y } });
    };
    await tonnageFilter.scrollIntoViewIfNeeded();
    await tonnageFilter.click();
    await waitTonnageMenu();
    const menuStyle = await tonnageMenu.evaluate(element => ({
      scheme: getComputedStyle(element).colorScheme,
      background: getComputedStyle(element).backgroundColor,
      radius: getComputedStyle(element).borderRadius,
    }));
    assert.equal(menuStyle.scheme, lightTheme ? 'light' : 'dark', 'Custom tonnage menu follows the selected theme');
    assert.equal(menuStyle.background, lightTheme ? 'rgb(255, 255, 255)' : 'rgb(27, 34, 34)', 'Menu uses a solid themed panel rather than the native popup');
    assert.equal(menuStyle.radius, '8px', 'Menu has rounded corners matching surrounding controls');
    assert.equal(await tonnageMenu.getByRole('option', { name: '未設定噸位', exact: true }).count(), 0);
    assert.equal(await tonnageMenu.locator('[aria-selected="true"]').count(), 1);
    assert.ok(Math.abs((await tonnageFilter.boundingBox()).width - (await tonnageMenu.boundingBox()).width) <= 1, 'Menu and trigger have matching widths');
    assert.ok(Math.abs((await tonnageFilter.boundingBox()).x - (await tonnageMenu.boundingBox()).x) <= 1, 'Menu is aligned with the trigger instead of its inner label');
    await checkLightContrast(page, '.tonnage-filter-panel', 'Expanded tonnage menu', true);
    await screenshotTonnageMenu('tonnage-filter-open.png');
    await tonnageFilter.press('Escape');
    await tonnageMenu.waitFor({ state: 'hidden' });
    assert.ok((await tonnageFilter.textContent()).includes('全部噸位'), 'Escape does not change the selected tonnage');
    await tonnageFilter.click();
    await tonnageMenu.getByRole('option', { name: '3.5 噸', exact: true }).click();
    const tonnageCount = vehicleData.filter(vehicle => vehicle.tonnage === 3.5).length;
    await page.waitForFunction(expected => document.querySelectorAll('.vehicle-table .resource-row').length === expected, tonnageCount);
    assert.ok(tonnageCount > 0);
    await checkLightContrast(page, '.resource-tools', 'Tonnage filter');
    await page.screenshot({ path: path.join(output, 'tonnage-filter-desktop.png') });
    await page.setViewportSize({ width: 390, height: 844 });
    await tonnageFilter.scrollIntoViewIfNeeded();
    assert.ok((await tonnageFilter.textContent()).includes('3.5 噸'));
    await tonnageFilter.click();
    await waitTonnageMenu();
    const mobileMenu = await tonnageMenu.boundingBox();
    assert.ok(mobileMenu.x >= 0 && mobileMenu.x + mobileMenu.width <= 390 && mobileMenu.y + mobileMenu.height <= 844, 'Mobile menu stays within the viewport');
    await screenshotTonnageMenu('tonnage-filter-open-mobile.png');
    await page.locator('.cdk-overlay-backdrop').click({ position: { x: 1, y: 1 } });
    await tonnageMenu.waitFor({ state: 'hidden' });
    assert.ok((await tonnageFilter.textContent()).includes('3.5 噸'), 'Clicking outside closes the menu without resetting the filter');
    await page.locator('.resource-tools').screenshot({ path: path.join(output, 'tonnage-filter-mobile.png') });
    await page.setViewportSize({ width: 390, height: 1200 });
    await vehicleRow.evaluate(element => element.scrollIntoView({ block: 'center', inline: 'nearest' }));
    await vehicleRow.evaluate(element => {
      const headerBottom = document.querySelector('.workspace-header')?.getBoundingClientRect().bottom || 0;
      window.scrollBy({ top: element.getBoundingClientRect().top - headerBottom - 12, behavior: 'instant' });
    });
    await vehicleRow.screenshot({ path: path.join(output, 'vehicle-maintenance-card-mobile.png') });
    assert.equal(await vehicleRow.evaluate(element => element.scrollWidth > element.clientWidth + 1), false, 'Whole vehicle row has no mobile overflow');
    await page.setViewportSize({ width: 1440, height: 1000 });
    await tonnageFilter.click();
    await tonnageMenu.getByRole('option', { name: '全部噸位', exact: true }).click();
    await page.waitForFunction(expected => document.querySelectorAll('.vehicle-table .resource-row').length === expected, vehicleData.length);
    await checkLightContrast(page, '.vehicle-table .resource-row, .resource-summary', 'Resource page');
    await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }));
    await page.screenshot({ path: path.join(output, 'resources-desktop.png') });
    await vehicleRow.evaluate(element => element.scrollIntoView({ block: 'center', inline: 'nearest' }));
    await vehicleRow.screenshot({ path: path.join(output, 'vehicle-maintenance-card-desktop.png') });
    await page.getByRole('button', { name: '保養與退役規則', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '保養與退役規則', exact: true });
    await dialog.locator('input[name="warningKm"]').waitFor();
    assert.equal(await dialog.locator('input[name="warningKm"]').inputValue(), '500');
    assert.ok(await dialog.locator('tbody tr').count() > 0);
    await checkLightContrast(page, 'app-maintenance-rules-editor section', 'Rules dialog');
    await page.screenshot({ path: path.join(output, 'rules-desktop.png') });
    // 驗證只填一個噸位能儲存；攔截 PUT，不在使用者資料庫寫入測試規則。
    let savedPayload;
    await page.route('**/api/vehicle-maintenance/rules', async route => {
      if (route.request().method() !== 'PUT') return route.continue();
      savedPayload = route.request().postDataJSON();
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(savedPayload) });
    });
    const row = dialog.locator('tbody tr').nth(1);
    const allRows = dialog.locator('tbody tr');
    for (let index = 0; index < await allRows.count(); index++) {
      if (index === 1) continue;
      for (const column of [1, 2, 3]) await allRows.nth(index).locator('input').nth(column).fill('');
    }
    assert.equal(await row.locator('input').nth(0).inputValue(), '3.5');
    await row.locator('input').nth(1).fill('3000');
    await row.locator('input').nth(2).fill('20000');
    await row.locator('input').nth(3).fill('500000');
    await dialog.getByRole('button', { name: '儲存並同步全部同噸位車輛', exact: true }).click();
    await dialog.waitFor({ state: 'detached' });
    assert.equal(savedPayload.policies.length, 1);
    assert.equal(savedPayload.policies[0].tonnage, 3.5);
    await page.getByRole('button', { name: /^編輯車輛 / }).first().click();
    const vehicle = page.getByRole('dialog', { name: '編輯車輛', exact: true });
    await vehicle.getByText('目前實際總里程（司機紀錄自動更新）', { exact: true }).waitFor();
    const odometer = vehicle.getByText('目前實際總里程（司機紀錄自動更新）', { exact: true }).locator('..').locator('input');
    assert.equal(await odometer.getAttribute('readonly'), '');
    for (const label of ['上次小保儀表里程（自動讀取保養紀錄）', '上次大保儀表里程（自動讀取保養紀錄）']) {
      assert.equal(await vehicle.getByText(label, { exact: true }).locator('..').locator('input').getAttribute('readonly'), '');
    }
    assert.ok(await vehicle.locator('select option[value="MINOR_MAINTENANCE"]').count());
    assert.ok(await vehicle.locator('select option[value="MAJOR_MAINTENANCE"]').count());
    assert.equal((await vehicle.locator('select option[value="MAINTENANCE"]').textContent()).trim(), '送維修（車禍／故障）');
    await vehicle.getByText(/車禍、故障等選「送維修」/).waitFor();
    await checkLightContrast(page, '.resource-modal', 'Vehicle editor');
    await page.screenshot({ path: path.join(output, 'vehicle-desktop.png') });
    await vehicle.getByRole('button', { name: '取消', exact: true }).click();
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByRole('button', { name: '保養與退役規則', exact: true }).click();
    await dialog.locator('input[name="warningKm"]').waitFor();
    await page.screenshot({ path: path.join(output, 'rules-mobile.png') });
    await dialog.getByRole('button', { name: '關閉', exact: true }).click();
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.goto(`${appUrl}/dispatch/dashboard`);
    await page.locator('app-dispatch-dashboard').waitFor();
    await page.locator('.dispatch-day-card').first().waitFor();
    const pastDay = page.locator('.dispatch-day-card').filter({ has: page.locator('[data-status="UNRESOLVED"]') }).first();
    if (await pastDay.count()) {
      await pastDay.click();
    }
    const boardPanel = page.locator('app-vehicle-maintenance-panel').first();
    await boardPanel.locator('.actual').waitFor();
    assert.equal(await page.locator('app-vehicle-maintenance-panel .history').count(), 0, 'Dispatch board hides completed service counts');
    assert.equal(await page.locator('app-vehicle-maintenance-panel .estimate-note').count(), 0, 'Dispatch board hides the estimate explanation');
    await boardPanel.scrollIntoViewIfNeeded();
    await checkLightContrast(page, '.published-route-card:first-child, .dispatch-day-card', 'Dispatch board');
    await page.screenshot({ path: path.join(output, 'dashboard-desktop.png') });
    const routeCard = boardPanel.locator('..');
    await routeCard.screenshot({ path: path.join(output, 'maintenance-card-desktop.png') });
    await page.setViewportSize({ width: 390, height: 844 });
    await boardPanel.scrollIntoViewIfNeeded();
    await routeCard.screenshot({ path: path.join(output, 'maintenance-card-mobile.png') });
    assert.equal(await boardPanel.evaluate(element => element.scrollWidth > element.clientWidth + 1), false, 'Maintenance panel has no horizontal overflow on mobile');
    assert.deepEqual(errors, []);
    console.log('UI smoke checks passed: resources, single-tonnage rule save, vehicle edit, mobile rules, dashboard; no database data saved.');
    console.log(`Screenshots: ${output}`);
  } finally { await browser.close(); }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
