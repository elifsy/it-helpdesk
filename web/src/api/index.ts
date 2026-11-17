import http from './http'
import { assetQrContent } from '../utils/asset-links'

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

export const authApi = {
  config: () => http.get<any, { demoEnabled: boolean; corpId: string }>('/auth/config'),
  me: () => http.get<any, LoginResult>('/auth/me'),
  login: (payload: { authCode?: string; demoUser?: string }) =>
    http.post<any, LoginResult>('/auth/login', payload)
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
  confirm: (no: string, data: any) => http.post<any, any>(`/tickets/${no}/confirm`, data),
  rate: (no: string, data: any) => http.post<any, any>(`/tickets/${no}/rate`, data),
  reject: (no: string, reason: string) => http.post<any, any>(`/tickets/${no}/reject`, { reason }),
  cancel: (no: string) => http.post<any, any>(`/tickets/${no}/cancel`)
}

export const assetApi = {
  update: (no: string, data: any) => http.put<any, any>(`/assets/${encodeURIComponent(no)}`, data),
  setCategory: (no: string, category: string) => http.put<any, any>(`/assets/${encodeURIComponent(no)}/category`, { category }),
  list: (params: Record<string, any> = {}) => http.get<any, any>('/assets', { params }),
  page: (params: Record<string, any> = {}) => http.get<any, { list: any[]; total: number; page: number; size: number }>('/assets', { params: { page: 1, size: 15, ...params } }),
  detail: (no: string) => http.get<any, any>(`/assets/${encodeURIComponent(no)}`),
  qrcode: async (no: string) => {
    const result = await http.get<any, any>(`/assets/${encodeURIComponent(no)}/qrcode`)
    return { ...result, content: assetQrContent(no, result.content, window.location.href) }
  },
  create: (data: any) => http.post<any, any>('/assets', data)
}

export interface AssetCategory { id: number; name: string; sort: number; assetCount: number }
export const assetCategoryApi = {
  list: () => http.get<any, AssetCategory[]>('/asset-categories'),
  create: (data: { name: string; sort: number }) => http.post<any, AssetCategory>('/asset-categories', data),
  update: (id: number, data: { name: string; sort: number }) => http.put<any, AssetCategory>(`/asset-categories/${id}`, data),
  remove: (id: number) => http.delete<any, any>(`/asset-categories/${id}`)
}

export const assetRequestApi = {
  list: (params: Record<string, any> = {}) => http.get<any, any>('/asset-requests', { params }),
  create: (data: any) => http.post<any, any>('/asset-requests', data),
  approve: (id: number, data: any = {}) => http.post<any, any>(`/asset-requests/${id}/approve`, data),
  reject: (id: number, reason: string) => http.post<any, any>(`/asset-requests/${id}/reject`, { reason }),
  cancel: (id: number) => http.post<any, any>(`/asset-requests/${id}/cancel`)
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

export const notifyApi = {
  list: () => http.get<any, any>('/notifies'),
  unread: () => http.get<any, any>('/notifies/unread-count'),
  read: (id: number) => http.post<any, any>(`/notifies/${id}/read`),
  readAll: () => http.post<any, any>('/notifies/read-all')
}

export const metaApi = {
  runtime: () => http.get<any, { maxAttachments: number; maxUploadMb: number; kbRecommendEnabled: boolean; requireSolutionCode: boolean }>('/meta/runtime'),
  categories: () => http.get<any, any>('/meta/categories'),
  categoriesByType: () => http.get<any, import('../config/ticket-types').TicketCategoriesByType>('/meta/categories-by-type'),
  solutionCodes: () => http.get<any, any>('/meta/solution-codes'),
  engineers: () => http.get<any, any>('/meta/engineers')
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
  jobs: () => http.get<any, any>('/admin/manage/jobs'),
  permissions: () => http.get<any, any>('/admin/manage/permissions'),
  savePermissions: (data: any) => http.put<any, any>('/admin/manage/permissions', data),
  dictionary: () => http.get<any, any>('/admin/manage/dictionary'),
  saveCategories: (data: any) => http.put<any, any>('/admin/manage/categories', data),
  syncDingTalk: (mode: 'dry' | 'demo' | 'live') =>
    http.post<any, any>(`/admin/sync/dingtalk?mode=${mode}`)
}
