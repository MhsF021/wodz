import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createRequire} from 'node:module';
import {test} from 'node:test';
import {runInNewContext} from 'node:vm';
import React from 'react';
import {renderToStaticMarkup} from 'react-dom/server';
import ts from 'typescript';
const nativeRequire=createRequire(import.meta.url);
const compile=path=>ts.transpileModule(readFileSync(new URL(path,import.meta.url),'utf8'),{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}}).outputText;
function module(path,imports,context={}) {
 const exports={};runInNewContext(compile(path),{exports,...context,require:id=>{
  if(id==='react/jsx-runtime')return nativeRequire(id);
  if(id in imports)return imports[id];
  if(id==='lucide-react')return {Check:()=>null,Lock:()=>null};
  throw new Error(id);
 }});return exports;
}
const link=p=>React.createElement('a',{href:p.to,className:p.className},p.children);
const fields={Field:p=>React.createElement('label',{},p.label,p.children)};
const states={InlineError:()=>null,Loading:()=>null,ErrorState:()=>null,Empty:p=>React.createElement('div',{},p.title,p.children)};
const navigation=module('../src/lib/navigation.ts',{}, {URL});
const format=module('../src/lib/format.ts',{});

test('different passwords stop signup before the API, matching passwords keep the selected event',async()=>{
 for(const confirmation of ['different','correct-password']) {
  let index=0;let calls=0;let destination;let error;
  const values=[{name:'QA',email:'qa@example.invalid',phone:'11999990000',password:'correct-password'},confirmation,'ATHLETE','F',null,false];
  const component=module('../src/pages/SignupPage.tsx',{
   react:{...React,useState:()=>{const i=index++;return[values[i],v=>{if(i===4)error=v;}];}},
   'react-router':{Link:link,useSearchParams:()=>[new URLSearchParams('next=%2Fc%2Fevento%2Finscricao')],useNavigate:()=>p=>destination=p},
   '../auth/AuthContext':{useAuth:()=>({signup:async()=>{calls++;return{role:'ATHLETE',gender:'F',document:'52998224725',phone:'11999990000'};}})},
   '../components/Field':fields,'../components/States':states,'../components/GoogleAuth':{GoogleAuth:()=>null},
   '../components/RoleChoice':{RoleChoice:()=>null},'../lib/navigation':navigation,'../lib/format':format,
  }).default;
  const element=component();const form=element.props.children.find(x=>x?.type==='form');
  await form.props.onSubmit({preventDefault(){}});
  if(confirmation==='different'){assert.equal(calls,0);assert.match(error.message,/confirmação de senha/);}
  else{assert.equal(calls,1);assert.equal(destination,'/c/evento/inscricao');}
 }
});

test('event cards have independent details and signup destinations without nested anchors',()=>{
 let state=0;
 const component=module('../src/pages/HomePage.tsx',{
  react:{...React,useState:init=>[init,()=>{}],useEffect:()=>{}},
  'react-router':{Link:link,useSearchParams:()=>[new URLSearchParams(),()=>{}]},
  '@tanstack/react-query':{useQuery:()=>({data:[{id:1,slug:'evento',name:'EVENTO',venueName:'LOCAL',city:'CIDADE',state:'SP',registrationOpen:true},
   {id:2,slug:'fechado',name:'FECHADO',venueName:'LOCAL',city:'CIDADE',state:'SP',registrationOpen:false}]})},
  'lucide-react':{Search:()=>null},'../api/endpoints':{publicApi:{}},'../lib/format':{UFS:[],dayBlock:()=>({days:'10',month:'OUT'})},
  '../lib/championshipFilters':module('../src/lib/championshipFilters.ts',{}),
  '../components/EventImage':{EventImage:()=>null},
  '../lib/theme':{brandLogoFor:()=>'/logo.svg'},'../auth/AuthContext':{useAuth:()=>({user:null,loading:false})},'./AuthContext':{useAuth:()=>({user:null,loading:false})},'../components/States':states,
 }).default;
 const html=renderToStaticMarkup(React.createElement(component));
 assert.match(html,/class="champ-cards"/);assert.equal((html.match(/<article/g)||[]).length,2);
 assert.match(html,/href="\/c\/evento"[^>]*>Detalhes do evento/);
 assert.match(html,/href="\/c\/evento\/inscricao"[^>]*>Inscrever-se/);
 assert.doesNotMatch(html,/href="\/c\/fechado\/inscricao"/);
 let open=0;for(const tag of html.matchAll(/<a\b[^>]*>|<\/a>/g)){open+=tag[0].startsWith('</')?-1:1;assert.ok(open<=1);}assert.equal(open,0);
});

