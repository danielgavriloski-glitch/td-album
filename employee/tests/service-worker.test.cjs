const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
const code=fs.readFileSync(path.join(__dirname,'../sw.js'),'utf8');new vm.Script(code);
const listeners=new Map(),buckets=new Map([['td-employee-pwa-v1',new Map()],['td-album-cache-v8',new Map()],['another-app-cache',new Map()]]);
const calls=[],scope='https://test.invalid/td-album/employee/',index=scope+'index.html';let offline=false,claimed=false,skipped=false;
const response=text=>({ok:true,text,clone(){return response(text)}});
const key=r=>typeof r==='string'?r:r.url;
const caches={keys:async()=>[...buckets.keys()],delete:async k=>buckets.delete(k),open:async name=>{if(!buckets.has(name))buckets.set(name,new Map());const b=buckets.get(name);return{addAll:async files=>{for(const f of files)b.set(new URL(f,scope).href,response('installed'))},put:async(k,v)=>b.set(key(k),v),match:async k=>b.get(key(k))}}};
const fetch=async(r,o)=>{calls.push({r,o});if(offline)throw new Error('offline');return response('fresh-web-1.1');};
vm.runInNewContext(code,{URL,caches,fetch,self:{registration:{scope},location:{origin:'https://test.invalid'},clients:{claim:async()=>{claimed=true}},skipWaiting:async()=>{skipped=true},addEventListener:(n,f)=>listeners.set(n,f)}});
async function lifecycle(name){let p;listeners.get(name)({waitUntil:x=>p=x});await p;}
function request(url,mode='navigate',method='GET'){let p;listeners.get('fetch')({request:{url,mode,method},respondWith:x=>p=x});return p;}
(async()=>{
 await lifecycle('install');assert.equal(skipped,true);await lifecycle('activate');assert.equal(claimed,true);assert.equal(buckets.has('td-employee-pwa-v1'),false);assert.equal(buckets.has('td-album-cache-v8'),true);assert.equal(buckets.has('another-app-cache'),true);
 const first=await request(scope);assert.equal(first.text,'fresh-web-1.1');assert.equal(calls.at(-1).o.cache,'no-store');offline=true;const cached=await request(scope+'?v=1.1');assert.equal(cached.text,'fresh-web-1.1');assert.equal(request('https://firebase-test.invalid/some.json','cors'),undefined);assert.equal(request('https://test.invalid/td-album/index.html'),undefined);assert.equal(request(index,'cors','POST'),undefined);
 console.log('Service worker checks passed: fresh app HTML, offline fallback, isolated cache cleanup, and no interception of Firebase or album requests.');
})().catch(e=>{console.error(e);process.exitCode=1});
