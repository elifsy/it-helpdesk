import http from './http'
import { assetQrContent } from '../utils/asset-links'
import type { TicketCategoriesByType } from '../config/ticket-types'
import type { PersonOption } from '../utils/people-search'

export interface TicketNotificationRule {
  type: string
  cat1: string
  cat2: string | null
  recipientIds: number[]
}

export interface IncomingNotificationRules {
  revision: number
  ticketRules: TicketNotificationRule[]
  assetRecipients: Record<string, number[]>
}

export interface NotificationRecipient extends PersonOption {
  department: string | null
  employeeNo: string | null
  role: 'IT' | 'ADMIN' | 'SUPER_ADMIN'
}

export interface IncomingNotificationConfig {
  rules: IncomingNotificationRules
  categories: TicketCategoriesByType
  recipients: NotificationRecipient[]
}

export const incomingNotificationApi = {
  config: () => http.get<any, IncomingNotificationConfig>('/admin/manage/incoming-notifications'),
  save: (rules: IncomingNotificationRules) =>
    http.put<any, IncomingNotificationRules>('/admin/manage/incoming-notifications', rules)
}

export interface LoginResult {
  token: string
  id: number
  name: string
  deptName: string
  role: string
  roles: string[]
  permissions: string[]
  dingUserId?: string
}

export interface PendingReportCounts {
  repair: number
  receive: number
  borrow: number
  replace: number
  returnCount: number
  overdueReturn?: number
}

export type MissingReportType = 'REPAIR' | 'RECEIVE' | 'BORROW' | 'REPLACE' | 'RETURN'
export interface MissingReportAsset {
  no: string
  name: string
  model?: string | null
  category: string
  status: string
  location?: string | null
}
export interface MissingReportAssetOptions {
  primary: MissingReportAsset[]
  replacement: MissingReportAsset[]
}
export interface MissingReportSummary {
  pending: number
  limit: number
  blocked: boolean
  pendingByType: PendingReportCounts
}
export interface MissingReport {
  no: string
  requesterId: number
  requesterName: string
  recordedBy: string
  title: string
  description: string
  channel: 'CHAT' | 'PHONE'
  completedAt: string
  status: 'PENDING' | 'SUBMITTED' | 'CANCELLED'
  businessType: MissingReportType
  assetNo?: string
  newAssetNo?: string
  expectReturn?: string
  assetRequestNo?: string
  ticketNo?: string
  cancelReason?: string
}
export type MissingReportRequester = PersonOption

export const missingReportApi = {
  summary: () => http.get<any, MissingReportSummary>('/missing-reports/summary'),
  mine: () => http.get<any, MissingReport[]>('/missing-reports/mine'),
  list: (page: number) => http.get<any, { list: MissingReport[]; total: number; size: number }>('/missing-reports', { params: { page } }),
  search: (keyword: string) => http.get<any, MissingReportRequester[]>('/missing-reports/requesters', { params: { keyword } }),
  assetOptions: (requesterId: number, type: MissingReportType) =>
    http.get<any, MissingReportAssetOptions>('/missing-reports/asset-options', { params: { requesterId, type } }),
  detail: (no: string) => http.get<any, {
    report: MissingReport
    evidence: { id: number; storedName: string; fileName: string; contentType?: string | null }[]
    asset?: MissingReportAsset
    replacement?: MissingReportAsset
  }>(`/missing-reports/${encodeURIComponent(no)}`),
  register: (report: {
    requesterId: number; title: string; description: string; channel: string; completedAt: string
    businessType?: MissingReportType; assetNo?: string; newAssetNo?: string; expectReturn?: string
  }, evidence: readonly File[]) => {
    const form = new FormData()
    form.append('report', new Blob([JSON.stringify(report)], { type: 'application/json' }), 'report.json')
    for (const file of evidence) form.append('evidence', file)
    return http.post<any, MissingReport>('/missing-reports', form)
  },
  submit: (no: string, form: { title: string; description: string; location: string; type: string; cat1: string; cat2: string }) =>
    http.post<any, { no: string }>(`/missing-reports/${encodeURIComponent(no)}/submit`, form),
  submitAsset: (no: string, reason: string) =>
    http.post<any, { no: string }>(`/missing-reports/${encodeURIComponent(no)}/submit-asset`, { reason }),
  cancel: (no: string, reason: string) => http.post(`/missing-reports/${encodeURIComponent(no)}/cancel`, { reason })
}

export const authApi = {
  config: () => http.get<any, { demoEnabled: boolean; corpId: string }>('/auth/config'),
  me: () => http.get<any, LoginResult>('/auth/me'),
  login: (payload: { authCode?: string; demoUser?: string }) =>
    http.post<any, LoginResult>('/auth/login', payload)
}

