import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {createAppServer} from './server.mjs';

test('historial: filtros acotados, cabeceras seguras y contrato de páginas',async()=>{
 const calls=[];
 const backend=http.createServer((req,res)=>{
  calls.push({url:req.url,cookie:req.headers.cookie});res.setHeader('Content-Type','application/json');
  res.end(JSON.stringify({data:[{id:'run',templateName:'Limpieza',locationId:'site',locationName:'Lima',performedBy:'chef',performedByName:'Chef Pruebas',status:'DRAFT',createdAt:'2026-10-19T12:00:00Z',answerCount:0,questionCount:1,privateValue:'private'}],pagination:{nextPageToken:'next'}}));
 });
 await new Promise(r=>backend.listen(0,'127.0.0.1',r));
 const app=createAppServer({apiBaseUrl:`http://127.0.0.1:${backend.address().port}`,allowInsecureForTests:true});
 await new Promise(r=>app.listen(0,'127.0.0.1',r));
 const base=`http://127.0.0.1:${app.address().port}/backend/api/v1/organizations/org/checklist-runs`;
 try{
  const response=await fetch(base+'?pageSize=25&scope=team&status=DRAFT&query=Lima',{headers:{Authorization:'Bearer test',Cookie:'secret=private'}});
  assert.equal(response.status,200);const value=await response.json();assert.equal(value.data[0].templateName,'Limpieza');assert.equal(value.data[0].privateValue,undefined);assert.equal(value.pagination.nextPageToken,'next');
  assert.equal(calls[0].cookie,undefined);assert.match(calls[0].url,/scope=team/);
  const count=calls.length;
  for(const query of ['?scope=all','?scope=mine&scope=team','?status=OPEN','?actor=other','?pageSize=101'])
   assert.equal((await fetch(base+query,{headers:{Authorization:'Bearer test'}})).status,400);
  assert.equal(calls.length,count);
  assert.equal((await fetch(base)).status,401);
 }finally{app.closeAllConnections();backend.closeAllConnections();await Promise.all([new Promise(r=>app.close(r)),new Promise(r=>backend.close(r))]);}
});
