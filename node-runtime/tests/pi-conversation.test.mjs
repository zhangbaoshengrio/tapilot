import test from 'node:test';
import assert from 'node:assert/strict';
import { createConversation, checkpointMessages, resumableTranscript } from './pi-conversation.mjs';
const input = text => [{ role: 'system', content: 'Tapilot' }, { role: 'user', content: text }];
const answer = content => ({ choices: [{ message: { content } }] });
const tool = (name, args) => ({ choices: [{ message: { tool_calls: [{ id: `call-${name}`, function: { name, arguments: JSON.stringify(name === 'operate_phone' ? { mode: 'act', app: '', finishWhenVerified: false, ...args } : args) } }] } }] });

test('cross-turn verification receives the exact prior reply, not an invented step target', async () => {
  for (const reply of ['70', 'x'.repeat(4097), '']) {
    let contract, models = 0;
    const goal = 'Calculate the number you just answered times two';
    const messages = [...input('Remember 73'), { role: 'assistant', content: 'Remembered' },
      { role: 'user', content: 'Subtract three' }, { role: 'assistant', content: reply },
      { role: 'user', content: goal }];
    const conversation = createConversation({ messages, goal, emit: () => {}, rpc: async (method, args) => {
      if (method === 'phone.execute') {
        contract = JSON.parse(args.success_condition);
        return { success: false, result: 'No evaluated result visible' };
      }
      return models++ === 0
        ? tool('operate_phone', { instruction: 'Calculate', success_condition: '999 is visible', finishWhenVerified: true })
        : tool('finish_task', { outcome: 'waiting_user', summary: 'Result not verified' });
    } });
    assert.notEqual((await conversation.run()).phase, 'completed');
    assert.deepEqual(contract.userRequirements, [goal]);
    assert.equal(contract.proposedStepCondition, '999 is visible');
    assert.deepEqual(contract.referenceContext, reply === '70' ? { previousAssistantReply: '70' } : undefined);
  }
});

test('Pi pushes a planned phone task to verified completion through native RPC', async () => {
  let count = 0;
  const events = [], methods = [];
  const conversation = createConversation({ language: 'zh', messages: input('打开计算器'), rpc: async (method, params) => {
    methods.push(method);
    if (method === 'phone.execute') return { success: true, result: '计算器已显示' };
    assert.deepEqual(params.tools.map(t => t.function.name), ['operate_phone', 'read_screen', 'screen_action', 'find_apps', 'set_plan', 'finish_task', 'answer_question']);
    assert.deepEqual(params.tools[0].function.parameters.required, ['instruction', 'success_condition', 'mode', 'finishWhenVerified', 'app']);
    if (count === 2) {
      const verified = JSON.parse(params.messages.at(-1).content).result;
      assert.equal(verified, '已独立看图核验：计算器已显示');
    }
    return [tool('set_plan', { steps: ['打开计算器'], currentStep: 0 }),
      tool('operate_phone', { instruction: '打开计算器', success_condition: '计算器显示' }),
      tool('finish_task', { outcome: 'completed', summary: '已打开计算器' })][count++];
  }, emit: e => events.push(e) });
  const result = await conversation.run();
  const checkpoints = events.filter(e => e.type === 'checkpoint');
  assert.deepEqual(checkpoints.map(e => e.messages.length), [1, 2, 3, 4, 5, 6, 7]);
  assert.equal(checkpoints.at(-1).messages.at(-1).toolName, 'finish_task');
  assert.equal(result.reply, '已打开计算器'); assert.equal(result.operations, 1);
  assert.equal(result.phase, 'completed'); assert.ok(events.some(e => e.type === 'plan'));
  assert.equal(methods.filter(m => m === 'phone.execute').length, 1);
});

test('steering amends an active task after its phone operation', async () => {
  let conversation, seen = false, models = 0;
  conversation = createConversation({ language: 'zh', messages: input('打开计算器'), rpc: async (method, params) => {
    if (method === 'phone.execute') {
      if (models === 1) conversation.steer({ id: 'amend-1', text: '改为只回复 PINE94' });
      return { success: true, result: '已打开' };
    }
    if (models++ === 0) return tool('operate_phone', { instruction: '打开计算器', success_condition: '计算器显示' });
    seen = params.messages.some(m => JSON.stringify(m.content).includes('PINE94'));
    if (models === 2) return tool('operate_phone', { instruction: '读取当前计算器', success_condition: '计算器显示', mode: 'observe' });
    return tool('finish_task', { outcome: 'completed', summary: 'PINE94' });
  }, emit: () => {} });
  assert.equal((await conversation.run()).reply, 'PINE94'); assert.ok(seen);
});

test('failed phone verification cannot finish successfully; adjusted retry can', async () => {
  let models = 0, phones = 0;
  const results = [];
  const conversation = createConversation({ language: 'zh', messages: input('任务'), rpc: async (method, params) => {
    if (method === 'phone.execute') return { success: ++phones > 1, result: phones === 1 ? '未验证成功' : '已验证' };
    results.push(params.messages);
    return [tool('operate_phone', { instruction: '操作', success_condition: '成功' }),
      tool('finish_task', { outcome: 'completed', summary: '错误地完成' }),
      tool('operate_phone', { instruction: '调整后操作', success_condition: '成功' }),
      tool('finish_task', { outcome: 'completed', summary: '重试完成' })][models++];
  }, emit: () => {} });
  assert.equal((await conversation.run()).reply, '重试完成'); assert.equal(phones, 2);
  assert.ok(JSON.stringify(results).includes('不能标记手机任务完成'));
});

