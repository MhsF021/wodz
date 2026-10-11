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
  throw new Error(id);
 }});return exports;
}
const link=p=>React.createElement('a',{href:p.to,className:p.className},p.children);
const fields={Field:p=>React.createElement('label',{},p.label,p.children)};
const states={InlineError:()=>null,Loading:()=>null,ErrorState:()=>null,Empty:p=>React.createElement('div',{},p.title,p.children)};

test('different passwords stop signup before the API, matching passwords keep the selected event',async()=>{
 for(const confirmation of ['different','correct-password']) {
  let index=0;let calls=0;let destination;let error;
  const values=[{name:'QA',email:'qa@example.invalid',phone:'',password:'correct-password'},confirmation,'ATHLETE',null,false];
  const component=module('../src/pages/SignupPage.tsx',{
   react:{...React,useState:()=>{const i=index++;return[values[i],v=>{if(i===3)error=v;}];}},
   'react-router':{Link:link,useSearchParams:()=>[new URLSearchParams('next=%2Fc%2Fevento%2Finscricao')],useNavigate:()=>p=>destination=p},
   '../auth/AuthContext':{useAuth:()=>({signup:async()=>{calls++;return{role:'ATHLETE'};}})},
   '../components/Field':fields,'../components/States':states,'../components/GoogleAuth':{GoogleAuth:()=>null},
   '../components/RoleChoice':{RoleChoice:()=>null},'../lib/navigation':{localReturnPath:p=>p},
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

test('browser success parameters never confirm a pending payment and checkout uses the same tab',()=>{
 let mutation;let assigned;let replaced;let query=0;
 const registration={code:'ABC',teamName:'EQUIPE',categoryName:'RX',championshipName:'EVENTO',championshipSlug:'evento',
  amount:150,teamStatus:'PENDING_PAYMENT',lastPayment:{status:'PENDING'},checkoutId:20};
 const {checkoutDestination}=module('../src/lib/checkout.ts',{}, {URL});
 const component=module('../src/pages/PaymentPage.tsx',{
  react:{...React,useState:x=>[x,()=>{}],useEffect:()=>{}},
  'react-router':{Link:link,useParams:()=>({code:'ABC'}),useSearchParams:()=>[new URLSearchParams('checkout=20&status=approved&retorno=sucesso'),p=>replaced=p]},
  '@tanstack/react-query':{useQuery:()=>({isPending:false,data:registration,refetch:()=>{}}),useMutation:c=>{mutation=c;return{mutate(){}};}},
  '../api/endpoints':{paymentApi:{}},'../lib/format':{money:x=>String(x)},'../lib/checkout':{checkoutDestination},'../components/States':states,
 },{window:{location:{assign:x=>assigned=x}}}).default;
 const html=renderToStaticMarkup(React.createElement(component));
 assert.match(html,/Aguardando confirmação/);assert.doesNotMatch(html,/Pagamento confirmado|type="password"|<iframe|<form/);
 mutation.onSuccess({id:20,url:'https://www.mercadopago.com.br/checkout/v1/redirect?pref_id=1'});
 assert.equal(assigned,'https://www.mercadopago.com.br/checkout/v1/redirect?pref_id=1');assert.equal(replaced.checkout,'20');
});
