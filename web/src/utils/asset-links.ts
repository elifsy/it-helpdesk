function assetNumber(value: string | null): string | null {
  if (!value) return null
  const no = value.trim()
  return no && Array.from(no).length <= 32 && !/[\\/\u0000-\u001f\u007f\ufffd]/.test(no) ? no : null
}

/** 支持旧标签、新的路由地址，以及直接包含设备编号的标签。扫码时只取编号，不跳往标签中的域名。 */
export function parseAssetNo(text: string): string | null {
  if (typeof text !== 'string' || !text.trim()) return null
  const value = text.trim()
  const queryAsset = (query: string) => assetNumber(new URLSearchParams(query).get('asset'))
  if (value.startsWith('#')) {
    const hash = value.slice(1)
    if (hash.startsWith('asset=')) return queryAsset(hash)
    return queryAsset(hash.includes('?') ? hash.slice(hash.indexOf('?') + 1) : hash)
  }
  if (/^https?:\/\//i.test(value)) {
    try {
      const url = new URL(value)
      return (url.hash ? parseAssetNo(url.hash) : null) || queryAsset(url.search)
    } catch { return null }
  }
  return /^[\p{L}\p{N}_.-]+$/u.test(value) ? assetNumber(value) : null
}

export function assetOperationPath(no: string): string {
  const normalized = assetNumber(no)
  if (!normalized) throw new Error('设备编号无效')
  return '/assets?asset=' + encodeURIComponent(normalized)
}

/** 必须在创建 hash 路由前兼容旧地址，避免免登后丢失扫码目标。 */
export function legacyAssetEntry(href: string): string | null {
  const url = new URL(href)
  const hash = url.hash.slice(1)
  const oldHash = hash.startsWith('asset=') || hash.startsWith('?asset=') || hash.startsWith('/?asset=')
  const queryEntry = !hash || hash === '/' || hash === '/home'
  const no = oldHash ? parseAssetNo(url.hash) : queryEntry ? assetNumber(url.searchParams.get('asset')) : null
  return no ? assetOperationPath(no) : null
}

/** 临时公网预览生成的标签使用当前预览地址，正式环境继续沿用管理员配置的基址。 */
export function assetQrContent(no: string, serverContent: string, currentHref: string): string {
  const current = new URL(currentHref)
  const preview = current.protocol === 'https:' && /^[a-z0-9-]+\.trycloudflare\.com$/i.test(current.hostname)
  const target = preview ? new URL(current.origin + '/') : new URL(serverContent)
  if (!['http:', 'https:'].includes(target.protocol) || target.username || target.password) throw new Error('二维码访问地址无效')
  target.hash = assetOperationPath(no)
  return target.href
}