test('failed phone actions use deeper planning for recovery while observations and verified follow-ups stay fast', async () => {
  let turn = 0;
  const calls = [];
  const modes = ['act', 'observe', 'act', 'act'];
  const conversation = createConversation({ messages: input('Complete the phone task'), emit: () => {},
    rpc: async (method, params) => {
      if (method === 'phone.execute') {
        calls.push(params);
        return { success: calls.length >= 3, result: calls.length >= 3 ? 'Visible result verified' : 'The displayed result does not match' };
      }
      if (turn < modes.length) return tool('operate_phone', {
        instruction: `Stage ${turn + 1}`, success_condition: 'Requested visible result',
        mode: modes[turn++], effort: 'fast',
      });
      return tool('finish_task', { outcome: 'completed', summary: 'Verified' });
    } });
  assert.equal((await conversation.run()).phase, 'completed');
  assert.deepEqual(calls.map(call => call.effort), ['fast', 'fast', 'deepThink', 'fast']);
});

test('waiting for clarification returns a durable waiting phase', async () => {
  const conversation = createConversation({ language: 'zh', messages: input('打开那个应用'), rpc: async () =>
    tool('finish_task', { outcome: 'waiting_user', summary: '要打开哪个应用？' }), emit: () => {} });
  const result = await conversation.run(); assert.equal(result.phase, 'waiting_user'); assert.equal(result.operations, 0);
});

test('per-attempt model limit stops a runaway agent', async () => {
  let calls = 0;
  const conversation = createConversation({ language: 'zh', messages: input('任务'), limits: { modelTurns: 1 }, rpc: async () => {
    calls++; return tool('set_plan', { steps: ['检查'], currentStep: 0 });
  }, emit: () => {} });
  await assert.rejects(conversation.run(), /对话轮次.*上限/); assert.equal(calls, 1);
});

test('abort rejects a blocked model request and stops the task', async () => {
  let ready;
  const started = new Promise(resolve => { ready = resolve; });
  const conversation = createConversation({ language: 'zh', messages: input('任务'), rpc: async (_m, _p, signal) =>
    new Promise((_resolve, reject) => { signal.addEventListener('abort', () => reject(new Error('stopped')), { once: true }); ready(); }), emit: () => {} });
  const result = conversation.run(); await started; conversation.abort();
  await assert.rejects(result, /stopped/);
});

test('resume repairs a missing tool result instead of inventing success', () => {
  const context = resumableTranscript([{ role: 'assistant', content: [{ type: 'toolCall', id: 'uncertain', name: 'operate_phone', arguments: {} }] }]);
  assert.equal(context[1].isError, true); assert.match(context[1].content[0].text, /先检查/);
});

test('checkpoint excludes archived image payload but preserves steering input identity', () => {
  const saved = checkpointMessages([{ role: 'user', inputId: 'input-1', content: [{ type: 'image', data: 'SECRET_IMAGE', mimeType: 'image/png' }] }]);
  assert.equal(saved[0].inputId, 'input-1'); assert.ok(!JSON.stringify(saved).includes('SECRET_IMAGE'));
});

test('disabled limits permit more than 100 operations and preserve fresh counters per attempt', async () => {
  for (let attempt = 0; attempt < 2; attempt++) {
    let operations = 0;
    const conversation = createConversation({ language: 'zh', messages: input('长任务'), limits: { modelTurns: 0, phoneOperations: 0 }, rpc: async method => {
      if (method === 'phone.execute') return { success: true, result: `步骤 ${++operations}` };
      return operations < 120 ? tool('operate_phone', { instruction: '检查屏幕', success_condition: '可见' })
        : tool('finish_task', { outcome: 'completed', summary: '完成' });
    }, emit: () => {} });
    assert.equal((await conversation.run()).operations, 120);
  }
});

test('phone ceiling blocks subsequent side effects and cannot be mistaken for success', async () => {
  let operations = 0, models = 0;
  const conversation = createConversation({ language: 'zh', messages: input('任务'), limits: { phoneOperations: 2, modelTurns: 6 }, rpc: async method => {
    if (method === 'phone.execute') { operations++; return { success: true, result: '成功' }; }
    models++;
    if (models === 4) return tool('finish_task', { outcome: 'completed', summary: '不能完成' });
    return tool('operate_phone', { instruction: '操作', success_condition: '成功' });
  }, emit: () => {} });
  await assert.rejects(conversation.run(), /上限/); assert.equal(operations, 2);
});

test('abort after model response prevents any queued phone operation', async () => {
  let conversation, operations = 0;
  conversation = createConversation({ language: 'zh', messages: input('任务'), rpc: async method => {
    if (method === 'phone.execute') { operations++; return { success: true, result: '' }; }
    conversation.abort(); return tool('operate_phone', { instruction: '操作', success_condition: '成功' });
  }, emit: () => {} });
  await assert.rejects(conversation.run()); assert.equal(operations, 0);
});

test('a completed task blocks later operations in the same model batch', async () => {
  let operations = 0;
  const first = tool('finish_task', { outcome: 'waiting_user', summary: '完成' });
  first.choices[0].message.tool_calls.push(tool('operate_phone', { instruction: '不应执行', success_condition: '成功' }).choices[0].message.tool_calls[0]);
  const conversation = createConversation({ language: 'zh', messages: input('任务'), rpc: async method => {
    if (method === 'phone.execute') operations++;
    return first;
  }, emit: () => {} });
  assert.equal((await conversation.run()).reply, '完成'); assert.equal(operations, 0);
});

