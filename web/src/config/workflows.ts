import { routeAllowed } from './menus.ts'

export type AssetWorkflow = 'RECEIVE' | 'BORROW' | 'RETURN' | 'REPLACE'
export type WorkflowKey = 'repair' | AssetWorkflow

export interface WorkflowStep {
  id: string
  title: string
  description: string
  target: string
  mode: 'click' | 'continue' | 'finish'
  trigger?: string
  minLength?: number
  skipTo?: string
}

export interface WorkflowCourse {
  key: WorkflowKey
  label: string
  summary: string
  path: string
  permission: string
  outcome: string
  steps: WorkflowStep[]
}

export const practiceCategory = '练习设备'
export const practiceCategories = [{ id: -1, name: practiceCategory }]
export function practiceAssets(name: string, id?: number) {
  return [
    { no: 'PRACTICE-01', name: '示例办公笔记本', status: '闲置' },
    { no: 'PRACTICE-02', name: '示例备用笔记本', status: '闲置' },
    { no: 'PRACTICE-03', name: '示例借用笔记本', status: '借出中', borrower: name, borrowerId: id },
    { no: 'PRACTICE-04', name: '示例在用笔记本', status: '在用', userName: name, userId: id }
  ].map(asset => ({ ...asset, category: practiceCategory, location: '示例办公区' }))
}

const target = (id: string) => '[data-workflow-step="' + id + '"]'
const click = (id: string, title: string, description: string, selector = target(id)): WorkflowStep =>
  ({ id, title, description, target: selector, mode: 'click' })
const fill = (id: string, title: string, description: string, minLength?: number): WorkflowStep =>
  ({ id, title, description, target: target(id), mode: 'continue', minLength })
const finish = (id: string, title: string, description: string): WorkflowStep =>
  ({ ...click(id, title, description), mode: 'finish' })

const repair: WorkflowCourse = {
  key: 'repair', label: '报修', path: '/home', permission: 'ticket.create',
  summary: '报修入口 → 分类和位置 → 描述故障 → 模拟提交',
  outcome: '正式报修后，工单会进入待受理，可在「我的工单」查看处理进度和工程师信息。',
  steps: [
    click('repair-entry', '打开报修入口', '点击首页的「报修」，进入问题登记表单。'),
    click('repair-type', '选择问题类型', '这次练习设备故障，点击「设备坏了」。', '.new-ticket [data-ticket-type="INCIDENT"]'),
    fill('repair-major', '选择问题大类', '选择故障所属大类，例如「网络」。选定后继续。', 1),
    fill('repair-sub', '选择问题子分类', '按故障现象选择子分类，例如「无线连不上」。', 1),
    { ...click('repair-impact', '选择影响范围', '按受影响的人数选择，例如「只影响我」。'), trigger: '.opt' },
    { ...click('repair-work', '说明工作能否继续', '选择工作受阻程度。系统会结合上一项确定优先级。'), trigger: '.opt' },
    fill('repair-location', '填写故障位置', '写明楼栋、楼层、房间或工位，例如「A 座 3 楼财务办公室」。', 1),
    { ...click('repair-asset', '关联发生故障的设备', '知道是哪台设备时可以关联，工程师能在详情核对设备的编号和履历。'), skipTo: 'repair-description' },
    click('repair-asset-choice', '选中示例设备', '核对设备卡片上的名称和编号，点击选中。', '.asset-pick-dialog [data-asset-no="PRACTICE-01"]'),
    click('repair-asset-confirm', '确认关联', '点击「确定关联」回到报修表单。'),
    fill('repair-description', '描述故障现象', '说明发生时间、错误提示及已尝试的方法。例如「今天上午办公室 WiFi 连不上，重连后仍失败」。', 4),
    { ...click('repair-kb', '提交前先自查', '打开知识建议，查看可能适用的解决方法。也可以跳过这一步。'), skipTo: 'repair-attachment' },
    click('repair-kb-close', '自查后返回报修', '实际报修时可以尝试建议步骤。确认问题仍在，点击「还是不行，继续报修」。'),
    fill('repair-attachment', '按需添加附件', '可选截图或 PDF，帮助工程师看清故障。没有附件也可以继续练习。'),
    finish('repair-submit', '模拟提交报修', '确认分类、位置和描述后，点击「提交报修」完成练习。练习不会生成工单或上传附件。')
  ]
}

