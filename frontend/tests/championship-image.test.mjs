import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {test} from 'node:test';
import {runInNewContext} from 'node:vm';
import ts from 'typescript';
const code=ts.transpileModule(readFileSync(new URL('../src/lib/championshipImage.ts',import.meta.url),'utf8'),
  {compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText;
function harness({decodeFails=false,width=3200,height=1600,encode=format=>`data:${format};base64,SAFE`}={}) {
  const exports={};let created=0;let revoked=0;
  const draws=[];const encodes=[];const context={fillRect(){},drawImage(...args){draws.push(args);}};
  const canvas={getContext:()=>context,toDataURL:(format,quality)=>{encodes.push({format,quality});return encode(format,quality);}};
  runInNewContext(code,{exports,URL:{createObjectURL(){created++;return 'blob:local';},revokeObjectURL(){revoked++;}},
    Image:class {naturalWidth=width;naturalHeight=height;decode(){return decodeFails?Promise.reject(new Error('decode')):Promise.resolve();}},
    document:{createElement:()=>canvas}});
  return {...exports,canvas,context,draws,encodes,counts:()=>({created,revoked})};
}

test('image preparation retains the original pixels until cropping without intermediate compression',async()=>{
  const image=harness();const result=await image.prepareChampionshipImage({type:'image/png',size:1000});
  assert.equal(result,'blob:local');assert.equal(image.draws.length,0);assert.equal(image.encodes.length,0);
  assert.deepEqual(image.counts(),{created:1,revoked:0});
});
test('active content and oversized files are rejected before decoding',async()=>{
  const image=harness();
  await assert.rejects(image.prepareChampionshipImage({type:'image/svg+xml',size:100}));
  await assert.rejects(image.prepareChampionshipImage({type:'image/jpeg',size:7*1024*1024}));
  assert.deepEqual(image.counts(),{created:0,revoked:0});
});
test('failed decode and excessive pixel count release temporary object URLs',async()=>{
  for(const options of [{decodeFails:true},{width:10000,height:10000}]) {
    const image=harness(options);await assert.rejects(image.prepareChampionshipImage({type:'image/png',size:100}));
    assert.deepEqual(image.counts(),{created:1,revoked:1});
  }
});
test('square and panoramic crops stay within the source at all zoom and position limits',()=>{
  const image=harness();
  for(const [width,height] of [[3200,1600],[600,1200],[640,640]]) {
    for(const aspect of [1,4]) for(const zoom of [1,1.5,3]) for(const x of [0,50,100]) for(const y of [0,50,100]) {
      const crop=image.cropBounds(width,height,aspect,zoom,x,y);
      assert.ok(crop.x>=0 && crop.y>=0);
      assert.ok(crop.x+crop.width<=width+1e-8 && crop.y+crop.height<=height+1e-8);
      assert.equal(crop.width/crop.height,aspect);
    }
  }
});
test('crop export uses original pixels, higher resolution and lossless PNG when it fits',async()=>{
  const image=harness();
  const profile=await image.cropChampionshipImage('blob:local',1,1,50,50);
  assert.match(profile,/^data:image\/png;base64,/);
  assert.equal(image.canvas.width,1024);assert.equal(image.canvas.height,1024);
  await image.cropChampionshipImage('blob:local',1,2,100,0);
  assert.equal(image.canvas.width,800);assert.equal(image.canvas.height,800);
  assert.deepEqual(image.draws.at(-1).slice(1),[2400,0,800,800,0,0,800,800]);
  await image.cropChampionshipImage('blob:local',4,1,50,50);
  assert.equal(image.canvas.width,2048);assert.equal(image.canvas.height,512);
  assert.equal(image.context.imageSmoothingQuality,'high');
});
test('small originals are never enlarged',async()=>{
  const image=harness({width:400,height:200});
  await image.cropChampionshipImage('blob:local',4,1,50,50);
  assert.equal(image.canvas.width,400);assert.equal(image.canvas.height,100);
  await image.cropChampionshipImage('blob:local',1,1,50,50);
  assert.equal(image.canvas.width,200);assert.equal(image.canvas.height,200);
});
test('large PNGs use a high quality JPEG and never silently fall to destructive compression',async()=>{
  const image=harness({encode:(format,quality)=>format==='image/png'?'X'.repeat(900001):`data:${format};base64,Q${quality}`});
  const data=await image.cropChampionshipImage('blob:local',4,1,50,50);
  assert.equal(data,'data:image/jpeg;base64,Q0.96');
  assert.deepEqual(image.encodes,[{format:'image/png',quality:undefined},{format:'image/jpeg',quality:0.96}]);
  const oversized=harness({encode:()=> 'X'.repeat(900001)});
  await assert.rejects(oversized.cropChampionshipImage('blob:local',1,1,50,50),/boa qualidade/);
  assert.ok(oversized.encodes.filter(e=>e.format==='image/jpeg').every(e=>e.quality>=0.9));
});
