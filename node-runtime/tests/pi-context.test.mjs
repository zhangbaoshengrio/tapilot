import assert from 'node:assert/strict';
import test from 'node:test';
import { projectContext, contextSize } from './pi-context.mjs';

test('long tool output is bounded while instructions and tool pairing survive', () => {
  const source = [{role:'system',content:'rules'}, {role:'user',content:'Do not send without approval'}];
  for(let i=0;i<30;i++) source.push({role:'assistant',content:null,tool_calls:[{id:`c${i}`,function:{name:'operate_phone',arguments:'{"instruction":"check"}'}}]},
    {role:'tool',tool_call_id:`c${i}`,content:JSON.stringify({success:i!==0,result:'x'.repeat(10000)})});
  const before=JSON.stringify(source);
  const result=projectContext(source,48000);
  assert.ok(contextSize(result)<=48000);
  assert.ok(result.some(m=>m.content==='Do not send without approval'));
  assert.ok(JSON.stringify(result).includes('false'));
  for(const message of result.filter(m=>m.role==='tool')) assert.ok(result.some(m=>m.tool_calls?.some(c=>c.id===message.tool_call_id)));
  assert.equal(JSON.stringify(source), before);
});
test('oversized user constraints and tool arguments fail before a model request', () => {
  assert.throws(()=>projectContext([{role:'user',content:'x'.repeat(50000)}],48000), /safe budget/);
  assert.throws(()=>projectContext([{role:'assistant',tool_calls:[{id:'1',function:{name:'operate_phone',arguments:'x'.repeat(50000)}}]}],48000), /safe budget/);
});
test('images have bounded visual cost instead of charging base64 as text', () => {
  const messages=[{role:'user',content:[{type:'image_url',image_url:{url:'data:image/jpeg;base64,'+'a'.repeat(100000)}}]}];
  assert.equal(projectContext(messages),messages);
  assert.throws(()=>projectContext([{role:'user',content:'x'.repeat(33*1024*1024)}]),/transfer limit/);
});
