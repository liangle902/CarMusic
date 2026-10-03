const fs=require('node:fs');
const vm=require('node:vm');
const assert=require('node:assert/strict');
const source=fs.readFileSync(require('node:path').join(__dirname,'../design/portrait-v1/app.js'),'utf8');
new vm.Script(source);
const lines=source.split(/\r?\n/);
const code=['const songs=','const state=','function playSingle','function setTrack'].map(prefix=>{
  const line=lines.find(value=>value.startsWith(prefix));
  assert.ok(line,`Missing ${prefix}`);return line;
}).join('\n');
const sandbox=vm.createContext({});
vm.runInContext(code,sandbox);
function queueAfter(expression){return JSON.parse(vm.runInContext(`${expression};JSON.stringify({queue:state.queue,current:state.current})`,sandbox));}
assert.deepEqual(queueAfter('state.queue=[1,2,3];playSingle(2)'),{queue:[2,1,3],current:2});
assert.deepEqual(queueAfter('playSingle(4)'),{queue:[4,2,1,3],current:4});
assert.deepEqual(queueAfter('playSingle(4)'),{queue:[4,2,1,3],current:4});
assert.deepEqual(queueAfter('state.queue=[6,7];setTrack(6)'),{queue:[6,7],current:6});
console.log('Prototype queue replacement, selected-track priority and deduplication passed.');
