const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const html = fs.readFileSync(path.join(__dirname, '../index.html'), 'utf8');
const scripts = [...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)].map(x => x[1]);
scripts.forEach((s, i) => new vm.Script(s, {filename: `employee-${i}.js`}));
const clone = x => x === undefined ? undefined : JSON.parse(JSON.stringify(x));
const offer = () => ({request:{id:'inv_test',weddingId:'w_test',weddingDate:'2026-10-18',weddingTitle:'Тест свадба',allowedRoles:['Фотограф']},response:{status:'accepted',roles:['Фотограф'],proposedFee:120,originalEmployeeFee:150,counterBy:'td',awaitingEmployee:true,counteredAt:'2026-10-11T21:00:00Z',counterNote:'TD тест понуда'}});
function setup(inv = offer()) {
  const s={requests:[],alerts:[],events:new Map(),docEvents:new Map(),intervals:[],fail:false,delay:0};
  s.remote={tdStudios:{studio_test:{invites:{user_test:{inv_test:clone(inv),__recovery__:{request:{system:'recovery'}}}},messages:{user_test:{}}}}};
  const nodes=new Map();let ctx;
  class El {
    constructor(tag){this.tagName=tag;this.id='';this.value='';this.textContent='';this.style={};this.dataset={};this.disabled=false;this.listeners=[];this.classes=new Set();this.classList={add:x=>this.classes.add(x),remove:x=>this.classes.delete(x),toggle:(x,on)=>on?this.classes.add(x):this.classes.delete(x)};}
    set innerHTML(v){this.html=v;this.parts=new Map();for(const m of v.matchAll(/<([\w-]+)\b([^>]*\bid="([^"]+)"[^>]*)>/g)){const e=new El(m[1]);e.id=m[3];e.inline=m[2].match(/onclick="([^"]*)"/)?.[1];this.parts.set(e.id,e);}}
    get innerHTML(){return this.html||'';}
    appendChild(e){nodes.set(e.id,e);for(const [id,n]of e.parts||[])nodes.set(id,n);}
    querySelector(q){return this.parts?.get(q.replace(/^#/,''))||null;}
    addEventListener(name,fn){this.listeners.push({name,fn});}
    remove(){nodes.delete(this.id);for(const [id,n]of this.parts||[])if(nodes.get(id)===n)nodes.delete(id);}
    focus(){}
    async click(force=false){if(this.disabled&&!force)return;for(const l of this.listeners.filter(x=>x.name==='click'))await l.fn({target:this,stopImmediatePropagation(){}});if(this.inline)vm.runInContext(this.inline,ctx);}
  }
  const markup=html.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi,'');
  for(const m of markup.matchAll(/<([\w-]+)\b[^>]*\bid="([^"]+)"[^>]*>/g)){const e=new El(m[1]);e.id=m[2];nodes.set(e.id,e);}
  const data=new Map([['tdEmpCloudV1',JSON.stringify({apiKey:'fixture',databaseURL:'https://employee-test.invalid'})],['tdEmpAuthV1',JSON.stringify({localId:'user_test',idToken:'fixture-token',expiresAt:Date.now()+3600000})],['tdEmpProfileV1',JSON.stringify({uid:'user_test',studioId:'studio_test',name:'Тест'})],['tdEmpPinV1',JSON.stringify({salt:'keep-this',hash:'keep-hash'})]]);
  const fetch=async(url,opt={})=>{assert.equal(new URL(url).hostname,'employee-test.invalid');const p=new URL(url).pathname.slice(1).replace(/\.json$/,'');const method=opt.method||'GET',body=opt.body?JSON.parse(opt.body):undefined;s.requests.push({path:p,method,body,cache:opt.cache});if(s.delay)await new Promise(r=>setTimeout(r,s.delay));if(s.fail&&method!=='GET')throw new Error('Нема интернет');let obj=s.remote;const keys=p.split('/');for(const k of keys.slice(0,-1))obj=obj?.[k];if(method==='PUT')obj[keys.at(-1)]=clone(body);else assert.equal(method,'GET');return{ok:true,text:async()=>JSON.stringify(obj?.[keys.at(-1)]??null)};};
  const globals={console,URL,URLSearchParams,TextEncoder,Uint8Array,AbortController,Date,crypto:require('node:crypto').webcrypto,fetch,setTimeout,clearTimeout,setInterval:(fn,ms)=>{s.intervals.push({fn,ms});return 0},
    localStorage:{getItem:k=>data.get(k)??null,setItem:(k,v)=>data.set(k,String(v)),removeItem:k=>data.delete(k)},document:{visibilityState:'visible',getElementById:id=>nodes.get(id)||null,createElement:t=>new El(t),body:new El('body'),querySelectorAll:()=>[],addEventListener:(n,f)=>s.docEvents.set(n,f)},
    navigator:{},alert:m=>s.alerts.push(m),confirm:()=>{throw new Error('Counteroffer must use a visible in-app dialog');},location:{reload(){}},addEventListener:(n,f)=>s.events.set(n,f)};
  ctx=vm.createContext(globals);ctx.window=ctx;scripts.forEach(code=>vm.runInContext(code,ctx));vm.runInContext('unlocked=true;',ctx);
  s.ctx=ctx;s.nodes=nodes;s.data=data;s.current=()=>s.remote.tdStudios.studio_test.invites.user_test.inv_test;s.sync=()=>ctx.empSync(false);s.open=(accept=true)=>accept?ctx.acceptTdCounter('inv_test'):ctx.rejectTdCounter('inv_test');s.save=()=>nodes.get('tdCounterSave').click();s.writes=()=>s.requests.filter(r=>r.method!=='GET');
  return s;
}
(async()=>{
  const results=[];
  let s=setup();await s.sync();assert.match(s.nodes.get('attention').innerHTML,/нови TD понуди/);assert.match(s.nodes.get('inviteList').innerHTML,/TD Production нуди: 120\.00/);assert.match(s.nodes.get('inviteList').innerHTML,/Прифаќам 120\.00/);assert.match(s.nodes.get('inviteList').innerHTML,/Одбивам TD цена/);assert.doesNotMatch(s.nodes.get('inviteList').innerHTML,/__recovery__/);assert.equal(s.requests.every(r=>r.cache==='no-store'),true);results.push('Synced counteroffer is visible on home and invites; recovery rows are hidden.');
  s.open();assert.match(s.nodes.get('dialog').innerHTML,/Прифати TD цена/);assert.equal(s.writes().length,0);await s.nodes.get('tdCounterCancel').click();assert.equal(s.writes().length,0);results.push('Confirmation displays the actual fee and cancel does not write.');
  s=setup();await s.sync();s.open();s.delay=5;const saved=s.save();await s.nodes.get('tdCounterSave').click(true);await saved;assert.equal(s.writes().length,1);assert.equal(s.current().response.counterBy,'employeeAcceptedTd');assert.equal(s.current().response.awaitingEmployee,false);assert.equal(s.current().response.proposedFee,120);assert.equal(s.current().response.originalEmployeeFee,150);assert.deepEqual(s.current().response.roles,['Фотограф']);assert.match(s.nodes.get('dialog').innerHTML,/Цената е прифатена/);assert.equal(s.current().decision,undefined);results.push('Accept preserves the quoted fee and history; duplicate clicks make one response write.');
  s=setup();await s.sync();s.open(false);await s.save();assert.equal(s.current().response.counterBy,'employeeRejectedTd');assert.equal(s.current().response.status,'accepted');assert.equal(s.current().response.awaitingEmployee,false);assert.match(s.nodes.get('dialog').innerHTML,/Цената е одбиена/);results.push('Reject sends the status TD Booking expects without confirming the engagement.');
  s=setup();await s.sync();s.open();s.fail=true;await s.save();assert.match(s.nodes.get('tdCounterSaveStatus').textContent,/Нема интернет/);assert.equal(s.current().response.counterBy,'td');assert.equal(s.nodes.get('tdCounterSave').disabled,false);s.fail=false;await s.save();assert.equal(s.current().response.counterBy,'employeeAcceptedTd');results.push('A failed reply remains pending, shows the error, and permits retry.');
  s=setup();await s.sync();s.open();s.current().response.proposedFee=100;await s.save();assert.equal(s.writes().length,0);assert.match(s.nodes.get('tdCounterSaveStatus').textContent,/изменета/);results.push('Changed TD price is not accepted from a stale confirmation.');
  s=setup();await s.sync();s.open();s.current().decision={status:'confirmed',confirmedFee:120};s.current().contract={base:{body:'keep'},signature:{data:'keep-signature',signedAt:'2026-10-11T21:00:00Z'}};await s.save();assert.equal(s.writes().length,0);assert.equal(s.current().contract.signature.data,'keep-signature');results.push('Confirmed and signed engagements cannot be overwritten by a counteroffer reply.');
  for(const raw of [null,undefined,'',-1,'bad']){const inv=offer();inv.response.proposedFee=raw;s=setup(inv);await s.sync();s.open();assert.equal(s.nodes.has('tdCounterSave'),false);assert.equal(s.writes().length,0);}results.push('Missing and invalid TD prices are rejected visibly.');
  const zero=offer();zero.response.proposedFee=0;s=setup(zero);await s.sync();s.open();await s.save();assert.equal(s.current().response.proposedFee,0);results.push('A valid zero-fee offer remains zero.');
  s=setup();await s.sync();s.data.set('tdEmpPinV1','original-pin');const profile=s.data.get('tdEmpProfileV1'),auth=s.data.get('tdEmpAuthV1');s.open();await s.save();assert.equal(s.data.get('tdEmpPinV1'),'original-pin');assert.equal(s.data.get('tdEmpProfileV1'),profile);assert.equal(s.data.get('tdEmpAuthV1'),auth);assert.equal(JSON.parse(s.data.get('tdEmpCacheV1')).invites.inv_test.response.counterBy,'employeeAcceptedTd');results.push('Reply updates the app cache while preserving profile, auth, and PIN.');
  s=setup();assert.equal(s.intervals[0].ms,15000);vm.runInContext('empSync=async()=>{window.syncTriggerCount=(window.syncTriggerCount||0)+1};',s.ctx);s.docEvents.get('visibilitychange')();s.events.get('pageshow')();s.intervals[0].fn();assert.equal(s.ctx.syncTriggerCount,3);s.ctx.document.visibilityState='hidden';s.docEvents.get('visibilitychange')();s.intervals[0].fn();assert.equal(s.ctx.syncTriggerCount,3);results.push('Return-to-app and visible polling refresh immediately; hidden polling stays idle.');
  console.log(JSON.stringify({passed:results.length,results},null,2));
})().catch(e=>{console.error(e);process.exitCode=1;});
