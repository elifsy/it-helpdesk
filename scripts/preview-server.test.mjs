import { test } from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import fs from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { createPreviewServer } from './preview-server.mjs'

async function fixture(t) {
  const directory = await fs.mkdtemp(fileURLToPath(new URL('../server/target/preview-check-', import.meta.url)))
  await fs.mkdir(directory + '/assets')
  await fs.writeFile(directory + '/index.html', '<!doctype html><title>IT preview fixture</title>')
  await fs.writeFile(directory + '/assets/app-test.js', 'window.fixture=true')
  await fs.writeFile(directory + '/.env', 'SECRET=must-not-be-served')
  const state = { demoEnabled: false, writes: [], binary: Buffer.from([0, 128, 255, 17]) }
  const backend = http.createServer(async (req, res) => {
    if (req.url === '/api/auth/config') {
      res.setHeader('Content-Type', 'application/json')
      res.end(JSON.stringify({ code: 0, data: { demoEnabled: state.demoEnabled, corpId: 'test-corp' } })); return
    }
    const chunks = []; for await (const chunk of req) chunks.push(chunk)
    state.writes.push({ method: req.method, url: req.url, headers: req.headers, body: Buffer.concat(chunks) })
    res.writeHead(201, { 'Content-Type': 'application/octet-stream' }); res.end(state.binary)
  })
  await new Promise(resolve => backend.listen(0, '127.0.0.1', resolve))
  const server = createPreviewServer({ backend: 'http://127.0.0.1:' + backend.address().port, distDir: directory })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  t.after(async () => {
    server.closeAllConnections(); backend.closeAllConnections()
    await Promise.all([new Promise(resolve => server.close(resolve)), new Promise(resolve => backend.close(resolve))])
  })
  return { base: 'http://127.0.0.1:' + server.address().port, state }
}

test('preview serves only build assets and keeps business routes behind authentication', async t => {
  const { base, state } = await fixture(t)
  const entry = await fetch(base + '/'); assert.equal(entry.status, 200); assert.match(await entry.text(), /IT preview fixture/)
  assert.equal(entry.headers.get('cache-control'), 'no-store')
  assert.equal(entry.headers.get('x-robots-tag'), 'noindex, nofollow, noarchive')
  assert.equal((await fetch(base + '/assets/app-test.js')).status, 200)
  for (const route of ['/.env', '/src/main.ts', '/scripts/start.mjs', '/assets/app-test.js.map']) assert.equal((await fetch(base + route)).status, 404)
  assert.equal((await fetch(base + '/api/tickets', { headers: { 'X-Demo-User': 'test-admin' } })).status, 401)
  assert.equal(state.writes.length, 0)
})

test('authenticated requests preserve their method, token, multipart bytes and download response', async t => {
  const { base, state } = await fixture(t)
  const payload = Buffer.from('--test-boundary\r\nContent-Disposition: form-data; name="file"\r\n\r\nbinary-test\r\n--test-boundary--\r\n')
  const response = await fetch(base + '/api/attachments?bizNo=test-only', { method: 'POST', body: payload,
    headers: { authorization: 'Bearer test-only-token', 'X-Demo-User': 'must-be-removed', 'Content-Type': 'multipart/form-data; boundary=test-boundary' } })
  assert.equal(response.status, 201); assert.deepEqual(Buffer.from(await response.arrayBuffer()), state.binary)
  assert.equal(state.writes[0].method, 'POST'); assert.equal(state.writes[0].url, '/api/attachments?bizNo=test-only')
  assert.equal(state.writes[0].headers.authorization, 'Bearer test-only-token')
  assert.equal(state.writes[0].headers['x-demo-user'], undefined)
  assert.equal(state.writes[0].headers['content-type'], 'multipart/form-data; boundary=test-boundary')
  assert.deepEqual(state.writes[0].body, payload)
})

test('preview stops proxying if the backend switches to demo mode', async t => {
  const { base, state } = await fixture(t); state.demoEnabled = true
  for (const route of ['/api/auth/config', '/api/auth/login', '/api/tickets']) {
    const response = await fetch(base + route, { headers: { authorization: 'Bearer test-only-token' } })
    assert.equal(response.status, 503)
  }
  assert.equal(state.writes.length, 0)
})

test('preview backend must stay on the local machine', () => {
  for (const backend of ['https://example.com', 'http://example.com', 'http://user:password@127.0.0.1:8081', 'http://127.0.0.1:8081/other']) {
    assert.throws(() => createPreviewServer({ backend, distDir: '.' }), /local HTTP origin/)
  }
})
