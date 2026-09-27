/* Read-only browser check for chart-to-history date-range navigation. */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const {chromium} = require(process.env.MAINTENANCE_PLAYWRIGHT_ROOT || 'playwright');
const xlsx = require('../frontend/node_modules/xlsx');
const theme = process.env.REPORT_CHART_UI_THEME || 'dark';
const appUrl = process.env.REPORT_CHART_UI_URL || 'http://localhost:63055';
(async () => {
  const env = Object.fromEntries(fs.readFileSync(path.join(__dirname, '../backend/.env'), 'utf8')
    .split(/\r?\n/).filter(line => line && !line.startsWith('#') && line.includes('='))
    .map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
  const now = Math.floor(Date.now() / 1000), encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  const payload = `${encode({alg: 'HS256', typ: 'JWT'})}.${encode({iss: 'logistics-dispatch', sub: 'report-chart-ui-check', userId: 1, name: '介面驗證', role: 'ADMIN', iat: now, exp: now + 300})}`;
  const token = `${payload}.${crypto.createHmac('sha256', env.APP_JWT_SECRET).update(payload).digest('base64url')}`;
  const browser = await chromium.launch({channel: 'chrome', headless: true});
  try {
    const context = await browser.newContext({viewport: {width: 1440, height: 1000}, timezoneId: 'Asia/Taipei'});
    await context.addInitScript(({token, theme}) => {
      localStorage.setItem('logistics-dispatch.access-token', token);
      localStorage.setItem('logistics-dispatch.admin-theme', theme);
    }, {token, theme});
    const page = await context.newPage(), errors = [], writes = [];
    page.on('pageerror', error => errors.push(error.message));
    await context.route('**/api/**', route => {
      if (route.request().method() !== 'GET') {writes.push(route.request().url()); return route.abort();}
      return route.continue();
    });
    const output = path.join(__dirname, '../artifacts/report-chart-history-ui', theme); fs.mkdirSync(output, {recursive: true});
    for (const [period, count, index] of [['年度', 12, 8], ['月度', null, 3], ['週別', 6, 0]]) {
      await page.goto(`${appUrl}/dispatch/reports`);
      await page.waitForFunction(() => document.querySelector('app-operation-report')
        && !document.querySelector('app-operation-report .report-feedback'));
      await page.getByRole('group', {name: '檢視粒度'}).getByRole('button', {name: period, exact: true}).click();
      const bars = page.locator('.chart-column'); await bars.first().waitFor();
      if (count !== null) await page.waitForFunction(expected => document.querySelectorAll('.chart-column').length === expected, count);
      if (count !== null) assert.equal(await bars.count(), count, period);
      else assert.ok((await bars.count()) >= 4 && (await bars.count()) <= 6, period);
      const bar = bars.nth(index), label = await bar.getAttribute('aria-label');
      const match = /前往 (\d{4}-\d{2}-\d{2}) 至 (\d{4}-\d{2}-\d{2}) 歷史查詢/.exec(label);
      assert.ok(match, label);
      if (period === '週別') {
        await page.locator('#people .execution-grid > button').first().waitFor();
        const sections = await page.locator('.report-section').evaluateAll(nodes => nodes.map(node => node.id));
        assert.deepEqual(sections, ['people', 'fleet', 'orders', 'exceptions', 'warehouses']);
        for (const ratio of ['上班打卡率', '路線里程結算率', '訂單完成率', '待排率', '異常訂單率', '本期案件結案率'])
          assert.ok((await page.locator('app-operation-report').innerText()).includes(ratio), ratio);
        await page.screenshot({path: path.join(output, `${theme}-operation-report.png`), fullPage: true});
      }
      await page.locator('.trend-panel').screenshot({path: path.join(output, `${period}-chart.png`)});
      await bar.click(); await page.waitForURL('**/dispatch/history?**');
      await page.waitForFunction(() => window.scrollY === 0);
      const url = new URL(page.url());
      assert.equal(url.searchParams.get('from'), match[1]); assert.equal(url.searchParams.get('to'), match[2]);
      assert.equal(url.searchParams.get('sheet'), 'orders');
      await page.locator('.query-panel').waitFor();
      assert.ok(await page.locator('.report-history-page').count());
      await page.locator('.comparison-card').first().waitFor();
      assert.equal(await page.locator('.comparison-card').count(), 5);
      if (period === '年度') { const date = new Date(`${match[1]}T00:00:00`); assert.equal(match[2], `${match[1].slice(0, 7)}-${String(new Date(date.getFullYear(), date.getMonth()+1, 0).getDate()).padStart(2, '0')}`); }
      if (period === '週別') assert.equal(match[1], match[2]);
      await page.locator('.query-panel').screenshot({path: path.join(output, `${period}-history-range.png`)});
      if (period === '週別') {
        await page.screenshot({path: path.join(output, `${theme}-history-report.png`), fullPage: true});
        const firstOrder = page.locator('.order-table .drill-row').first();
        await firstOrder.waitFor();
        const orderNumber = (await firstOrder.locator('span').nth(1).innerText()).trim();
        await firstOrder.click();
        await page.waitForURL('**/dispatch/orders?**');
        assert.equal(new URL(page.url()).searchParams.get('order'), orderNumber);
        await page.locator('.order-detail-panel .order-id').getByText(orderNumber, {exact: true}).waitFor();
        await page.goBack();
        await page.locator('.order-table .drill-row').first().waitFor();
        await page.getByRole('button', {name: '近 7 日'}).click();
        await page.locator('.stale-feedback').waitFor();
        assert.equal(await page.getByRole('button', {name: '匯出 Excel'}).isDisabled(), true);
        await page.getByRole('button', {name: '產生分析報表'}).click();
        await page.waitForFunction(() => !document.querySelector('.loading-state') && !document.querySelector('.stale-feedback'));
        assert.equal(await page.getByRole('button', {name: '匯出 Excel'}).isEnabled(), true);
        await page.getByRole('button', {name: '門市表現'}).click();
        await page.waitForFunction(() => document.querySelector('.sheet-scope')?.textContent?.includes('倉庫、門市'));
        assert.match(await page.locator('.sheet-scope').innerText(), /倉庫、門市/);
        const downloadPromise = page.waitForEvent('download');
        await page.getByRole('button', {name: '匯出 Excel'}).click();
        const download = await downloadPromise;
        const workbook = xlsx.read(fs.readFileSync(await download.path()), {type: 'buffer'});
        assert.ok(workbook.SheetNames.includes('門市表現'));
        assert.ok(workbook.SheetNames.includes('倉庫表現'));
        assert.ok(workbook.SheetNames.includes('前期比較'));
        const overview = xlsx.utils.sheet_to_json(workbook.Sheets['營運總覽'], {header: 1});
        assert.equal(overview[0][0], '查詢起日');
        assert.equal(overview[0][1], '查詢迄日');
        assert.ok(overview[0].includes('完成率') && overview[0].includes('待排率') && overview[0].includes('配送失敗率'));
        assert.equal(xlsx.utils.sheet_to_json(workbook.Sheets['司機出勤'], {header: 1})[0][2], '上班打卡率');
        assert.ok(xlsx.utils.sheet_to_json(workbook.Sheets['倉庫表現'], {header: 1})[0].includes('完成率'));
        assert.equal(xlsx.utils.sheet_to_json(workbook.Sheets['前期比較'], {header: 1})[5][0], '指標');
      }
    }
    await page.goto(`${appUrl}/dispatch/reports`);
    await page.locator('#orders .status-item').first().waitFor();
    await page.locator('#orders .status-item').first().click();
    await page.waitForURL('**/dispatch/history?**');
    assert.equal(new URL(page.url()).searchParams.get('sheet'), 'orders');
    assert.equal(new URL(page.url()).searchParams.get('status'), 'COMPLETED');
    assert.equal(await page.locator('.order-status-filter select').inputValue(), 'COMPLETED');
    await page.locator('.order-table .drill-row').first().waitFor();
    assert.ok((await page.locator('.order-table .drill-row').allInnerTexts()).every(row => row.includes('已完成')));
    await page.goto(`${appUrl}/dispatch/reports`);
    await page.locator('#warehouses .warehouse-row button').first().waitFor();
    await page.locator('#warehouses .warehouse-row button').first().click();
    await page.waitForURL('**/dispatch/history?**');
    assert.ok(new URL(page.url()).searchParams.get('warehouseId'));
    assert.equal(new URL(page.url()).searchParams.get('sheet'), 'orders');
    await page.goto(`${appUrl}/dispatch/history?from=2026-09-22&to=2026-09-28`);
    await page.locator('.comparison-card').first().waitFor();
    const priorRange = /前期 (\d{4}-\d{2}-\d{2}) 至 (\d{4}-\d{2}-\d{2})/.exec(await page.locator('.comparison-heading').innerText());
    assert.ok(priorRange);
    await page.locator('.comparison-card').first().getByRole('button', {name: '前期明細'}).click();
    await page.waitForFunction(() => new URL(location.href).searchParams.get('from') === '2026-09-15');
    assert.equal(new URL(page.url()).searchParams.get('from'), priorRange[1]);
    assert.equal(new URL(page.url()).searchParams.get('to'), priorRange[2]);
    assert.equal(new URL(page.url()).searchParams.get('sheet'), 'orders');
    await page.locator('.advanced-filters summary').click();
    await page.locator('.advanced-grid select').nth(1).selectOption('NO_SIGNATURE');
    await page.locator('.stale-feedback').waitFor();
    await page.getByRole('button', {name: '產生分析報表'}).click();
    await page.waitForFunction(() => !document.querySelector('.loading-state') && !document.querySelector('.stale-feedback'));
    assert.equal(new URL(page.url()).searchParams.get('caseType'), 'NO_SIGNATURE');
    await page.setViewportSize({width: 390, height: 844});
    await page.goto(`${appUrl}/dispatch/reports`);
    await page.waitForFunction(() => document.querySelector('app-operation-report')
      && !document.querySelector('app-operation-report .report-feedback'));
    await page.screenshot({path: path.join(output, `${theme}-operation-mobile.png`), fullPage: true});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true);
    await page.goto(`${appUrl}/dispatch/history?from=2026-09-22&to=2026-09-28`);
    await page.waitForFunction(() => document.querySelector('.preview-shell') && !document.querySelector('.loading-state'));
    await page.screenshot({path: path.join(output, `${theme}-history-mobile.png`), fullPage: true});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true);
    assert.deepEqual(writes, []); assert.deepEqual(errors, []);
    console.log(`${theme}: annual/monthly/weekly bars opened the matching month/week/day history ranges; no writes.`);
  } finally { await browser.close(); }
})().catch(error => {console.error(error); process.exitCode = 1;});