test('checkpoint stores consumed steering identities for crash recovery', async () => {
  const checkpoints = []; let conversation;
  conversation = createConversation({ language: 'zh', messages: input('任务'), rpc: async (_method, params) => {
    if (!params.messages.some(m => JSON.stringify(m.content).includes('NEW_INPUT'))) {
      conversation.steer({ id: 'durable-input', text: 'NEW_INPUT' }); return answer('旧回复');
    }
    return tool('answer_question', { answer: '新回复' });
  }, emit: event => { if (event.type === 'checkpoint') checkpoints.push(event.messages); } });
  assert.equal((await conversation.run()).reply, '新回复');
  assert.ok(checkpoints.at(-1).some(m => m.inputId === 'durable-input'));
});

test('resume retains an amendment accepted before an interrupted operation finished', async () => {
  let seen = false;
  const conversation = createConversation({ language: 'zh', messages: input('继续'),
    resume: [{ role: 'user', content: '打开计算器', timestamp: 1 }], pendingInputs: [{ id: 'old-input', text: '改成返回桌面' }],
    rpc: async (_method, params) => {
      seen = JSON.stringify(params.messages).includes('改成返回桌面'); return tool('finish_task', { outcome: 'waiting_user', summary: '已收到补充' });
    }, emit: () => {} });
  await conversation.run(); assert.ok(seen);
});

test('steering received during a finish response can still execute the added requirement', async () => {
  let conversation, models = 0, phones = 0;
  conversation = createConversation({ language: 'zh', messages: input('原任务'), rpc: async method => {
    if (method === 'phone.execute') { phones++; return { success: true, result: '新增操作成功' }; }
    if (models++ === 0) {
      conversation.steer({ id: 'late-amendment', text: '还需要打开计算器' });
      return tool('finish_task', { outcome: 'completed', summary: '旧任务已完成' });
    }
    if (models === 2) return tool('operate_phone', { instruction: '打开计算器', success_condition: '显示计算器' });
    return tool('finish_task', { outcome: 'completed', summary: '追加任务已完成' });
  }, emit: () => {} });
  assert.equal((await conversation.run()).reply, '追加任务已完成'); assert.equal(phones, 1);
});

test('restored task goal is explicit in the model context and truncated calls never operate', async () => {
  let seen = false, phones = 0, turns = 0;
  const conversation = createConversation({ language: 'zh', messages: input('继续'), goal: '打开计算器并计算 3+4', limits: { modelTurns: 2 },
    rpc: async (method, params) => {
      if (method === 'phone.execute') { phones++; return { success: true, result: '不应发生' }; }
      seen = params.messages[0].content.includes('打开计算器并计算 3+4');
      if (turns++ === 0) {
        const response = tool('operate_phone', { instruction: '截断的操作', success_condition: '成功' });
        response.choices[0].finish_reason = 'length'; return response;
      }
      return tool('finish_task', { outcome: 'failed', summary: '参数被截断，请重试' });
    }, emit: () => {} });
  const result = await conversation.run(); assert.ok(seen); assert.equal(phones, 0); assert.equal(result.lastPhoneResult.success, false);
});

test('agent can report an unrecoverable failure without claiming completion', async () => {
  const conversation = createConversation({ language: 'zh', messages: input('任务'), rpc: async () =>
    tool('finish_task', { outcome: 'failed', summary: '服务不可用，请稍后重试' }), emit: () => {} });
  const result = await conversation.run(); assert.equal(result.phase, 'failed'); assert.match(result.reply, /服务不可用/);
});

import { projectContext } from './pi-context.mjs';
test('context projection preserves constraints, failures, and complete recent tool batches', () => {
  const messages = [{role:'system',content:'system'}, {role:'user',content:'Never send messages without my approval'}];
  for (let i=0;i<30;i++) messages.push({role:'assistant',content:null,tool_calls:[{id:`c${i}`,function:{name:'operate_phone',arguments:'{"instruction":"check"}'}}]}, {role:'tool',tool_call_id:`c${i}`,content:JSON.stringify({success:i!==0,result:'start'+'.'.repeat(4000)+'end'})});
  const projected=projectContext(messages,48000);
  assert.ok(JSON.stringify(projected).length<JSON.stringify(messages).length);
  assert.ok(projected.some(m=>m.content==='Never send messages without my approval'));
  assert.ok(JSON.stringify(projected).includes('false'));
  for(let i=0;i<projected.length;i++) if(projected[i].role==='tool') assert.ok(projected.slice(0,i).some(m=>m.tool_calls?.some(c=>c.id===projected[i].tool_call_id)));
  assert.equal(messages[3].content.length>4000,true); // Durable source is untouched.
});

test('Responses replay state survives tool round trips and checkpoints', async () => {
  const replay = [{ type: 'reasoning', encrypted_content: 'opaque-test-state' },
    { type: 'function_call', call_id: 'call-1', name: 'set_plan', arguments: '{}' }];
  let turns = 0;
  const checkpoints = [];
  const conversation = createConversation({ language: 'zh', messages: input('检查任务'), limits: { modelTurns: 3 }, rpc: async (_method, params) => {
    if (turns++ === 0) return { ...tool('set_plan', { steps: ['检查'], currentStep: 0 }), tapilotResponsesOutput: replay };
    assert.deepEqual(params.messages.find(m => m.role === 'assistant').tapilotResponsesOutput, replay);
    return tool('finish_task', { outcome: 'waiting_user', summary: '需要检查手机' });
  }, emit: event => { if (event.type === 'checkpoint') checkpoints.push(event.messages); } });
  await conversation.run();
  assert.ok(checkpoints.some(messages => messages.some(m => m.tapilotResponsesOutput?.[0].encrypted_content === 'opaque-test-state')));
});


