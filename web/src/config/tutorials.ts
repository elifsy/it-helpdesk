import type { InjectionKey } from 'vue'
import { roleFromRoles, routeAllowed, type WorkRole } from './menus.ts'

interface TutorialStep {
  title: string
  description: string
  target: string
  permission?: string
}
type Group = '我的服务' | '服务台' | '资产与知识' | '组织与权限' | '系统管理'
export interface TutorialCourse {
  key: string
  label: string
  path: string
  group: Group
  steps: TutorialStep[]
  permission?: string
}

export const openTutorialPicker = Symbol('openTutorialPicker') as InjectionKey<() => void>

const step = (title: string, description: string, target: string, permission?: string): TutorialStep =>
  ({ title, description, target, permission })
const course = (key: string, label: string, path: string, group: Group, steps: TutorialStep[], permission?: string): TutorialCourse =>
  ({ key, label, path, group, steps, permission })

const personal = [
  course('home', '首页', '/home', '我的服务', [
    step('查看当前工单', '首页优先显示正在处理或待确认的工单。点开可查看工程师、排队情况与处理记录。', '.hero', 'ticket.view.own'),
    step('找到常用服务', '从报修、工单和资产入口办理个人事项。电脑端看左侧导航，手机端可用顶部菜单。', '.home .entries'),
    step('联系 IT', '在首页的联系人卡片查看服务内线和当天的请假情况。', '.home-rail .service-contacts'),
  ]),
  course('repair', '我要报修', '/new', '我的服务', [
    step('选择问题类型', '设备故障选「设备坏了」，开通权限或申领服务选「要开通/申领」，操作不熟悉选「不会用」。', '.new-ticket > .card:first-child'),
    step('填好故障位置', '选择对应分类，填写楼栋、楼层、办公室或工位，并说明影响范围。', '#fault-location'),
    step('提交前看建议', '如有相关知识条目，可以先按建议自查。问题已解决可结束填写，仍有问题就继续提交。', '.new-ticket .btn.pri'),
  ]),
  course('my-tickets', '我的工单', '/tickets', '我的服务', [
    step('按状态找工单', '切换「处理中」「待确认」或「全部」。收到处理完成通知后，可以先从「待确认」找工单。', '.tabs2'),
    step('打开详情核对进度', '点开工单可查看记录和负责工程师。待确认时，请根据实际处理结果完成确认。', '.list'),
  ]),
  course('my-assets', '资产服务', '/assets', '我的服务', [
    step('按业务选择设备', '可先按分类筛选，再选择领用、借用、更换或归还的设备并填写事由。', '.category-toolbar', 'request.create'),
    step('跟进自己的申请', '展开「我的申请」看审批和交接进度。待审批的申请可撤销，系统会弹窗确认。', '.service-section:first-of-type > summary', 'request.view.own'),
    step('没有合适设备时申请购入', '如需采购新设备，从「购入申请」提交。提交后的采购记录也能在本页展开查看。', '.purchase-card', 'request.create'),
  ]),
  course('knowledge', '知识库', '/kb', '我的服务', [
    step('搜索已有处理办法', '输入 WiFi、打印机或 VPN 等关键词，可从联想结果选择相关条目，也可以按分类浏览。', '.kb-search'),
    step('打开条目自查', '点开后核对适用场景，再按正文尝试处理。报修表单也可能推荐匹配的条目。', '.cats'),
  ]),
  course('my-supplement', '我的待补交事项', '/missing-reports', '我的服务', [
    step('核对待补交事项', 'IT 登记的报修、资产领用、借用、更换和归还都在这里。打开可核对处理说明、设备和佐证。', '.report-heading'),
    step('填写并确认提交', '报修需填写故障位置和分类。资产补交需核对设备和申请事由，提交后系统同步更新设备台账。', '.report-row'),
  ]),
]

