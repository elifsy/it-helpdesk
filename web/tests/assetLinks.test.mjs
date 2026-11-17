import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'

const source = ts.transpileModule(fs.readFileSync(new URL('../src/utils/asset-links.ts', import.meta.url), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText
const exports = {}
vm.runInNewContext(source, { exports, URL, URLSearchParams })
const { parseAssetNo, assetOperationPath, legacyAssetEntry, assetQrContent } = exports

test('scans read old labels, new hash routes, URL queries and plain inventory numbers', () => {
  for (const text of ['http://localhost:5173/#asset=IT-PC-2024-0137', 'https://test.trycloudflare.com/#/assets?asset=IT-PC-2024-0137',
    'https://helpdesk.example/?asset=IT-PC-2024-0137', '#asset=IT-PC-2024-0137', ' IT-PC-2024-0137 ']) {
    assert.equal(parseAssetNo(text), 'IT-PC-2024-0137')
  }
  assert.equal(parseAssetNo('ASSET-TEST-1'), 'ASSET-TEST-1')
})
test('asset numbers round-trip safely through route queries', () => {
  const no = '设备 A&B+1'
  const route = assetOperationPath(no)
  assert.equal(route, '/assets?asset=' + encodeURIComponent(no))
  assert.equal(parseAssetNo('https://helpdesk.example/#' + route), no)
})
test('invalid scans and malformed encodings do not crash or match unrelated query fields', () => {
  for (const text of ['', 'not an asset label', '#asset=', '#asset=%E0%A4%A', 'https://helpdesk.example/?notasset=IT-PC-1',
    '#asset=one%2Ftwo', '#asset=one%00two', '#asset=' + 'A'.repeat(33), 'javascript:alert(1)']) assert.equal(parseAssetNo(text), null)
})
test('legacy entry links become valid Vue routes before login while current routes stay unchanged', () => {
  assert.equal(legacyAssetEntry('https://helpdesk.example/#asset=ASSET-TEST-1'), '/assets?asset=ASSET-TEST-1')
  assert.equal(legacyAssetEntry('https://helpdesk.example/?asset=ASSET-TEST-1'), '/assets?asset=ASSET-TEST-1')
  assert.equal(legacyAssetEntry('https://helpdesk.example/#/assets?asset=ASSET-TEST-1'), null)
  assert.equal(legacyAssetEntry('https://helpdesk.example/?asset=ASSET-TEST-1#/admin/config'), null)
  assert.equal(legacyAssetEntry('https://helpdesk.example/#asset=%E0%A4%A'), null)
})
test('QR labels generated inside the temporary preview point to that public preview', () => {
  assert.equal(assetQrContent('ASSET-TEST-1', 'http://localhost:5174/#asset=ASSET-TEST-1',
    'https://active-preview.trycloudflare.com/?v=mobile#/admin/assets/ASSET-TEST-1'),
    'https://active-preview.trycloudflare.com/#/assets?asset=ASSET-TEST-1')
})
test('stable deployments retain their configured label host and deployment prefix', () => {
  const expected = 'https://assets.example/helpdesk/#/assets?asset=ASSET-TEST-1'
  for (const current of ['http://localhost:5174/', 'https://helpdesk.example/', 'https://trycloudflare.com.evil.example/'])
    assert.equal(assetQrContent('ASSET-TEST-1', 'https://assets.example/helpdesk/#asset=ASSET-TEST-1', current), expected)
})
test('QR labels reject invalid inventory numbers and non-HTTP configured addresses', () => {
  assert.throws(() => assetOperationPath(''), /设备编号无效/)
  assert.throws(() => assetQrContent('ASSET-TEST-1', 'javascript:alert(1)', 'https://helpdesk.example/'), /二维码访问地址无效/)
})
