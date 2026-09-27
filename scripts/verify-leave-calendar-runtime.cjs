// Read-only diagnostic: no submissions, approvals or changes to operational data.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const env = Object.fromEntries(fs.readFileSync(path.join(__dirname, '../backend/.env'), 'utf8').split(/\r?\n/)
  .filter(line => line && !line.startsWith('#') && line.includes('='))
  .map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
function token(userId, role) {
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  const now = Math.floor(Date.now() / 1000);
  const body = `${encode({alg: 'HS256', typ: 'JWT'})}.${encode({iss: 'logistics-dispatch', sub: 'leave-calendar-readonly', userId, role, iat: now, exp: now + 120})}`;
  return `${body}.${crypto.createHmac('sha256', env.APP_JWT_SECRET).update(body).digest('base64url')}`;
}
async function read(url, jwt) {
  const response = await fetch(`http://127.0.0.1:8080${url}`, {headers: {Authorization: `Bearer ${jwt}`}});
  assert.equal(response.status, 200, `GET ${url.split('?')[0]}: ${response.status}`);
  return response.json();
}
(async () => {
  const today = new Intl.DateTimeFormat('en-CA', {timeZone: 'Asia/Taipei'}).format(new Date());
  const from = `${today.slice(0, 7)}-01`;
  const active = (await read('/api/drivers', token(1, 'ADMIN'))).filter(driver => driver.isActive);
  for (const driver of active) {
    const jwt = token(driver.id, 'DRIVER');
    const [shifts, requests, eligible] = await Promise.all([
      read(`/api/driver/shifts?from=${from}&to=${today}`, jwt),
      read('/api/driver/leave-requests', jwt),
      read(`/api/driver/leave-requests/makeup-candidates?from=${from}&to=${today}`, jwt),
    ]);
    const pastWork = shifts.filter(shift => shift.shiftType === 'WORK' && shift.workDate < today);
    const unavailable = pastWork.filter(shift => !eligible.includes(shift.workDate));
    const leaveBlocked = unavailable.filter(shift => requests.some(request => request.workDate === shift.workDate && request.status !== 'REJECTED'
      && !(request.status === 'PENDING' && request.fullDay && request.submissionSource === 'SYSTEM'
        && ['SYSTEM_NO_SHOW', 'TEMPORARY'].includes(request.requestMode))));
    // Attendance must no longer make a WORK day unselectable; only leave conflicts can exclude it.
    const unexpected = unavailable.filter(shift => !leaveBlocked.includes(shift));
    assert.equal(unexpected.length, 0, `Driver ${driver.id}: unexpected unavailable WORK dates`);
    console.log(JSON.stringify({driverId: driver.id, publishedPastWorkDays: pastWork.length, eligibleMakeupDays: eligible.length,
      blockedByLeaveRequest: leaveBlocked.map(shift => shift.workDate), unexpectedUnavailableDays: unexpected.map(shift => shift.workDate)}));
  }
})().catch(error => {console.error(error.message); process.exitCode = 1;});