const workbench = (admin: boolean) => course('workbench', '工作台', '/admin/dashboard', '服务台', admin ? [
  step('查看团队当天积压', '先看待受理、处理中和超时预警，点击统计卡片进入对应列表。', '.kpis'),
  step('兼顾资产审批和工单', '工作台汇总待办。右侧区域或「辅助信息」可查看团队工作量和 SLA 达成情况。', '.grid2'),
] : [
  step('先处理待受理工单', '从待受理或超时预警进入工单中心，优先查看影响范围大或临近时限的工单。', '.kpis'),
  step('受理后开始处理', '从列表进入详情，核对问题描述、位置和附件。点击「受理」后，工单归到自己名下。', '.grid2 > .panel:first-child', 'ticket.accept'),
])

const ticketCenter = (readOnly: boolean) => course('tickets', '工单中心', '/admin/tickets', '服务台', readOnly ? [
  step('查找团队工单', '可用编号、标题或描述搜索，再按状态筛选。', '.page-h'),
  step('查看处理轨迹', '点开工单可看处理节点、结论和负责人。自己提交的事项可在「我的工单」查看。', '.page-h + *'),
] : [
  step('筛选待处理工单', '按编号、标题、描述或状态筛选。优先看待受理和接近 SLA 时限的工单。', '.page-h'),
  step('记录排查过程', '添加处理记录让需求人看到进度。处理完成后填写结论，工单进入待确认。', '.page-h + *', 'ticket.handle'),
  step('按需交接工单', '存在交接需要时，从详情选择工程师。可用姓名、拼音或首字母查找人员。', '.page-h', 'ticket.dispatch'),
])

const ledger = (readOnly: boolean) => course('ledger', '资产台账', '/admin/assets', '资产与知识', [
  step('查找设备', '可用资产编号、ERP 编号、名称或序列号搜索，再按分类或状态筛选。', '.asset-toolbar'),
  step('查看设备详情', '点开设备后可核对使用人、存放位置、状态与履历，并查看设备二维码。', '.page-h'),
  ...(!readOnly ? [step('维护资产资料', '新增时系统自动生成资产编号。批量导入请先下载模板，核对分类、状态和使用人。', '.asset-toolbar', 'asset.create')] : []),
])

const approvals = course('approvals', '资产申请', '/admin/approvals', '资产与知识', [
  step('筛选资产申请', '从「待审批」看新申请，或切到「全部」查历史。打开后核对设备、申请人和事由。', '.filters'),
  step('审批并确认交接', '按申请类型核对设备后再审批。需交接的设备在实际领取或归还后，按提示确认。', '.page-h', 'request.approve'),
])
const supplement = course('supplement', '补报与补交登记', '/admin/missing-reports', '服务台', [
  step('登记线下已办理事项', '选择需求人与事项类型。资产事项要选关联设备，借用要填归还日期，更换要选新设备。填写办理情况并上传佐证。', '.report-heading'),
  step('跟进补交或撤销误登记', '需求人会在首页看到提醒。点开记录可查看提交结果，误登记可在详情撤销。', '.report-heading'),
])
const statistics = course('statistics', '统计看板', '/admin/stats', '资产与知识', [
  step('查看服务指标', '从顶部看工单量、超时和 SLA 达成率，再结合趋势判断积压。', '.kpis'),
  step('打开数据明细', '点击问题设备或资产状态数字可查看对应数据，结合报修频次评估设备状况。', '.page-h'),
])
const knowledgeManage = course('knowledge-manage', '知识草稿与发布', '/admin/kb', '资产与知识', [
  step('整理关单经验', '从处理结论沉淀草稿。发布前补齐可复用的排查步骤和适用范围。', '.page-h'),
  step('核对后发布', '检查标题、摘要和正文。已发布条目可在员工知识库检索。', '.page-h + *'),
], 'kb.manage')

