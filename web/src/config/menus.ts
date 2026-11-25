export type WorkRole = 'emp' | 'it' | 'admin' | 'viewer'
export const ROLE_NAMES: Record<string, string> = { USER: '普通用户', IT: 'IT', ADMIN: '管理员', VIEWER: '可查看用户', SUPER_ADMIN: '超级管理员' }
export const CONFIG_PERMISSIONS: Record<string, string[]> = {
  '': ['settings.manage'], users: ['user.manage'], depts: ['dept.manage', 'contact.sync'], matrix: ['role.manage'],
  duty: ['settings.manage'], sla: ['settings.manage'], dict: ['dict.manage'], audit: ['audit.view'], storage: ['settings.manage']
}
export interface MenuItem { path: string; icon: string; label: string; badge?: 'pending' | 'approvals'; permission?: string }
export interface MenuGroup { title: string; items: MenuItem[] }
export interface RoleMeta { label: string; home: string; initial: string; avatar: string; color: string }
export const ROLE_META: Record<WorkRole, RoleMeta> = {
  emp: { label: '普通用户', home: '/home', initial: '张', avatar: 'linear-gradient(145deg,#d9e6ff,#a9c7ff)', color: '#22508f' },
  it: { label: 'IT', home: '/admin/dashboard', initial: '李', avatar: 'linear-gradient(145deg,#d8f0e4,#a9dcc0)', color: '#1a6b41' },
  admin: { label: '管理员', home: '/admin/dashboard', initial: '陈', avatar: 'linear-gradient(145deg,#e7dcff,#c9b3f5)', color: '#5b3a91' },
  viewer: { label: '可查看用户', home: '/admin/tickets', initial: '赵', avatar: 'linear-gradient(145deg,#e5e7eb,#cbd5e1)', color: '#475569' }
}
export function roleFromRoles(roles: string[] = []): WorkRole {
  if (roles.includes('ADMIN') || roles.includes('SUPER_ADMIN')) return 'admin'
  if (roles.includes('IT')) return 'it'
  if (roles.includes('VIEWER')) return 'viewer'
  return 'emp'
}
export function routeAllowed(roles: string[], fullPath: string, permissions: string[] = []): boolean {
  const [path, query = ''] = fullPath.split('?')
  const has = (...keys: string[]) => keys.some(key => permissions.includes(key))
  if (path === '/home' || path === '/') return true
  if (path === '/missing-reports') return true
  if (path === '/admin/missing-reports') return roles.includes('IT') || roleFromRoles(roles) === 'admin'
  if (path.startsWith('/admin/config')) {
    const tab = new URLSearchParams(query).get('tab') || ''
    if (tab === 'jobs') return roles.includes('SUPER_ADMIN')
    return !!CONFIG_PERMISSIONS[tab] && has(...CONFIG_PERMISSIONS[tab])
  }
  if (path === '/new') return has('ticket.create')
  if (path === '/tickets' || path.startsWith('/ticket/')) return has('ticket.view.own', 'ticket.view.all')
  if (path === '/assets') return has('asset.view')
  if (path === '/kb' || path === '/admin/kb') return has('kb.view')
  if (path.startsWith('/admin/ticket')) return has('ticket.view.all')
  if (path === '/admin/dashboard' || path === '/admin/stats') return has('stats.view')
  if (path === '/admin/approvals') return has('request.view.all')
  if (path === '/admin/ding-approval' || path === '/admin/incoming-notifications') return has('settings.manage')
  if (path === '/admin/purchase') return has('request.approve')
  if (path === '/admin/assets' || path.startsWith('/admin/assets/')) return has('asset.view')
  return false
}
export function homeForRoles(roles: string[] = [], permissions: string[] = []): string {
  const candidates = roleFromRoles(roles) === 'admin'
    ? ['/admin/dashboard', '/admin/tickets', '/admin/config', '/admin/config?tab=matrix', '/admin/assets', '/kb']
    : roles.includes('VIEWER')
      ? ['/admin/tickets', '/admin/assets', '/admin/stats', '/kb']
    : roles.includes('IT')
      ? ['/admin/dashboard', '/admin/tickets', '/admin/assets', '/admin/approvals', '/kb']
      : ['/home']
  return candidates.find(path => routeAllowed(roles, path, permissions)) || '/home'
}
const business: MenuGroup[] = [
  { title: '服务台', items: [
    { path: '/admin/dashboard', icon: '▧', label: '工作台' },
    { path: '/admin/tickets', icon: '▤', label: '工单中心' },
    { path: '/admin/missing-reports', icon: '▤', label: '紧急事项补报' }
  ] },
  { title: '资产与知识', items: [
    { path: '/admin/assets', icon: '▣', label: '资产台账' },
    { path: '/admin/approvals', icon: '⇄', label: '资产申请' },
    { path: '/admin/purchase', icon: '＋', label: '采购管理', permission: 'request.approve' },
    { path: '/admin/stats', icon: '▧', label: '统计看板' }
  ] }
]
const employeeBusiness = business.filter(group => group.title === '服务台')
  .map(group => ({ ...group, items: group.items.filter(item => item.path !== '/new') }))
