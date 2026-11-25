const reference = /^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$/

export function notificationReference(value: unknown): string | undefined {
  return typeof value === 'string' && reference.test(value) ? value : undefined
}

export function notificationEntry(href: string): URL | null {
  const url = new URL(href)
  if (url.hash && url.hash !== '#/') return null
  const target = url.searchParams.get('notice')
  if (!target) return null
  const ticket = /^\/ticket\/([^/?#]+)$/.exec(target)
  const request = /^\/(?:admin\/(?:approvals|purchase)\?request|assets\?(?:request|purchase|returnAsset))=([^/?#&]+)$/.exec(target)
  if (!notificationReference(ticket?.[1] ?? request?.[1])) return null
  url.searchParams.delete('notice')
  url.hash = target
  return url
}