const management = [
  course('purchasing', '采购管理', '/admin/purchase', '资产与知识', [
    step('处理购入申请', '查看设备规格、数量和事由，再按实际采购流程处理。', '.page-h'),
    step('跟进到货和入库', '核对采购单号与到货进度，设备入库后可在资产台账查看。', '.page-h + *'),
  ], 'request.approve'),
  course('users', '用户与角色', '/admin/config?tab=users', '组织与权限', [
    step('查找人员', '使用姓名、拼音、首字母、部门或工号搜索，也可按账号状态筛选。', '.toolbar'),
    step('调整角色或办理交接', '打开人员编辑页核对角色。停用账号或移除处理权限前，先为其未结工单选择交接人。', '.table-wrap'),
  ]),
  course('departments', '部门与通讯录', '/admin/config?tab=depts', '组织与权限', [
    step('维护组织信息', '查看部门层级和来源，在有权限时维护本地部门。', '.section-head'),
    step('同步前先核对', '可运行样例试跑。真实同步会更新人员与组织信息，保留本地角色。', '.toolbar', 'contact.sync'),
  ]),
  course('permissions', '角色权限配置', '/admin/config?tab=matrix', '组织与权限', [
    step('选择要调整的角色', '选择普通用户、IT、管理员或可查看用户，核对该角色可以使用的模块。', '.panel'),
    step('保存前检查影响', '重点核对个人服务、工单处理、资产审批和管理权限。保存后对后续访问生效。', '.panel'),
  ]),
  course('notifications', '通知接收人', '/admin/incoming-notifications', '系统管理', [
    step('按业务配置通知', '报修可按问题类型和分类设置。资产领用、借用、更换和归还可分别设置。', '.rule-editor'),
    step('选人后保存', '输入姓名、拼音或首字母选择已绑定钉钉的 IT 或管理员，核对后保存。', '.person-search'),
  ]),
  course('contacts', 'IT 服务联系人', '/admin/config?tab=duty', '系统管理', [
    step('关联实际联系人', '在人员输入框选择已关联钉钉的成员，可同步当天请假情况。', '.duty-settings'),
    step('保存热线并刷新状态', '人员和服务热线会展示在首页。点击「刷新状态」可核对当天请假情况。', '.section-head'),
  ]),
  course('dingtalk', '钉钉审批', '/admin/ding-approval', '系统管理', [
    step('核对审批模板', '先确认需要接入钉钉的资产业务，再核对模板和字段映射。', 'h2'),
    step('查看同步结果', '到资产申请页查看钉钉审批进度。在有权限时可手动同步。', '.panel'),
  ]),
  course('settings', '运行配置', '/admin/config', '系统管理', [
    step('配置资产交接确认', '按资产业务选择需要线下交接的设备分类，核对适用流程后保存。', '.page-head'),
  ]),
  course('dictionary', '字典与分类', '/admin/config?tab=dict', '系统管理', [
    step('按问题类型配置分类', '分别维护故障报修、服务申请和咨询的分类，核对各子分类归属后保存。', '.toolbar'),
  ]),
]

const roleCourses: Record<WorkRole, TutorialCourse[]> = {
  emp: personal,
  it: [workbench(false), ticketCenter(false), supplement, ledger(false), approvals, statistics, knowledgeManage, ...personal],
  admin: [workbench(true), ticketCenter(false), supplement, ledger(false), approvals, statistics, knowledgeManage, ...management, ...personal],
  viewer: [ticketCenter(true), ledger(true), statistics, ...personal],
}

export function tutorialsFor(roles: string[], permissions: string[]): TutorialCourse[] {
  if (roles.includes('SUPER_ADMIN')) return []
  return roleCourses[roleFromRoles(roles)]
    .filter(item => routeAllowed(roles, item.path, permissions) && (!item.permission || permissions.includes(item.permission)))
    .map(item => ({ ...item, steps: item.steps.filter(s => !s.permission || permissions.includes(s.permission)) }))
    .filter(item => item.steps.length > 0)
}
