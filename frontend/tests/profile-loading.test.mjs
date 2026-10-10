import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createRequire} from 'node:module';
import {test} from 'node:test';
import {runInNewContext} from 'node:vm';
import React from 'react';
import {renderToStaticMarkup} from 'react-dom/server';
import ts from 'typescript';
const require=createRequire(import.meta.url);
const source=file=>ts.transpileModule(readFileSync(new URL(file,import.meta.url),'utf8'),{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}}).outputText;
const react={...React,useState:init=>[typeof init==='function'?init():init,()=>{}]};
function profile(user){
 const exports={};runInNewContext(source('../src/pages/MyProfilePage.tsx'),{exports,require:id=>{
  if(id==='react')return react;
  if(id==='react/jsx-runtime')return require(id);
  if(id==='@tanstack/react-query')return{useQuery:()=>({isPending:false,data:null})};
  if(id==='../auth/AuthContext')return{useAuth:()=>({user,logout:()=>{},updateProfile:()=>{}})};
  if(id==='../components/Field')return{Field:p=>React.createElement('label',{},p.label,p.children)};
  if(id==='../components/States')return{InlineError:()=>null,Loading:()=>null};
  if(id==='../components/GoogleButton')return{GoogleButton:()=>null};
  if(id==='../components/PayoutAccountSection')return{PayoutAccountSection:()=>null};
  if(id==='../api/endpoints')return{authApi:{},publicApi:{}};
  throw new Error(id);
 }});return renderToStaticMarkup(React.createElement(exports.default));
}
const user={id:1,name:'TESTE QA',email:'qa@example.invalid',role:'ATHLETE',phone:null};
test('Google-only profiles show unlink without any password fields or local deletion',()=>{
 const html=profile({...user,googleLinked:true,hasPassword:false});assert.match(html,/Desvincular conta Google/);assert.match(html,/Não é necessário criar uma senha/);assert.doesNotMatch(html,/type="password"|>Alterar senha<|>Excluir minha conta</);
});
test('Google-linked local accounts also hide password controls and explain retained local access',()=>{
 const html=profile({...user,role:'ORGANIZER',googleLinked:true,hasPassword:true});assert.match(html,/Desvincular conta Google/);assert.match(html,/acesso por e-mail e senha continuará disponível/);assert.doesNotMatch(html,/type="password"|>Alterar senha</);
});
test('password accounts retain password change and deletion controls',()=>{
 const html=profile({...user,googleLinked:false,hasPassword:true});assert.match(html,/type="password"/);assert.match(html,/>Alterar senha</);assert.match(html,/>Excluir minha conta</);assert.doesNotMatch(html,/Desvincular conta Google/);
});
test('loading shows only a ring while retaining an accessible label',()=>{
 const exports={};runInNewContext(source('../src/components/States.tsx'),{exports,require:id=>{
  if(id==='react/jsx-runtime')return require(id);
  if(id==='../lib/useActivity')return{useActivity:()=>{}};
  if(id==='lucide-react')return{CircleAlert:()=>null};
  if(id==='../api/client')return{errorMessage:()=>''};
  if(id==='react-router')return{Link:()=>null};
  throw new Error(id);
 }});const html=renderToStaticMarkup(React.createElement(exports.Loading,{label:'Carregando informações'}));assert.match(html,/aria-label="Carregando informações"/);assert.match(html,/class="spinner"/);assert.equal(html.replace(/<[^>]*>/g,''),'');
});

