// Read-only report checks. Never print tokens, personal details, or raw response bodies.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const env = Object.fromEntries(fs.readFileSync(path.join(__dirname, '../backend/.env'), 'utf8')
  .split(/\r?\n/).filter(line => line && !line.startsWith('#') && line.includes('='))
  .map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
const now = Math.floor(Date.now() / 1000);
const payload = `${encode({alg:'HS256',typ:'JWT'})}.${encode({iss:'logistics-dispatch',sub:'report-readonly-smoke',userId:1,role:'ADMIN',iat:now,exp:now+120})}`;
const jwt = `${payload}.${crypto.createHmac('sha256', env.APP_JWT_SECRET).update(payload).digest('base64url')}`;
async function read(url) {
  const response = await fetch(`http://127.0.0.1:63055${url}`, {headers:{Authorization:`Bearer ${jwt}`}});
  assert.equal(response.status, 200, `GET ${url.split('?')[0]}: ${response.status}`);
  return response.json();
}
(async () => {
  const orders = await read('/api/orders');
  const latest = orders.map(order => order.deliveryDate).filter(Boolean).sort().at(-1);
  assert.ok(latest, 'No orders to determine report period');
  const start = new Date(`${latest}T00:00:00`);
  start.setDate(start.getDate() - (start.getDay() + 6) % 7);
  const end = new Date(start); end.setDate(start.getDate() + 6);
  const format = date => `${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')}`;
  const from = format(start), to = format(end);
  const query = `period=CUSTOM&from=${from}&to=${to}`;
  const routes = (await read(`/api/reports/routes?${query}`)).routes;
  const today = new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Taipei'}).format(new Date());
  const past = routes.filter(route => route.status === 'PUBLISHED' && route.date < today);
  const reasons = {};
  for (const route of past) {
    const status = route.mileageComparisonStatus || 'UNKNOWN';
    reasons[status] = (reasons[status] || 0) + 1;
  }
  console.log(JSON.stringify({from,to,pastPublished:past.length,settled:past.filter(route=>typeof route.actualKm==='number').length,
    startedAwaitingReturn:past.filter(route=>route.tripStartAt && !route.tripEndAt).length,
    returnedAwaitingSettlement:past.filter(route=>route.tripEndAt && route.actualKm===null).length,reasons}));
})().catch(error => {console.error(error.message);process.exitCode=1;});
