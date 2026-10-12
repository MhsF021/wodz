import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { test } from 'node:test';
import { runInNewContext } from 'node:vm';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import ts from 'typescript';
const require = createRequire(import.meta.url);
const load = (path, imports, context = {}) => {
  const exports = {};
  const code = ts.transpileModule(readFileSync(new URL(path, import.meta.url), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
  }).outputText;
  runInNewContext(code, { exports, ...context, require: id => id === 'react/jsx-runtime' ? require(id) : imports[id] });
  return exports;
};
const format = load('../src/lib/format.ts', {});
const registration = {code:'ABC',teamName:'EQUIPE',categoryName:'RX',championshipName:'EVENTO',championshipSlug:'evento',
  amount:210,teamStatus:'PENDING_PAYMENT',lastPayment:null,checkoutId:null};
const qr = {id:20,method:'PIX',expiresAt:new Date(Date.now()+600000).toISOString(),qrCode:'000201-test',qrCodeBase64:'iVBORw0KGgo='};
const link = p => React.createElement('a', {href:p.to,target:p.target,rel:p.rel}, p.children);
function fixture({ method='PIX', cpf='', accepted=false, checkout, payment=null, confirmed=false, copyFails=false }={}) {
  let index=0, mutation, request, replaced, redirected, clipboard, copyError;
  const effects=[],handlers={},calls={};
  const values=[false,method,cpf,accepted,false,false,Date.now()];
  const summary={...registration,checkoutId:checkout?.id,lastPayment:payment,teamStatus:confirmed?'CONFIRMED':'PENDING_PAYMENT'};
  const component=load('../src/pages/PaymentPage.tsx',{
    react:{...React,useState:()=>{const i=index++;return[values[i],value=>{if(i===5)copyError=value;}];},useEffect:fn=>effects.push(fn)},
    'react-router':{Link:link,useParams:()=>({code:'ABC'}),useSearchParams:()=>[new URLSearchParams(checkout?'checkout=20&status=approved':''),p=>replaced=p]},
    '@tanstack/react-query':{useQuery:o=>({isPending:false,data:o.queryKey[0]==='checkout-details'?checkout:o.queryKey[0]==='public-config'?{}:summary,
      refetch(){calls[o.queryKey[0]]=(calls[o.queryKey[0]]??0)+1;}}),
      useMutation:c=>{mutation=c;return{data:null,mutate(){},reset(){}};}},
    '../api/endpoints':{publicApi:{},paymentApi:{checkout:async(code,body)=>{request={code,body};return qr;}}},
    '../lib/format':format,'../lib/checkout':load('../src/lib/checkout.ts',{}, {URL}),
    '../components/States':{InlineError:()=>null,Loading:()=>null,ErrorState:()=>null,Empty:p=>React.createElement('div',{},p.title,p.children)},
  },{window:{location:{assign:url=>redirected=url},setInterval:()=>1,clearInterval(){},addEventListener:(name,fn)=>handlers[name]=fn,removeEventListener(){}},
    navigator:{clipboard:{writeText:async value=>{if(copyFails)throw Error();clipboard=value;}}}}).default;
  const element=component();
  return {element,html:renderToStaticMarkup(element),mutation,calls,resume(){effects.forEach(fn=>fn());handlers.pageshow();},
    get request(){return request;},get replaced(){return replaced;},get redirected(){return redirected;},get clipboard(){return clipboard;},get copyError(){return copyError;}};
}
const nodes = element => {
  if (!element || typeof element !== 'object') return [];
  return [element,...React.Children.toArray(element.props?.children).flatMap(nodes)];
};
test('Pix only asks payer CPF, links published policies and requires valid CPF plus consent',()=>{
  for(const [cpf,accepted,disabled] of [['',true,true],['11111111111',true,true],['191.191.191-00',false,true],['191.191.191-00',true,false]]){
    const f=fixture({cpf,accepted});
    assert.match(f.html,/CPF do Responsável Pagador/);
    assert.doesNotMatch(f.html,/type="email"|type="password"|name="card/);
    assert.match(f.html,/href="\/termos"/);assert.match(f.html,/href="\/privacidade"/);
    assert.equal(nodes(f.element).find(n=>n.type==='button'&&n.props.children==='Gerar Pix').props.disabled,disabled);
  }
});
test('Pix payload carries choice, normalized CPF and acceptance, and stays in WODZ',async()=>{
  const f=fixture({cpf:'191.191.191-00',accepted:true});
  await f.mutation.mutationFn();
  assert.equal(f.request.code,'ABC');assert.deepEqual(JSON.parse(JSON.stringify(f.request.body)),{method:'PIX',payerCpf:'19119119100',termsAccepted:true});
  f.mutation.onSuccess(qr);
  assert.equal(f.replaced.checkout,'20');assert.equal(f.redirected,undefined);
});
test('card omits CPF and redirects only to the checked Mercado Pago URL',async()=>{
  const f=fixture({method:'CARD',accepted:true});
  assert.match(f.html,/Redirecionamento seguro/);assert.doesNotMatch(f.html,/id="payer-cpf"/);
  await f.mutation.mutationFn();assert.deepEqual(JSON.parse(JSON.stringify(f.request.body)),{method:'CARD',termsAccepted:true});
  assert.throws(()=>f.mutation.onSuccess({id:20,method:'CARD',url:'https://evil.invalid/checkout'}));
  f.mutation.onSuccess({id:20,method:'CARD',url:'https://www.mercadopago.com.br/checkout/v1/redirect?pref_id=1'});
  assert.match(f.redirected,/^https:\/\/www.mercadopago.com.br\//);
});
test('pending Pix resumes provider QR, copies it and never trusts browser success',async()=>{
  const f=fixture({checkout:qr,payment:{status:'PENDING'}});
  assert.match(f.html,/data:image\/png;base64,iVBOR/);assert.match(f.html,/Aguardando pagamento Pix/);
  assert.doesNotMatch(f.html,/Pagamento confirmado|id="payer-cpf"/);
  const copy=nodes(f.element).find(n=>n.type==='button'&&n.props.children==='Copiar');await copy.props.onClick();assert.equal(f.clipboard,'000201-test');
  const failed=fixture({checkout:qr,copyFails:true});await nodes(failed.element).find(n=>n.type==='button'&&n.props.children==='Copiar').props.onClick();assert.equal(failed.copyError,true);
});
test('expired, rejected, refunded and confirmed payments no longer display a payable QR',()=>{
  for(const f of [fixture({checkout:{...qr,expiresAt:new Date(Date.now()-1000).toISOString()}}),
    fixture({checkout:qr,payment:{status:'REJECTED'}}),fixture({checkout:qr,payment:{status:'REFUNDED'}}),fixture({checkout:qr,confirmed:true})]){
    assert.doesNotMatch(f.html,/data:image\/png|Aguardando pagamento Pix/);
  }
  assert.match(fixture({checkout:qr,confirmed:true}).html,/Pagamento confirmado/);
});
test('legal pages describe payment verification, refunds and privacy without blanket image consent',()=>{
  const Legal=load('../src/pages/LegalPage.tsx',{'react-router':{Link:link}}).default;
  const terms=renderToStaticMarkup(React.createElement(Legal));
  const privacy=renderToStaticMarkup(React.createElement(Legal,{privacy:true}));
  assert.match(terms,/sete dias/);assert.match(terms,/não executa automaticamente um reembolso/);
  assert.match(terms,/não concede uma autorização geral/);assert.match(privacy,/armazenados cifrados/);
  assert.match(privacy,/mailto:mhsferreira021@gmail.com/);assert.match(privacy,/dados completos.*não compõem o placar público/);
});
test('pageshow only resumes status and QR when a checkout exists',()=>{
  const fresh=fixture(); fresh.resume(); assert.deepEqual(fresh.calls,{registration:1});
  const resumed=fixture({checkout:qr}); resumed.resume();
  assert.deepEqual(resumed.calls,{registration:1,'checkout-status':1,'checkout-details':1});
});