test('lookup then direct-launch observation preserves routing and finishes without a repeat operation', async () => {
  let turns = 0;
  const methods = [];
  const conversation = createConversation({ language: 'zh', messages: input('Read existing weather in Doubao'), rpc: async (method, params) => {
    methods.push(method);
    if (method === 'phone.findApps') return { apps: [{ label: '豆包', component: 'example.doubao/.Main' }] };
    if (method === 'phone.execute') {
      assert.equal(params.mode, 'observe'); assert.equal(params.app, 'example.doubao/.Main');
      return { success: true, result: 'Cloudy, 20–28°C' };
    }
    return [tool('find_apps', { query: '豆包' }),
      tool('operate_phone', { instruction: 'Read existing reply; do not send', success_condition: 'Weather reply visible', mode: 'observe', app: 'example.doubao/.Main' }),
      tool('finish_task', { outcome: 'completed', summary: 'Cloudy, 20–28°C' })][turns++];
  }, emit: () => {} });
  const result = await conversation.run();
  assert.equal(result.phase, 'completed'); assert.equal(result.operations, 1);
  assert.equal(methods.filter(m => m === 'phone.execute').length, 1);
});


test('routing fields cannot be omitted silently by the model', async () => {
  let turns = 0, phoneCalls = 0;
  const conversation = createConversation({ language: 'zh', messages: input('Read the current screen'), rpc: async (method) => {
    if (method === 'phone.execute') { phoneCalls++; return { success: true, result: 'Visible' }; }
    if (turns++ === 0) return { choices: [{ message: { tool_calls: [{ id: 'missing-route', function: {
      name: 'operate_phone', arguments: JSON.stringify({ instruction: 'Read', success_condition: 'Visible' }),
    } }] } }] };
    return tool('finish_task', { outcome: 'failed', summary: 'Missing routing fields' });
  }, emit: () => {} });
  const result = await conversation.run();
  assert.equal(phoneCalls, 0);
  assert.equal(result.phase, 'failed');
});

test('bounded launcher candidates are available on the first turn without a lookup round trip', async () => {
  let calls = 0;
  const installedApps = [{ label: '计算器', component: 'example.calc/.Main' },
    ...Array.from({ length: 100 }, (_, i) => ({ label: `App ${i}`, component: `example.app${i}/.Main` }))];
  const conversation = createConversation({ language: 'zh', messages: input('计算'), installedApps, rpc: async (method, params) => {
    if (method === 'phone.execute') { assert.equal(params.app, 'example.calc/.Main'); return { success: true, result: '56' }; }
    assert.equal(method, 'model.complete');
    const system = params.messages[0].content;
    assert.match(system, /untrusted data/); assert.match(system, /example.calc/); assert.ok(!system.includes('example.app99'));
    return calls++ === 0 ? tool('operate_phone', { instruction: '计算', success_condition: '56', app: 'example.calc/.Main' })
      : tool('finish_task', { outcome: 'completed', summary: '56' });
  }, emit: () => {} });
  assert.equal((await conversation.run()).reply, '56'); assert.equal(calls, 2);
});

test('single whole-goal operation finishes with real visual evidence without a summary inference', async () => {
  let models = 0;
  const conversation = createConversation({ language: 'zh', messages: input('计算并读取结果'), emit: () => {}, rpc: async method => {
    if (method === 'phone.execute') return { success: true, result: '屏幕显示 9×6 = 54' };
    models++;
    return tool('operate_phone', { instruction: '实际计算', success_condition: '读取屏幕结果', finishWhenVerified: true });
  } });
  const result = await conversation.run();
  assert.equal(models, 1); assert.equal(result.phase, 'completed');
  assert.equal(result.reply, '屏幕显示 9×6 = 54');
});

test('explicit continuation preserves multi-page facts and a synthesized answer', async () => {
  let models = 0, phones = 0;
  const conversation = createConversation({ language: 'zh', messages: input('读取两个页面并比较'), emit: () => {},
    rpc: async (method, params) => {
      if (method === 'phone.execute') return { success: true, result: ++phones === 1 ? '页面甲：12' : '页面乙：9' };
      if (models++ < 2) return tool('operate_phone', { instruction: '读取下一页', success_condition: '数值可见', finishWhenVerified: false });
      const history = JSON.stringify(params.messages);
      assert.match(history, /页面甲：12/);
      assert.match(history, /页面乙：9/);
      return tool('finish_task', { outcome: 'completed', summary: '甲比乙多 3' });
    } });
  const result = await conversation.run();
  assert.equal(models, 3);
  assert.equal(phones, 2);
  assert.equal(result.reply, '甲比乙多 3');
  assert.equal(result.phase, 'completed');
});

test('missing completion choice is rejected before a phone action', async () => {
  let models = 0, phones = 0;
  const conversation = createConversation({ language: 'zh', messages: input('读取页面'), emit: () => {},
    rpc: async method => {
      if (method === 'phone.execute') { phones++; return { success: true, result: '不应执行' }; }
      return models++ === 0
        ? tool('operate_phone', { instruction: '读取', success_condition: '页面可见', finishWhenVerified: undefined })
        : tool('finish_task', { outcome: 'waiting_user', summary: '需要澄清' });
    } });
  assert.equal((await conversation.run()).phase, 'waiting_user');
  assert.equal(phones, 0);
});

