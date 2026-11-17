export interface QueueTicket {
  status: string
  assigneeId?: number | null
  assigneeName?: string | null
  queueAhead?: number | null
}

export function ticketQueueText(ticket?: QueueTicket | null): string {
  if (!ticket || ['CLOSED', 'REJECTED', 'CANCELLED'].includes(ticket.status)) return ''
  if (ticket.status === 'PENDING_CONFIRM') return 'IT 已处理完成，等待您确认'
  if (!ticket.assigneeId) return '等待 IT 接单'
  const engineer = ticket.assigneeName || 'IT'
  if (['ACCEPTED', 'IN_PROGRESS', 'PENDING_USER', 'PENDING_VENDOR'].includes(ticket.status)
      && Number.isSafeInteger(ticket.queueAhead) && (ticket.queueAhead as number) >= 0) {
    return `接单 IT：${engineer} · 前面还有 ${ticket.queueAhead} 单待处理`
  }
  return `接单 IT：${engineer} · 排队信息暂不可用，请刷新`
}
