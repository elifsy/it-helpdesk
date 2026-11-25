export const controlSelector = [
  'button', 'a', 'summary', 'select', 'textarea', 'input:not([type=hidden])', '[role=button]', '[role=tab]', '[role=option]', '[data-tutorial-control]',
  '.entries .entry', '.qcard', '.kb-entry', '.home .wo', '.work-ticket', '.main .wo', '.asset-row',
  '.tabs2 > div', '.cats > span', '.filters > span:not(.sp):not(.muted)', '.tabs > span',
  '.new-ticket .opt', '.dlg .opt', '.dlg .stars > span', '.dlg-h .x', '.new-ticket .del', '.grid2 .li',
].join(',')

export interface TutorialControl {
  node: HTMLElement
  label: string
  description: string
  kind: 'field' | 'click' | 'write'
}

const fieldDetails: [RegExp, string][] = [
  [/故障位置/, '填写楼栋、楼层、办公室或具体工位，方便工程师到现场定位。'],
  [/问题大类|问题分类/, '先选问题大类，再根据实际现象选子分类。选「其他」时可补充具体分类。'],
  [/问题子分类/, '从当前大类下选择最符合实际的问题。切换大类时，子分类会重新匹配。'],
  [/影响范围|还能不能干活/, '按故障影响的人数和工作中断程度选择，系统据此定级。'],
  [/故障描述|问题描述|描述问题|故障现象/, '简述发生时间、现象、错误提示和已尝试的方法。练习时可输入示例文字。'],
  [/执行人|选择工程师|未结工单交接给|需求人|搜索 IT|人员姓名/, '可输入姓名、全拼、首字母、部门或工号。点选下拉结果才算关联人员；重名时请核对部门与工号。'],
  [/申请事由|申请原因|申领原因|借用原因/, '说明业务用途和需要设备的原因，便于审批时核对。'],
  [/归还日期|归还时间|约定归还/, '借用时填写预计归还日期；正式申请应按实际使用周期填写。'],
  [/佐证附件/, '选择聊天截图、通话记录截图或录音作为佐证。练习不会上传文件。'],
  [/处理完成时间/, '填写紧急事项实际处理完的日期与时间。'],
  [/沟通方式/, '按最初收到需求的方式选择消息或电话。'],
  [/处理说明|处理结论|解决说明|处理记录|排查过程/, '记录处理过程、采取的措施以及验证结果，便于需求人确认和后续追溯。'],
  [/ERP\s*采购单号/, '填写实际 ERP 采购单号以关联采购流程，每台设备可分别登记。'],
  [/ERP\s*资产编号/, '选填已有的 ERP 资产编号；平台内部资产编号由系统生成。'],
  [/SN\s*序列号|设备序列号/, '录入设备铭牌的序列号，便于查询同型号设备和维保。'],
  [/存放位置/, '填写设备具体库房、办公区或工位。待入库设备录入存放位置后可转为闲置。'],
  [/设备状态/, '按设备当前实际状态选择。已领用或已借出的设备由业务流程更新状态。'],
  [/设备型号|设备名称\s*\/\s*型号|设备名称/, '填写能区分设备的名称或型号，便于设备筛选、查询与配发。'],
  [/资产分类|设备分类|更换分类/, '选择设备归属的分类，列表将显示这一分类下符合当前业务的设备。'],
  [/维保到期|购置日期|维保方式/, '按采购与维保资料填写，方便后续查询质保范围及到期时间。'],
  [/角色/, '用于给人员分配应用内权限。正式修改前请核对其职责与未结工单。'],
  [/状态|启用/, '选择当前状态或切换启用项。练习中改变的选项只保留在本次表单内。'],
  [/ERP|模板|映射/, '按实际系统的编号或审批模板填写，正式操作前核对对应业务和字段。'],
  [/搜索|筛选|查询/, '输入关键词缩小结果范围；也可用页面上的分类、状态或日期继续筛选。'],
  [/热线/, '填写员工可以拨打的服务号码以及适用场景。'],
  [/备注|说明|结论|原因/, '填写有助于工作人员判断和追溯的具体信息。练习内容不会随教程提交。'],
]