const assets: { key: AssetWorkflow; label: string; summary: string; selection: string; outcome: string }[] = [
  { key:'RECEIVE', label:'资产领用', summary:'选择可领设备 → 填用途 → 模拟提交',
    selection:'选择一台闲置设备。领用适用于需要长期使用的设备。',
    outcome:'正式提交后可在「我的申请」查看审批结果。按提示完成领取或交接后，设备归到本人名下。' },
  { key:'BORROW', label:'资产借用', summary:'选可借设备 → 填用途和归还日期 → 模拟提交',
    selection:'选择一台可借设备。借用适用于需要限期使用的设备。',
    outcome:'正式提交后可在「我的申请」查看审批和领取进度，按约定日期办理归还。' },
  { key:'RETURN', label:'资产归还', summary:'选本人设备 → 填归还说明 → 模拟提交',
    selection:'选择这次要归还的设备。练习示例是一台由你借用的笔记本。',
    outcome:'正式提交后可在「我的申请」查看审批结果，按提示实际交还设备并完成交接。' },
  { key:'REPLACE', label:'资产更换', summary:'选原设备 → 选替换机 → 模拟提交',
    selection:'选中自己名下需要更换的在用设备，再进入填写申请。',
    outcome:'正式提交后可在「我的申请」查看审批结果。更换通过后，按提示完成新旧设备交接。' }
]

const selectCategory = '.asset-selection-dialog [data-category="' + practiceCategory + '"]'
const device = (no: string) => '.asset-selection-dialog [data-asset-no="PRACTICE-' + no + '"]'
function assetCourse(asset: typeof assets[number]): WorkflowCourse {
  const {key,label,summary,selection,outcome} = asset
  const sample = key === 'RETURN' ? '03' : key === 'REPLACE' ? '04' : '01'
  return {
    key,label,summary,outcome,path:'/assets',permission:'request.create',
    steps:[
      click('asset-entry', '打开' + label, '点击资产服务中的「' + label.slice(2) + '」，打开设备选择弹窗。',
        '[data-workflow-asset="' + key + '"]'),
      click('asset-category', '选择设备分类', '点击左侧的「练习设备」。切换分类后，右侧只显示该分类下符合条件的设备。', selectCategory),
      click('asset-device', key === 'REPLACE' ? '选中原设备' : '选中要办理的设备', selection, device(sample)),
      click('asset-next', '进入申请表单', '核对已选择的设备，点击「下一步」填写申请。'),
      fill('asset-reason', '填写申请事由',
        key === 'RETURN' ? '说明归还原因，例如「临时培训已结束，归还借用设备」。'
          : key === 'REPLACE' ? '说明更换原因，例如「原设备频繁关机，影响正常办公」。'
            : '说明用途，例如「部门培训需要一台办公电脑」。', 4),
      ...(key === 'BORROW' ? [fill('asset-date','核对预计归还日期','选择预计归还日期。可以调整页面预填的日期，再继续。',1)] : []),
      ...(key === 'REPLACE' ? [
        click('asset-spare-open','打开替换机选择','点击「选择一台闲置设备作为替换机」。'),
        click('asset-spare-category','选择替换机分类','选择「练习设备」分类，右侧显示可用的替换机。',selectCategory),
        click('asset-spare-device','选中替换机','点击示例备用笔记本。正式申请时应核对型号与可用状态。',device('02')),
        click('asset-spare-confirm','确认替换机','点击「确定」回到申请表单，核对原设备和替换机。')
      ] : []),
      finish('asset-submit','模拟提交申请','点击「提交申请」完成练习。练习不会生成申请单、送审或改变设备状态。')
    ]
  }
}

export const workflowCourses = [repair,...assets.map(assetCourse)]
export function workflowsFor(roles: string[], permissions: string[]) {
  if (roles.includes('SUPER_ADMIN')) return []
  return workflowCourses.filter(course => permissions.includes(course.permission)
    && routeAllowed(roles, course.key === 'repair' ? '/new' : course.path, permissions))
}