const management: MenuGroup[] = [
  { title: '知识管理', items: [
    { path: '/admin/kb', icon: '▤', label: '知识草稿与发布', permission: 'kb.manage' }
  ] },
  { title: '组织与权限', items: [
    { path: '/admin/config?tab=users', icon: '◎', label: '用户与角色' },
    { path: '/admin/config?tab=depts', icon: '⌘', label: '部门与通讯录' },
    { path: '/admin/config?tab=matrix', icon: '▦', label: '角色权限配置' }
  ] },
  { title: '系统管理', items: [
    { path: '/admin/config', icon: '⚙', label: '运行配置' },
    { path: '/admin/ding-approval', icon: '⇢', label: '钉钉审批', permission: 'settings.manage' },
    { path: '/admin/incoming-notifications', icon: '♧', label: '通知接收人', permission: 'settings.manage' },
    { path: '/admin/config?tab=duty', icon: '◷', label: 'IT 服务联系人' },
    { path: '/admin/config?tab=sla', icon: '◷', label: 'SLA 与优先级' },
    { path: '/admin/config?tab=dict', icon: '▤', label: '字典与分类' },
    { path: '/admin/config?tab=audit', icon: '≡', label: '审计日志' },
    { path: '/admin/config?tab=storage', icon: '▣', label: '附件与存储' },
    { path: '/admin/config?tab=jobs', icon: '◔', label: '定时任务' }
  ] }
]
const personal: MenuGroup[] = [
    { title: '我的服务', items: [
      { path: '/home', icon: '▦', label: '首页' }, { path: '/new', icon: '＋', label: '我要报修' },
      { path: '/tickets', icon: '≡', label: '我的工单' }, { path: '/assets', icon: '▣', label: '资产服务' },
      { path: '/kb', icon: '▤', label: '知识库' }
    ] }
]
const serviceDesk: MenuGroup[] = business.filter(group => group.title === '服务台')
const assetsKb: MenuGroup[] = business.filter(group => group.title !== '服务台')
export const MENUS: Record<WorkRole, MenuGroup[]> = {
  emp: [...personal, ...employeeBusiness, ...management],
  // IT 与管理员：服务台（工作台/工单中心）置顶，先看当日任务再处理个人事务
  it: [...serviceDesk, ...personal, ...assetsKb, ...management],
  admin: [...serviceDesk, ...personal, ...assetsKb, ...management],
  viewer: [...personal, ...business, ...management]
}
const personalTabs: MenuItem[] = [
  { path: '/home', icon: '▦', label: '首页' }, { path: '/new', icon: '＋', label: '报修' },
  { path: '/tickets', icon: '≡', label: '我的工单' }, { path: '/assets', icon: '▣', label: '资产' }
]
export const TABS: Record<WorkRole, MenuItem[]> = {
  emp: [
    { path: '/home', icon: '▦', label: '首页' }, { path: '/new', icon: '＋', label: '报修' },
    { path: '/tickets', icon: '≡', label: '工单' }, { path: '/assets', icon: '▣', label: '资产' },
    { path: '/kb', icon: '▤', label: '知识' }
  ],
  it: [
    ...personalTabs, { path: '/admin/dashboard', icon: '▧', label: '工作台' }
  ],
  admin: [
    ...personalTabs, { path: '/admin/config?tab=matrix', icon: '⚙', label: '管理' }
  ],
  viewer: [
    ...personalTabs, { path: '/admin/tickets', icon: '▤', label: '工单中心' }
  ]
}
export function titleFor(role: WorkRole, fullPath: string): string {
  if (fullPath === '/missing-reports') return '我的待补报事项'
  for (const group of MENUS[role]) for (const item of group.items) if (item.path === fullPath) return item.label
  if (fullPath.startsWith('/admin/tickets?')) return '工单中心'
  if (fullPath.startsWith('/admin/assets?')) return '资产台账'
  if (/^\/(admin\/)?ticket\//.test(fullPath)) return '工单详情'
  if (fullPath.startsWith('/admin/assets/')) return '设备详情'
  return '服务平台'
}
/** 本地演示账号由演示种子数据提供，权限以服务端返回为准。 */
export const DEMO_USERS = [
  { name: '谈帥宏', tag: ROLE_NAMES.USER },
  { name: '刘宇泽', tag: ROLE_NAMES.USER },
  { name: '林凤新', tag: ROLE_NAMES.IT },
  { name: '邵昕炜', tag: ROLE_NAMES.SUPER_ADMIN }
]
