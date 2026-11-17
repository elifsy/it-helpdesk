import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'

const exports = {}
vm.runInNewContext(ts.transpileModule(fs.readFileSync(new URL('../src/utils/ticketQueue.ts', import.meta.url), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText, { exports })
const { ticketQueueText } = exports

test('queue text includes the current IT and zero is a valid position', () => {
  const ticket = { status: 'IN_PROGRESS', assigneeId: 4, assigneeName: '李强' }
  assert.equal(ticketQueueText({ ...ticket, queueAhead: 3 }), '接单 IT：李强 · 前面还有 3 单待处理')
  assert.equal(ticketQueueText({ ...ticket, queueAhead: 0 }), '接单 IT：李强 · 前面还有 0 单待处理')
})
test('unassigned work does not claim a queue position', () => {
  assert.equal(ticketQueueText({ status: 'NEW' }), '等待 IT 接单')
  assert.equal(ticketQueueText({ status: 'ACCEPTED', assigneeId: null, queueAhead: 0 }), '等待 IT 接单')
})
test('finished work stops queueing and pending confirmation has its own message', () => {
  assert.equal(ticketQueueText({ status: 'PENDING_CONFIRM', assigneeId: 4, queueAhead: 2 }), 'IT 已处理完成，等待您确认')
  for (const status of ['CLOSED', 'REJECTED', 'CANCELLED'])
    assert.equal(ticketQueueText({ status, assigneeId: 4, queueAhead: 2 }), '')
})
test('missing, stale or invalid queue counts are never presented as zero', () => {
  for (const queueAhead of [undefined, null, -1, 1.5, '2']) {
    assert.equal(ticketQueueText({ status: 'IN_PROGRESS', assigneeId: 4, assigneeName: '李强', queueAhead }), '接单 IT：李强 · 排队信息暂不可用，请刷新')
  }
  assert.equal(ticketQueueText(null), '')
})
