import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {test} from 'node:test';
import {runInNewContext} from 'node:vm';
import ts from 'typescript';
const exports = {};
runInNewContext(ts.transpileModule(readFileSync(new URL('../src/lib/categoryComposition.ts',import.meta.url),'utf8'),
  {compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText,{exports});
const {matchesComposition,categoryComposition} = exports;
test('single-sex categories reject the other composition and missing members',()=>{
  assert.equal(matchesComposition({genderPolicy:'FEMALE',teamSize:2},['F','F']),true);
  assert.equal(matchesComposition({genderPolicy:'FEMALE',teamSize:2},['M','F']),false);
  assert.equal(matchesComposition({genderPolicy:'MALE',teamSize:2},['M','M']),true);
  assert.equal(matchesComposition({genderPolicy:'MALE',teamSize:2},['M','']),false);
});
test('mixed teams accept exactly the organizer selections',()=>{
  const category = {genderPolicy:'MIXED',teamSize:3,allowedMaleCounts:[1,2]};
  assert.equal(matchesComposition(category,['M','F','F']),true);
  assert.equal(matchesComposition(category,['M','M','F']),true);
  assert.equal(matchesComposition(category,['M','M','M']),false);
  assert.equal(matchesComposition(category,['M','F']),false);
  assert.equal(matchesComposition({...category,allowedMaleCounts:[1]},['M','M','F']),false);
  assert.equal(categoryComposition(category),'Mista: 1 homem e 2 mulheres ou 2 homens e 1 mulher');
});
test('legacy categories require an explicit rule before enrollment',()=>{
  assert.equal(matchesComposition({genderPolicy:null,teamSize:1},['M']),false);
  assert.match(categoryComposition({genderPolicy:null}),/não definida/);
});
