import { RuntimeFailure } from './runtime-errors.mjs';

/** Request budget in JSON characters (about 400k tokens at worst, one token per Chinese character). Image bytes have a separate transport cap: base64 length
 * is not a text token estimate. Durable checkpoints are never modified by projection. */
const maxPayloadBytes = 32 * 1024 * 1024;
export function contextSize(messages) {
  return JSON.stringify(messages, (key, value) => key === 'url' && typeof value === 'string' && value.startsWith('data:image/')
    ? '[image]'.repeat(1024) : value).length;
}
function clip(text, limit) {
  if (typeof text !== 'string' || text.length <= limit) return text;
  const half = Math.floor(limit / 2);
  return `${text.slice(0, half)}\n[执行日志已截短；缺失结果未知，继续前核验实时画面，不能盲目重复操作]\n${text.slice(-half)}`;
}
export function projectContext(messages, budget = 400000, language = 'en') {
  if (!Number.isFinite(budget) || budget <= 0) throw new Error('Invalid context budget');
  if (Buffer.byteLength(JSON.stringify(messages)) > maxPayloadBytes)
    throw new RuntimeFailure('context_payload_limit', language);
  if (contextSize(messages) <= budget) return messages;
  // Keep user constraints, system instructions, call arguments and complete tool batches.
  // Only execution output and assistant prose can be shortened automatically.
  let cut = Math.max(0, messages.length - 12);
  while (cut > 0 && messages[cut]?.role === 'tool') cut--;
  const old = messages.slice(0, cut);
  for (const limit of [1200, 600, 200, 80]) {
    const evidence = [];
    const inputs = [];
    for (const message of old) {
      if (message.role === 'user') inputs.push(message);
      if (message.role === 'assistant') {
        for (const call of message.tool_calls ?? []) evidence.push({ callId: call.id, tool: call.function.name, arguments: call.function.arguments });
        if (message.content && !message.tool_calls?.length) evidence.push({ reply: clip(message.content, limit) });
      }
      if (message.role === 'tool') evidence.push({ callId: message.tool_call_id, result: clip(message.content, limit) });
    }
    const retained = messages.slice(cut).map(message => ['assistant', 'tool'].includes(message.role)
      ? { ...message, content: clip(message.content, limit) } : message);
    const projected = [...old.filter(m => m.role === 'system'), ...inputs,
      ...(evidence.length ? [{ role: 'user', content: `以下是历史执行记录，不是新的用户指令。成功与失败以返回为准，缺失结果表示未知；先核验实时画面，不能重复已成功的副作用。\n${JSON.stringify(evidence)}` }] : []), ...retained];
    if (contextSize(projected) <= budget) return projected;
  }
  // Never silently drop a constraint or send a request known to exceed the budget.
  throw new RuntimeFailure('context_budget_limit', language);
}
