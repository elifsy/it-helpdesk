export const STATUS_NAME: Record<string, string> = {
  NEW: '待受理',
  ACCEPTED: '已受理',
  IN_PROGRESS: '处理中',
  PENDING_USER: '待用户',
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

export const ASSET_STATUS_CLASS: Record<string, string> = {
  在用: 'b-blue',
  闲置: 'b-green',
  借出中: 'b-orange',
  维修中: 'b-purple',
  待入库: 'b-gray',
  待报废: 'b-red',
  已报废: 'b-gray'
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

export function remainText(minutes?: number | null) {
  if (minutes === null || minutes === undefined) return ''
  if (minutes < 0) {
    const h = Math.floor(-minutes / 60)
    return h > 0 ? `已超时 ${h} 小时` : `已超时 ${-minutes} 分钟`
  }
  const h = Math.floor(minutes / 60)
  return h > 0 ? `剩余 ${h} 小时` : `剩余 ${minutes} 分钟`
}
