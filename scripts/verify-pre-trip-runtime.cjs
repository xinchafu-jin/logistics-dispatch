// Read-only runtime smoke check. Tokens and personal details never go to stdout.
const fs = require('node:fs');
const crypto = require('node:crypto');
const path = require('node:path');
const assert = require('node:assert/strict');
const env = Object.fromEntries(fs.readFileSync(path.join(__dirname, '../backend/.env'), 'utf8').split(/\r?\n/)
  .filter(line => line && !line.startsWith('#') && line.includes('=')).map(line => [line.slice(0,line.indexOf('=')),line.slice(line.indexOf('=')+1)]));
function token(userId,role) {
  const encode=value=>Buffer.from(JSON.stringify(value)).toString('base64url'); const now=Math.floor(Date.now()/1000);
  const text=`${encode({alg:'HS256',typ:'JWT'})}.${encode({iss:'logistics-dispatch',sub:'pre-trip-readonly-smoke',userId,role,iat:now,exp:now+120})}`;
  return `${text}.${crypto.createHmac('sha256',env.APP_JWT_SECRET).update(text).digest('base64url')}`;
}
async function read(url,jwt) {
  const response=await fetch(url,{headers:{Authorization:`Bearer ${jwt}`}});
  assert.equal(response.status,200,`GET ${new URL(url).pathname}: ${response.status}`); return response.json();
}
(async()=>{
  const root='http://127.0.0.1:63055', admin=token(1,'ADMIN');
  await read(`${root}/api/auth/me`,admin);
  const drivers=await read(`${root}/api/drivers`,admin);
  assert.ok(drivers.every(driver=>typeof driver.monthlyOvertimeMinutes==='number' && driver.monthlyOvertimeMinutes>=0));
  console.log(`Admin session and monthly actual-overtime API OK (${drivers.length} drivers)`);
  const vehicles=await read(`${root}/api/vehicles`,admin);
  assert.ok(vehicles.every(vehicle=>vehicle.maintenance &&
    ['minorCount','majorCount','repairCount'].every(key=>typeof vehicle.maintenance[key]==='number') &&
    ['currentOdometerKm','minorRemainingKm','majorRemainingKm','retirementRemainingKm'].every(key=>Object.hasOwn(vehicle.maintenance,key))));
  console.log(`Vehicle maintenance API OK (${vehicles.length} vehicles; summaries and counts present)`);
  const missing=vehicles.filter(vehicle=>vehicle.maintenance.currentOdometerKm===null).length;
  console.log(`Vehicles with no recorded odometer: ${missing} (not substituted with a guessed zero)`);
  const driver=drivers.find(driver=>driver.isActive);
  if(driver){
    const jwt=token(driver.id,'DRIVER'); await read(`${root}/api/auth/me`,jwt);
    const tasks=await read(`${root}/api/driver/tasks/today`,jwt);
    for(const route of tasks.routes) {
      const inspection=await read(`${root}/api/driver/pre-trip?routeId=${route.routeId}`,jwt);
      assert.equal(inspection.routeId,route.routeId); assert.equal(typeof inspection.passed,'boolean');
    }
    console.log(`Driver session and task/inspection API OK (${tasks.routes.length} routes; no changes made)`);
  }
})().catch(error=>{console.error(error.message);process.exitCode=1;});
