/** 钉钉通知把工单号放在查询参数里，启动时交给应用的工单详情路由。 */
export function ticketNotificationEntry(href: string): URL | null {
  const url = new URL(href)
  if (url.hash && url.hash !== '#/') return null
  const no = url.searchParams.get('ticket')
  if (!no || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$/.test(no)) return null
  url.searchParams.delete('ticket')
  url.hash = '/ticket/' + no
  return url
}