function layout({requesting=false,loading=false,previous='/',pathname='/',search=''}={}) {
 const indicator={};runInNewContext(source('../src/components/ActivityIndicator.tsx'),{exports:indicator,require});
 const exports={};let ref=0;
 const activity={};runInNewContext(source('../src/lib/activity.ts'),{exports:activity,URLSearchParams,setTimeout});
 runInNewContext(source('../src/components/Layout.tsx'),{exports,require:id=>{
  if(id==='react')return{...react,useEffect:()=>{},useLayoutEffect:()=>{},useRef:x=>({current:ref++===0?previous:x})};
  if(id==='react/jsx-runtime')return require(id);
  if(id==='react-router')return{useLocation:()=>({pathname,hash:'',search,key:'page'}),Link:p=>React.createElement('a',{href:p.to,'aria-label':p['aria-label'],className:p.className},p.children),NavLink:p=>React.createElement('a',{href:p.to},p.children),Outlet:()=>React.createElement('h1',{},'Informações prontas')};
  if(id==='lucide-react')return{Menu:()=>null,X:()=>null};
  if(id==='../auth/AuthContext')return{useAuth:()=>({user:null,loading,logout:()=>{}})};
  if(id==='../lib/format')return{firstName:()=>''};
  if(id==='../lib/theme')return{applyRoleTheme:()=>{},logoFor:()=>'/logo.svg'};
  if(id==='./Footer')return{Footer:()=>null};
  if(id==='./ActivityIndicator')return indicator;
  if(id==='../lib/useActivity')return{useForegroundActivity:()=>requesting};
  if(id==='../lib/activity')return{...activity,navigationActivity:()=>{}};
  throw new Error(id);
 }});return renderToStaticMarkup(React.createElement(exports.Layout));
}

test('page preloader precedes hidden mounted content and never appears in the toolbar',()=>{
 for(const state of [{requesting:true},{loading:true},{previous:null},{previous:'old-page'}]) {
  const html=layout(state);const header=html.match(/<header[\s\S]*?<\/header>/)[0];
  assert.doesNotMatch(header,/activity-indicator|class="spinner"/);
  assert.match(html,/<main[^>]*aria-busy="true"/);
  assert.match(html,/<div class="activity-indicator" role="status" aria-label="Carregando"><span class="spinner" aria-hidden="true"><\/span><\/div>/);
  assert.match(html,/<div class="main__content" aria-hidden="true" inert=""><h1>Informações prontas<\/h1>/);
 }
});

test('ready pages reveal mounted content and remove the preloader and inert state',()=>{
 const html=layout();assert.match(html,/<main[^>]*aria-busy="false"/);
 assert.match(html,/<div class="main__content" aria-hidden="false"><h1>Informações prontas<\/h1>/);
 assert.doesNotMatch(html,/activity-indicator|inert=""/);
});
test('local filters and team dialogs keep ready pages visible while championship tabs still load',()=>{
 for(const search of ['?status=OPEN','?q=Recife&uf=PE','?equipe=2']) {
  const html=layout({search});assert.match(html,/<main[^>]*aria-busy="false"/);assert.doesNotMatch(html,/activity-indicator/);
 }
 const html=layout({pathname:'/c/evento',search:'?aba=baterias',previous:'/c/evento'});
 assert.match(html,/<main[^>]*aria-busy="true"/);
});
test('Google button configures same-tab POST, matching nonce/state and latest selected role',async()=>{
 const exports={};let effect;let init;let button;let refreshed;let refs=0;const slot={clientWidth:400,clientHeight:40,style:{}};
 const google={accounts:{id:{initialize:x=>init=x,renderButton:(_,x)=>button=x}}};
 runInNewContext(source('../src/components/GoogleButton.tsx'),{exports,window:{location:{origin:'https://wodz.test'}},require:id=>{
  if(id==='react')return{useEffect:fn=>effect=fn,useRef:x=>({current:refs++===0?slot:x}),useState:()=>[null,()=>{}]};
  if(id==='react/jsx-runtime')return require(id);
  if(id==='../lib/googleIdentity')return{loadGoogleIdentity:()=>Promise.resolve(google)};
  if(id==='../lib/googleRedirect')return{prepareGoogleRedirect:()=> 'nonce',refreshGoogleRedirect:(id,flow)=>refreshed={id,flow}};
  throw new Error(id);
 }});
 exports.GoogleButton({clientId:'client',text:'signin_with',flow:{kind:'login',role:'ORGANIZER'}});effect();await Promise.resolve();await Promise.resolve();
 assert.equal(init.ux_mode,'redirect');assert.equal(init.login_uri,'https://wodz.test/api/public/google/callback');assert.equal(init.nonce,button.state);assert.equal(init.callback,undefined);button.click_listener();assert.equal(refreshed.id,'nonce');assert.equal(refreshed.flow.role,'ORGANIZER');
});
