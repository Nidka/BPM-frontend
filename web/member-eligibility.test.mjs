import {test} from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {once} from 'node:events';
import {createAppServer} from './server.mjs';

test('comprobar cuenta: ruta privada, JSON acotado, sin datos personales ni mutaciones', async t => {
  let calls = 0, status = 'READY';
  const api = http.createServer(async (req, res) => {
    calls++;
    assert.equal(req.url, '/api/v1/organizations/org/members/check');
    assert.equal(req.headers.authorization, 'Bearer test.token');
    assert.equal(req.headers.cookie, undefined);
    assert.equal(req.headers['x-role'], undefined);
    let body = ''; for await (const chunk of req) body += chunk;
    assert.deepEqual(JSON.parse(body), {email:'chef@example.com'});
    res.setHeader('Content-Type','application/json');
    res.end(JSON.stringify({data:{status, email:'private', uid:'private'}}));
  });
  api.listen(0,'127.0.0.1'); await once(api,'listening');
  const app = createAppServer({apiBaseUrl:`http://127.0.0.1:${api.address().port}`,allowInsecureForTests:true});
  app.listen(0,'127.0.0.1'); await once(app,'listening');
  t.after(async()=>{app.closeAllConnections();api.closeAllConnections();await Promise.all([new Promise(r=>app.close(r)),new Promise(r=>api.close(r))]);});
  const url=`http://127.0.0.1:${app.address().port}/backend/api/v1/organizations/org/members/check`;
  const headers={Authorization:'Bearer test.token','Content-Type':'application/json',Cookie:'private=value','X-Role':'SUPER_ADMIN'};
  assert.equal((await fetch(url)).status,405);
  assert.equal((await fetch(url,{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'})).status,401);
  assert.equal((await fetch(url,{method:'POST',headers,body:'{"email":"chef@example.com","role":"ADMIN"}'})).status,400);
  assert.equal((await fetch(url+'?email=private@example.com',{method:'POST',headers,body:'{}'})).status,404);
  assert.equal(calls,0);
  for(const value of ['READY','ALREADY_MEMBER','UNAVAILABLE']) {
    status=value;
    const response=await fetch(url,{method:'POST',headers,body:'{"email":"chef@example.com"}'});
    assert.equal(response.status,200);
    assert.deepEqual(await response.json(),{data:{status:value}});
  }
  status='UNKNOWN';
  assert.equal((await fetch(url,{method:'POST',headers,body:'{"email":"chef@example.com"}'})).status,503);
});
