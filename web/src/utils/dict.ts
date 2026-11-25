export const STATUS_NAME: Record<string, string> = {
  NEW: '待受理',
  ACCEPTED: '已受理',
  IN_PROGRESS: '处理中',
  PENDING_USER: '待补充信息',
  PENDING_VENDOR: '待第三方',
  PENDING_CONFIRM: '待确认',
  CLOSED: '已关闭',
  REJECTED: '已拒绝',
  CANCELLED: '已取消'
}

export const STATUS_CLASS: Record<string, string> = {
  NEW: 'b-gray',
  ACCEPTED: 'b-blue',
  IN_PROGRESS: 'b-blue',
  PENDING_USER: 'b-purple',
  PENDING_VENDOR: 'b-purple',
  PENDING_CONFIRM: 'b-orange',
  CLOSED: 'b-green',
  REJECTED: 'b-red',
  CANCELLED: 'b-gray'
}

export const TICKET_TYPE_NAME: Record<string, string> = {
  INCIDENT: '故障报修',
  SERVICE_REQUEST: '服务申请',
  QUESTION: '使用咨询'
}

export const IMPACT_NAME: Record<string, string> = {
  SELF: '只影响我',
  DEPT: '影响部门',
  FLOOR: '影响整层',
  ALL: '影响全公司'
}

export const WORK_NAME: Record<string, string> = {
  FINE: '不影响工作',
  SLOW: '影响但能凑合',
  BLOCKED: '完全无法工作'
}

export const REQ_TYPE: Record<string, { name: string; icon: string; desc: string }> = {
  RECEIVE: { name: '资产领用', icon: '▣', desc: '长期领用，设备绑定到个人名下' },
  BORROW: { name: '资产借用', icon: '◷', desc: '短期借用，需按约定日期归还' },
  REPLACE: { name: '资产更换', icon: '⇄', desc: '旧机换新机，含旧机回收与工单转移' },
  RETURN: { name: '归还设备', icon: '↩', desc: '归还已借出的设备' },
  REPAIR: { name: '故障报修', icon: '＋', desc: '设备异常，提交维修工单' }
}

export const REQ_STATUS_NAME: Record<string, string> = {
  PENDING: '待审批',
  APPROVED: '已批准',
  REJECTED: '已驳回',
  COMPLETED: '已完成',
  CANCELLED: '已撤销'
}

export const REQ_STATUS_CLASS: Record<string, string> = {
  PENDING: 'b-orange',
  APPROVED: 'b-blue',
  REJECTED: 'b-red',
  COMPLETED: 'b-green',
  CANCELLED: 'b-gray'
}

/** 线下交接状态：审批通过后等 IT 确认，确认完才真正改资产 */
export const HANDOVER_NAME: Record<string, string> = {
  PICKUP: '待领取',
  RETURN: '待归还'
}
export const HANDOVER_HINT: Record<string, string> = {
  PICKUP: '审批已通过，请到 IT 服务台/备件库领取，IT 确认后完成',
  RETURN: '审批已通过，请把设备交到 IT 服务台/备件库，IT 确认后完成'
}
/** 卡片上给 IT 的按钮文案（更换分两次） */
export function handoverActionLabel(type: string, stage?: string | null) {
  if (stage === 'RETURN') return type === 'REPLACE' ? '确认已归还旧机' : '确认已归还'
  if (stage === 'PICKUP') return type === 'REPLACE' ? '确认已领取新机' : '确认已领取'
  return '确认交接'
}

export const ASSET_STATUS_CLASS: Record<string, string> = {
  在用: 'b-blue',
  闲置: 'b-green',
  借出中: 'b-orange',
  维修中: 'b-purple',
  待入库: 'b-gray',
  待报废: 'b-red',
  已报废: 'b-gray',
  // 采购目录项在选择器里显示的虚拟状态
  可申请: 'b-blue'
}

export const RECORD_KIND: Record<string, string> = {
  REMOTE: '远程处理',
  ONSITE: '现场处理',
  PHONE: '电话指导',
  REPAIR: '送修',
  VENDOR: '厂商处理',
  REPLACE: '换机',
  KB: '知识引导'
}

export function statusName(s: string) {
  return STATUS_NAME[s] || s
}

export function remainText(minutes?: number | null, workTime?: boolean, pausedAt?: string | null) {
  if (pausedAt) return 'SLA 已停表'
  if (minutes === null || minutes === undefined) return ''
  const hours = workTime ? '工作小时' : '小时'
  if (minutes < 0) {
    const h = Math.floor(-minutes / 60)
    return h > 0 ? `已超时 ${h} ${hours}` : `已超时 ${-minutes} 分钟`
  }
  const h = Math.floor(minutes / 60)
  return h > 0 ? `剩余 ${h} ${hours}` : `剩余 ${minutes} 分钟`
}

/** 购入申请单状态 */
export const PR_STATUS_NAME: Record<string, string> = {
  PENDING: '待审批',
  APPROVED: '已批准待处理',
  COMPLETED: '已完成',
  REJECTED: '已驳回',
  CANCELLED: '已撤销'
}
export const PR_STATUS_CLASS: Record<string, string> = {
  PENDING: 'b-orange',
  APPROVED: 'b-blue',
  COMPLETED: 'b-green',
  REJECTED: 'b-red',
  CANCELLED: 'b-gray'
}

/** 购入申请明细状态：一行一台设备，各自独立流转 */
export const PR_ITEM_STATUS_NAME: Record<string, string> = {
  PENDING: '待处理',
  ORDERED: '已申请采购',
  PENDING_PICKUP: '待领取',
  COMPLETED: '已交付'
}
export const PR_ITEM_STATUS_CLASS: Record<string, string> = {
  PENDING: 'b-gray',
  ORDERED: 'b-blue',
  PENDING_PICKUP: 'b-green',
  COMPLETED: 'b-green'
}
