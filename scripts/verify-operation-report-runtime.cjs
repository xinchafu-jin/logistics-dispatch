// Read-only checks for the running report API and frontend proxies. Never publishes or changes records.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const env = Object.fromEntries(fs.readFileSync(path.join(__dirname, '../backend/.env'), 'utf8').split(/\r?\n/)
  .filter(line => line && !line.startsWith('#') && line.includes('='))
  .map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
function token(role) {
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  const now = Math.floor(Date.now() / 1000);
  const body = `${encode({alg: 'HS256', typ: 'JWT'})}.${encode({iss: 'logistics-dispatch', sub: 'report-readonly', userId: 1, role, iat: now, exp: now + 180})}`;
  return `${body}.${crypto.createHmac('sha256', env.APP_JWT_SECRET).update(body).digest('base64url')}`;
}
async function read(base, url, jwt) {
  const response = await fetch(base + url, {headers: {Authorization: `Bearer ${jwt}`}, signal: AbortSignal.timeout(30000)});
  assert.equal(response.status, 200, `${base}${url}: HTTP ${response.status}`);
  return response.json();
}
function checkRate(value, numerator, denominator) {
  if (!denominator) assert.equal(value, null);
  else assert.ok(Math.abs(value - numerator / denominator * 100) < 0.000001);
}
function checkMetrics(report) {
  const w = report.workforce, f = report.fleet;
  checkRate(w.attendanceRate, w.attendedShifts, w.dueShifts);
  checkRate(w.onTimeRate, w.onTimeShifts, w.attendedShifts);
  checkRate(w.overtimeRate, w.overtimeShifts, w.finishedShifts);
  checkRate(f.returnRate, f.returnedTrips, f.startedTrips);
  assert.equal(f.startedTrips, f.returnedTrips + f.openTrips + f.invalidTrips);
  assert.equal(report.shifts.filter(row => row.due).length, w.dueShifts);
  assert.equal(report.trips.length, f.startedTrips);
  assert.equal(report.warehouses.reduce((sum, row) => sum + row.workforce.dueShifts, 0), w.dueShifts);
  assert.equal(report.warehouses.reduce((sum, row) => sum + row.fleet.startedTrips, 0), f.startedTrips);
}
(async () => {
  const today = new Intl.DateTimeFormat('sv-SE', {timeZone: 'Asia/Taipei'}).format(new Date());
  const query = `from=${today.slice(0, 7)}-01&to=${today}`;
  const jwt = token('ADMIN');
  const warehouses = await read('http://127.0.0.1:8080', '/api/warehouses', jwt);
  for (const base of ['http://127.0.0.1:8080', 'http://localhost:4200', 'http://127.0.0.1:63055']) {
    const report = await read(base, `/api/reports/performance?${query}`, jwt);
    checkMetrics(report);
    assert.equal(report.warehouses.filter(row => row.warehouseId != null).length, warehouses.length);
    const first = warehouses[0];
    if (first) {
      const filtered = await read(base, `/api/reports/performance?${query}&warehouseId=${first.id}`, jwt);
      checkMetrics(filtered);
      assert.equal(filtered.warehouses.length, 1);
      assert.ok(filtered.shifts.every(row => row.warehouseId === first.id));
      assert.ok(filtered.trips.every(row => row.warehouseId === first.id));
    }
    for (const endpoint of ['warehouses', 'exceptions']) await read(base, `/api/reports/${endpoint}?${query}`, jwt);
    console.log(JSON.stringify({base, status: 'passed', warehouseCount: warehouses.length, attendanceRate: report.workforce.attendanceRate,
      onTimeRate: report.workforce.onTimeRate, overtimeRate: report.workforce.overtimeRate, returnRate: report.fleet.returnRate}));
  }
  const driverResponse = await fetch(`http://127.0.0.1:8080/api/reports/performance?${query}`, {headers: {Authorization: `Bearer ${token('DRIVER')}`}});
  assert.equal(driverResponse.status, 403, 'Driver must not access supervisor reports');
  console.log('Supervisor-only authorization passed; all requests were read-only.');
  if (process.argv.includes('--demo')) {
    const base = 'http://127.0.0.1:8080';
    const drivers = await read(base, '/api/drivers', jwt);
    const demo = drivers.filter(row => row.account.startsWith('DEMO-RPT-'));
    assert.equal(demo.length, 6);
    const totals = {due: 0, attended: 0, onTime: 0, finished: 0, overtimeShifts: 0, overtimeMinutes: 0,
      started: 0, returned: 0, open: 0, unsettled: 0};
    for (const driver of demo) {
      assert.equal(driver.isActive, false, 'Demo drivers must not enter real dispatch');
      const report = await read(base, `/api/reports/performance?${query}&driverId=${driver.id}`, jwt);
      checkMetrics(report);
      const w = report.workforce, f = report.fleet;
      assert.equal(driver.monthlyOvertimeMinutes, w.overtimeMinutes, 'Monthly resource card must match monthly report');
      assert.equal(driver.monthlyUnsettledShifts, w.attendedShifts - w.finishedShifts);
      totals.due += w.dueShifts; totals.attended += w.attendedShifts; totals.onTime += w.onTimeShifts;
      totals.finished += w.finishedShifts; totals.overtimeShifts += w.overtimeShifts; totals.overtimeMinutes += w.overtimeMinutes;
      totals.started += f.startedTrips; totals.returned += f.returnedTrips; totals.open += f.openTrips;
      totals.unsettled += driver.monthlyUnsettledShifts;
      console.log(JSON.stringify({demoDriver: driver.account, attendance: w.attendanceRate, onTime: w.onTimeRate,
        overtime: w.overtimeRate, overtimeMinutes: driver.monthlyOvertimeMinutes, unsettled: driver.monthlyUnsettledShifts, returned: f.returnRate}));
    }
    assert.deepEqual(totals, {due:36, attended:30, onTime:22, finished:28, overtimeShifts:6, overtimeMinutes:270,
      started:30, returned:28, open:2, unsettled:2});
    const vehicles = (await read(base, '/api/vehicles', jwt)).filter(row => row.plateNumber.startsWith('DEMO-RPT-'));
    assert.equal(vehicles.length, 6);
    assert.ok(vehicles.every(row => row.status === 'RETIRED'));
    const orders = (await read(base, '/api/orders', jwt)).filter(row => row.orderNumber.startsWith('DEMO-RPT-'));
    assert.equal(orders.length, 66);
    console.log('Demo fixtures verified: all six warehouses, consistent monthly minutes, inactive drivers/retired vehicles; only read requests.');
  }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