export const tutorialApi = {
  seen: () => http.get<any, string[]>('/tutorials'),
  start: (module: string) => http.post<any, { shouldStart: boolean }>(`/tutorials/${encodeURIComponent(module)}/start`)
}

export type BadgeAvatarSource = 'DINGTALK' | 'VIRTUAL'
export interface AccountBadge {
  avatarSource: BadgeAvatarSource
  avatarUrl?: string | null
  acceptedTickets?: number | null
  titleKey?: 'steel' | 'jade' | 'sapphire' | 'legend' | null
  title?: string | null
  nextTitle?: string | null
  nextTitleAt?: number | null
}
export const accountBadgeApi = {
  get: () => http.get<any, AccountBadge>('/account/badge'),
  setAvatar: (source: BadgeAvatarSource) => http.put<any, AccountBadge>('/account/badge/avatar', { source }),
  getTitleSettings: () => http.get<any, { enabled: boolean }>('/account/badge/titles'),
  setTitlesEnabled: (enabled: boolean) => http.put<any, { enabled: boolean }>('/account/badge/titles', { enabled })
}

export const ticketApi = {
  list: (params: Record<string, any>) => http.get<any, any>('/tickets', { params }),
  create: (data: any) => http.post<any, any>('/tickets', data),
  detail: (no: string) => http.get<any, any>(`/tickets/${no}`),
  accept: (no: string, remark?: string) => http.post<any, any>(`/tickets/${no}/accept`, { remark }),
  dispatch: (no: string, assigneeId: number) => http.post<any, any>(`/tickets/${no}/dispatch`, { assigneeId }),
  addRecord: (no: string, data: any) => http.post<any, any>(`/tickets/${no}/records`, data),
  finish: (no: string, data: { cat1: string; cat2?: string; note: string }) =>
    http.post<any, any>(`/tickets/${no}/finish`, data),
  requestInfo: (no: string, note: string) => http.post<any, any>(`/tickets/${no}/request-info`, { note }),
  provideInfo: (no: string, note: string) => http.post<any, any>(`/tickets/${no}/provide-info`, { note }),
  confirm: (no: string, data: any) => http.post<any, any>(`/tickets/${no}/confirm`, data),
  rate: (no: string, data: any) => http.post<any, any>(`/tickets/${no}/rate`, data),
  reject: (no: string, reason: string) => http.post<any, any>(`/tickets/${no}/reject`, { reason }),
  cancel: (no: string) => http.post<any, any>(`/tickets/${no}/cancel`)
}

export const assetApi = {
  update: (no: string, data: any) => http.put<any, any>(`/assets/${encodeURIComponent(no)}`, data),
  people: (keyword: string) => http.get<any, PersonOption[]>('/assets/people', { params: { keyword } }),
  bindHolders: (no: string, data: { editToken: string; userId?: number; borrowerId?: number }) =>
    http.put<any, any>(`/assets/${encodeURIComponent(no)}/holders`, data),
  setCategory: (no: string, category: string) => http.put<any, any>(`/assets/${encodeURIComponent(no)}/category`, { category }),
  list: (params: Record<string, any> = {}) => http.get<any, any>('/assets', { params }),
  page: (params: Record<string, any> = {}) => http.get<any, { list: any[]; total: number; page: number; size: number }>('/assets', { params: { page: 1, size: 15, ...params } }),
  detail: (no: string) => http.get<any, any>(`/assets/${encodeURIComponent(no)}`),
  borrowDue: () => http.get<any, BorrowDueList>('/assets/borrow-due'),
  qrcode: async (no: string) => {
    const result = await http.get<any, any>(`/assets/${encodeURIComponent(no)}/qrcode`)
    return { ...result, content: assetQrContent(no, result.content, window.location.href) }
  },
  create: (data: any) => http.post<any, any>('/assets', data),
  importTemplate: () => http.get<any, Blob>('/assets/import/template', { responseType: 'blob' }),
  importFile: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.post<any, { imported: number }>('/assets/import', form, { timeout: 60000 })
  }
}

export interface BorrowDueDevice {
  no: string
  name: string
  borrowDue: string
  category?: string | null
  icon?: string | null
}
export interface BorrowDueList {
  upcoming: BorrowDueDevice[]
  overdue: BorrowDueDevice[]
  advanceDays: number
  returnSupplementAssets?: string[]
}

export interface AssetCategory { id: number; name: string; sort: number; assetCount: number }
export const assetCategoryApi = {
  list: () => http.get<any, AssetCategory[]>('/asset-categories'),
  create: (data: { name: string; sort: number }) => http.post<any, AssetCategory>('/asset-categories', data),
  update: (id: number, data: { name: string; sort: number }) => http.put<any, AssetCategory>(`/asset-categories/${id}`, data),
  remove: (id: number) => http.delete<any, any>(`/asset-categories/${id}`)
}

