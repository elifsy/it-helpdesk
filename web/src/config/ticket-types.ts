export const TICKET_TYPES = [
  { v: 'INCIDENT', l: '设备坏了', s: '故障报修' },
  { v: 'SERVICE_REQUEST', l: '要开通/申领', s: '服务申请' },
  { v: 'QUESTION', l: '不会用', s: '咨询' }
] as const

export type TicketCategoriesByType = Record<string, Record<string, string[]>>