test('anonymous signup route redirects to login with the original registration destination',()=>{
 const component=module('../src/auth/RequireRole.tsx',{
  '../lib/navigation':navigation,
  'react-router':{Link:link,Navigate:p=>React.createElement('div',{'data-to':p.to}),useLocation:()=>({pathname:'/c/evento/inscricao',search:'?categoria=1'})},
  '../auth/AuthContext':{useAuth:()=>({user:null,loading:false})},'./AuthContext':{useAuth:()=>({user:null,loading:false})},'../components/States':states,
 }).RequireRole;
 const element=component({role:'ATHLETE',children:'form'});
 assert.equal(element.props.to,'/entrar?next=%2Fc%2Fevento%2Finscricao%3Fcategoria%3D1');
});

test('checkout permits only official HTTPS checkout pages',()=>{
 const {checkoutDestination}=module('../src/lib/checkout.ts',{}, {URL});
 assert.equal(checkoutDestination('https://www.mercadopago.com.br/checkout/v1/redirect?pref_id=1'),'https://www.mercadopago.com.br/checkout/v1/redirect?pref_id=1');
 for(const url of ['https://sandbox.mercadopago.com.br/checkout/v1/redirect?pref_id=1',
  '//www.mercadopago.com.br/checkout/v1','javascript:alert(1)','https://www.mercadopago.com.br.attacker.test/checkout/v1',
  'https://user@www.mercadopago.com.br/checkout/v1','https://www.mercadopago.com.br:8443/checkout/v1','https://www.mercadopago.com.br/profile'])
  assert.equal(checkoutDestination(url),null);
});

test('checkout leaves payment choice to Mercado Pago and browser success cannot confirm payment',async()=>{
 let mutation;let assigned;let replaced;let requested;
 const registration={code:'ABC',teamName:'EQUIPE',categoryName:'RX',championshipName:'EVENTO',championshipSlug:'evento',
  amount:150,teamStatus:'PENDING_PAYMENT',lastPayment:{status:'PENDING'},checkoutId:20};
 const {checkoutDestination}=module('../src/lib/checkout.ts',{}, {URL});
 const component=module('../src/pages/PaymentPage.tsx',{
  react:{...React,useState:x=>[x,()=>{}],useEffect:()=>{}},
  'react-router':{Link:link,useParams:()=>({code:'ABC'}),useSearchParams:()=>[new URLSearchParams('checkout=20&status=approved&retorno=sucesso'),p=>replaced=p]},
  '@tanstack/react-query':{useQuery:o=>({isPending:false,data:o.queryKey[0]==='checkout-details'
   ? {id:20,method:'CARD',expiresAt:new Date(Date.now()+600000).toISOString()} : registration,refetch:()=>{}}),useMutation:c=>{mutation=c;return{mutate(){}};}},
  '../api/endpoints':{paymentApi:{checkout:async(code,request)=>{requested={code,request};}},publicApi:{}},'../lib/format':{money:x=>String(x)},'../lib/checkout':{checkoutDestination},'../components/States':states,
 },{window:{location:{assign:x=>assigned=x}}}).default;
 const html=renderToStaticMarkup(React.createElement(component));
 assert.match(html,/Aguardando confirmação/);assert.doesNotMatch(html,/Pagamento confirmado|type="password"|<iframe|<form/);
 assert.match(html,/Lá, escolha como pagar: Pix, cartão/);
 assert.doesNotMatch(html,/<select|type="radio"|Gerar Pix|QR Code|Copia e Cola/);
 await mutation.mutationFn();
 assert.equal(requested.code,'ABC');
 assert.deepEqual(Object.keys(requested.request),['termsAccepted']);
 mutation.onSuccess({id:20,url:'https://www.mercadopago.com.br/checkout/v1/redirect?pref_id=1'});
 assert.equal(assigned,'https://www.mercadopago.com.br/checkout/v1/redirect?pref_id=1');assert.equal(replaced.checkout,'20');
});

