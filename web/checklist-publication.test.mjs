import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {once} from 'node:events';
import {createAppServer} from './server.mjs';

test('publicación cerrada: origen, Bearer, tamaño, campos y respuesta confirmada',async t=>{
 let calls=0,status=201,lastBody;
 const draft={id:'10000000-0000-0000-0000-000000000001',versionId:'10000000-0000-0000-0000-000000000002',name:'Apertura',area:'Cocina',description:'',
  items:[{id:'10000000-0000-0000-0000-000000000003',sectionName:'Personal',label:'Lavado de manos',responseType:'COMPLIANCE',displayOrder:1,required:true}]};
 const backend=http.createServer(async(req,res)=>{
  calls++;assert.equal(req.method,'POST');assert.equal(req.url,'/api/v1/organizations/org/checklists');assert.equal(req.headers.authorization,'Bearer token');
  for(const key of ['cookie','x-organization-id','x-role'])assert.equal(req.headers[key],undefined);
  let body='';for await(const chunk of req)body+=chunk;lastBody=JSON.parse(body);
  res.writeHead(status,{'Content-Type':'application/json','X-BPM-Session-Expires-At':'1900000000'});
  res.end(JSON.stringify(status===201?{data:{...draft,versionNumber:1,private:'hidden'}}:{errors:[{message:'SQL private',fields:{name:'secret',password:'secret'}}]}));
 });
 backend.listen(0,'127.0.0.1');await once(backend,'listening');
 const app=createAppServer({apiBaseUrl:'http://127.0.0.1:'+backend.address().port,allowInsecureForTests:true});app.listen(0,'127.0.0.1');await once(app,'listening');
 t.after(async()=>{app.closeAllConnections();backend.closeAllConnections();await Promise.all([new Promise(r=>app.close(r)),new Promise(r=>backend.close(r))]);});
 const base='http://127.0.0.1:'+app.address().port;const url=base+'/backend/api/v1/organizations/org/checklists';
 const headers={Authorization:'Bearer token','Content-Type':'application/json',Cookie:'private','X-Role':'ADMIN','X-Organization-Id':'other'};
 const post=(value,extra={})=>fetch(url,{method:'POST',headers,body:JSON.stringify(value),...extra});
 assert.equal((await post(draft,{headers:{'Content-Type':'application/json'}})).status,401);
 assert.equal((await post(draft,{headers:{...headers,Origin:'https://foreign.example'}})).status,403);
 assert.equal((await post(draft,{headers:{...headers,'Content-Type':'text/plain'}})).status,415);
 for(const invalid of [{...draft,organizationId:'other'},{...draft,versionNumber:4},{...draft,items:[{...draft.items[0],role:'ADMIN'}]},
  {...draft,items:[{...draft.items[0],evidenceRequiredOnFailure:true}]},{...draft,description:'x'.repeat(65537)}])assert.equal((await post(invalid)).status,400);
 assert.equal((await fetch(url+'/id',{method:'POST',headers,body:'{}'})).status,405);
 assert.equal((await fetch(url+'?pageSize=2',{method:'POST',headers,body:'{}'})).status,404);assert.equal(calls,0);
 const success=await post(draft,{headers:{...headers,Origin:base}});assert.equal(success.status,201);assert.equal(success.headers.get('x-bpm-session-expires-at'),'1900000000');
 assert.equal((await success.text()).includes('hidden'),false);assert.equal(lastBody.versionNumber,undefined);assert.equal(lastBody.organizationId,undefined);
 status=422;const invalid=await post(draft);assert.equal(invalid.status,422);const failure=await invalid.json();assert.deepEqual(Object.keys(failure.errors[0].fields),['name']);assert.equal(JSON.stringify(failure).includes('private'),false);
 status=409;assert.equal((await post(draft)).status,409);
 status=403;assert.equal((await post(draft)).status,403);
});