for (const condition of ['failed', 'steering', 'followUp', 'planned', 'batch', 'resume', 'empty']) {
  test(`whole-goal completion cannot bypass ${condition}`, async () => {
    let conversation, models = 0, phones = 0;
    const op = () => tool('operate_phone', { instruction: '读取', success_condition: '读取画面', finishWhenVerified: true });
    conversation = createConversation({ language: 'zh', messages: input('任务'),
      resume: condition === 'resume' ? [{ role: 'user', content: '此前任务', timestamp: 1 }] : [], emit: () => {},
      rpc: async method => {
        if (method === 'phone.execute') {
          phones++;
          if (condition === 'steering' || condition === 'followUp') conversation.steer({ id: 'new', text: '还有新的要求' }, condition === 'followUp');
          return { success: condition !== 'failed', result: condition === 'empty' ? '' : '观察结果' };
        }
        if (method === 'phone.findApps') return { apps: [] };
        const index = models++;
        if (condition === 'planned' && index === 0) return tool('set_plan', { steps: ['读取', '总结'], currentStep: 0 });
        if (index === (condition === 'planned' ? 1 : 0)) {
          const response = op();
          if (condition === 'batch') response.choices[0].message.tool_calls.push(tool('find_apps', { query: 'x' }).choices[0].message.tool_calls[0]);
          return response;
        }
        return tool('finish_task', { outcome: condition === 'failed' ? 'failed' : 'waiting_user', summary: '需要继续处理' });
      } });
    const result = await conversation.run();
    assert.equal(phones, 1); assert.ok(models >= 2); assert.notEqual(result.phase, 'completed');
    assert.equal(result.reply, '需要继续处理');
  });
}

test('cancellation during verified operation cannot trigger direct completion', async () => {
  let conversation;
  conversation = createConversation({ language: 'zh', messages: input('任务'), emit: () => {}, rpc: async method => {
    if (method === 'phone.execute') {
      conversation.abort();
      return { success: true, result: '晚到的观察' };
    }
    return tool('operate_phone', { instruction: '读取', success_condition: '读取画面', finishWhenVerified: true });
  } });
  await assert.rejects(conversation.run());
});

test('new input arriving after verified result checkpoint is not lost to direct completion', async () => {
  let conversation, queued = false, models = 0;
  conversation = createConversation({ language: 'zh', messages: input('任务'), emit: event => {
    if (!queued && event.type === 'checkpoint' && event.messages.at(-1)?.toolName === 'operate_phone') {
      queued = true; conversation.steer({ id: 'late', text: '请继续新要求' });
    }
  }, rpc: async method => {
    if (method === 'phone.execute') return { success: true, result: '首次观察' };
    return models++ === 0
      ? tool('operate_phone', { instruction: '读取', success_condition: '读取画面', finishWhenVerified: true })
      : tool('finish_task', { outcome: 'waiting_user', summary: '请说明新要求' });
  } });
  const result = await conversation.run();
  assert.equal(models, 2); assert.equal(result.phase, 'waiting_user');
  assert.equal(result.reply, '请说明新要求');
});

test('text-only recovery gets protocol guidance and can complete after inspecting evidence', async () => {
  let models = 0, phones = 0;
  const conversation = createConversation({ language: 'zh', messages: input('读取选中项'), rpc: async (method, params) => {
    if (method === 'phone.execute') return { success: ++phones > 1, result: phones === 1 ? '表头不可见' : '选中项和表头可见' };
    models++;
    if (models === 1) return tool('operate_phone', { instruction: '读取', success_condition: '标签和值可见' });
    if (models === 2) return answer('还需要核验');
    if (models === 3) {
      assert.match(params.messages[0].content, /必须调用工具/);
      return tool('operate_phone', { instruction: '只恢复表头', success_condition: '标签和值可见' });
    }
    return tool('finish_task', { outcome: 'completed', summary: '已读取' });
  }, emit: () => {} });
  const result = await conversation.run();
  assert.equal(result.phase, 'completed'); assert.equal(phones, 2); assert.equal(models, 4);
});

test('repeated prose after failed verification terminates honestly without replaying effects', async () => {
  let models = 0, phones = 0;
  const conversation = createConversation({ language: 'zh', messages: input('读取'), rpc: async method => {
    if (method === 'phone.execute') { phones++; return { success: false, result: '缺少表头' }; }
    return ++models === 1 ? tool('operate_phone', { instruction: '读取', success_condition: '完整可见' }) : answer('任务完成');
  }, emit: () => {} });
  const result = await conversation.run();
  assert.equal(result.phase, 'failed'); assert.equal(result.lastPhoneResult.success, false);
  assert.match(result.reply, /缺少表头/); assert.equal(models, 4); assert.equal(phones, 1);
});

test('prose after a verified intermediate step cannot falsely complete the whole task', async () => {
  let models = 0;
  const conversation = createConversation({ language: 'zh', messages: input('两步任务'), rpc: async method => {
    if (method === 'phone.execute') return { success: true, result: '第一步完成' };
    return ++models === 1 ? tool('operate_phone', { instruction: '第一步', success_condition: '可见' }) : answer('完成');
  }, emit: () => {} });
  assert.equal((await conversation.run()).phase, 'failed'); assert.equal(models, 4);
});


test('recovery retains user requirements separately from invented step requirements', async () => {
  let models = 0; const contracts = [];
  const conversation = createConversation({ language: 'zh', messages: input('Select item A and read its column'), rpc: async (method, args) => {
    if (method === 'phone.execute') {
      contracts.push(JSON.parse(args.success_condition));
      return { success: contracts.length > 1, result: contracts.length > 1 ? 'A, X' : 'Missing full label' };
    }
    return [
      tool('operate_phone', { instruction: 'Select A', success_condition: 'Full label X in detail view', finishWhenVerified: true }),
      tool('operate_phone', { instruction: 'Read current selection', success_condition: 'A under X', mode: 'observe', finishWhenVerified: true }),
      tool('finish_task', { outcome: 'completed', summary: 'A, X' }),
    ][models++];
  }, emit: () => {} });
  assert.equal((await conversation.run()).phase, 'completed');
  assert.deepEqual(contracts.map(c => c.userRequirements), [['Select item A and read its column'], ['Select item A and read its column']]);
  assert.equal(contracts[0].proposedStepCondition, 'Full label X in detail view');
  assert.equal(contracts[1].scope, 'entire_user_goal');
});

