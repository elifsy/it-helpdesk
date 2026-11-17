import http from 'node:http'
import { createReadStream } from 'node:fs'
import { stat } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const mimeTypes = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.ico': 'image/x-icon', '.woff': 'font/woff', '.woff2': 'font/woff2' }
const publicApi = new Set(['/api/auth/config', '/api/auth/login', '/api/health'])

export function createPreviewServer({ backend, distDir }) {
  const target = new URL(backend)
  if (target.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(target.hostname)
      || target.username || target.password || target.pathname !== '/' || target.search || target.hash) throw new Error('Preview backend must be a local HTTP origin')
  const bundle = path.resolve(distDir)
  return http.createServer(async (request, response) => {
    response.setHeader('X-Robots-Tag', 'noindex, nofollow, noarchive')
    response.setHeader('X-Content-Type-Options', 'nosniff')
    response.setHeader('Referrer-Policy', 'same-origin')
    response.setHeader('X-Helpdesk-Preview', '1')
    const json = (status, message) => {
      request.resume()
      response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' })
      response.end(JSON.stringify({ code: status, message }))
    }
    let url, pathname
    try { url = new URL(request.url, 'http://preview.local'); pathname = decodeURIComponent(url.pathname) }
    catch { return json(400, '请求地址无效') }
    if (pathname.includes('\\') || pathname.includes('\0') || /%2f|%5c/i.test(url.pathname)
        || pathname.split('/').some(part => part === '.' || part === '..')) return json(404, '未找到页面')

    if (pathname.startsWith('/api/')) {
      // 业务接口仍由原后端鉴权；额外阻止匿名访问与演示身份头，避免误把本地演示模式分享到公网。
      if (!publicApi.has(pathname) && !/^Bearer \S+$/.test(request.headers.authorization || '')) return json(401, '请先通过钉钉登录')
      try {
        const configResponse = await fetch(new URL('/api/auth/config', target), { signal: AbortSignal.timeout(3000) })
        const config = await configResponse.json()
        if (!configResponse.ok || config.code !== 0 || config.data?.demoEnabled !== false) return json(503, '预览需要启用钉钉免登，请检查本地服务')
      } catch { return json(503, '本地报修服务暂时不可用，请稍后重试') }
      const headers = {}
      for (const name of ['accept', 'accept-encoding', 'accept-language', 'authorization', 'content-type', 'content-length', 'range', 'if-none-match', 'if-modified-since']) {
        if (request.headers[name] !== undefined) headers[name] = request.headers[name]
      }
      const upstream = http.request(new URL(url.pathname + url.search, target), { method: request.method, headers }, remote => {
        for (const [name, value] of Object.entries(remote.headers)) {
          if (value !== undefined && !['connection', 'keep-alive', 'transfer-encoding', 'x-robots-tag', 'x-helpdesk-preview'].includes(name)) response.setHeader(name, value)
        }
        response.writeHead(remote.statusCode || 502)
        remote.pipe(response)
        response.on('close', () => remote.destroy())
      })
      upstream.setTimeout(30000, () => upstream.destroy())
      upstream.on('error', () => { if (!response.headersSent) json(502, '预览接口暂时不可用'); else response.destroy() })
      request.on('aborted', () => upstream.destroy())
      request.pipe(upstream)
      return
    }
    if (!['GET', 'HEAD'].includes(request.method)) return json(405, '请求方式不支持')
    if (pathname === '/robots.txt') {
      response.writeHead(200, { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store' })
      response.end(request.method === 'HEAD' ? undefined : 'User-agent: *\nDisallow: /\n'); return
    }
    // 仅发布构建包入口和打包后的资源，不托管项目根目录或前端源码。
    const entry = pathname === '/' || pathname === '/index.html'
    const asset = /^\/assets\/[A-Za-z0-9_.-]+$/.test(pathname) && !pathname.endsWith('.map')
    if (!entry && !asset) return json(404, '未找到页面')
    const file = path.resolve(bundle, entry ? 'index.html' : '.' + pathname)
    if (!file.startsWith(bundle + path.sep)) return json(404, '未找到页面')
    try {
      const info = await stat(file)
      if (!info.isFile()) return json(404, '未找到页面')
      response.writeHead(200, { 'Content-Type': mimeTypes[path.extname(file)] || 'application/octet-stream', 'Content-Length': info.size,
        'Cache-Control': entry ? 'no-store' : 'public, max-age=86400, immutable' })
      if (request.method === 'HEAD') { response.end(); return }
      const stream = createReadStream(file)
      stream.on('error', () => response.destroy()); response.on('close', () => stream.destroy())
      stream.pipe(response)
    } catch { json(404, '未找到页面') }
  })
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = Object.fromEntries(process.argv.slice(2).map(arg => arg.replace(/^--/, '').split('=')))
  const port = Number(args.port || 8789), backendPort = Number(args['backend-port'] || 8081)
  if (![port, backendPort].every(value => Number.isInteger(value) && value > 0 && value < 65536)) throw new Error('Invalid preview port')
  const server = createPreviewServer({ backend: 'http://127.0.0.1:' + backendPort, distDir: fileURLToPath(new URL('../web/dist', import.meta.url)) })
  server.on('error', error => { console.error('Preview server failed: ' + error.code); process.exitCode = 1 })
  server.listen(port, '127.0.0.1', () => console.log('Helpdesk preview ready: http://127.0.0.1:' + port))
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { server.closeAllConnections(); server.close(() => process.exit(0)) })
}
