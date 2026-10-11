import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createRequire} from 'node:module';
import {test} from 'node:test';
import {runInNewContext} from 'node:vm';
import ts from 'typescript';
const require=createRequire(import.meta.url);
const payoutReturn={};runInNewContext(ts.transpileModule(readFileSync(new URL('../src/lib/payoutReturn.ts',import.meta.url),'utf8'),{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText,{exports:payoutReturn,URLSearchParams});
const compiled=ts.transpileModule(readFileSync(new URL('../src/components/PayoutAccountSection.tsx',import.meta.url),'utf8'),{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}}).outputText;
function harness({result=null,reason=null,connected=false,version='v1',previous,isFetching=false,isError=false}={}){
 const effects=[],mutations=[],handlers=new Map(),storage=new Map(); const params=new URLSearchParams(result?{recebimentos:result}:{});if(reason)params.set('motivo',reason); const seen={};const account={data:{connected,updatedAt:version,liveMode:true,testMode:false},isFetching,isError,refetch:()=>{seen.refetched=true;return Promise.resolve({});}};
 if(previous)storage.set('wodz.payout-attempt',JSON.stringify({version:previous,at:Date.now()}));
 const exports={};runInNewContext(compiled,{exports,URL,URLSearchParams,Date,sessionStorage:{getItem:k=>storage.get(k)??null,setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)},window:{location:{assign:url=>seen.url=url},addEventListener:(name,fn)=>handlers.set(name,fn),removeEventListener:()=>{},open:()=>{throw new Error('popup');}},require:id=>{
  if(id==='react')return{useState:init=>[init,x=>seen.feedback=x],useEffect:fn=>effects.push(fn)};
  if(id==='react/jsx-runtime')return require(id);
  if(id==='@tanstack/react-query')return{useQuery:()=>account,useQueryClient:()=>({invalidateQueries:()=>{}}),useMutation:options=>{mutations.push(options);return{isPending:false,reset:()=>seen.reset=true};}};
  if(id==='react-router')return{useSearchParams:()=>[params,next=>seen.params=next]};
  if(id==='lucide-react')return{Wallet:()=>null};
  if(id==='../api/endpoints')return{adminApi:{}};
  if(id==='../lib/payoutReturn')return payoutReturn;
  if(id==='./ActionFeedback')return{ActionFeedback:()=>null};
  if(id==='./States')return{InlineError:()=>null,Loading:()=>null};throw new Error(id);
 }});
 exports.PayoutAccountSection();return{effects,mutations,handlers,storage,seen};
}
test('Mercado Pago authorization navigates this tab and preserves only an attempt version',()=>{
 const h=harness();h.mutations[0].onSuccess({authorizationUrl:'https://auth.mercadopago.com.br/authorization?state=example'});
 assert.equal(h.seen.url,'https://auth.mercadopago.com.br/authorization?state=example');assert.equal(Object.keys(JSON.parse(h.storage.get('wodz.payout-attempt'))).sort().join(','),'at,version');
});
test('unsafe authorization addresses are rejected before navigation',()=>{
 const h=harness();assert.throws(()=>h.mutations[0].onSuccess({authorizationUrl:'javascript:alert(1)'}));assert.equal(h.seen.url,undefined);
});
test('a successful callback still requires a connected account from the API',()=>{
 const h=harness({result:'connected',connected:false});h.effects.forEach(fn=>fn());assert.equal(h.seen.feedback.status,'pending');assert.equal(h.seen.params.has('recebimentos'),false);
});
test('renewed authorization is confirmed only when the API version changes',()=>{
 for(const version of ['v1','v2']){const h=harness({result:'connected',connected:true,version,previous:'v1'});h.effects.forEach(fn=>fn());assert.equal(h.seen.feedback.status,version==='v2'?'success':'pending');assert.equal(h.storage.size,0);}
});
test('a cancelled callback reports error even if an older connection exists',()=>{
 const h=harness({result:'error',connected:true});h.effects.forEach(fn=>fn());assert.equal(h.seen.feedback.status,'error');
});
test('returning with browser back resets a frozen mutation and rechecks the API',()=>{
 const h=harness({previous:'v1'});h.effects.forEach(fn=>fn());assert.equal(h.seen.feedback.status,'pending');h.handlers.get('pageshow')({persisted:true});assert.equal(h.seen.reset,true);assert.equal(h.seen.refetched,true);
});

test('provider configuration, mode and validation failures are not labelled cancellation',()=>{
 for(const reason of ['configuration','mode_mismatch','account_unavailable','provider_unavailable','unexpected']) {
  const h=harness({result:'error',reason});h.effects.forEach(fn=>fn());
  assert.equal(h.seen.feedback.status,'error');assert.doesNotMatch(h.seen.feedback.label,/cancelad|expirou/);
  assert.equal(h.seen.params.has('motivo'),false);
 }
});

test('successful callback does not confirm a cached account when the fresh API request is pending or fails',()=>{
 for(const state of [{isFetching:true},{isError:true}]) {
  const h=harness({result:'connected',connected:true,previous:'v1',...state});h.effects.forEach(fn=>fn());
  assert.equal(h.seen.feedback,undefined);assert.equal(h.storage.has('wodz.payout-attempt'),true);
 }
});

test('direct OAuth parameters on the SPA route identify a missing API callback and remove all credentials',()=>{
 const destination=payoutReturn.payoutReturnDestination(new URLSearchParams({code:'PRIVATE-CODE',state:'PRIVATE-STATE',error:'PRIVATE-MESSAGE'}));
 assert.equal(destination,'/minha-conta?recebimentos=error&motivo=callback_missing#recebimentos');
 assert.doesNotMatch(destination,/PRIVATE|code=|state=|error=/);
});

test('an unclassified return stays pending and error URLs only preserve fixed reasons',()=>{
 assert.equal(payoutReturn.payoutReturnDestination(new URLSearchParams()),'/minha-conta?recebimentos=pending#recebimentos');
 assert.equal(payoutReturn.payoutReturnDestination(new URLSearchParams({recebimentos:'connected',motivo:'PRIVATE'})),'/minha-conta?recebimentos=connected#recebimentos');
 assert.equal(payoutReturn.payoutReturnDestination(new URLSearchParams({recebimentos:'error',motivo:'configuration'})),'/minha-conta?recebimentos=error&motivo=configuration#recebimentos');
 for(const reason of ['PRIVATE-MESSAGE','__proto__','constructor','toString',null])assert.equal(payoutReturn.payoutFailureMessage(reason),payoutReturn.payoutFailureMessage('unexpected'));
});