test('delivered user amendment changes next verification contract, not an in-flight operation', async () => {
  let conversation, models = 0; const contracts = [];
  conversation = createConversation({ language: 'zh', messages: input('Read A'), rpc: async (method, args) => {
    if (method === 'phone.execute') {
      contracts.push(JSON.parse(args.success_condition));
      if (contracts.length === 1) conversation.steer({ id: 'new-target', text: 'Instead read B, preserving its exact label' });
      return { success: true, result: 'Observed' };
    }
    return models++ < 2 ? tool('operate_phone', { instruction: 'Read selection', success_condition: 'Selection visible', mode: 'observe' }) :
      tool('finish_task', { outcome: 'completed', summary: 'B' });
  }, emit: () => {} });
  await conversation.run();
  assert.deepEqual(contracts[0].userRequirements, ['Read A']);
  assert.deepEqual(contracts[1].userRequirements, ['Read A', 'Instead read B, preserving its exact label']);
  assert.equal(contracts[1].scope, 'intermediate_step');
});

test('a final step after verified intermediate results does not require all past screens again', async () => {
  let models = 0;
  const contracts = [];
  const conversation = createConversation({ messages: input('Compute 12 times 3, check it, then add 5 and check the final result'),
    emit: () => {}, rpc: async (method, params) => {
      if (method === 'phone.execute') {
        contracts.push(JSON.parse(params.success_condition));
        return { success: true, result: contracts.length === 1 ? '12 × 3 = 36' : '36 + 5 = 41' };
      }
      return [
        tool('operate_phone', { instruction: 'Multiply and evaluate', success_condition: '12 × 3 = 36 is visible' }),
        tool('operate_phone', { instruction: 'Add 5 to the verified result', success_condition: '36 + 5 = 41 is visible', finishWhenVerified: true }),
        tool('finish_task', { outcome: 'completed', summary: '12 × 3 = 36, then 36 + 5 = 41' }),
      ][models++];
    } });
  assert.equal((await conversation.run()).phase, 'completed');
  assert.equal(contracts[1].scope, 'intermediate_step');
  assert.equal(contracts[1].proposedStepCondition, '36 + 5 = 41 is visible');
  assert.deepEqual(contracts[1].userRequirements, contracts[0].userRequirements);
  assert.equal(models, 3); // Whole-task synthesis still belongs to the conversation.
});

test('an explicit plan keeps screenshot verification scoped to its current step', async () => {
  let models = 0;
  let contract;
  const conversation = createConversation({ messages: input('Read a value, then use it on the next screen'), emit: () => {},
    rpc: async (method, params) => {
      if (method === 'phone.execute') {
        contract = JSON.parse(params.success_condition);
        return { success: true, result: 'Current result observed' };
      }
      return [
        tool('operate_phone', { instruction: 'Read this step', success_condition: 'Current value visible', finishWhenVerified: true,
          plan: { steps: ['Read the first screen', 'Use the value on the next screen'], currentStep: 0 } }),
        tool('finish_task', { outcome: 'waiting_user', summary: 'Which destination should receive it?' }),
      ][models++];
    } });
  assert.equal((await conversation.run()).phase, 'waiting_user');
  assert.equal(contract.scope, 'intermediate_step');
  assert.equal(contract.proposedStepCondition, 'Current value visible');
});

test('zero-operation completion is rejected and can recover to clarification', async () => {
  let models = 0;
  const c = createConversation({ language: 'zh', messages: input('打开那个应用'), rpc: async method => {
    assert.equal(method, 'model.complete');
    return ++models === 1
      ? tool('finish_task', { outcome: 'completed', summary: '已打开' })
      : tool('finish_task', { outcome: 'waiting_user', summary: '哪个应用？' });
  }, emit: () => {} });
  const r = await c.run();
  assert.equal(r.phase, 'waiting_user'); assert.equal(r.operations, 0); assert.equal(models, 2);
});

test('plain clarification cannot default to completed, while knowledge has an explicit answer exit', async () => {
  const c = createConversation({ language: 'zh', messages: input('打开那个应用'), rpc: async () => answer('哪个应用？'), emit: () => {} });
  const r = await c.run(); assert.equal(r.phase, 'failed'); assert.equal(r.operations, 0); assert.equal(r.turns, 3);
  const knowledge = createConversation({ language: 'zh', messages: input('What is a screenshot?'), rpc: async () =>
    tool('answer_question', { answer: 'An image of a display.' }), emit: () => {} });
  assert.equal((await knowledge.run()).reply, 'An image of a display.');
});

test('a general question can finish after clarification without being stuck waiting for another answer', async () => {
  let checkpoint = [];
  const question = 'Which multiplication did you mean?';
  const first = createConversation({messages:input('Explain that calculation'),
    emit:event => { if (event.type === 'checkpoint') checkpoint = event.messages; },
    rpc:async () => tool('finish_task', {outcome:'waiting_user', summary:question})});
  assert.equal((await first.run()).phase, 'waiting_user');
  let calls = 0;
  const next = createConversation({messages:input('50 times 2'), resume:checkpoint, emit:() => {},
    rpc:async (method, params) => {
      assert.equal(method, 'model.complete');
      assert.ok(JSON.stringify(params.messages).includes(question));
      return calls++ === 0 ? tool('answer_question', {answer:'100'})
        : tool('finish_task', {outcome:'waiting_user', summary:'100'});
    }});
  const result = await next.run();
  assert.equal(result.phase, 'completed');
  assert.equal(result.completionKind, 'answer');
  assert.equal(result.reply, '100');
  assert.equal(calls, 1);
});