const actionDetails: [RegExp, string][] = [
  [/设备坏了/, '选择故障报修，适用于电脑、网络、打印机等设备或系统出现异常。选择后会加载对应分类。'],
  [/要开通|申领/, '选择服务申请，用于开通权限、账号或其他 IT 服务。'],
  [/不会用/, '选择咨询，用于咨询软件、设备或使用步骤。'],
  [/只影响我|影响部门|影响整层|全公司|不受影响|能凑合|完全干不了/, '按实际影响范围和工作中断程度选择，系统会据此计算工单优先级。'],
  [/^领用|资产领用/, '打开选设备窗口，先选分类与设备，再填写用途。领用适合长期使用的设备。'],
  [/^借用|资产借用/, '打开可借设备列表，再填写用途与预计归还日期。'],
  [/^更换|资产更换/, '先选需更换的现用设备，可再指定替换设备，并说明更换原因。'],
  [/^归还|资产归还/, '从自己领用或借用的设备中选择需归还的一台，并填写说明。'],
  [/购入申请/, '从采购目录选设备，可调整数量、加入申请单，再进入填写事由的步骤。'],
  [/重新选择分类与设备|选择关联设备|选择替换设备|选择设备|库存配发|到货配发/, '打开设备选择窗口，先用分类和搜索缩小范围，再核对卡片中的名称、状态和编号。'],
  [/确定关联|确认选择|^确定$/, '把当前选项带回上一步的练习表单。'],
  [/加入申请单/, '把当前选中的品类和数量加入申请单。可继续添加其他品类。'],
  [/增加一台|减少一台|清空|移除该项/, '调整当前练习申请单中的购买数量。'],
  [/批量导入/, '打开导入窗口。先下载模板、查看格式和选项，再选择填写好的 Excel。提交导入通常会新增多条设备记录。'],
  [/下载模板/, '下载带填写示例的 Excel 模板。资产编号由系统生成，使用人可按模板说明填写。'],
  [/新增资产/, '打开设备录入表单。先填设备名称、分类和状态，按需填写型号、ERP 编号及位置。'],
  [/管理分类|管理资产分类/, '打开分类管理窗口，可查看分类中的设备数量，或练习填写分类名称、排序。'],
  [/调整分类/, '在弹窗里选择新的资产分类。正式保存会改变设备在台账和选择器中的归类。'],
  [/二维码/, '查看设备专属二维码与访问地址。在详情弹窗中可了解复制地址和下载标签的用途。'],
  [/派单/, '选择接手的工程师，正式确认后工单会转到对方名下。'],
  [/受理/, '正式受理会把工单分给当前工程师，并进入处理流程。练习停在这一步。'],
  [/添加处理记录/, '打开处理记录表单，填写排查、处理措施及进展，方便需求人跟进。'],
  [/提交完成/, '填写处理结论和分类。正式提交后进入待确认，需求人核对结果。'],
  [/评价|已解决|还没好|没解决/, '用于反馈处理结果。可以练习选择结果或星级，反馈不会写入工单或知识库。'],
  [/撤销/, '撤销会终止该项申请或登记。看到确认窗口时先核对编号和原因，练习停在确认前。'],
  [/批准|审批|驳回/, '核对该条申请的设备、数量和理由，再选择批准或驳回。练习不会更改审批状态或发通知。'],
  [/确认交接|确认交付|确认配发/, '根据界面上的设备与申请人核对实际交接情况。正式确认会更新设备或申请状态。'],
  [/转采购/, '进入 ERP 采购单号的填写步骤，只影响当前这一项设备。'],
  [/登记已完成事项/, '展开登记表单。选择需求人，再填写沟通方式、完成时间、处理过程和佐证附件。'],
  [/^通知$|消息通知/, '打开消息列表。点开一条通知，可沿对应入口核对工单、资产申请或审批详情。'],
  [/新手教程|全部功能/, '打开目录，从中选择要看的功能或教程。'],
  [/^\d 星$/, '点亮相应星级以反馈服务满意度。星级会保留在练习表单内。'],
  [/查看证据|打开.*附件/, '读取该事项的已有佐证，可核对截图或通话记录。'],
  [/补报/, '打开补报事项或填写入口。核对已完成事项的内容，再补充问题类型、分类和位置。'],
  [/查看设备.*明细|查看.*明细|问题设备/, '打开统计明细。可按筛选条件查设备，点开记录还能核对实际报修历史。'],
  [/解决办法|建议|提交前先看看/, '打开相似知识建议，逐条展开查看自查步骤。练习不会传送你填写的问题描述。'],
  [/保存|提交|发布|删除|同步|归档/, '这是正式业务操作的入口。请先检查当前表单与选择项，练习期间点击只说明操作效果。'],
  [/下一步|上一步|继续添加/, '在当前流程的选择与填表步骤之间切换，方便核对练习内容。'],
  [/取消|关闭|返回|^×$|^完成$/, '关闭当前面板或返回上一级。教程仍可继续介绍页面上的其他按钮。'],
  [/上一页|下一页/, '查看当前筛选结果的相邻一页。'],
  [/刷新|加载|重试/, '刷新本页或弹窗的数据，查看最新状态。'],
  [/查询|搜索|筛选|清除/, '应用或重置当前筛选条件，方便在列表中定位所需记录。'],
  [/新增|添加|编辑|整理/, '打开编辑区或在练习表单中添加一项，可逐个查看字段的说明。'],
  [/查看|详情|进度|全部|展开/, '打开对应列表或详情，核对记录与状态。'],
]