export const purchaseApi = {
  catalog: (all = false) => http.get<any, any>('/purchase-catalog', { params: { all } }),
  saveCatalog: (data: any) => http.post<any, any>('/purchase-catalog', data),
  deleteCatalog: (id: number) => http.delete<any, any>(`/purchase-catalog/${id}`),
  list: (params: Record<string, any> = {}) => http.get<any, any>('/purchase-requests', { params }),
  detail: (id: number) => http.get<any, any>(`/purchase-requests/${id}`),
  /** 购物车：items = 目录项 + 台数 */
  create: (data: { items: { catalogId: number; qty: number }[]; reason: string }) =>
    http.post<any, any>('/purchase-requests', data),
  approve: (id: number, remark = '') => http.post<any, any>(`/purchase-requests/${id}/approve`, { remark }),
  reject: (id: number, remark = '') => http.post<any, any>(`/purchase-requests/${id}/reject`, { remark }),
  /** 明细级：库存配发 / 转采购 / 确认交付 */
  assignItem: (id: number, itemId: number, assetNo: string) =>
    http.post<any, any>(`/purchase-requests/${id}/items/${itemId}/assign`, { assetNo }),
  orderItem: (id: number, itemId: number, erpNo: string) =>
    http.post<any, any>(`/purchase-requests/${id}/items/${itemId}/order`, { erpNo }),
  deliverItem: (id: number, itemId: number) =>
    http.post<any, any>(`/purchase-requests/${id}/items/${itemId}/deliver`),
  cancel: (id: number) => http.post<any, any>(`/purchase-requests/${id}/cancel`)
}

export const dingApprovalApi = {
  status: () => http.get<any, any>('/admin/ding-approval/status'),
  fields: (type?: string) => http.get<any, any>('/admin/ding-approval/fields', { params: { type } }),
  lookup: (name: string) => http.get<any, any>('/admin/ding-approval/lookup', { params: { name } }),
  sync: () => http.post<any, any>('/admin/ding-approval/sync'),
  mock: (no: string, status = 'COMPLETED') =>
    http.post<any, any>(`/admin/ding-approval/mock/${encodeURIComponent(no)}?status=${encodeURIComponent(status)}`)
}

export const assetRequestApi = {
  list: (params: Record<string, any> = {}) => http.get<any, any>('/asset-requests', { params }),
  create: (data: any) => http.post<any, any>('/asset-requests', data),
  approve: (id: number, data: any = {}) => http.post<any, any>(`/asset-requests/${id}/approve`, data),
  reject: (id: number, reason: string) => http.post<any, any>(`/asset-requests/${id}/reject`, { reason }),
  cancel: (id: number) => http.post<any, any>(`/asset-requests/${id}/cancel`),
  handover: (id: number) => http.post<any, any>(`/asset-requests/${id}/handover`)
}

export const kbApi = {
  managedList: (params: Record<string, any> = {}) => http.get<any, any>('/kb/manage', { params }),
  managedDetail: (id: number) => http.get<any, any>(`/kb/manage/${id}`),
  capture: (ticketNo: string) => http.post<any, any>('/kb/manage/from-ticket', { ticketNo }),
  save: (id: number, data: any) => http.put<any, any>(`/kb/manage/${id}`, data),
  changeStatus: (id: number, action: 'publish' | 'withdraw' | 'archive', revision: number) =>
    http.post<any, any>(`/kb/manage/${id}/${action}`, { revision }),
  list: (params: Record<string, any> = {}) => http.get<any, any>('/kb', { params }),
  recommend: (params: Record<string, any>) => http.get<any, any>('/kb/recommend', { params }),
  detail: (id: number) => http.get<any, any>(`/kb/${id}`),
  vote: (id: number, helpful: boolean) => http.post<any, any>(`/kb/${id}/vote`, { helpful })
}

export const statsApi = {
  overview: () => http.get<any, any>('/stats/overview'),
  trend: (days = 14) => http.get<any, any>('/stats/trend', { params: { days } }),
  category: (days = 30) => http.get<any, any>('/stats/category', { params: { days } }),
  engineers: (days = 30) => http.get<any, any>('/stats/engineers', { params: { days } }),
  problemAssets: (limit = 5) => http.get<any, any>('/stats/problem-assets', { params: { limit } })
}

export interface Notification {
  id: number
  title: string
  content: string
  isRead: number
  createdAt: string
  action?: { label: string; path: string } | null
}

export const notifyApi = {
  list: () => http.get<any, Notification[]>('/notifies'),
  unread: () => http.get<any, any>('/notifies/unread-count'),
  read: (id: number) => http.post<any, any>(`/notifies/${id}/read`),
  readAll: () => http.post<any, any>('/notifies/read-all')
}