test('a resumed phone operation or plan cannot use the general-answer exit', async () => {
  for (const name of ['operate_phone', 'set_plan']) {
    let calls = 0;
    const resumed = createConversation({messages:input('Continue'),
      resume:[{role:'assistant',content:[{type:'toolCall',id:'previous',name,arguments:{}}]}],
      emit:() => {}, rpc:async () => calls++ === 0
        ? tool('answer_question', {answer:'Everything succeeded'})
        : tool('finish_task', {outcome:'failed',summary:'Needs phone verification'})});
    assert.equal((await resumed.run()).phase, 'failed');
    assert.equal(calls, 2);
  }
});

test('knowledge exit cannot bypass a failed phone operation', async () => {
  let models = 0;
  const c = createConversation({ language: 'zh', messages: input('Read screen'), rpc: async method => {
    if (method === 'phone.execute') return { success: false, result: 'Unknown' };
    return [tool('operate_phone', { instruction: 'Read', success_condition: 'Visible' }),
      tool('answer_question', { answer: 'Everything succeeded' }),
      tool('finish_task', { outcome: 'failed', summary: 'Unknown' })][models++];
  }, emit: () => {} });
  assert.equal((await c.run()).phase, 'failed'); assert.equal(models, 3);
});

test('repeated unsupported completion is bounded without phone actions', async () => {
  const c = createConversation({ language: 'zh', messages: input('Read the phone'), rpc: async () =>
    tool('finish_task', { outcome: 'completed', summary: 'Invented result' }), emit: () => {} });
  const r = await c.run(); assert.equal(r.phase, 'failed'); assert.equal(r.operations, 0); assert.equal(r.turns, 3);
});

test('an amendment invalidates old phone evidence before another completed result', async () => {
  let c, models = 0, phones = 0;
  c = createConversation({ language: 'zh', messages: input('Read first page'), rpc: async method => {
    if (method === 'phone.execute') { phones++; return { success: true, result: 'First page' }; }
    if (models++ === 0) return tool('operate_phone', { instruction: 'Read first', success_condition: 'Visible' });
    if (models === 2) c.steer({ id: 'new-goal', text: 'Also read second page' });
    if (models === 3) return tool('finish_task', { outcome: 'waiting_user', summary: 'Need second-page evidence' });
    return tool('finish_task', { outcome: 'completed', summary: 'Both pages' });
  }, emit: () => {} });
  const r = await c.run(); assert.equal(r.phase, 'waiting_user'); assert.equal(phones, 1);
});


test('attachment provenance stays in model context and cannot invalidate fresh phone evidence', async () => {
  const goal = 'Read the current system version';
  const warning = 'Attached image was saved earlier and is not the current live screen.';
  const document = 'File contents: a historical screenshot with an old version.';
  let models = 0;
  const contracts = [];
  const conversation = createConversation({ language: 'zh', goal,
    messages: input(`${goal}\n${warning}\n${document}`),
    rpc: async (method, params) => {
      if (method === 'phone.execute') {
        contracts.push(JSON.parse(params.success_condition));
        return { success: true, result: 'System version: 14' };
      }
      if (models++ === 0) {
        assert.ok(JSON.stringify(params.messages).includes(warning));
        return tool('operate_phone', {instruction:'Read the current system version', success_condition:'Version and label visible'});
      }
      return tool('finish_task', {outcome:'completed', summary:'System version: 14'});
    }, emit: () => {},
  });
  assert.equal((await conversation.run()).phase, 'completed');
  assert.deepEqual(contracts[0].userRequirements, [goal]);
  assert.ok(!JSON.stringify(contracts[0]).includes(warning));
  assert.ok(!JSON.stringify(contracts[0]).includes(document));
});


test('clarification answer resumes the saved task and reaches phone execution with amended requirements', async () => {
  const original = 'Open the application I choose';
  const checkpoints = [];
  const first = createConversation({ language: 'zh', messages: input(original), goal: original,
    rpc: async () => tool('finish_task', { outcome: 'waiting_user', summary: 'Which application?' }),
    emit: event => { if (event.type === 'checkpoint') checkpoints.push(event.messages); } });
  assert.equal((await first.run()).phase, 'waiting_user');
  let models = 0, phones = 0;
  const answer = 'Camera. Do not take a photo.';
  const next = createConversation({ language: 'zh', messages: input(answer), goal: original, userInput: answer,
    resume: checkpoints.at(-1), emit: () => {}, rpc: async (method, params) => {
      if (method === 'phone.execute') {
        phones++;
        const contract = JSON.parse(params.success_condition);
        assert.deepEqual(contract.userRequirements, [original, answer]);
        assert.equal(contract.scope, 'intermediate_step');
        assert.equal(contract.proposedStepCondition, 'Camera preview visible');
        return { success: true, result: 'Camera preview is visible' };
      }
      if (models++ === 0) {
        assert.ok(JSON.stringify(params.messages).includes('Which application?'));
        assert.ok(JSON.stringify(params.messages.at(-1)).includes(answer));
        return tool('operate_phone', { instruction: 'Open Camera without taking a photo', success_condition: 'Camera preview visible', mode: 'act', finishWhenVerified: true });
      }
      return tool('finish_task', { outcome: 'completed', summary: 'Camera is open' });
    } });
  const result = await next.run();
  assert.equal(result.phase, 'completed');
  assert.equal(phones, 1);
});


