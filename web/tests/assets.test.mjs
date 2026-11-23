import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'
const exports = {}
vm.runInNewContext(ts.transpileModule(fs.readFileSync(new URL('../src/utils/assets.ts', import.meta.url), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText, { exports })
const { assetActions, eligibleAssets } = exports
const assets = [
  { no: 'PC-IDLE', category: '终端', status: '闲置' },
  { no: 'PRINT-IDLE', category: '打印复印', status: '待入库' },
  { no: 'PC-MINE', category: '终端', status: '在用', userName: '张伟' },
  { no: 'PC-OTHER', category: '终端', status: '在用', userName: '赵敏' },
  { no: 'PRINT-BORROWED', category: '打印复印', status: '借出中', borrower: '张伟' },
  { no: 'PC-BORROWED-OTHER', category: '终端', status: '借出中', borrower: '赵敏' },
  { no: 'SHARED', category: '终端', status: '在用', userName: '' }
]
test('receive choices honor both category and available status', () => {
  assert.deepEqual(eligibleAssets(assets, 'RECEIVE', '张伟', '终端').map(a => a.no), ['PC-IDLE'])
  assert.deepEqual(eligibleAssets(assets, 'RECEIVE', '张伟', '打印复印').map(a => a.no), ['PRINT-IDLE'])
})
test('borrow choices include unassigned shared equipment in the selected category', () => {
  assert.deepEqual(eligibleAssets(assets, 'BORROW', '张伟', '终端').map(a => a.no), ['PC-IDLE', 'SHARED'])
})
test('replacement shows only the current user owned equipment in the selected category', () => {
  assert.deepEqual(eligibleAssets(assets, 'REPLACE', '张伟', '终端').map(a => a.no), ['PC-MINE'])
  assert.equal(eligibleAssets(assets, 'REPLACE', '张伟', '打印复印').length, 0)
})
test('return shows the current user owned or borrowed equipment and supports an empty category', () => {
  assert.deepEqual(eligibleAssets(assets, 'RETURN', '张伟', '打印复印').map(a => a.no), ['PRINT-BORROWED'])
  assert.deepEqual(eligibleAssets(assets, 'RETURN', '张伟', '终端').map(a => a.no), ['PC-MINE'])
  assert.deepEqual(eligibleAssets(assets, 'RETURN', '张伟', '').map(a => a.no), ['PC-MINE', 'PRINT-BORROWED'])
})
test('inconsistent allocation and scrapped equipment are not offered for receive or borrow', () => {
  for (const asset of [
    { no: 'BAD-IDLE', category: '终端', status: '闲置', userName: '赵敏' },
    { no: 'BAD-SHARED', category: '终端', status: '在用', borrower: '赵敏' },
    { no: 'SCRAPPED', category: '终端', status: '已报废' }
  ]) {
    assert.equal(assetActions(asset, '张伟').includes('RECEIVE'), false)
    assert.equal(assetActions(asset, '张伟').includes('BORROW'), false)
  }
})