export function controlLabel(node: HTMLElement): string {
  if (node.matches('.dlg .stars > span') && node.parentElement) return (Array.from(node.parentElement.children).indexOf(node) + 1) + ' 星'
  if (node.matches('.category-option')) return node.dataset.category || '全部分类'
  const field = node.matches('input,select,textarea')
  const text = node.getAttribute('aria-label') || (node.classList.contains('device-option') && node.querySelector('h5')?.textContent)
    || (field && (node as HTMLInputElement).labels?.[0]?.querySelector('.lb')?.textContent)
    || (field && (node as HTMLInputElement).labels?.[0]?.childNodes[0]?.textContent)
    || (field && node.closest('.fld,.field')?.querySelector('.lb')?.textContent)
    || (field && node.getAttribute('placeholder'))
    || (field && node.getAttribute('title'))
    || (!field && node.innerText) || ''
  return text.replace(/\s+/g, ' ').trim().replace(/\s*\*\s*$/, '').slice(0, 54) || (field ? '表单字段' : '打开选项')
}

function isWrite(node: HTMLElement, label: string): boolean {
  if (node.dataset.tutorialEffect === 'write') return true
  if (node.dataset.tutorialEffect === 'open') return false
  if (node.matches('a') && /^(https?:|mailto:|tel:|dingtalk:)/i.test(node.getAttribute('href') || '')) return true
  if (node.matches('.ops > button') && !/^(受理|驳回|撤销)$/.test(label)) return false
  if (node.matches('.dlg .opt,.dlg .stars span')) return false
  if (node.matches('.cancel-confirm,[data-kb-resolve],.scan-entry,.user-item')) return true
  if (node instanceof HTMLButtonElement && node.type === 'submit' && node.form && !/^(查询|搜索|筛选)$/.test(label)) return true
  return /^(保存|提交(?!前)|确认(?:批准|审批|派单|交接|交付|配发|转采购|撤销|入库|导入|提交|发布|归档)|确认撤回|立即同步|同步真实|写入样例|样例只读试跑|联调：模拟|受理$|驳回$|发布知识|撤回发布|归档$|删除|导入资产|开始导入|已解决$|没解决$)/.test(label)
}

export function describeControl(node: HTMLElement): TutorialControl {
  const label = controlLabel(node)
  const field = node.matches('input,select,textarea')
  const rule = (field ? fieldDetails : actionDetails).find(([pattern]) => pattern.test(label))?.[1]
  const kind = field ? 'field' : isWrite(node, label) ? 'write' : 'click'
  const select = node.matches('select,.category-option,.device-option,[role=option]')
  const description = node.matches('.category-option') ? '切换左侧分类，右侧会显示该分类下可用于当前业务的设备。'
    : rule || (node.matches('input[type=file]') ? '选择一个符合页面要求的文件。练习时文件留在本机。'
    : node.matches('.device-option') ? '核对这台设备的编号、型号和状态，点击后会选入本次练习。'
    : select ? '从这里选取适合当前场景的值，再观察表单或列表的变化。'
    : field ? '按字段名称和页面示例填写或选择内容。必填项通常标有星号。'
    : kind === 'write' ? '这个按钮在正式操作中会提交或更新本项业务。'
    : node.matches('a,.asset-row,.ticket-card,.wo,.report-row') ? '打开这条记录或对应页面，可继续了解其中的操作。'
    : '点开或切换这一项，观察对应面板和可用选项。')
  return { node, label, description, kind }
}