export interface ServiceContactMember { position: string; name: string; userId?: number | null }
export interface ServiceContact {
  member: ServiceContactMember
  status: 'ON_LEAVE' | 'TODAY' | 'NONE' | 'UNKNOWN' | 'UNLINKED'
  leaveStart?: string
  leaveEnd?: string
}
export type ServiceContactPerson = PersonOption
export interface ServiceContactRoster { contacts: ServiceContact[]; hotline: string; hotlineNote: string }
export interface DutySettings {
  members: ServiceContactMember[]
  hotline: string
  hotlineNote: string
}

export const serviceContactApi = {
  roster: () => http.get<any, ServiceContactRoster>('/meta/service-contacts'),
  display: () => http.get<any, ServiceContactRoster>('/meta/service-contacts/display'),
  people: (keyword: string) => http.get<any, ServiceContactPerson[]>('/meta/service-contacts/people', { params: { keyword } })
}

export const metaApi = {
  runtime: () => http.get<any, { maxAttachments: number; maxUploadMb: number; kbRecommendEnabled: boolean; requireSolutionCode: boolean; duty?: DutySettings }>('/meta/runtime'),
  categories: () => http.get<any, any>('/meta/categories'),
  categoriesByType: () => http.get<any, import('../config/ticket-types').TicketCategoriesByType>('/meta/categories-by-type'),
  solutionCodes: () => http.get<any, any>('/meta/solution-codes'),
  engineers: () => http.get<any, PersonOption[]>('/meta/engineers')
}

export const attachmentApi = {
  upload: (file: File, bizType: string, bizNo: string) => {
    const fd = new FormData()
    fd.append('file', file)
    fd.append('bizType', bizType)
    fd.append('bizNo', bizNo)
    return http.post<any, any>('/attachments', fd)
  },
  list: (bizType: string, bizNo: string) =>
    http.get<any, any>('/attachments', { params: { bizType, bizNo } }),
  blob: (storedName: string) => http.get<any, Blob>(`/attachments/raw/${encodeURIComponent(storedName)}`, { responseType: 'blob' }),
  remove: (id: number) => http.delete<any, any>(`/attachments/${id}`)
}

export interface JobSettings {
  revision: number
  slaScanEnabled: boolean
  slaCron: string
  slaReminderHours: number
  confirmationReminderHours: number
  contactSyncEnabled: boolean
  contactCron: string
}
interface JobRun {
  jobKey?: string
  job_key?: string
  finishedAt?: string
  finished_at?: string
  status: string
  message: string
}
export interface JobStatus {
  settings: JobSettings
  runs: JobRun[]
  dingTalkEnabled: boolean
  timezone: string
  slaNextRun?: string
  contactNextRun?: string
  borrowDueNextRun?: string
}

export const adminApi = {
  settings: () => http.get<any, any>('/admin/manage/settings'),
  saveSettings: (data: any) => http.put<any, any>('/admin/manage/settings', data),
  users: (params: Record<string, any> = {}) => http.get<any, any>('/admin/manage/users', { params }),
  updateUser: (id: number, data: any) => http.put<any, any>(`/admin/manage/users/${id}`, data),
  departments: () => http.get<any, any[]>('/admin/manage/departments'),
  saveDepartment: (id: number | null, data: any) => id ? http.put<any, any>(`/admin/manage/departments/${id}`, data) : http.post<any, any>('/admin/manage/departments', data),
  deleteDepartment: (id: number) => http.delete<any, any>(`/admin/manage/departments/${id}`),
  saveSolution: (id: number | null, data: any) => id ? http.put<any, any>(`/admin/manage/solution-codes/${id}`, data) : http.post<any, any>('/admin/manage/solution-codes', data),
  deleteSolution: (id: number) => http.delete<any, any>(`/admin/manage/solution-codes/${id}`),
  audit: (params: Record<string, any> = {}) => http.get<any, any>('/admin/manage/audit', { params }),
  storage: () => http.get<any, any>('/admin/manage/storage'),
  jobs: () => http.get<any, JobStatus>('/admin/manage/jobs'),
  saveJobs: (data: JobSettings) => http.put<any, JobStatus>('/admin/manage/jobs', data),
  permissions: () => http.get<any, any>('/admin/manage/permissions'),
  savePermissions: (data: any) => http.put<any, any>('/admin/manage/permissions', data),
  dictionary: () => http.get<any, any>('/admin/manage/dictionary'),
  saveCategories: (data: any) => http.put<any, any>('/admin/manage/categories', data),
  syncDingTalk: (mode: 'dry' | 'demo' | 'live') =>
    http.post<any, any>(`/admin/sync/dingtalk?mode=${mode}`)
}
