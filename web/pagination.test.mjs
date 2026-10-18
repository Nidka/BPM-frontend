import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { createAppServer } from './server.mjs';
import { pageQuery, publicPagination } from './pagination.mjs';

test('paginación: solo parámetros acotados, sin duplicados ni ampliación de rutas', () => {
  for (const query of ['pageSize=0','pageSize=101','pageSize=-1','pageSize=01','pageToken=bad!','query=%00','role=ADMIN','pageSize=1&pageSize=2','query=a&query=b', 'query='+'a'.repeat(161)])
    assert.throws(() => pageQuery(new URL('https://app.example/?'+query)));
  assert.throws(() => pageQuery(new URL('https://app.example/?query=tenant'), { workspace:true }));
  assert.throws(() => pageQuery(new URL('https://app.example/?pageSize=1'), { detail:true }));
  const query = pageQuery(new URL('https://app.example/?pageSize=17&pageToken=next_2&query=%25_%C3%A1'));
  assert.equal(new URL('https://app.example/'+query).searchParams.get('query'), '%_á');
  assert.deepEqual(publicPagination({nextPageToken:'next_2',secret:'private'}), {nextPageToken:'next_2'});
  assert.throws(() => publicPagination({nextPageToken:'x'.repeat(2049)}));
});

test('proxy paginado conserva cursor, totales, filtros literales y detalle, sin datos privados', async t => {
  const seen = [];
  const org = {id:'org',legalName:'Pruebas',tradeName:'',businessType:'RESTAURANT',taxId:'',contactName:'',contactPhone:'',contactEmail:''};
  const template = {id:'tpl', name:'Control', area:'Cocina',description:'',versionId:'version',versionNumber:1,
    items:[{id:'item',sectionName:'Personal',label:'Pregunta',responseType:'COMPLIANCE',displayOrder:1,required:true}]};
  const backend = http.createServer((req,res) => {
    seen.push(req.url);
    assert.equal(req.headers.authorization,'Bearer test.token');
    assert.equal(req.headers.cookie,undefined);
    const url = new URL(req.url,'http://backend');
    let data = [];
    if (url.pathname.endsWith('/workspace')) data = {organization:org,role:'ADMIN',locations:[],members:[],courses:[],checklists:[],incidents:[],
      nextPageTokens:{locations:'next_25',private:'secret'},totals:{locations:137,courses:20,checklists:12,private:'secret'}};
    else if (url.pathname.endsWith('/checklists/tpl')) data=template;
    else if (url.pathname.endsWith('/checklists')) data=[template];
    res.writeHead(200, {'Content-Type':'application/json','X-BPM-Session-Expires-At':'1900000000'});
    res.end(JSON.stringify({data,pagination:{nextPageToken:'next_25',private:'secret'},private:'secret'}));
  });
  backend.listen(0,'127.0.0.1');await once(backend,'listening');
  const app = createAppServer({apiBaseUrl:'http://127.0.0.1:'+backend.address().port,allowInsecureForTests:true});
  app.listen(0,'127.0.0.1');await once(app,'listening');
  t.after(async()=>{app.closeAllConnections();backend.closeAllConnections();await Promise.all([new Promise(r=>app.close(r)),new Promise(r=>backend.close(r))]);});
  const base='http://127.0.0.1:'+app.address().port+'/backend/api/v1/organizations';
  const headers={Authorization:'Bearer test.token',Cookie:'private'};
  for (const resource of ['', '/org/locations','/org/members','/org/courses','/org/incidents','/org/checklist-summaries','/org/checklists']) {
    const response=await fetch(base+resource+'?pageSize=17&pageToken=next_2&query=%25_%C3%A1',{headers});
    assert.equal(response.status,200,resource);
    const body=await response.json();assert.equal(body.pagination.nextPageToken,'next_25');assert.equal(JSON.stringify(body).includes('secret'),false);
    const forwarded = new URL(seen.at(-1),'http://backend');
    assert.equal(forwarded.searchParams.get('pageSize'),'17');assert.equal(forwarded.searchParams.get('pageToken'),'next_2');assert.equal(forwarded.searchParams.get('query'),'%_á');
  }
  const workspace=await(await fetch(base+'/org/workspace?pageSize=25',{headers})).json();
  assert.deepEqual(workspace.data.totals,{locations:137,courses:20,checklists:12});assert.deepEqual(workspace.data.nextPageTokens,{locations:'next_25'});
  const detail=await(await fetch(base+'/org/checklists/tpl',{headers})).json();assert.equal(detail.data.items.length,1);assert.equal(detail.pagination,undefined);
  const before=seen.length;
  assert.equal((await fetch(base+'/org/checklists/tpl?pageSize=1',{headers})).status,404);
  assert.equal((await fetch(base+'/org/workspace?query=anything',{headers})).status,404);
  assert.equal(seen.length,before);
});