test('return without a provider payment offers a retry instead of claiming a pending transaction',()=>{
 for(const payment of [null,{status:'REJECTED'},{status:'PENDING'}]) {
  const registration={code:'ABC',teamName:'EQUIPE',categoryName:'RX',championshipName:'EVENTO',championshipSlug:'evento',
   amount:210,teamStatus:'PENDING_PAYMENT',lastPayment:payment,checkoutId:20};
  const component=module('../src/pages/PaymentPage.tsx',{
   react:{...React,useState:x=>[x,()=>{}],useEffect:()=>{}},
   'react-router':{Link:link,useParams:()=>({code:'ABC'}),useSearchParams:()=>[new URLSearchParams('checkout=20&status=approved&retorno=sucesso'),()=>{}]},
   '@tanstack/react-query':{useQuery:options=>({isPending:false,data:options.queryKey[0]==='public-config'
    ? {mercadoPagoTestMode:true} : options.queryKey[0]==='checkout-details'
    ? {id:20,method:'CARD',expiresAt:new Date(Date.now()+600000).toISOString()} : registration,refetch:()=>{}}),useMutation:()=>({mutate(){}})},
   '../api/endpoints':{paymentApi:{},publicApi:{}},'../lib/format':{money:x=>String(x)},
   '../lib/checkout':{checkoutDestination:x=>x},'../components/States':states,
  }).default;
  const html=renderToStaticMarkup(React.createElement(component));
  assert.doesNotMatch(html,/Pagamento confirmado/);
  assert.match(html,/Modo de teste de pagamento/);
  assert.doesNotMatch(html,/QR Code|Copia e Cola|Gerar Pix|Responsável Pagador/);
  if(!payment){assert.match(html,/Ainda não há um pagamento registrado/);assert.doesNotMatch(html,/Aguardando confirmação/);}
  else if(payment.status==='REJECTED'){assert.match(html,/não foi aprovado/);assert.doesNotMatch(html,/Ainda não há um pagamento registrado|Aguardando confirmação/);}
  else{assert.match(html,/Aguardando confirmação/);assert.doesNotMatch(html,/Ainda não há um pagamento registrado/);}
 }
});

test('checkout requires consent and submits no payment method, payer or QR fields',async()=>{
 for(const accepted of [false,true]) {
  let mutation;let sent;let state=0;
  const registration={code:'ABC',teamName:'EQUIPE',categoryName:'RX',championshipName:'EVENTO',championshipSlug:'evento',
   amount:210,teamStatus:'PENDING_PAYMENT',lastPayment:null,checkoutId:null};
  const Page=module('../src/pages/PaymentPage.tsx',{
   react:{...React,useState:x=>[state++===1?accepted:x,()=>{}],useEffect:()=>{}},
   'react-router':{Link:link,useParams:()=>({code:'ABC'}),useSearchParams:()=>[new URLSearchParams('checkout=10'),()=>{}]},
   '@tanstack/react-query':{useQuery:o=>({isPending:false,data:o.queryKey[0]==='public-config'?{}:registration,refetch:()=>{}}),
    useMutation:o=>{mutation=o;return{mutate(){}};}},
   '../api/endpoints':{publicApi:{},paymentApi:{checkout:async(code,body)=>{sent={code,body};return{id:20,method:'CARD'};}}},
   '../lib/format':{money:x=>String(x)},'../lib/checkout':{checkoutDestination:x=>x},'../components/States':states,
  }).default;
  const html=renderToStaticMarkup(React.createElement(Page));
  assert.match(html,/Continuar para pagamento/);
  assert.doesNotMatch(html,/QR Code|Copia e Cola|CPF|payment-methods|type="radio"/);
  const button=html.match(/<button[^>]*>Continuar para pagamento<\/button>/)[0];
  assert.equal(button.includes('disabled'),!accepted);
  if(accepted){await mutation.mutationFn();assert.equal(sent.code,'ABC');
   assert.deepEqual(JSON.parse(JSON.stringify(sent.body)),{termsAccepted:true});}
 }
});

test('pending Pix remains pending when the preference expires and cannot start another checkout',()=>{
 let state=0;let statusQuery;
 const registration={code:'ABC',teamName:'EQUIPE',categoryName:'RX',championshipName:'EVENTO',championshipSlug:'evento',
  amount:210,teamStatus:'PENDING_PAYMENT',lastPayment:{status:'PENDING',method:'PIX'},checkoutId:20};
 const Page=module('../src/pages/PaymentPage.tsx',{
  react:{...React,useState:x=>[state++===1?true:x,()=>{}],useEffect:()=>{}},
  'react-router':{Link:link,useParams:()=>({code:'ABC'}),useSearchParams:()=>[new URLSearchParams('checkout=20'),()=>{}]},
  '@tanstack/react-query':{useQuery:o=>{
   if(o.queryKey[0]==='checkout-status')statusQuery=o;
   return{isPending:false,data:o.queryKey[0]==='public-config'?{}:o.queryKey[0]==='checkout-details'
    ? {id:20,expiresAt:new Date(Date.now()-60000).toISOString()}:registration,refetch:()=>{}};
  },useMutation:()=>({mutate(){}})},
  '../api/endpoints':{publicApi:{},paymentApi:{}},'../lib/format':{money:x=>String(x)},
  '../lib/checkout':{checkoutDestination:x=>x},'../components/States':states,
 }).default;
 const html=renderToStaticMarkup(React.createElement(Page));
 assert.match(html,/Aguardando confirmação/);
 assert.doesNotMatch(html,/prazo desta cobrança terminou|gerar um novo pagamento/);
 assert.match(html,/<button[^>]*disabled[^>]*>Continuar para pagamento<\/button>/);
 assert.equal(statusQuery.refetchInterval({state:{data:registration}}),5000);
});