test('multiple clarification answers survive checkpoints without promoting attachment notes to requirements', async () => {
  const goal = 'Open the settings page I choose';
  const warning = 'Attachment provenance is not a user requirement';
  let checkpoint = [];
  for (const answer of ['Only view, never change settings', 'About device']) {
    const conversation = createConversation({ language: 'zh', goal, userInput: answer,
      messages: input(`${answer}\n${warning}`), resume: checkpoint,
      emit: event => { if (event.type === 'checkpoint') checkpoint = event.messages; },
      rpc: async () => tool('finish_task', { outcome: 'waiting_user', summary: 'Anything else?' }) });
    assert.equal((await conversation.run()).phase, 'waiting_user');
  }
  let models = 0;
  const last = createConversation({ language: 'zh', goal, userInput: 'Continue', messages: input('Continue'), resume: checkpoint,
    emit: () => {}, rpc: async (method, params) => {
      if (method === 'phone.execute') {
        const requirements = JSON.parse(params.success_condition).userRequirements;
        assert.deepEqual(requirements, [goal, 'Only view, never change settings', 'About device', 'Continue']);
        assert.ok(!JSON.stringify(requirements).includes(warning));
        return { success: true, result: 'About device is visible' };
      }
      return models++ === 0
        ? tool('operate_phone', { instruction: 'View About device', success_condition: 'About device visible' })
        : tool('finish_task', { outcome: 'completed', summary: 'About device is open' });
    } });
  assert.equal((await last.run()).phase, 'completed');
});

test('inline plans advance a two-page task in three model calls with prior facts intact', async () => {
  let models = 0, phones = 0;
  const events = [], plans = ['Read page A', 'Read page B', 'Combine results'];
  const conversation = createConversation({ language: 'zh', messages: input('Read A and B'), emit: e => events.push(e),
    rpc: async (method, params) => {
      if (method === 'phone.execute') {
        assert.equal(Object.hasOwn(params, 'plan'), false);
        assert.equal(JSON.parse(params.success_condition).scope, 'intermediate_step');
        assert.equal(events.filter(e => e.type === 'plan').at(-1).currentStep, phones);
        return { success: true, result: ++phones === 1 ? 'A: 12' : 'B: 9' };
      }
      if (models > 0) assert.match(JSON.stringify(params.messages), /A: 12/);
      if (models++ < 2) return tool('operate_phone', {
        instruction: `Read page ${models}`, success_condition: 'Current value visible',
        plan: { steps: plans, currentStep: models - 1 },
      });
      assert.match(JSON.stringify(params.messages), /B: 9/);
      return tool('finish_task', { outcome: 'completed', summary: 'A: 12, B: 9; difference: 3' });
    } });
  const result = await conversation.run();
  assert.equal(models, 3); assert.equal(phones, 2);
  assert.equal(result.phase, 'completed');
  assert.deepEqual(events.filter(e => e.type === 'plan').map(e => e.currentStep), [0, 1]);
});

test('inline plan with invalid index cannot reach the phone', async () => {
  let models = 0, phones = 0;
  const conversation = createConversation({ language: 'zh', messages: input('Read A'), emit: () => {},
    rpc: async method => {
      if (method === 'phone.execute') { phones++; return { success: true, result: 'A' }; }
      return models++ === 0 ? tool('operate_phone', { instruction: 'Read A', success_condition: 'A visible',
        plan: { steps: ['Read A'], currentStep: 2 } }) : tool('finish_task', { outcome: 'failed', summary: 'Invalid plan' });
    } });
  assert.equal((await conversation.run()).phase, 'failed');
  assert.equal(phones, 0);
});


test('forwards complex-operation effort while retaining independent verification', async () => {
  let models = 0;
  let received;
  const conversation = createConversation({ language: 'zh', messages: input('organize across pages'), rpc: async (method, args) => {
    if (method === 'phone.execute') { received = args; return { success: true, result: 'folder verified' }; }
    return models++ === 0 ? tool('operate_phone', { instruction: 'group icons', success_condition: 'folder visible',
      mode: 'act', app: '', effort: 'deepThink', finishWhenVerified: true }) :
      tool('finish_task', { outcome: 'completed', summary: 'folder verified' });
  }, emit: () => {} });
  assert.equal((await conversation.run()).phase, 'completed');
  assert.equal(received.effort, 'deepThink');
  assert.equal(JSON.parse(received.success_condition).scope, 'entire_user_goal');
});

test('a second precomputed phone operation waits for a new model decision', async () => {
  let turns = 0;
  const performed = [];
  const conversation = createConversation({ language: 'zh', messages: input('整理桌面'), rpc: async (method, args) => {
    if (method === 'phone.execute') { performed.push(args.instruction); return { success: true, result: '当前页已盘点' }; }
    if (turns++ === 0) {
      const batch = tool('operate_phone', { instruction: '盘点', success_condition: '可见图标' });
      const later = tool('operate_phone', { instruction: '不应预先执行', success_condition: '未知' }).choices[0].message.tool_calls[0];
      later.id = 'later-phone'; batch.choices[0].message.tool_calls.push(later); return batch;
    }
    if (turns === 2) {
      assert.match(JSON.stringify(args.messages), /后续预先生成的操作未执行/);
      return tool('operate_phone', { instruction: '根据结果继续', success_condition: '已归组' });
    }
    return tool('finish_task', { outcome: 'completed', summary: '完成' });
  }, emit: () => {} });
  const result = await conversation.run();
  assert.deepEqual(performed, ['盘点', '根据结果继续']);
  assert.equal(result.operations, 2);
});
